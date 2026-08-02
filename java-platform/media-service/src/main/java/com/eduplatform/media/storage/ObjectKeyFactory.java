package com.eduplatform.media.storage;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaPurpose;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 只在服务端生成 OSS Object Key。原始对象使用随机 UUID，派生对象使用稳定组合键支持 Worker 幂等覆盖。
 */
@Component
public class ObjectKeyFactory {
    private final MediaProperties properties;
    private final Clock clock = Clock.systemUTC();

    public ObjectKeyFactory(MediaProperties properties) {
        this.properties = properties;
    }

    /** 根据用途和扩展名生成不可猜测的原始对象键。 */
    public String original(MediaPurpose purpose, String extension) {
        LocalDate date = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String prefix = normalizePrefix(properties.oss().keyPrefix());
        String suffix = extension.isBlank() ? "" : "." + extension.toLowerCase(Locale.ROOT);
        return prefix + "/" + date.getYear() + "/" + String.format("%02d", date.getMonthValue())
                + "/" + String.format("%02d", date.getDayOfMonth()) + "/"
                + purpose.name().toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID() + suffix;
    }

    /** 根据媒体 ID、处理版本和扩展名生成幂等派生键。 */
    public String derived(String mediaId, String variant, String extension) {
        return normalizePrefix(properties.oss().keyPrefix()) + "/derived/" + mediaId + "/"
                + variant + "." + extension;
    }

    /** 为一个 HLS 变体的切片生成稳定键，重试会覆盖同名切片而不扩散对象。 */
    public String derivedComponent(String mediaId, String variant, String componentName) {
        if (!componentName.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("HLS component name is unsafe");
        }
        return normalizePrefix(properties.oss().keyPrefix()) + "/derived/" + mediaId + "/"
                + variant + "/" + componentName;
    }

    /** 为一次短期 HLS 授权生成不可猜测且可精确清理的临时播放清单键。 */
    public String temporaryManifest(String mediaId, String grantId) {
        return normalizePrefix(properties.oss().keyPrefix()) + "/temporary-access/" + mediaId + "/"
                + grantId + ".m3u8";
    }

    private static String normalizePrefix(String value) {
        String normalized = value == null ? "quarantine" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank() || normalized.contains("..")) {
            throw new IllegalStateException("OSS key prefix must be a non-empty relative prefix");
        }
        return normalized;
    }
}
