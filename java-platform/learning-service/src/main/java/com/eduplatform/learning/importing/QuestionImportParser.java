package com.eduplatform.learning.importing;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.web.QuestionDtos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.ObjectMapper;

/**
 * 严格解析 T09 CSV/XLSX 规范行。
 *
 * <p>选择题选项必须放在 optionsJson 数组中，绝不从题干猜拆 A/B/C/D。LaTeX 与普通 HTML 文本
 * 原样保留；Excel 公式、嵌入图片、外部图片（后续由题目校验识别）和未知结构产生明确行错误。</p>
 */
@Component
public class QuestionImportParser {
    private static final List<String> HEADERS = List.of("type", "difficulty", "grade", "stem", "optionsJson",
            "answersJson", "analysis", "knowledgePointCodesJson", "mediaJson");
    private final ObjectMapper mapper;
    private final long maxBytes;

    public QuestionImportParser(ObjectMapper mapper, LearningProperties properties) {
        this.mapper = mapper; this.maxBytes = properties.importSettings().maxSourceBytes();
    }

    /** 解析一份源文件并返回逐行成功/错误；不会输出或记录原始单元格内容。 */
    public Parsed parse(InputStream source, QuestionModels.ImportFormat format) {
        try {
            return format == QuestionModels.ImportFormat.CSV ? fromRows(csv(source)) : xlsx(source);
        } catch (SizeExceeded exception) {
            return Parsed.fileError("QUESTION_IMPORT.SOURCE_TOO_LARGE", "导入源超过允许大小");
        } catch (Exception exception) {
            return Parsed.fileError("QUESTION_IMPORT.FILE_UNREADABLE", "导入源无法解析为声明的格式");
        }
    }

    private Parsed xlsx(InputStream source) throws Exception {
        byte[] shared = null; byte[] sheet = null; boolean embeddedImage = false; boolean multipleSheets = false;
        try (ZipInputStream zip = new ZipInputStream(new LimitedInputStream(source, maxBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("xl/media/") && !entry.isDirectory()) embeddedImage = true;
                if (name.equals("xl/sharedStrings.xml")) shared = readLimited(zip, maxBytes);
                if (name.equals("xl/worksheets/sheet1.xml")) sheet = readLimited(zip, maxBytes);
                if (name.matches("xl/worksheets/sheet[0-9]+\\.xml") && !name.equals("xl/worksheets/sheet1.xml")) {
                    multipleSheets = true;
                }
            }
        }
        if (embeddedImage) return Parsed.fileError("QUESTION_IMPORT.EMBEDDED_IMAGE_UNSUPPORTED",
                "XLSX 嵌入图片不支持，请先上传为 QUESTION_IMAGE 并在 mediaJson 中引用");
        if (multipleSheets) return Parsed.fileError("QUESTION_IMPORT.MULTIPLE_SHEETS_UNSUPPORTED", "XLSX 只能包含一个工作表");
        if (sheet == null) return Parsed.fileError("QUESTION_IMPORT.FILE_UNREADABLE", "XLSX 缺少第一个工作表");
        return fromRows(xlsxRows(sheet, shared));
    }

