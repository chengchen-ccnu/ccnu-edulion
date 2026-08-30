package com.eduplatform.iam.web;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.config.TraceFilter;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.AuthenticatedUser;
import com.eduplatform.iam.service.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
/**
 * IAM 浏览器接口适配器：访问令牌放在 JSON 响应，刷新令牌只放入 HttpOnly Cookie。
 *
 * <p>控制器不自行判断角色；当前用户接口依赖 Gateway/资源服务器验证后的 JWT。</p>
 */
public class AuthController {
    static final String REFRESH_COOKIE = "EDU_REFRESH";

    private final AuthService authService;
    private final IamProperties properties;

    public AuthController(AuthService authService, IamProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/auth/login")
    /** 校验登录请求并设置刷新 Cookie；认证失败由统一异常处理器返回错误信封。 */
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request) {
        TokenPair tokens = body.deviceId() == null && body.deviceName() == null
                ? authService.login(body.account(), body.password(), request.getRemoteAddr(),
                        (String) request.getAttribute(TraceFilter.ATTRIBUTE))
                : authService.login(body.account(), body.password(), request.getRemoteAddr(),
                        (String) request.getAttribute(TraceFilter.ATTRIBUTE),
                        body.deviceId() == null ? null : body.deviceId().toString(), body.deviceName());
        return withRefreshCookie(tokens);
    }

    @PostMapping("/auth/refresh")
    /** 轮换 Cookie 中的刷新令牌，并用新 Cookie 覆盖旧值。 */
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/auth/logout")
    /** 撤销当前刷新会话并立即清除浏览器 Cookie；缺少 Cookie 仍返回成功。 */
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        ResponseCookie cleared = cookie("").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }

    @GetMapping("/users/me")
    /** 返回 JWT subject 对应用户的实时角色和权限，用于前端恢复登录态。 */
    public CurrentUserResponse currentUser(JwtAuthenticationToken authentication) {
        Number sessionVersion = authentication.getToken().getClaim("sessionVersion");
        if (sessionVersion == null) throw new com.eduplatform.iam.service.AuthException(
                HttpStatus.UNAUTHORIZED, "IAM.SESSION_INVALID", "登录会话已失效，请重新登录");
        AuthenticatedUser user = authService.currentUser(authentication.getToken().getSubject(),
                sessionVersion.longValue());
        return new CurrentUserResponse(user.id(), user.displayName(), user.avatarUrl(),
                user.roles(), user.permissions(), user.sessionVersion());
    }

    private ResponseEntity<LoginResponse> withRefreshCookie(TokenPair tokens) {
        ResponseCookie cookie = cookie(tokens.refreshToken()).maxAge(properties.refresh().ttl()).build();
        LoginResponse body = new LoginResponse("Bearer", tokens.accessToken(), tokens.expiresIn());
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.refresh().cookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth");
    }
}
