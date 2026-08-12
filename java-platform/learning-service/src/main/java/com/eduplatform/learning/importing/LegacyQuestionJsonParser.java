package com.eduplatform.learning.importing;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.web.QuestionDtos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 将一份历史逐题 JSON 转为 T09A 候选题，并以保守规则识别必须人工复核的结构。
 *
 * <p>输入只在当前请求内存中存在；输出不携带原始 JSON。显式 A～H 标记必须完整有序才拆选项，
 * 显式子题必须各自具备答案才生成有序子题。HTML 仅保留无属性的展示标签，LaTeX 文本原样保留，
 * 外部图片仅暂存在候选的内存 URL 列表，后续交给 media-service 转存。</p>
 */
@Component
public class LegacyQuestionJsonParser {
    private static final Pattern IMAGE = Pattern.compile("(?is)<img\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1[^>]*>");
    private static final Pattern BLOCKED = Pattern.compile("(?is)<(script|style|iframe|object|embed|svg|math)[^>]*>.*?</\\1\\s*>");
    private static final Pattern TAG = Pattern.compile("(?is)</?([a-z0-9]+)\\b[^>]*>");
    private static final Pattern OPTION = Pattern.compile("(?<![A-Za-z])([A-H])[.．、]");
    private static final Pattern MULTI_PART = Pattern.compile("[（(][1-9][)）]");
    private static final Pattern NUMBERED_ANSWER = Pattern.compile("[（(]([1-9][0-9]*)[)）]");
    private static final Set<String> SAFE_TAGS = Set.of("p", "br", "sup", "sub", "b", "strong", "i", "em",
            "u", "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th");

    /**
     * 解析一项来源。
     * @param json 单份历史对象，必须小于上层请求限制
     * @param pathEdition 相对路径解析出的教材版本原文
     * @param pathGrade 相对路径解析出的年级原文
     * @return 候选题、原始元数据和稳定问题码；有问题码时上层不得静默导入
     */
    public Parsed parse(JsonNode json, String pathEdition, String pathGrade) {
        List<String> issues = new ArrayList<>();
        if (json == null || !json.isObject()) return Parsed.review(List.of("FIELD_INVALID"), null, null, null, null);
        QuestionModels.Type type = type(text(json, "exerciseType"));
        if (type == null) issues.add("TYPE_UNSUPPORTED");
        QuestionModels.Grade grade = grade(json);
        String jsonGrade = text(json, "gradeName");
        String jsonEdition = firstText(json, "editionName", "textbookEdition", "versionName");
        if (jsonGrade == null || jsonGrade.isBlank()) jsonGrade = numberText(json, "gradeIndex");
        if (grade == null) issues.add("FIELD_INVALID");
        if (grade != null && !pathMatches(pathGrade, grade)) issues.add("PATH_JSON_METADATA_CONFLICT");
        if (jsonGrade != null && !jsonGrade.matches("[789]")
                && (pathGrade == null || !pathGrade.contains(jsonGrade))) issues.add("PATH_JSON_METADATA_CONFLICT");
        if (jsonEdition != null && (pathEdition == null || !pathEdition.contains(jsonEdition))) issues.add("PATH_JSON_METADATA_CONFLICT");
        String originalDifficulty = numberText(json, "difficulty");
        QuestionModels.Difficulty difficulty = difficulty(originalDifficulty);
        if (difficulty == null) issues.add("FIELD_INVALID");

        Cleaned stem = clean(text(json, "question"));
        Cleaned solution = clean(text(json, "solution"));
        if (stem.unsafe() || solution.unsafe()) issues.add("HTML_UNSAFE");
        if (stem.text().isBlank()) issues.add("FIELD_INVALID");

        List<QuestionDtos.OptionInput> options = List.of();
        List<String> answers = type == QuestionModels.Type.COMPOSITE
                ? (text(json, "queAns") == null ? List.of("") : List.of(text(json, "queAns")))
                : answers(text(json, "queAns"), type);
        if (type == QuestionModels.Type.SINGLE_CHOICE || type == QuestionModels.Type.MULTIPLE_CHOICE) {
            OptionSplit split = splitOptions(stem.text(), integer(json, "optCount"));
            if (split == null) issues.add("EMBEDDED_OPTIONS_AMBIGUOUS");
            else { stem = new Cleaned(split.stem(), stem.images(), stem.unsafe()); options = split.options(); }
        }
        if (answers.isEmpty() && type != QuestionModels.Type.COMPOSITE) issues.add("FIELD_INVALID");

        JsonNode subQues = json.get("subQues");
        List<Candidate> children = new ArrayList<>();
        if (subQues != null && subQues.isArray() && subQues.size() > 0) {
            type = QuestionModels.Type.COMPOSITE;
            if (answers.isEmpty()) answers = List.of("");
            List<String> explicitAnswers = splitNumbered(text(json, "queAns"), subQues.size());
            int index = 0;
            for (JsonNode child : subQues) {
                index++;
                String fallbackAnswer = explicitAnswers == null ? null : explicitAnswers.get(index - 1);
                Candidate parsedChild = child(child, grade, difficulty, index, fallbackAnswer);
                if (parsedChild == null) issues.add("SUBQUESTION_ANSWER_MISSING");
                else children.add(parsedChild);
            }
            if (children.size() != subQues.size()) children.clear();
        } else if (type == QuestionModels.Type.SHORT_ANSWER && multiPartCount(stem.text()) >= 2) {
            issues.add("IMPLICIT_MULTI_PART");
        }

        issues = issues.stream().distinct().toList();
        if (!issues.isEmpty() || type == null || grade == null || difficulty == null) {
            return Parsed.review(issues, originalDifficulty, jsonEdition, jsonGrade, grade);
        }
        QuestionDtos.VersionInput input = new QuestionDtos.VersionInput(type, difficulty, QuestionModels.Stage.JUNIOR,
                grade, QuestionModels.Subject.MATHEMATICS, stem.text(), options, answers, solution.text(),
                List.of(), List.of());
        List<String> images = new ArrayList<>(stem.images()); images.addAll(solution.images());
        return new Parsed(new Candidate(input, List.copyOf(images), List.copyOf(children)), List.of(),
                originalDifficulty, jsonEdition, jsonGrade, grade);
    }

