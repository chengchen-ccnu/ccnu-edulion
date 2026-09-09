package com.eduplatform.agentaccess.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 接收 Gateway 可信 Trace 或生成本地 Trace，用于会话运行、领域调用和错误信封串联。 */
@Component
public class TraceFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = TraceFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Trace-Id");
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9_-]{8,64}")
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader("X-Trace-Id", traceId);
        chain.doFilter(request, response);
    }
}