    private List<RawRow> xlsxRows(byte[] sheetXml, byte[] sharedXml) throws Exception {
        List<String> shared = sharedXml == null ? List.of() : sharedStrings(sharedXml);
        var document = factory().newDocumentBuilder().parse(new ByteArrayInputStream(sheetXml));
        NodeList rows = document.getElementsByTagNameNS("*", "row"); List<RawRow> result = new ArrayList<>();
        for (int index = 0; index < rows.getLength(); index++) {
            Element row = (Element) rows.item(index); int rowNumber = parseInt(row.getAttribute("r"), index + 1);
            Map<Integer, String> values = new HashMap<>(); boolean formula = false;
            NodeList cells = row.getElementsByTagNameNS("*", "c");
            for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
                Element cell = (Element) cells.item(cellIndex); int column = column(cell.getAttribute("r"));
                if (cell.getElementsByTagNameNS("*", "f").getLength() > 0) formula = true;
                String type = cell.getAttribute("t"); String value = text(cell, "v");
                if ("inlineStr".equals(type)) value = text(cell, "t");
                else if ("s".equals(type) && !value.isBlank()) value = shared.get(Integer.parseInt(value));
                values.put(column, value);
            }
            int max = values.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            List<String> ordered = new ArrayList<>(); for (int col = 0; col <= max; col++) ordered.add(values.getOrDefault(col, ""));
            result.add(new RawRow(rowNumber, ordered, formula));
        }
        return result;
    }

    private List<String> sharedStrings(byte[] xml) throws Exception {
        var document = factory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList items = document.getElementsByTagNameNS("*", "si"); List<String> result = new ArrayList<>();
        for (int index = 0; index < items.getLength(); index++) result.add(items.item(index).getTextContent());
        return result;
    }

    private Parsed fromRows(List<RawRow> rows) {
        if (rows.isEmpty()) return Parsed.fileError("QUESTION_IMPORT.HEADER_INVALID", "导入文件缺少表头");
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < rows.get(0).values().size(); index++) columns.put(rows.get(0).values().get(index).trim(), index);
        if (!columns.keySet().containsAll(HEADERS)) return Parsed.fileError("QUESTION_IMPORT.HEADER_INVALID",
                "表头必须包含 type,difficulty,grade,stem,optionsJson,answersJson,analysis,knowledgePointCodesJson,mediaJson");
        List<Row> parsed = new ArrayList<>();
        for (int index = 1; index < rows.size(); index++) {
            RawRow raw = rows.get(index); if (raw.values().stream().allMatch(String::isBlank)) continue;
            if (raw.formula()) { parsed.add(Row.error(raw.number(), "QUESTION_IMPORT.FORMULA_UNSUPPORTED", "XLSX 公式单元格不支持")); continue; }
            parsed.add(parseRow(raw, columns));
        }
        return new Parsed(parsed);
    }

    private Row parseRow(RawRow row, Map<String, Integer> columns) {
        try {
            QuestionModels.Type type = QuestionModels.Type.valueOf(value(row, columns, "type").trim().toUpperCase(Locale.ROOT));
            QuestionModels.Difficulty difficulty = QuestionModels.Difficulty.valueOf(value(row, columns, "difficulty").trim().toUpperCase(Locale.ROOT));
            QuestionModels.Grade grade = QuestionModels.Grade.valueOf(value(row, columns, "grade").trim().toUpperCase(Locale.ROOT));
            String stem = value(row, columns, "stem"); String analysis = value(row, columns, "analysis");
            var options = List.of(mapper.readValue(value(row, columns, "optionsJson"), QuestionDtos.OptionInput[].class));
            var answers = List.of(mapper.readValue(value(row, columns, "answersJson"), String[].class));
            var codes = List.of(mapper.readValue(value(row, columns, "knowledgePointCodesJson"), String[].class));
            var media = List.of(mapper.readValue(value(row, columns, "mediaJson"), QuestionDtos.MediaInput[].class));
            QuestionDtos.VersionInput input = new QuestionDtos.VersionInput(type, difficulty, QuestionModels.Stage.JUNIOR,
                    grade, QuestionModels.Subject.MATHEMATICS, stem, options, answers, analysis,
                    List.of(), media);
            return Row.success(row.number(), input, codes);
        } catch (Exception exception) {
            return Row.error(row.number(), "QUESTION_IMPORT.ROW_INVALID",
                    "枚举或 JSON 列格式不合法；选择题选项不能从题干自动猜拆");
        }
    }

    private List<RawRow> csv(InputStream source) throws IOException {
        List<RawRow> rows = new ArrayList<>(); List<String> current = new ArrayList<>(); StringBuilder value = new StringBuilder();
        boolean quoted = false; int row = 1; int read; PushbackReader reader = new PushbackReader(
                new InputStreamReader(new LimitedInputStream(source, maxBytes), StandardCharsets.UTF_8), 1);
        while ((read = reader.read()) != -1) {
            char ch = (char) read;
            if (ch == '"') {
                if (quoted) { int next = reader.read(); if (next == '"') value.append('"'); else { quoted = false; if (next != -1) reader.unread(next); } }
                else if (value.length() == 0) quoted = true; else value.append(ch);
            } else if (ch == ',' && !quoted) { current.add(value.toString()); value.setLength(0); }
            else if ((ch == '\n' || ch == '\r') && !quoted) {
                if (ch == '\r') { int next = reader.read(); if (next != '\n' && next != -1) reader.unread(next); }
                current.add(value.toString()); value.setLength(0); rows.add(new RawRow(row++, List.copyOf(current), false)); current.clear();
            } else value.append(ch);
        }
        if (quoted) throw new IOException("unterminated CSV quote");
        if (value.length() > 0 || !current.isEmpty()) { current.add(value.toString()); rows.add(new RawRow(row, List.copyOf(current), false)); }
        if (!rows.isEmpty() && !rows.get(0).values().isEmpty()) {
            String first = rows.get(0).values().get(0); if (first.startsWith("\uFEFF")) {
                List<String> fixed = new ArrayList<>(rows.get(0).values()); fixed.set(0, first.substring(1)); rows.set(0, new RawRow(1, fixed, false));
            }
        }
        return rows;
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static String value(RawRow row, Map<String, Integer> columns, String name) {
        int index = columns.get(name); return index < row.values().size() ? row.values().get(index) : "";
    }
    private static String text(Element element, String tag) {
        NodeList values = element.getElementsByTagNameNS("*", tag); return values.getLength() == 0 ? "" : values.item(0).getTextContent();
    }
    private static int column(String reference) {
        int value = 0; for (int index = 0; index < reference.length() && Character.isLetter(reference.charAt(index)); index++)
            value = value * 26 + Character.toUpperCase(reference.charAt(index)) - 'A' + 1;
        return Math.max(0, value - 1);
    }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }
    private static byte[] readLimited(InputStream input, long max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) { total += count; if (total > max) throw new SizeExceeded(); output.write(buffer, 0, count); }
        return output.toByteArray();
    }

    public record Parsed(List<Row> rows) {
        static Parsed fileError(String code, String message) { return new Parsed(List.of(Row.error(2, code, message))); }
    }
    public record Row(int rowNumber, QuestionDtos.VersionInput input, List<String> knowledgePointCodes,
            String errorCode, String errorMessage) {
        static Row success(int row, QuestionDtos.VersionInput input, List<String> codes) { return new Row(row, input, codes, null, null); }
        static Row error(int row, String code, String message) { return new Row(row, null, List.of(), code, message); }
        public boolean valid() { return errorCode == null; }
    }
    private record RawRow(int number, List<String> values, boolean formula) {}
    private static final class SizeExceeded extends IOException {}
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate; private final long max; private long count;
        private LimitedInputStream(InputStream delegate, long max) { this.delegate = delegate; this.max = max; }
        @Override public int read() throws IOException { int value = delegate.read(); if (value >= 0 && ++count > max) throw new SizeExceeded(); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int value = delegate.read(bytes, offset, length); if (value > 0 && (count += value) > max) throw new SizeExceeded(); return value;
        }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
