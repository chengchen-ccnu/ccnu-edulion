package com.eduplatform.course.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 课程域调用媒体契约和发布媒体引用事件的非敏感运行参数。
 *
 * <p>baseUrl 只指向 media-service JSON API；course-service 不获得 OSS 凭据、Bucket 或 Object Key。</p>
 */
@ConfigurationProperties(prefix = "platform.course.media")
public record CourseMediaProperties(String baseUrl, Duration requestTimeout, Messaging messaging,
        ServiceAuthorization serviceAuthorization) {
    /** Outbox 补投与发布确认上限；达到上限后记录保留为 FAILED，课程事务不会回滚。 */
    public record Messaging(boolean enabled, Duration outboxPollDelay, Duration confirmTimeout,
            int maxPublishAttempts) {}

    /** course-service 为单次媒体访问签发的服务令牌参数；secret 只能由本机密钥管理或 CI Secret 注入。 */
    public record ServiceAuthorization(String issuer, String audience, Duration tokenTtl, String secret) {}
}
