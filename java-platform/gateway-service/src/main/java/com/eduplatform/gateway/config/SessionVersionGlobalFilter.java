package com.eduplatform.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * 对每个已认证请求比较 JWT 与 Redis 中的账号会话版本。
 *
 * <p>IAM 在登录/刷新时从 MySQL 权威事实填充缓存，在密码变更、禁用或强制下线前先删除
 * 缓存。Key 缺失或 Redis 故障均明确 503；因此不会把缓存缺失误当作“版本有效”。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.gateway.session-validation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SessionVersionGlobalFilter implements GlobalFilter, Ordered {
    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;
    private final ObjectMapper mapper;

    public SessionVersionGlobalFilter(ReactiveStringRedisTemplate redis,
            GatewayProperties properties, ObjectMapper mapper) {
        this.redis = redis;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!requiresValidation(exchange.getRequest().getPath().value())) return chain.filter(exchange);
        return ReactiveSecurityContextHolder.getContext()
                .defaultIfEmpty(new SecurityContextImpl())
                .flatMap(context -> context.getAuthentication() instanceof JwtAuthenticationToken token
                        ? validate(exchange, chain, token) : chain.filter(exchange));
    }

    private Mono<Void> validate(ServerWebExchange exchange, GatewayFilterChain chain,
            JwtAuthenticationToken authentication) {
        Number tokenVersion = authentication.getToken().getClaim("sessionVersion");
        if (tokenVersion == null) {
            return GatewayErrorWriter.write(exchange, mapper, HttpStatus.UNAUTHORIZED,
                    "IAM.SESSION_INVALID", "登录会话已失效，请重新登录", null);
        }
        String key = properties.sessionValidation().keyPrefix() + ":" + authentication.getToken().getSubject();
        return redis.opsForValue().get(key)
                .map(cached -> new CacheDecision(cached, false))
                .switchIfEmpty(Mono.just(new CacheDecision(null, true)))
                .onErrorResume(error -> Mono.just(new CacheDecision(null, true)))
                .flatMap(decision -> {
                    if (decision.unavailable()) return unavailable(exchange);
                    if (Long.toString(tokenVersion.longValue()).equals(decision.value())) return chain.filter(exchange);
                    return GatewayErrorWriter.write(exchange, mapper, HttpStatus.UNAUTHORIZED,
                            "IAM.SESSION_INVALID", "登录会话已失效，请重新登录", null);
                });
    }

    private Mono<Void> unavailable(ServerWebExchange exchange) {
        long retry = Math.max(1L, properties.sessionValidation().unavailableRetryAfter().toSeconds());
        return GatewayErrorWriter.write(exchange, mapper, HttpStatus.SERVICE_UNAVAILABLE,
                "GATEWAY.SECURITY_STATE_UNAVAILABLE", "身份安全服务暂时不可用，请稍后重试", retry);
    }

    private record CacheDecision(String value, boolean unavailable) {}

    /** 刷新、退出和公开账号恢复端点必须能在旧会话失效后继续完成恢复或清理。 */
    static boolean requiresValidation(String path) {
        return !path.equals("/api/v1/auth/login")
                && !path.equals("/api/v1/auth/refresh")
                && !path.equals("/api/v1/auth/logout")
                && !path.equals("/api/v1/auth/registration-challenges")
                && !path.equals("/api/v1/auth/register")
                && !path.equals("/api/v1/auth/password-reset-challenges")
                && !path.equals("/api/v1/auth/password-reset");
    }

    @Override public int getOrder() { return -2; }
}
