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
public class TrustedIdentityGlobalFilter implements GlobalFilter, Ordered {
    private static final List<String> TRUSTED_HEADERS =
            List.of("X-User-Id", "X-User-Roles", "X-User-Permissions");

    @Override
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
