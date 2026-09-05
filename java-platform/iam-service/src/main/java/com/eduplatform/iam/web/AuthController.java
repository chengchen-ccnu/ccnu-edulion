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
    private static final String REFRESH_COOKIE_PATH = "/api/v1";
    private static final String LEGACY_REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final IamProperties properties;

    public AuthController(AuthService authService, IamProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    /** 校验登录请求并设置刷新 Cookie；认证失败由统一异常处理器返回错误信封。 */
    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request) {
        AccountLifecycleDtos.ArithmeticCaptchaAnswer captcha = body.captcha();
        TokenPair tokens = authService.login(body.identity(), body.password(), request.getRemoteAddr(),
                (String) request.getAttribute(TraceFilter.ATTRIBUTE),
                body.deviceId() == null ? null : body.deviceId().toString(), body.deviceName(),
                captcha == null ? null : captcha.captchaId().toString(), captcha == null ? null : captcha.answer());
        return withRefreshCookie(tokens);
    }

    /** 一次消费验证码并登录既有账号；未知身份不会自动注册。 */
    @PostMapping("/auth/verification-login")
    public ResponseEntity<LoginResponse> verificationLogin(
            @Valid @RequestBody AccountLifecycleDtos.VerificationCodeLoginRequest body,
            HttpServletRequest request) {
        TokenPair tokens = authService.loginWithVerificationCode(body.challengeId().toString(),
                body.identityType().name(), body.target(), body.verificationCode(), request.getRemoteAddr(),
                (String) request.getAttribute(TraceFilter.ATTRIBUTE),
                body.deviceId() == null ? null : body.deviceId().toString(), body.deviceName());
        return withRefreshCookie(tokens);
    }

    /** 轮换 Cookie 中的刷新令牌，并用新 Cookie 覆盖旧值。 */
    @PostMapping("/auth/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    /** 撤销当前刷新会话并立即清除浏览器 Cookie；缺少 Cookie 仍返回成功。 */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        ResponseCookie cleared = cookie("", REFRESH_COOKIE_PATH).maxAge(Duration.ZERO).build();
        ResponseCookie legacyCleared = cookie("", LEGACY_REFRESH_COOKIE_PATH).maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                cleared.toString(), legacyCleared.toString()).build();
    }

    /** 返回 JWT subject 对应用户的实时角色和权限，用于前端恢复登录态。 */
    @GetMapping("/users/me")
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
        ResponseCookie cookie = cookie(tokens.refreshToken(), REFRESH_COOKIE_PATH)
                .maxAge(properties.refresh().ttl()).build();
        // T12D.1 曾将 Path 从 /api/v1/auth 扩到 /api/v1；成功签发时同步清除旧 Cookie，
        // 避免浏览器同时发送两个同名值并让刷新接口读取到已经失效的旧会话。
        ResponseCookie legacyCleared = cookie("", LEGACY_REFRESH_COOKIE_PATH).maxAge(Duration.ZERO).build();
        LoginResponse body = new LoginResponse("Bearer", tokens.accessToken(), tokens.expiresIn());
        return ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.SET_COOKIE,
                cookie.toString(), legacyCleared.toString()).body(body);
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String value, String path) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.refresh().cookieSecure())
                .sameSite("Strict")
                .path(path);
    }
}
