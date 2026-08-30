package com.eduplatform.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gateway")
/** Gateway 可访问前端源与 JWT 信任边界；secret 必须由本地秘密注入，禁止提交真实值。 */
public record GatewayProperties(List<String> allowedOrigins, Jwt jwt, RateLimit rateLimit,
        SessionValidation sessionValidation) {
    public record Jwt(String issuer, String audience, String secret) {}
    /** Redis 固定窗限流配置；所有实例共享同一前缀和窗口状态。 */
    public record RateLimit(boolean enabled, String keyPrefix) {}
    /** 访问令牌会话版本缓存；缺失或故障均保守拒绝，刷新/登录可重建。 */
    public record SessionValidation(boolean enabled, String keyPrefix, Duration unavailableRetryAfter) {}
}
