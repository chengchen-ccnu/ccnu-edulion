package com.eduplatform.learning.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * 题库媒体访问、导入保留期和可靠消息参数。
 *
 * @param media 仅包含 media-service HTTP 地址与超时，不包含 OSS 参数
 * @param course 版本化 course-service HTTP 地址与超时；失败时学习写事务不会提交
 * @param importSettings 导入大小、保留期、Outbox 发布和 RabbitMQ 开关
 */
@ConfigurationProperties("platform.learning")
public record LearningProperties(Media media, Course course, @Name("import") ImportSettings importSettings) {
    /** 学习域不持有 OSS 参数；只用短时服务令牌向 media-service 换取单资产授权。 */
    public record Media(String baseUrl, Duration requestTimeout, String authorizationIssuer,
            String authorizationAudience, Duration authorizationTokenTtl, String authorizationSecret) {}
    public record Course(String baseUrl, Duration requestTimeout) {}
    public record ImportSettings(long maxSourceBytes, Duration retention, boolean messagingEnabled,
            Duration outboxPollDelay, Duration confirmTimeout, int maxPublishAttempts) {}
}
