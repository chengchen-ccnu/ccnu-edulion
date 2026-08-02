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
/**
 * 规范化入口追踪 ID，并把同一 ID 同时写入下游请求、响应头和错误处理上下文。
 *
 * <p>只接受长度与字符集受限的外部值，非法或缺失时生成新值，防止日志注入。</p>
 */
public class TraceWebFilter implements WebFilter {
    public static final String ATTRIBUTE = TraceWebFilter.class.getName() + ".traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    /**
     * 为单次浏览器请求建立追踪上下文；不写数据库或外部系统。
     * @return 后续过滤链完成信号
     */
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
