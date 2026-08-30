package com.eduplatform.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** T12D Gateway Redis 安全边界：覆盖策略映射、故障关闭与下游异常保真。 */
class DistributedSecurityFilterTest {
    private final GatewayProperties properties = new GatewayProperties(List.of(),
            new GatewayProperties.Jwt("issuer", "audience", "test-only-secret"),
            new GatewayProperties.RateLimit(true, "test:rate"),
            new GatewayProperties.SessionValidation(true, "test:session", Duration.ofSeconds(3)));
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsEveryRequiredHighRiskPostEndpoint() {
        assertPolicy("/api/v1/auth/login", "login");
        assertPolicy("/api/v1/auth/registration-challenges", "verification");
        assertPolicy("/api/v1/auth/password-reset-challenges", "verification");
        assertPolicy("/api/v1/auth/verification-login-challenges", "verification");
        assertPolicy("/api/v1/auth/verification-login", "verification-login");
        assertPolicy("/api/v1/auth/captchas", "captcha");
        assertPolicy("/api/v1/auth/captchas/00000000-0000-4000-8000-000000000001/refresh", "captcha");
        assertPolicy("/api/v1/users/me/identity-verification-challenges", "identity-verification");
        assertPolicy("/api/v1/users/me/identities", "identity-mutation");
        assertThat(DistributedRateLimitGlobalFilter.policy(HttpMethod.PUT,
                "/api/v1/users/me/identities/EMAIL")).extracting(
                        DistributedRateLimitGlobalFilter.Policy::name).isEqualTo("identity-mutation");
        assertThat(DistributedRateLimitGlobalFilter.policy(HttpMethod.DELETE,
                "/api/v1/users/me/identities/PHONE")).extracting(
                        DistributedRateLimitGlobalFilter.Policy::name).isEqualTo("identity-mutation");
        assertPolicy("/api/v1/auth/register", "registration");
        assertPolicy("/api/v1/auth/password-reset", "password-reset");
        assertPolicy("/api/v1/media/uploads", "upload-credential");
        assertPolicy("/api/v1/media/uploads/00000000-0000-4000-8000-000000000001/parts/authorize",
                "upload-credential");
        assertPolicy("/api/v1/teacher/question-imports", "question-import");
        assertPolicy("/api/v1/admin/legacy-question-imports/x/batches", "question-import");
        assertPolicy("/api/v1/learning/attempts/00000000-0000-4000-8000-000000000001/submission",
                "attempt-submit");
        assertThat(DistributedRateLimitGlobalFilter.policy(HttpMethod.GET, "/api/v1/auth/login")).isNull();
        assertThat(SessionVersionGlobalFilter.requiresValidation("/api/v1/auth/logout")).isFalse();
        assertThat(SessionVersionGlobalFilter.requiresValidation("/api/v1/auth/refresh")).isFalse();
        assertThat(SessionVersionGlobalFilter.requiresValidation("/api/v1/users/me")).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redisFailureFailsClosedWithRetryAfter() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn((Flux) Flux.error(new IllegalStateException("redis")));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/auth/login").remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 1234)).build());

        new DistributedRateLimitGlobalFilter(redis, properties, mapper).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("GATEWAY.SECURITY_STATE_UNAVAILABLE");
        verify(chain, never()).filter(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void allowedRequestDoesNotRewriteDownstreamFailure() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn((Flux) Flux.just(List.of(1L, 60_000L)));
        GatewayFilterChain chain = exchange -> Mono.error(new IllegalStateException("downstream"));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login").build());

        assertThatThrownBy(() -> new DistributedRateLimitGlobalFilter(redis, properties, mapper)
                .filter(exchange, chain).block()).hasMessage("downstream");
    }

    @Test
    void missingSessionCacheFailsClosed() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.empty());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me").build());
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
                Map.of("sub", "00000000-0000-4000-8000-000000000001", "sessionVersion", 4L));

        new SessionVersionGlobalFilter(redis, properties, mapper).filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
        verify(chain, never()).filter(any());
    }

    private static void assertPolicy(String path, String name) {
        assertThat(DistributedRateLimitGlobalFilter.policy(HttpMethod.POST, path)).isNotNull()
                .extracting(DistributedRateLimitGlobalFilter.Policy::name).isEqualTo(name);
    }
}
