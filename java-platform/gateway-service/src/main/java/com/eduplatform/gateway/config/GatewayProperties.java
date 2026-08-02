package com.eduplatform.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gateway")
/** Gateway 可访问前端源与 JWT 信任边界；secret 必须由本地秘密注入，禁止提交真实值。 */
public record GatewayProperties(List<String> allowedOrigins, Jwt jwt) {
    public record Jwt(String issuer, String audience, String secret) {}
}
