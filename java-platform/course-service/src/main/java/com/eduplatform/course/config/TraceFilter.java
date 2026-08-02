package com.eduplatform.course.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
/** 复用合法 Gateway Trace ID 或生成新值，供 course-service 响应与统一错误信封关联。 */
public class TraceFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = TraceFilter.class.getName() + ".traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    /** 校验字符集和长度以防日志注入；本过滤器不修改课程数据。 */
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
