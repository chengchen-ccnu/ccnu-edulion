package com.eduplatform.iam.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.iam")
/**
 * IAM 令牌时限、登录限流与 local/test seed 配置。
 * secret 和 seed password 均来自秘密环境；生产环境必须关闭 seed 并启用 Secure Cookie。
 */
public record IamProperties(Jwt jwt, Refresh refresh, LoginRateLimit loginRateLimit,
        Verification verification, SessionCache sessionCache, Media media, Seed seed) {
    public record Jwt(String issuer, String audience, String secret, Duration accessTtl) {}
    public record Refresh(Duration ttl, boolean cookieSecure) {}
    public record LoginRateLimit(int attempts, Duration window) {}
    /** 验证码只允许 local/test 固定适配器；生产未接供应商时明确 503。 */
    public record Verification(Duration ttl, Duration cooldown, int maxAttempts, String deliveryMode,
            String fixedCode, String hmacSecret) {}
    /** Gateway 与 IAM 共用 sessionVersion Key；TTL 到期后要求刷新令牌重建。 */
    public record SessionCache(String keyPrefix, Duration ttl) {}
    /** IAM 仅调用 media-service JSON 绑定验证，不持有 OSS 参数。 */
    public record Media(String baseUrl, Duration requestTimeout) {}
    public record Seed(boolean enabled, String password) {}
}
