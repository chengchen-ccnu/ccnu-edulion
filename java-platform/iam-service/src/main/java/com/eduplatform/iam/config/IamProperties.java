package com.eduplatform.iam.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.iam")
public record IamProperties(Jwt jwt, Refresh refresh, LoginRateLimit loginRateLimit, Seed seed) {
    public record Jwt(String issuer, String audience, String secret, Duration accessTtl) {}
    public record Refresh(Duration ttl, boolean cookieSecure) {}
    public record LoginRateLimit(int attempts, Duration window) {}
    public record Seed(boolean enabled, String password) {}
}
