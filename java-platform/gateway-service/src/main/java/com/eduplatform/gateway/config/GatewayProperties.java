package com.eduplatform.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.gateway")
public record GatewayProperties(List<String> allowedOrigins, Jwt jwt) {
    public record Jwt(String issuer, String audience, String secret) {}
}
