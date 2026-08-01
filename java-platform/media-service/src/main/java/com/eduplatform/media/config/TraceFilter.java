package com.eduplatform.media.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个 HTTP 请求复用或生成 Trace ID，并同时写入响应头和请求属性。
 *
 * <p>后续业务事务和 Outbox 使用同一值，使 HTTP、数据库与 MQ 链路可关联。</p>
 */
@Component
public class TraceFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = TraceFilter.class.getName() + ".traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9_-]{8,64}")
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        chain.doFilter(request, response);
    }
}
