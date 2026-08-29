package com.eduplatform.iam.web;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.config.TraceFilter;
import com.eduplatform.iam.service.AdminUserService;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.PasswordResetService;
import com.eduplatform.iam.service.RegistrationService;
import com.eduplatform.iam.service.TeacherApplicationService;
import com.eduplatform.iam.service.VerificationChallengeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** T12D 账号生命周期、教师申请提交和管理员账号控制 HTTP 边界。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class AccountLifecycleController {
    private final VerificationChallengeService challenges;
    private final RegistrationService registrations;
    private final PasswordResetService resets;
    private final AuthService auth;
    private final TeacherApplicationService applications;
    private final AdminUserService admins;
    private final IamProperties properties;

    public AccountLifecycleController(VerificationChallengeService challenges, RegistrationService registrations,
            PasswordResetService resets, AuthService auth, TeacherApplicationService applications,
            AdminUserService admins, IamProperties properties) {
        this.challenges = challenges; this.registrations = registrations; this.resets = resets; this.auth = auth;
        this.applications = applications; this.admins = admins; this.properties = properties;
    }

    @PostMapping("/auth/registration-challenges")
    public ResponseEntity<AccountLifecycleDtos.ChallengeResponse> registrationChallenge(
            @Valid @RequestBody AccountLifecycleDtos.ChallengeRequest request) {
        return ResponseEntity.accepted().body(challenge(challenges.create("REGISTRATION",
                request.identityType().name(), request.target())));
    }

    @PostMapping("/auth/register")
    public ResponseEntity<AccountLifecycleDtos.RegistrationResult> register(
            @Valid @RequestBody AccountLifecycleDtos.RegisterRequest request, HttpServletRequest servlet) {
        return ResponseEntity.status(201).body(registrations.register(request,
                servlet.getRemoteAddr(), trace(servlet)));
    }

    @PostMapping("/auth/password-reset-challenges")
    public ResponseEntity<AccountLifecycleDtos.ChallengeResponse> passwordResetChallenge(
            @Valid @RequestBody AccountLifecycleDtos.ChallengeRequest request) {
        return ResponseEntity.accepted().body(challenge(challenges.create("PASSWORD_RESET",
                request.identityType().name(), request.target())));
    }

    @PostMapping("/auth/password-reset")
    public ResponseEntity<Void> passwordReset(@Valid @RequestBody AccountLifecycleDtos.ResetPasswordRequest request,
            HttpServletRequest servlet) {
        resets.reset(request, servlet.getRemoteAddr(), trace(servlet));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/password/change")
    public ResponseEntity<Void> changePassword(JwtAuthenticationToken token,
            @Valid @RequestBody AccountLifecycleDtos.ChangePasswordRequest request, HttpServletRequest servlet) {
        auth.changePassword(token.getToken().getSubject(), version(token), request.currentPassword(),
                request.newPassword(), servlet.getRemoteAddr(), trace(servlet));
        return cleared();
    }

    @PostMapping("/auth/logout-all")
    public ResponseEntity<Void> logoutAll(JwtAuthenticationToken token, HttpServletRequest servlet) {
        auth.logoutAll(token.getToken().getSubject(), version(token), servlet.getRemoteAddr(), trace(servlet));
        return cleared();
    }

    @GetMapping("/users/me/sessions")
    public List<AccountLifecycleDtos.AccountSession> sessions(JwtAuthenticationToken token,
            @CookieValue(name = AuthController.REFRESH_COOKIE, required = false) String refreshToken) {
        return auth.sessions(token.getToken().getSubject(), refreshToken).stream().map(value ->
                new AccountLifecycleDtos.AccountSession(UUID.fromString(value.id()), value.deviceName(), value.current(),
                        value.createdAt(), value.lastUsedAt(), value.expiresAt())).toList();
    }

    @PostMapping("/teacher-applications")
    public ResponseEntity<AccountLifecycleDtos.TeacherApplicationResponse> submitApplication(
            JwtAuthenticationToken token,
            @Valid @RequestBody AccountLifecycleDtos.SubmitTeacherApplicationRequest request,
            HttpServletRequest servlet) {
        TeacherApplicationService.Result result = applications.submit(token.getToken().getSubject(), request,
                servlet.getRemoteAddr(), trace(servlet));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.application());
    }

    @GetMapping("/teacher-applications/me")
    public AccountLifecycleDtos.TeacherApplicationResponse myApplication(JwtAuthenticationToken token) {
        return applications.getMine(token.getToken().getSubject());
    }

    @GetMapping("/admin/users")
    public AccountLifecycleDtos.AdminUserPage listUsers(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) AccountLifecycleDtos.AccountStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return admins.list(keyword, status, page, size);
    }

    @GetMapping("/admin/users/{userId}")
    public AccountLifecycleDtos.AdminUserResponse getUser(@PathVariable UUID userId) {
        return admins.get(userId.toString());
    }

    @PostMapping("/admin/users/{userId}/unlock")
    public AccountLifecycleDtos.AdminCommandResult unlock(JwtAuthenticationToken token, @PathVariable UUID userId,
            @Valid @RequestBody AccountLifecycleDtos.AdminCommandRequest request, HttpServletRequest servlet) {
        return admins.unlock(token.getToken().getSubject(), userId.toString(), request,
                servlet.getRemoteAddr(), trace(servlet));
    }

    @PostMapping("/admin/users/{userId}/disable")
    public AccountLifecycleDtos.AdminCommandResult disable(JwtAuthenticationToken token, @PathVariable UUID userId,
            @Valid @RequestBody AccountLifecycleDtos.AdminCommandRequest request, HttpServletRequest servlet) {
        return admins.disable(token.getToken().getSubject(), userId.toString(), request,
                servlet.getRemoteAddr(), trace(servlet));
    }

    @PostMapping("/admin/users/{userId}/session-revocation")
    public AccountLifecycleDtos.AdminCommandResult forceLogout(JwtAuthenticationToken token, @PathVariable UUID userId,
            @Valid @RequestBody AccountLifecycleDtos.AdminCommandRequest request, HttpServletRequest servlet) {
        return admins.forceLogout(token.getToken().getSubject(), userId.toString(), request,
                servlet.getRemoteAddr(), trace(servlet));
    }

    private AccountLifecycleDtos.ChallengeResponse challenge(VerificationChallengeService.Challenge value) {
        return new AccountLifecycleDtos.ChallengeResponse(UUID.fromString(value.challengeId()),
                AccountLifecycleDtos.VerificationPurpose.valueOf(value.purpose()), value.expiresIn(),
                value.resendAfter(), value.deliveryHint());
    }
    private long version(JwtAuthenticationToken token) {
        Number value = token.getToken().getClaim("sessionVersion");
        if (value == null) throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.SESSION_INVALID",
                "登录会话已失效，请重新登录");
        return value.longValue();
    }
    private ResponseEntity<Void> cleared() {
        ResponseCookie cookie = ResponseCookie.from(AuthController.REFRESH_COOKIE, "").httpOnly(true)
                .secure(properties.refresh().cookieSecure()).sameSite("Strict").path("/api/v1/auth")
                .maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }
    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(TraceFilter.ATTRIBUTE);
    }
}
