package com.eduplatform.course.service;

import com.eduplatform.shared.api.FieldError;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 校验并解析 ARTICLE 受控 Markdown。
 *
 * <p>输入是教师保存的原始 Markdown；输出是正文引用的稳定 mediaId 集合。校验拒绝原始 HTML、
 * 外部图片、空替代文字、非 HTTPS 链接、data URL、危险协议和畸形 media 占位符。代码围栏与
 * 行内代码不参与可执行语法检查，正文仍原样保存供受控渲染器使用。</p>
 */
@Component
public class ControlledMarkdownValidator {
    public static final int MAX_VISIBLE_CHARACTERS = 100_000;
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern FENCED_CODE = Pattern.compile("(?ms)^(```|~~~).*?^\\1[ \\t]*$");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\r\\n]*`");
    private static final Pattern RAW_HTML = Pattern.compile("(?is)<\\s*/?\\s*[a-z][^>]*>");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^]\\r\\n]*)]\\(([^)\\r\\n]+)\\)");
    private static final Pattern MEDIA_IMAGE = Pattern.compile("!\\[([^]\\r\\n]+)]\\(media:(" + UUID_PATTERN + ")\\)");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[([^]\\r\\n]+)]\\(([^)\\r\\n]+)\\)");
    private static final Pattern MEDIA_TOKEN = Pattern.compile("(?i)media:[^\\s)]+", Pattern.MULTILINE);
    private static final Pattern DANGEROUS = Pattern.compile("(?i)(?:javascript|vbscript|data|file):");

    /**
     * 校验草稿正文并返回去重后的正文图片引用；空正文合法且返回空集合。
     *
     * @throws CourseException 任一危险或不受支持语法、非法占位符、可见字符超限时返回 422
     */
    public Set<String> validate(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Set.of();
        }
        if (markdown.indexOf('\0') >= 0 || markdown.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
            throw invalid("正文包含不允许的控制字符");
        }
        String renderedSyntax = INLINE_CODE.matcher(FENCED_CODE.matcher(markdown).replaceAll(" ")).replaceAll(" ");
        if (RAW_HTML.matcher(renderedSyntax).find()) {
            throw invalid("正文禁止原始 HTML 或 iframe");
        }
        if (DANGEROUS.matcher(renderedSyntax).find()) {
            throw invalid("正文禁止 data URL 或危险协议");
        }
        Set<String> references = new HashSet<>();
        Matcher image = IMAGE.matcher(renderedSyntax);
        while (image.find()) {
            Matcher controlled = MEDIA_IMAGE.matcher(image.group());
            if (!controlled.matches()) {
                throw invalid("图片必须使用 ![替代文字](media:UUID)，且替代文字不能为空");
            }
            references.add(UUID.fromString(controlled.group(2)).toString());
        }
        Matcher token = MEDIA_TOKEN.matcher(renderedSyntax);
        while (token.find()) {
            String tokenValue = token.group();
            boolean partOfValidImage = false;
            Matcher controlled = MEDIA_IMAGE.matcher(renderedSyntax);
            while (controlled.find()) {
                if (token.start() >= controlled.start() && token.end() <= controlled.end()) {
                    partOfValidImage = true;
                    break;
                }
            }
            if (!partOfValidImage) {
                throw invalid("media 占位符只能用于正文图片，且必须是合法 UUID");
            }
        }
        Matcher link = LINK.matcher(renderedSyntax);
        while (link.find()) {
            if (!link.group(2).trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                throw invalid("正文链接只允许 HTTPS");
            }
        }
        if (visibleCharacters(markdown) > MAX_VISIBLE_CHARACTERS) {
            throw invalid("每课时正文最多 100,000 个可见字符");
        }
        return Set.copyOf(references);
    }

    /** 以去除常用 Markdown 标记和目标 URL 后的 Unicode 码点数作为稳定可见字符上限。 */
    int visibleCharacters(String markdown) {
        String value = FENCED_CODE.matcher(markdown).replaceAll(match -> match.group().replaceAll("(?m)^(```|~~~).*$", ""));
        value = IMAGE.matcher(value).replaceAll("$1");
        value = LINK.matcher(value).replaceAll("$1");
        value = value.replaceAll("(?m)^[ \\t]{0,3}(?:#{1,6}[ \\t]+|>[ \\t]?|[-+*][ \\t]+|\\d+[.)][ \\t]+)", "")
                .replaceAll("[*_~`]", "")
                .replaceAll("(?m)^\\|?[-: |]+\\|?[ \\t]*$", "");
        return value.codePointCount(0, value.length());
    }

    private static CourseException invalid(String reason) {
        return new CourseException(HttpStatus.UNPROCESSABLE_CONTENT, "COURSE.MARKDOWN_INVALID",
                "图文课时正文不合法", List.of(new FieldError("articleMarkdown", reason)));
    }
}
