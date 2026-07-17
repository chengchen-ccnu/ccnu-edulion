package com.eduplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.gateway.config.TrustedIdentityGlobalFilter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class TrustedIdentityGlobalFilterTest {
    @Test
    void removesSpoofedHeadersAndInjectsVerifiedClaims() {
        TrustedIdentityGlobalFilter filter = new TrustedIdentityGlobalFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
                .header("X-User-Id", "attacker").header("X-User-Roles", "ADMIN"));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("verified-user")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("roles", List.of("STUDENT")).claim("permissions", List.of("learning:access")).build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, value -> { forwarded.set(value); return Mono.empty(); })
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)))
                .block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("verified-user");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("STUDENT");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Permissions")).isEqualTo("learning:access");
    }
}
