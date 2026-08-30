package com.eduplatform.iam.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IAM 令牌时限、登录限流与 local/test seed 配置。
 * secret 和 seed password 均来自秘密环境；生产环境必须关闭 seed 并启用 Secure Cookie。
 */
@ConfigurationProperties(prefix = "platform.iam")
public record IamProperties(Jwt jwt, Refresh refresh, LoginRateLimit loginRateLimit,
        Verification verification, Captcha captcha, SessionCache sessionCache, Media media, Seed seed) {
    public record Jwt(String issuer, String audience, String secret, Duration accessTtl) {}
    public record Refresh(Duration ttl, boolean cookieSecure) {}
    public record LoginRateLimit(int attempts, Duration window) {}
    /** 一次性验证码通过供应商无关端口发送；固定码仅允许 local/test，生产邮箱使用 SMTP。 */
    public record Verification(Duration ttl, Duration cooldown, int maxAttempts, String deliveryMode,
            String fixedCode, String hmacSecret, String smtpFrom) {}
    /** Redis 算式验证码约两分钟过期，错误和刷新次数均有上限。 */
    public record Captcha(Duration ttl, int maxAttempts, int maxRefreshes) {}
    /** Gateway 与 IAM 共用 sessionVersion Key；TTL 到期后要求刷新令牌重建。 */
    public record SessionCache(String keyPrefix, Duration ttl) {}
    /** IAM 仅调用 media-service JSON 绑定验证，不持有 OSS 参数。 */
    public record Media(String baseUrl, Duration requestTimeout) {}
    public record Seed(boolean enabled, String password) {}
}
