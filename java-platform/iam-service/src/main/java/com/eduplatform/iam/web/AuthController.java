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
public class AuthController {
    static final String REFRESH_COOKIE = "EDU_REFRESH";

    private final AuthService authService;
    private final IamProperties properties;

    public AuthController(AuthService authService, IamProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request) {
        TokenPair tokens = authService.login(body.account(), body.password(), request.getRemoteAddr(),
                (String) request.getAttribute(TraceFilter.ATTRIBUTE));
        return withRefreshCookie(tokens);
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        ResponseCookie cleared = cookie("").maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }

    @GetMapping("/users/me")
    public CurrentUserResponse currentUser(JwtAuthenticationToken authentication) {
        AuthenticatedUser user = authService.currentUser(authentication.getToken().getSubject());
        return new CurrentUserResponse(user.id(), user.displayName(), user.avatarUrl(),
                user.roles(), user.permissions());
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