    private Candidate child(JsonNode json, QuestionModels.Grade grade, QuestionModels.Difficulty difficulty,
            int sequence, String fallbackAnswer) {
        QuestionModels.Type type = type(text(json, "exerciseType"));
        if (type == null || type == QuestionModels.Type.COMPOSITE) return null;
        Cleaned stem = clean(text(json, "question")); Cleaned solution = clean(text(json, "solution"));
        List<String> answers = answers(text(json, "queAns"), type);
        if (answers.isEmpty() && fallbackAnswer != null && !fallbackAnswer.isBlank()) answers = answers(fallbackAnswer, type);
        if (stem.text().isBlank() || answers.isEmpty() || stem.unsafe() || solution.unsafe()) return null;
        List<QuestionDtos.OptionInput> options = List.of();
        if (type == QuestionModels.Type.SINGLE_CHOICE || type == QuestionModels.Type.MULTIPLE_CHOICE) {
            OptionSplit split = splitOptions(stem.text(), integer(json, "optCount"));
            if (split == null) return null;
            stem = new Cleaned(split.stem(), stem.images(), stem.unsafe()); options = split.options();
        }
        List<String> images = new ArrayList<>(stem.images()); images.addAll(solution.images());
        return new Candidate(new QuestionDtos.VersionInput(type, difficulty, QuestionModels.Stage.JUNIOR, grade,
                QuestionModels.Subject.MATHEMATICS, stem.text(), options, answers, solution.text(), List.of(), List.of()),
                List.copyOf(images), List.of());
    }

    /** 清洗危险 HTML 属性/标签并提取 HTTPS 图片；安全展示标签与 LaTeX 字符保持原序。 */
    private static Cleaned clean(String source) {
        String value = source == null ? "" : source;
        boolean unsafe = BLOCKED.matcher(value).find();
        value = BLOCKED.matcher(value).replaceAll("");
        List<String> images = new ArrayList<>(); Matcher image = IMAGE.matcher(value); StringBuffer buffer = new StringBuffer();
        while (image.find()) {
            String url = image.group(2).trim();
            if (url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                images.add(url); image.appendReplacement(buffer, Matcher.quoteReplacement("[图片" + images.size() + "]"));
            } else { unsafe = true; image.appendReplacement(buffer, "[图片待复核]"); }
        }
        image.appendTail(buffer); value = buffer.toString();
        Matcher tag = TAG.matcher(value); buffer = new StringBuffer();
        while (tag.find()) {
            String name = tag.group(1).toLowerCase(Locale.ROOT);
            String replacement = SAFE_TAGS.contains(name)
                    ? (tag.group().startsWith("</") ? "</" + name + ">" : "<" + name + ">") : "";
            tag.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        tag.appendTail(buffer);
        return new Cleaned(buffer.toString().trim(), List.copyOf(images), unsafe);
    }

    private static OptionSplit splitOptions(String value, int declaredCount) {
        Matcher matcher = OPTION.matcher(value); List<Integer> starts = new ArrayList<>(); List<Integer> ends = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        while (matcher.find()) { starts.add(matcher.start()); ends.add(matcher.end()); keys.add(matcher.group(1)); }
        int count = declaredCount > 0 ? declaredCount : keys.size();
        if (count < 2 || count > 8 || keys.size() != count) return null;
        for (int index = 0; index < count; index++) if (!keys.get(index).equals(String.valueOf((char) ('A' + index)))) return null;
        String stem = value.substring(0, starts.get(0)).trim(); if (stem.isBlank()) return null;
        List<QuestionDtos.OptionInput> options = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int end = index + 1 < count ? starts.get(index + 1) : value.length();
            String content = value.substring(ends.get(index), end).trim(); if (content.isBlank()) return null;
            options.add(new QuestionDtos.OptionInput(keys.get(index), content));
        }
        return new OptionSplit(stem, List.copyOf(options));
    }

