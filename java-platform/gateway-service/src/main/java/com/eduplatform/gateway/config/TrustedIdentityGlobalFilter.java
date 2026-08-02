package com.eduplatform.gateway.config;

import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
/**
 * 在请求进入领域服务前移除浏览器伪造的身份头，并只用已验证 JWT 重建可信身份投影。
 *
 * <p>匿名请求只执行清理；已认证请求写入用户 ID、角色和权限。该过滤器不查询 IAM 数据库，
 * 下游只能把这些头作为来自 Gateway 的传输凭据，不能接受外部直连。</p>
 */
public class TrustedIdentityGlobalFilter implements GlobalFilter, Ordered {
    private static final List<String> TRUSTED_HEADERS =
            List.of("X-User-Id", "X-User-Roles", "X-User-Permissions");

    @Override
    /**
     * 清理输入身份头，并根据当前响应式安全上下文决定是否注入可信身份。
     *
     * @param exchange 浏览器进入 Gateway 的请求
     * @param chain 后续路由过滤器链
     * @return 下游处理完成信号；本方法不持久化身份数据
     */
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> withIdentity(exchange, context.getAuthentication()))
                .defaultIfEmpty(stripIdentity(exchange))
                .flatMap(chain::filter);
    }

    private ServerWebExchange withIdentity(ServerWebExchange exchange, Authentication authentication) {
        ServerWebExchange stripped = stripIdentity(exchange);
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return stripped;
        }
        Jwt jwt = token.getToken();
        List<String> roles = jwt.getClaimAsStringList("roles");
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return stripped.mutate().request(request -> request.headers(headers -> {
            headers.set("X-User-Id", jwt.getSubject());
            headers.set("X-User-Roles", String.join(",", roles == null ? List.of() : roles));
            headers.set("X-User-Permissions", String.join(",", permissions == null ? List.of() : permissions));
        })).build();
    }

    private ServerWebExchange stripIdentity(ServerWebExchange exchange) {
        return exchange.mutate().request(request -> request.headers(headers ->
                TRUSTED_HEADERS.forEach(headers::remove))).build();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
