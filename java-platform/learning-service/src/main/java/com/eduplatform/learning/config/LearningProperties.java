package com.eduplatform.learning.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.Name;

/**
 * 题库媒体访问、导入保留期和可靠消息参数。
 *
 * @param media 仅包含 media-service HTTP 地址与超时，不包含 OSS 参数
 * @param course 版本化 course-service HTTP 地址与超时；失败时学习写事务不会提交
 * @param iam 仅包含 IAM 内部昵称批量投影地址与超时；失败时成绩册降级为空昵称
 * @param importSettings 导入大小、保留期、Outbox 发布和 RabbitMQ 开关
 */
@ConfigurationProperties("platform.learning")
public record LearningProperties(Media media, Course course, Iam iam, @Name("import") ImportSettings importSettings) {
    @ConstructorBinding
    public LearningProperties(Media media, Course course, Iam iam, @Name("import") ImportSettings importSettings) {
        this.media = media;
        this.course = course;
        this.iam = iam == null ? new Iam("http://127.0.0.1:8081", Duration.ofSeconds(1)) : iam;
        this.importSettings = importSettings == null
                ? new ImportSettings(104857600L, Duration.ofDays(7), false, Duration.ofSeconds(2),
                        Duration.ofSeconds(10), 20)
                : importSettings;
    }

    /** 兼容既有 HTTP 客户端测试；IAM 地址使用默认本地地址，运行时配置仍优先使用绑定属性。 */
    public LearningProperties(Media media, Course course, ImportSettings importSettings) {
        this(media, course, new Iam("http://127.0.0.1:8081", Duration.ofSeconds(1)), importSettings);
    }
    /** 学习域不持有 OSS 参数；只用短时服务令牌向 media-service 换取单资产授权。 */
    public record Media(String baseUrl, Duration requestTimeout, String authorizationIssuer,
            String authorizationAudience, Duration authorizationTokenTtl, String authorizationSecret) {}
    public record Course(String baseUrl, Duration requestTimeout, Duration factCacheTtl) {}
    /** IAM 仅提供内部昵称投影；Learning 不保存其返回值，也不依赖该调用完成成绩册查询。 */
    public record Iam(String baseUrl, Duration requestTimeout, String serviceToken) {
        /** 兼容旧客户端测试；生产服务令牌必须通过本地秘密环境注入。 */
        public Iam(String baseUrl, Duration requestTimeout) {
            this(baseUrl, requestTimeout, "");
        }
    }
    public record ImportSettings(long maxSourceBytes, Duration retention, boolean messagingEnabled,
            Duration outboxPollDelay, Duration confirmTimeout, int maxPublishAttempts) {}
}
