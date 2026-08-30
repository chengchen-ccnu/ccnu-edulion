package com.eduplatform.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Refresh Cookie 只允许转发给 IAM 路由，其他业务 Cookie 保持不变。 */
class RefreshCookieIsolationGlobalFilterTest {
    private final RefreshCookieIsolationGlobalFilter filter = new RefreshCookieIsolationGlobalFilter();

    @Test
    void stripsRefreshCookieFromNonIamRoutes() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/courses/public")
                .header(HttpHeaders.COOKIE, "EDU_REFRESH=secret; locale=zh-CN").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, value -> { forwarded.set(value); return Mono.empty(); }).block();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE))
                .isEqualTo("locale=zh-CN").doesNotContain("secret", "EDU_REFRESH");
    }

    @Test
    void keepsRefreshCookieForIdentityBindingRoute() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/users/me/identities")
                .header(HttpHeaders.COOKIE, "EDU_REFRESH=secret").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, value -> { forwarded.set(value); return Mono.empty(); }).block();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.COOKIE))
                .contains("EDU_REFRESH=secret");
    }
}
