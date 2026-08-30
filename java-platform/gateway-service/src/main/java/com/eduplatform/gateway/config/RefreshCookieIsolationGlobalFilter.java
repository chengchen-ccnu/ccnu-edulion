package com.eduplatform.gateway.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Refresh Cookie 的 Path 扩到 /api/v1 以支持身份绑定读取当前会话；本过滤器在转发到非 IAM
 * 服务前精确移除 EDU_REFRESH，避免课程、媒体、学习和运营服务接触刷新凭据。
 */
@Component
public class RefreshCookieIsolationGlobalFilter implements GlobalFilter, Ordered {
    private static final String REFRESH_COOKIE = "EDU_REFRESH";

    /** @return IAM 路由保留刷新 Cookie，其他路由只保留与它无关的 Cookie。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        List<String> rawCookies = exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.COOKIE);
        if (isIamPath(path) || rawCookies.stream().noneMatch(value -> value.contains(REFRESH_COOKIE + "="))) {
            return chain.filter(exchange);
        }
        List<String> remaining = rawCookies.stream().flatMap(value -> Arrays.stream(value.split(";")))
                .map(String::trim)
                .filter(value -> !value.isEmpty() && !value.startsWith(REFRESH_COOKIE + "="))
                .toList();
        ServerWebExchange isolated = exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(HttpHeaders.COOKIE);
            if (!remaining.isEmpty()) headers.add(HttpHeaders.COOKIE, String.join("; ", remaining));
        })).build();
        return chain.filter(isolated);
    }

    static boolean isIamPath(String path) {
        return path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/users/")
                || path.startsWith("/api/v1/protocols/") || path.startsWith("/api/v1/teacher-applications")
                || path.startsWith("/api/v1/admin/users");
    }

    @Override public int getOrder() { return -5; }
}
