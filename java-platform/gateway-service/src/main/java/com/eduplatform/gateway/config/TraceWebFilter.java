package com.eduplatform.gateway.config;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceWebFilter implements WebFilter {
    public static final String ATTRIBUTE = TraceWebFilter.class.getName() + ".traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9_-]{8,64}")
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(ATTRIBUTE, traceId);
        exchange.getResponse().getHeaders().set(HEADER, traceId);
        ServerWebExchange traced = exchange.mutate().request(request -> request.headers(headers -> {
            headers.remove(HEADER);
            headers.set(HEADER, traceId);
        })).build();
        return chain.filter(traced);
    }
}
