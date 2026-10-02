package com.eduplatform.iam.web;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.config.TraceFilter;
import com.eduplatform.iam.service.AdminUserService;
import com.eduplatform.iam.service.ArithmeticCaptchaService;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.AuthenticationChannelService;
import com.eduplatform.iam.service.IdentityService;
import com.eduplatform.iam.service.PasswordResetService;
import com.eduplatform.iam.service.ProtocolDocumentService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final ArithmeticCaptchaService captchas;
    private final AuthenticationChannelService channels;
    private final ProtocolDocumentService protocols;
    private final IdentityService identities;
    private final RegistrationService registrations;
    private final PasswordResetService resets;
    private final AuthService auth;
    private final TeacherApplicationService applications;
    private final AdminUserService admins;
    private final IamProperties properties;

    public AccountLifecycleController(VerificationChallengeService challenges, ArithmeticCaptchaService captchas,
            AuthenticationChannelService channels, ProtocolDocumentService protocols,
            IdentityService identities,
            RegistrationService registrations,
            PasswordResetService resets, AuthService auth, TeacherApplicationService applications,
            AdminUserService admins, IamProperties properties) {
        this.challenges = challenges; this.captchas = captchas; this.channels = channels; this.protocols = protocols;
        this.identities = identities;
        this.registrations = registrations; this.resets = resets; this.auth = auth;
        this.applications = applications; this.admins = admins; this.properties = properties;
    }

    /** 创建约两分钟的 Redis 算式挑战；响应只含图片，不含答案。 */
    @PostMapping("/auth/captchas")
    public ResponseEntity<AccountLifecycleDtos.ArithmeticCaptchaResponse> createCaptcha() {
        return ResponseEntity.status(201).body(captcha(captchas.create()));
    }

    /** 在有限刷新链中原子废弃旧挑战并返回新图片。 */
    @PostMapping("/auth/captchas/{captchaId}/refresh")
    public ResponseEntity<AccountLifecycleDtos.ArithmeticCaptchaResponse> refreshCaptcha(
            @PathVariable UUID captchaId) {
        return ResponseEntity.status(201).body(captcha(captchas.refresh(captchaId.toString())));
    }

    /** 返回环境安全的渠道能力；生产未配置短信时 PHONE 验证入口为 false。 */
    @GetMapping("/auth/channel-capabilities")
    public AccountLifecycleDtos.AuthenticationChannelCapabilities channelCapabilities() {
        AuthenticationChannelService.Capabilities current = channels.current();
        return new AccountLifecycleDtos.AuthenticationChannelCapabilities(List.of(
                capability(AccountLifecycleDtos.IdentityType.EMAIL, current.emailPasswordLogin(),
                        current.emailVerification()),
                capability(AccountLifecycleDtos.IdentityType.PHONE, current.phonePasswordLogin(),
                        current.phoneVerification())));
    }

    /** 返回当前已生效的不可变用户协议和隐私政策正文。 */
    @GetMapping("/protocols/current")
    public AccountLifecycleDtos.CurrentProtocolDocuments currentProtocols() {
        return new AccountLifecycleDtos.CurrentProtocolDocuments(protocols.current().stream().map(value ->
                new AccountLifecycleDtos.ProtocolDocumentResponse(value.getDocumentType(),
                        value.getDocumentVersion(), value.getBody(), value.getEffectiveAt(), value.isCurrent(),
                        value.isRequiresReconsent())).toList());
    }

    /** 查询本人脱敏身份和 revision；完整邮箱、手机号永不返回。 */
    @GetMapping("/users/me/identities")
    public AccountLifecycleDtos.VerifiedIdentityCollection identities(JwtAuthenticationToken token) {
        return identities.list(token.getToken().getSubject(), version(token));
    }

    /** 校验当前密码和 revision 后，向待绑定或换绑的新身份发送一次性验证码。 */
    @PostMapping("/users/me/identity-verification-challenges")
    public ResponseEntity<AccountLifecycleDtos.ChallengeResponse> identityChallenge(
            JwtAuthenticationToken token,
            @Valid @RequestBody AccountLifecycleDtos.IdentityChallengeRequest request) {
        return ResponseEntity.accepted().body(challenge(identities.createChallenge(
                token.getToken().getSubject(), version(token), request)));
    }

    /** 绑定第二身份，保留并升级当前 Refresh Session，撤销其他设备。 */
    @PostMapping("/users/me/identities")
    public AccountLifecycleDtos.IdentityMutationResult bindIdentity(JwtAuthenticationToken token,
            @CookieValue(name = AuthController.REFRESH_COOKIE, required = false) String refreshToken,
            @Valid @RequestBody AccountLifecycleDtos.BindIdentityRequest request, HttpServletRequest servlet) {
        return identities.bind(token.getToken().getSubject(), version(token), request, refreshToken,
                servlet.getRemoteAddr(), trace(servlet));
    }

    /** 换绑成功后撤销全部设备并清除 Refresh Cookie。 */
    @PutMapping("/users/me/identities/{identityType}")
    public ResponseEntity<AccountLifecycleDtos.IdentityMutationResult> replaceIdentity(
            JwtAuthenticationToken token, @PathVariable AccountLifecycleDtos.IdentityType identityType,
            @Valid @RequestBody AccountLifecycleDtos.BindIdentityRequest request, HttpServletRequest servlet) {
        AccountLifecycleDtos.IdentityMutationResult result = identities.replace(token.getToken().getSubject(),
                version(token), identityType.name(), request, servlet.getRemoteAddr(), trace(servlet));
        return withClearedCookie(result);
    }

    /** 解绑仍需保留至少一个身份；成功后撤销全部设备并清除 Refresh Cookie。 */
    @DeleteMapping("/users/me/identities/{identityType}")
    public ResponseEntity<AccountLifecycleDtos.IdentityMutationResult> unbindIdentity(
            JwtAuthenticationToken token, @PathVariable AccountLifecycleDtos.IdentityType identityType,
            @Valid @RequestBody AccountLifecycleDtos.UnbindIdentityRequest request, HttpServletRequest servlet) {
        AccountLifecycleDtos.IdentityMutationResult result = identities.unbind(token.getToken().getSubject(),
                version(token), identityType.name(), request, servlet.getRemoteAddr(), trace(servlet));
        return withClearedCookie(result);
    }

    /** 消费算式验证码后创建注册验证码；已占用身份保持相同 202 响应且不发送。 */
    @PostMapping("/auth/registration-challenges")
    public ResponseEntity<AccountLifecycleDtos.ChallengeResponse> registrationChallenge(
            @Valid @RequestBody AccountLifecycleDtos.ChallengeRequest request) {
        String type = request.identityType().name();
        challenges.requireSupported(type);
        consumeCaptcha(request.captcha());
        return ResponseEntity.accepted().body(challenge(challenges.create("REGISTRATION", type,
                request.target(), !auth.identityExists(type, request.target()))));
    }

    /** 消费注册验证码并创建服务端内部账号、已验证身份及当前协议同意事实。 */
    @PostMapping("/auth/register")
    public ResponseEntity<AccountLifecycleDtos.RegistrationResult> register(
            @Valid @RequestBody AccountLifecycleDtos.RegisterRequest request, HttpServletRequest servlet) {
        return ResponseEntity.status(201).body(registrations.register(request,
                servlet.getRemoteAddr(), trace(servlet)));
    }

    /** 消费算式验证码后创建不可枚举的密码找回验证码。 */
    @PostMapping("/auth/password-reset-challenges")
    public ResponseEntity<AccountLifecycleDtos.ChallengeResponse> passwordResetChallenge(
            @Valid @RequestBody AccountLifecycleDtos.ChallengeRequest request) {
        String type = request.identityType().name();
        challenges.requireSupported(type);
        consumeCaptcha(request.captcha());
        return ResponseEntity.accepted().body(challenge(challenges.create("PASSWORD_RESET", type,
                request.target(), auth.activeIdentityExists(type, request.target()))));
    }

    /** 验证码登录只向已有 ACTIVE 身份发送；未知身份仍返回相同 202 投影。 */
    @PostMapping("/auth/verification-login-challenges")
    public ResponseEntity<AccountLifecycleDtos.ChallengeResponse> verificationLoginChallenge(
            @Valid @RequestBody AccountLifecycleDtos.ChallengeRequest request) {
        String type = request.identityType().name();
        challenges.requireSupported(type);
        consumeCaptcha(request.captcha());
        return ResponseEntity.accepted().body(challenge(challenges.create("VERIFICATION_LOGIN", type,
                request.target(), auth.activeIdentityExists(type, request.target()))));
    }

    /** 消费找回挑战并重置匹配账号密码；未知身份保持相同无内容结果。 */
    @PostMapping("/auth/password-reset")
    public ResponseEntity<Void> passwordReset(@Valid @RequestBody AccountLifecycleDtos.ResetPasswordRequest request,
            HttpServletRequest servlet) {
        resets.reset(request, servlet.getRemoteAddr(), trace(servlet));
        return ResponseEntity.noContent().build();
    }

    /** 当前用户校验旧密码后修改密码并撤销所有设备。 */
    @PostMapping("/auth/password/change")
    public ResponseEntity<Void> changePassword(JwtAuthenticationToken token,
            @Valid @RequestBody AccountLifecycleDtos.ChangePasswordRequest request, HttpServletRequest servlet) {
        auth.changePassword(token.getToken().getSubject(), version(token), request.currentPassword(),
                request.newPassword(), servlet.getRemoteAddr(), trace(servlet));
        return cleared();
    }

    /** 递增持久会话版本并撤销当前账号的全部 Refresh Session。 */
    @PostMapping("/auth/logout-all")
    public ResponseEntity<Void> logoutAll(JwtAuthenticationToken token, HttpServletRequest servlet) {
        auth.logoutAll(token.getToken().getSubject(), version(token), servlet.getRemoteAddr(), trace(servlet));
        return cleared();
    }

    /** 返回本人活跃设备投影，原始 Refresh Token 及摘要不会出现在响应中。 */
    @GetMapping("/users/me/sessions")
    public List<AccountLifecycleDtos.AccountSession> sessions(JwtAuthenticationToken token,
            @CookieValue(name = AuthController.REFRESH_COOKIE, required = false) String refreshToken) {
        return auth.sessions(token.getToken().getSubject(), refreshToken).stream().map(value ->
                new AccountLifecycleDtos.AccountSession(UUID.fromString(value.id()), value.deviceName(), value.current(),
                        value.createdAt(), value.lastUsedAt(), value.expiresAt())).toList();
    }

    /** 提交普通账号的教师申请；只绑定 Media 已校验的稳定资质 mediaId。 */
    @PostMapping("/teacher-applications")
    public ResponseEntity<AccountLifecycleDtos.TeacherApplicationResponse> submitApplication(
            JwtAuthenticationToken token,
            @Valid @RequestBody AccountLifecycleDtos.SubmitTeacherApplicationRequest request,
            HttpServletRequest servlet) {
        TeacherApplicationService.Result result = applications.submit(token.getToken().getSubject(), request,
                servlet.getRemoteAddr(), trace(servlet));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.application());
    }

    /** 返回调用者自己的教师申请状态，不执行审核或授予教学权限。 */
    @GetMapping("/teacher-applications/me")
    public AccountLifecycleDtos.TeacherApplicationResponse myApplication(JwtAuthenticationToken token) {
        return applications.getMine(token.getToken().getSubject());
    }

    /** 按管理权限分页查询账号脱敏投影。 */
    @GetMapping("/admin/users")
    public AccountLifecycleDtos.AdminUserPage listUsers(JwtAuthenticationToken token, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) AccountLifecycleDtos.AccountStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return admins.list(token.getToken().getSubject(), keyword, status, page, size);
    }

    /** 按稳定 userId 查询单个管理投影。 */
    @GetMapping("/admin/users/{userId}")
    public AccountLifecycleDtos.AdminUserResponse getUser(JwtAuthenticationToken token, @PathVariable UUID userId) {
        return admins.get(token.getToken().getSubject(), userId.toString());
    }

    /** 管理员按 revision 幂等解除目标账号短期登录锁。 */
    @PostMapping("/admin/users/{userId}/unlock")
    public AccountLifecycleDtos.AdminCommandResult unlock(JwtAuthenticationToken token, @PathVariable UUID userId,
            @Valid @RequestBody AccountLifecycleDtos.AdminCommandRequest request, HttpServletRequest servlet) {
        return admins.unlock(token.getToken().getSubject(), userId.toString(), request,
                servlet.getRemoteAddr(), trace(servlet));
    }

    /** 旧入口废弃，返回409要求先预览并明确确认；不能绕过新权限命令。 */
    @PostMapping("/admin/users/{userId}/disable")
    public AccountLifecycleDtos.AdminCommandResult disable(JwtAuthenticationToken token, @PathVariable UUID userId,
            @Valid @RequestBody AccountLifecycleDtos.AdminCommandRequest request, HttpServletRequest servlet) {
        return admins.disable(token.getToken().getSubject(), userId.toString(), request,
                servlet.getRemoteAddr(), trace(servlet));
    }

    /** 管理员按 revision 强制目标账号全部设备下线。 */
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
    private void consumeCaptcha(AccountLifecycleDtos.ArithmeticCaptchaAnswer answer) {
        captchas.consume(answer.captchaId().toString(), answer.answer());
    }
    private static AccountLifecycleDtos.ArithmeticCaptchaResponse captcha(ArithmeticCaptchaService.Captcha value) {
        return new AccountLifecycleDtos.ArithmeticCaptchaResponse(UUID.fromString(value.captchaId()),
                value.imageMimeType(), value.imageBase64(), value.expiresIn(), value.refreshesRemaining());
    }
    private static AccountLifecycleDtos.AuthenticationChannelCapability capability(
            AccountLifecycleDtos.IdentityType type, boolean password, boolean verification) {
        return new AccountLifecycleDtos.AuthenticationChannelCapability(type, password, verification,
                verification, verification);
    }
    private long version(JwtAuthenticationToken token) {
        Number value = token.getToken().getClaim("sessionVersion");
        if (value == null) throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.SESSION_INVALID",
                "登录会话已失效，请重新登录");
        return value.longValue();
    }
    private ResponseEntity<Void> cleared() {
        ResponseCookie cookie = ResponseCookie.from(AuthController.REFRESH_COOKIE, "").httpOnly(true)
                .secure(properties.refresh().cookieSecure()).sameSite("Strict").path("/api/v1")
                .maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }
    private ResponseEntity<AccountLifecycleDtos.IdentityMutationResult> withClearedCookie(
            AccountLifecycleDtos.IdentityMutationResult body) {
        ResponseCookie cookie = ResponseCookie.from(AuthController.REFRESH_COOKIE, "").httpOnly(true)
                .secure(properties.refresh().cookieSecure()).sameSite("Strict").path("/api/v1")
                .maxAge(Duration.ZERO).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
    }
    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(TraceFilter.ATTRIBUTE);
    }
}
