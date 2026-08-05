package com.eduplatform.media.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体域可调运行参数。时间值都使用 Spring {@link Duration} 格式，字节阈值使用 byte。
 *
 * <p>OSS 凭据只能由运行环境注入，不得记录或返回给客户端。</p>
 */
@ConfigurationProperties(prefix = "platform.media")
public record MediaProperties(
        Messaging messaging,
        Upload upload,
        Maintenance maintenance,
        Worker worker,
        Oss oss,
        ServiceAuthorization serviceAuthorization) {

    public record Messaging(boolean enabled, Duration outboxPollDelay, Duration confirmTimeout,
            int maxPublishAttempts) {}

    public record Upload(Duration grantTtl, Duration sessionTtl, long multipartThreshold, long partSize) {}

    public record Maintenance(boolean enabled, Duration fixedDelay, Duration unboundRetention) {}

    /** FFmpeg HLS 处理参数；maxConcurrentJobs 是单实例硬上限，防止大视频无限并发耗尽本机资源。 */
    public record Worker(boolean enabled, String ffmpegPath, Duration timeout,
            int maxConcurrentJobs, Duration hlsSegmentDuration) {}

    public record Oss(boolean enabled, String endpoint, String region, String bucket, String keyPrefix,
            String accessKeyId, String accessKeySecret, String securityToken, Duration accessGrantTtl) {}

    /** 可信领域服务令牌的验证参数；secret 不得通过 HTTP、日志或数据库传播。 */
    public record ServiceAuthorization(String issuer, String audience, Duration maxTokenTtl,
            Duration clockSkew, String secret) {}
}