    private static List<String> answers(String value, QuestionModels.Type type) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim()) || type == null) return List.of();
        String normalized = value.trim();
        if (type == QuestionModels.Type.SINGLE_CHOICE) return normalized.matches("(?i)[A-H]")
                ? List.of(normalized.toUpperCase(Locale.ROOT)) : List.of();
        if (type == QuestionModels.Type.MULTIPLE_CHOICE) {
            List<String> values = List.of(normalized.toUpperCase(Locale.ROOT).split("[,，、\\s]+"));
            Set<String> distinct = new HashSet<>(values);
            return values.size() >= 2 && values.size() == distinct.size() && values.stream().allMatch(item -> item.matches("[A-H]"))
                    ? values : List.of();
        }
        return List.of(normalized);
    }

    private static QuestionModels.Type type(String value) {
        if (value == null) return null;
        return switch (value) {
            case "select_single" -> QuestionModels.Type.SINGLE_CHOICE;
            case "select_multiple" -> QuestionModels.Type.MULTIPLE_CHOICE;
            case "fillin" -> QuestionModels.Type.FILL_BLANK;
            case "writeout" -> QuestionModels.Type.SHORT_ANSWER;
            default -> null;
        };
    }

    private static QuestionModels.Difficulty difficulty(String value) {
        return switch (value == null ? "" : value) { case "1" -> QuestionModels.Difficulty.EASY;
            case "5" -> QuestionModels.Difficulty.MEDIUM; case "9" -> QuestionModels.Difficulty.HARD; default -> null; };
    }
    private static QuestionModels.Grade grade(JsonNode json) {
        return switch (integer(json, "gradeIndex")) { case 7 -> QuestionModels.Grade.SEVEN;
            case 8 -> QuestionModels.Grade.EIGHT; case 9 -> QuestionModels.Grade.NINE; default -> null; };
    }
    private static boolean pathMatches(String value, QuestionModels.Grade grade) {
        if (value == null) return false;
        return switch (grade) { case SEVEN -> value.contains("七"); case EIGHT -> value.contains("八"); case NINE -> value.contains("九"); };
    }
    private static int multiPartCount(String value) { int count = 0; Matcher matcher = MULTI_PART.matcher(value); while (matcher.find()) count++; return count; }
    /** 只有编号从 1 连续到 N 且每段非空时才拆父级答案，避免按空格或标点猜测子题答案。 */
    private static List<String> splitNumbered(String value, int count) {
        if (value == null || count < 1) return null;
        Matcher matcher = NUMBERED_ANSWER.matcher(value); List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>(); List<Integer> numbers = new ArrayList<>();
        while (matcher.find()) { starts.add(matcher.start()); ends.add(matcher.end()); numbers.add(Integer.parseInt(matcher.group(1))); }
        if (numbers.size() != count) return null;
        List<String> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (numbers.get(index) != index + 1) return null;
            int end = index + 1 < count ? starts.get(index + 1) : value.length();
            String answer = value.substring(ends.get(index), end).trim(); if (answer.isBlank()) return null;
            result.add(answer);
        }
        return List.copyOf(result);
    }
    private static String text(JsonNode json, String field) { JsonNode value = json.get(field); return value == null || value.isNull() ? null : value.asString(); }
    private static String firstText(JsonNode json, String... fields) { for (String field : fields) { String value = text(json, field); if (value != null && !value.isBlank()) return value; } return null; }
    private static int integer(JsonNode json, String field) { JsonNode value = json.get(field); return value == null || !value.isNumber() ? 0 : value.asInt(); }
    private static String numberText(JsonNode json, String field) { JsonNode value = json.get(field); return value == null || value.isNull() ? null : value.asString(); }

    public record Candidate(QuestionDtos.VersionInput input, List<String> imageUrls, List<Candidate> children) {}
    public record Parsed(Candidate candidate, List<String> issueCodes, String originalDifficulty,
            String jsonEdition, String jsonGrade, QuestionModels.Grade grade) {
        static Parsed review(List<String> issues, String difficulty, String jsonEdition, String jsonGrade, QuestionModels.Grade grade) {
            return new Parsed(null, issues.isEmpty() ? List.of("FIELD_INVALID") : List.copyOf(issues), difficulty, jsonEdition, jsonGrade, grade);
        }
        public boolean review() { return candidate == null || !issueCodes.isEmpty(); }
    }
    private record Cleaned(String text, List<String> images, boolean unsafe) {}
    private record OptionSplit(String stem, List<QuestionDtos.OptionInput> options) {}
}
