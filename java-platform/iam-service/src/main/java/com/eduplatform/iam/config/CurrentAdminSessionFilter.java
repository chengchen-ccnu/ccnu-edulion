package com.eduplatform.iam.config;

import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.shared.api.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** IAM管理HTTP入口的持久会话版本复核；Gateway的Redis安全拒绝保留，此处防直连IAM或解封后重用旧JWT。 */
public class CurrentAdminSessionFilter extends OncePerRequestFilter {
    private final IamMapper users;
    private final ObjectMapper json;
    public CurrentAdminSessionFilter(IamMapper users,ObjectMapper json) { this.users=users;this.json=json; }

    /** 已验签管理员JWT必须匹配当前ACTIVE版本；不存在/旧版本401，数据库不可用503，不向下游放行。 */
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/api/v1/admin/")
                && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            boolean valid;
            try {
                var user=users.findUserById(jwt.getToken().getSubject());
                Object version=jwt.getToken().getClaim("sessionVersion");
                valid=user!=null && "ACTIVE".equals(user.getStatus()) && version instanceof Number number
                        && user.getSessionVersion()==number.longValue();
            }catch(RuntimeException unavailable) {
                error(request,response,503,"IAM.SECURITY_STATE_UNAVAILABLE","身份安全服务暂时不可用，请稍后重试");return;
            }
            if(!valid) { error(request,response,401,"IAM.SESSION_INVALID","登录会话已失效，请重新登录");return; }
        }
        chain.doFilter(request,response);
    }
    private void error(HttpServletRequest request,HttpServletResponse response,int status,String code,String message)throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        if(status==503)response.setHeader("Retry-After","2");
        json.writeValue(response.getOutputStream(),new ApiError(code,message,(String)request.getAttribute(TraceFilter.ATTRIBUTE),
                Instant.now(),request.getRequestURI(),List.of()));
    }
}
