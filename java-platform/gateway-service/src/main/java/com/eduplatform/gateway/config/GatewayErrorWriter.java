package com.eduplatform.gateway.config;

import com.eduplatform.shared.api.ApiError;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** 为 Gateway 自定义安全过滤器输出统一错误信封，避免异常落到 HTML 默认页。 */
final class GatewayErrorWriter {
    private GatewayErrorWriter() {}

    /**
     * 终止当前请求并返回可追踪错误；可选 Retry-After 使用整数秒。
     * @return 响应写入完成信号
     */
    static Mono<Void> write(ServerWebExchange exchange, ObjectMapper mapper, HttpStatus status,
            String code, String message, Long retryAfterSeconds) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (retryAfterSeconds != null) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
                    Long.toString(Math.max(1L, retryAfterSeconds)));
        }
        ApiError error = new ApiError(code, message, exchange.getAttribute(TraceWebFilter.ATTRIBUTE),
                Instant.now(), exchange.getRequest().getPath().value(), List.of());
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(error);
        } catch (Exception serializationFailure) {
            bytes = "{\"code\":\"COMMON.INTERNAL_ERROR\",\"message\":\"服务暂时不可用\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
