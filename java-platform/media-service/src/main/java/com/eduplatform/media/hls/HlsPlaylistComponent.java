package com.eduplatform.media.hls;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析并重写私有 HLS 清单中的本地组件引用，统一处理普通媒体行与 fMP4 的 EXT-X-MAP 初始化段。
 *
 * <p>输入必须是 Worker 生成的未授权相对路径；返回的组件名只保留文件名，避免绝对路径或目录穿越
 * 进入对象键和临时授权清单。该工具不接触签名 URL、数据库或对象存储。</p>
 */
public final class HlsPlaylistComponent {
    private static final Pattern MAP_URI = Pattern.compile("URI=\"([^\"]+)\"");

    private HlsPlaylistComponent() { }

    /**
     * 提取一行引用的组件文件名；注释、空行和不含 URI 的标签返回空。
     *
     * @param line 原始清单行
     * @return 普通媒体切片或 EXT-X-MAP 初始化段的安全文件名；无引用时为 null
     */
    public static String name(String line) {
        String value = line.trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("#EXT-X-MAP:")) {
            Matcher matcher = MAP_URI.matcher(line);
            if (!matcher.find()) throw new IllegalStateException("HLS init component URI is missing");
            return fileName(matcher.group(1));
        }
        if (value.startsWith("#")) return null;
        return fileName(value);
    }

    /**
     * 将组件引用替换为安全文件名或短期 URL，其他标签/空行原样返回。
     *
     * @param line 原始清单行
     * @param replacement 已验证的相对文件名或刚签发的临时 URL
     * @return 保留标签结构的替换行
     */
    public static String replace(String line, String replacement) {
        if (line.trim().startsWith("#EXT-X-MAP:")) {
            Matcher matcher = MAP_URI.matcher(line);
            if (!matcher.find()) throw new IllegalStateException("HLS init component URI is missing");
            return matcher.replaceFirst(Matcher.quoteReplacement("URI=\"" + replacement + "\""));
        }
        return replacement;
    }

    private static String fileName(String value) {
        String name = Path.of(value).getFileName().toString();
        if (!name.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalStateException("HLS component name is invalid");
        }
        return name;
    }
}
