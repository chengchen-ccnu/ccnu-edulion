package com.eduplatform.iam.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.iam")
/**
 * IAM 令牌时限、登录限流与 local/test seed 配置。
 * secret 和 seed password 均来自秘密环境；生产环境必须关闭 seed 并启用 Secure Cookie。
 */
public record IamProperties(Jwt jwt, Refresh refresh, LoginRateLimit loginRateLimit, Seed seed) {
    public record Jwt(String issuer, String audience, String secret, Duration accessTtl) {}
    public record Refresh(Duration ttl, boolean cookieSecure) {}
    public record LoginRateLimit(int attempts, Duration window) {}
    public record Seed(boolean enabled, String password) {}
}
