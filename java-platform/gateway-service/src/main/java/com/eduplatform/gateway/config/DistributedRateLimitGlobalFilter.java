package com.eduplatform.gateway.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 用 Redis Lua 固定窗实现多实例共享限流。
 *
 * <p>覆盖登录、注册/验证码/重置、上传凭证、题库导入和答题提交。Redis 故障时这些
 * 高风险写入口返回明确 503，不会退化成单机计数或无限流。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.gateway.rate-limit", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class DistributedRateLimitGlobalFilter implements GlobalFilter, Ordered {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if current > tonumber(ARGV[1]) then return {0, ttl} end
            return {1, ttl}
            """, List.class);

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;
    private final ObjectMapper mapper;

    public DistributedRateLimitGlobalFilter(ReactiveStringRedisTemplate redis,
            GatewayProperties properties, ObjectMapper mapper) {
        this.redis = redis;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Policy policy = policy(exchange.getRequest().getMethod(), exchange.getRequest().getPath().value());
        if (policy == null) return chain.filter(exchange);
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication() instanceof JwtAuthenticationToken jwt
                        ? "user:" + jwt.getToken().getSubject() : remote(exchange))
                .defaultIfEmpty(remote(exchange))
                .flatMap(subject -> evaluate(exchange, chain, policy, subject));
    }

    private Mono<Void> evaluate(ServerWebExchange exchange, GatewayFilterChain chain, Policy policy, String subject) {
        String key = properties.rateLimit().keyPrefix() + ":" + policy.name() + ":" + digest(subject);
        return redis.execute(SCRIPT, List.of(key),
                        List.of(Long.toString(policy.limit()), Long.toString(policy.windowMillis())))
                .next()
                .map(result -> new RedisDecision(result, false))
                .switchIfEmpty(Mono.just(new RedisDecision(List.of(), true)))
                .onErrorResume(error -> Mono.just(new RedisDecision(List.of(), true)))
                .flatMap(decision -> decision.unavailable() ? securityUnavailable(exchange)
                        : applyDecision(exchange, chain, decision.result()));
    }

    /** Redis 调用的错误只影响限流判定，不能吞掉下游领域服务自身的异常。 */
    private Mono<Void> applyDecision(ServerWebExchange exchange, GatewayFilterChain chain, List<?> result) {
        long allowed = ((Number) result.get(0)).longValue();
        long ttlMillis = Math.max(1L, ((Number) result.get(1)).longValue());
        if (allowed == 1L) return chain.filter(exchange);
        return GatewayErrorWriter.write(exchange, mapper, HttpStatus.TOO_MANY_REQUESTS,
                "GATEWAY.RATE_LIMITED", "请求过于频繁，请稍后再试", (ttlMillis + 999L) / 1000L);
    }

    private Mono<Void> securityUnavailable(ServerWebExchange exchange) {
        long retry = Math.max(1L, properties.sessionValidation().unavailableRetryAfter().toSeconds());
        return GatewayErrorWriter.write(exchange, mapper, HttpStatus.SERVICE_UNAVAILABLE,
                "GATEWAY.SECURITY_STATE_UNAVAILABLE", "安全限流服务暂时不可用，请稍后重试", retry);
    }

    /** 根据已评审端点选择独立窗口，未匹配请求不创建 Redis Key。 */
    static Policy policy(HttpMethod method, String path) {
        if (method != HttpMethod.POST && method != HttpMethod.PUT && method != HttpMethod.DELETE) return null;
        if (path.equals("/api/v1/auth/login")) return new Policy("login", 10, 60_000);
        if (method == HttpMethod.POST && (path.equals("/api/v1/auth/captchas")
                || path.matches("/api/v1/auth/captchas/[^/]+/refresh"))) {
            return new Policy("captcha", 20, 60_000);
        }
        if (path.equals("/api/v1/auth/registration-challenges")
                || path.equals("/api/v1/auth/password-reset-challenges")
                || path.equals("/api/v1/auth/verification-login-challenges")) {
            return new Policy("verification", 5, 60_000);
        }
        if (method == HttpMethod.POST && path.equals("/api/v1/auth/verification-login")) {
            return new Policy("verification-login", 10, 60_000);
        }
        if (path.equals("/api/v1/auth/register")) return new Policy("registration", 5, 60_000);
        if (path.equals("/api/v1/auth/admin-invitations/accept")) return new Policy("admin-invitation-accept", 5, 60_000);
        if (path.equals("/api/v1/admin/invitations") || path.matches("/api/v1/admin/invitations/[^/]+/resend"))
            return new Policy("admin-invitation-send", 5, 60_000);
        if (path.equals("/api/v1/auth/password-reset")) return new Policy("password-reset", 5, 60_000);
        if (method == HttpMethod.POST && path.equals("/api/v1/users/me/identity-verification-challenges")) {
            return new Policy("identity-verification", 5, 60_000);
        }
        if ((method == HttpMethod.POST && path.equals("/api/v1/users/me/identities"))
                || ((method == HttpMethod.PUT || method == HttpMethod.DELETE)
                && path.matches("/api/v1/users/me/identities/[^/]+"))) {
            return new Policy("identity-mutation", 5, 60_000);
        }
        if (method != HttpMethod.POST) return null;
        if (path.startsWith("/api/v1/media/uploads")) return new Policy("upload-credential", 30, 60_000);
        if ((path.contains("question-import") || path.contains("legacy-question-import"))
                && (path.endsWith("imports") || path.endsWith("batches"))) return new Policy("question-import", 10, 60_000);
        if (path.matches("/api/v1/learning/attempts/[^/]+/submission")) return new Policy("attempt-submit", 20, 60_000);
        return null;
    }

    private static String remote(ServerWebExchange exchange) {
        return "ip:" + (exchange.getRequest().getRemoteAddress() == null ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record Policy(String name, long limit, long windowMillis) {}
    private record RedisDecision(List<?> result, boolean unavailable) {}

    @Override public int getOrder() { return -3; }
}
