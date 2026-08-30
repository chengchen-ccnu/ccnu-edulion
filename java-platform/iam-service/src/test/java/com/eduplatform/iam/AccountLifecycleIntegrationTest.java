package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.eduplatform.iam.media.TeacherQualificationMediaClient;
import com.eduplatform.iam.service.AdminUserService;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.PasswordResetService;
import com.eduplatform.iam.service.RegistrationService;
import com.eduplatform.iam.service.TeacherApplicationService;
import com.eduplatform.iam.service.TokenPair;
import com.eduplatform.iam.service.VerificationChallengeService;
import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** T12D 端到端服务层验收：一次验证、MySQL 事实、会话失效、教师申请与管理命令。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:iam_lifecycle_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class AccountLifecycleIntegrationTest {
    private static final String CODE = "246810";
    private static final String ADMIN_ID = "00000000-0000-4000-8000-000000009901";

    @Autowired VerificationChallengeService challenges;
    @Autowired RegistrationService registrations;
    @Autowired PasswordResetService resets;
    @Autowired AuthService auth;
    @Autowired TeacherApplicationService applications;
    @Autowired AdminUserService admins;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired IdentitySecurityStore securityStore;
    @Autowired SecurityDigests digests;
    @Autowired IamProperties properties;
    @MockitoBean TeacherQualificationMediaClient media;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM teacher_application_command");
        jdbc.update("DELETE FROM teacher_application");
        jdbc.update("DELETE FROM admin_account_command");
        jdbc.update("DELETE FROM identity_change_command");
        jdbc.update("DELETE FROM account_security_audit");
        jdbc.update("DELETE FROM protocol_agreement");
        jdbc.update("DELETE FROM registration_request");
        jdbc.update("DELETE FROM user_identity");
        jdbc.update("DELETE FROM login_audit");
        jdbc.update("DELETE FROM refresh_session");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("DELETE FROM `role`");
        jdbc.update("DELETE FROM permission");
        jdbc.update("DELETE FROM protocol_document");
        insertCurrentProtocol("00000000-0000-4000-8000-000000009801", "TERMS");
        insertCurrentProtocol("00000000-0000-4000-8000-000000009802", "PRIVACY");
    }

    @Test
    /** Given 固定 local/test 验证码；When 注册、重置、改密和全退；Then 挑战一次消费且版本/会话事实持久化。 */
    void registrationPasswordLifecycleAndSessionVersionAreTransactional() {
        Registered registered = register("lifecycle_student", "lifecycle_student@example.invalid");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identity WHERE user_id = ?", Integer.class,
                registered.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_agreement WHERE user_id = ?", Integer.class,
                registered.userId())).isEqualTo(2);

        AccountLifecycleDtos.RegisterRequest replay = new AccountLifecycleDtos.RegisterRequest(
                registered.requestId(), registered.challengeId(), AccountLifecycleDtos.IdentityType.EMAIL,
                registered.email(), CODE, "生命周期测试学生", "Initial-password-01",
                agreements());
        assertThat(registrations.register(replay, "127.0.0.1", "trace-replay").userId().toString())
                .isEqualTo(registered.userId());
        AccountLifecycleDtos.RegisterRequest reusedChallenge = new AccountLifecycleDtos.RegisterRequest(
                UUID.randomUUID(), registered.challengeId(), AccountLifecycleDtos.IdentityType.EMAIL,
                registered.email(), CODE, "重放学生", "Initial-password-01", agreements());
        assertThatThrownBy(() -> registrations.register(reusedChallenge, "127.0.0.1", "trace-reuse"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("IAM.VERIFICATION_FAILED"));

        TokenPair first = auth.login(registered.email(), "Initial-password-01", "127.0.0.1", "trace-login",
                UUID.randomUUID().toString(), "Chrome 测试设备");
        assertThat(auth.sessions(registered.userId(), first.refreshToken())).singleElement()
                .satisfies(session -> assertThat(session.current()).isTrue());

        VerificationChallengeService.Challenge reset = challenges.create("PASSWORD_RESET", "EMAIL", registered.email());
        resets.reset(new AccountLifecycleDtos.ResetPasswordRequest(UUID.fromString(reset.challengeId()),
                AccountLifecycleDtos.IdentityType.EMAIL, registered.email(), CODE, "Reset-password-02"),
                "127.0.0.1", "trace-reset");
        assertVersionAndNoActiveSessions(registered.userId(), 1L);
        assertInvalidRefresh(first.refreshToken());

        TokenPair second = auth.login(registered.email(), "Reset-password-02", "127.0.0.1", "trace-login-2");
        auth.changePassword(registered.userId(), 1L, "Reset-password-02", "Changed-password-03",
                "127.0.0.1", "trace-change");
        assertVersionAndNoActiveSessions(registered.userId(), 2L);
        assertInvalidRefresh(second.refreshToken());

        TokenPair third = auth.login(registered.email(), "Changed-password-03", "127.0.0.1", "trace-login-3");
        auth.logoutAll(registered.userId(), 2L, "127.0.0.1", "trace-all");
        assertVersionAndNoActiveSessions(registered.userId(), 3L);
        assertInvalidRefresh(third.refreshToken());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_security_audit WHERE user_id = ?",
                Integer.class, registered.userId())).isEqualTo(4);
    }

    @Test
    /** 未知邮箱的正确挑战也返回成功语义，避免重置结果成为账号存在性探针。 */
    void passwordResetDoesNotEnumerateUnknownIdentity() {
        String email = "unknown_t12d@example.invalid";
        VerificationChallengeService.Challenge challenge = challenges.create("PASSWORD_RESET", "EMAIL", email);
        resets.reset(new AccountLifecycleDtos.ResetPasswordRequest(UUID.fromString(challenge.challengeId()),
                AccountLifecycleDtos.IdentityType.EMAIL, email, CODE, "Unknown-password-01"),
                "127.0.0.1", "trace-unknown-reset");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT action FROM account_security_audit", String.class))
                .isEqualTo("PASSWORD_RESET_NO_CHANGE");
    }

    @Test
    /** 注册只接受当前协议版本；内部 account 由服务端生成，中文重名昵称允许而邮箱大小写唯一。 */
    void registrationUsesCurrentProtocolsServerAccountAndNormalizedUniqueIdentity() {
        String staleEmail = "stale_protocol@example.invalid";
        VerificationChallengeService.Challenge staleChallenge = challenges.create("REGISTRATION", "EMAIL", staleEmail);
        var stale = new AccountLifecycleDtos.RegisterRequest(UUID.randomUUID(),
                UUID.fromString(staleChallenge.challengeId()), AccountLifecycleDtos.IdentityType.EMAIL, staleEmail,
                CODE, "同名中文昵称", "Initial-password-01", List.of(
                        new AccountLifecycleDtos.Agreement("TERMS", "old-v1"),
                        new AccountLifecycleDtos.Agreement("PRIVACY", "test-v1")));
        assertThatThrownBy(() -> registrations.register(stale, "127.0.0.1", "trace-stale"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("IAM.PROTOCOL_VERSION_INVALID"));
        var corrected = new AccountLifecycleDtos.RegisterRequest(stale.clientRequestId(), stale.challengeId(),
                stale.identityType(), stale.target(), stale.verificationCode(), stale.nickname(), stale.password(),
                agreements());
        var first = registrations.register(corrected, "127.0.0.1", "trace-current");
        assertThat(first.nickname()).isEqualTo("同名中文昵称");
        String internal = jdbc.queryForObject("SELECT account FROM `user` WHERE id=?", String.class,
                first.userId().toString());
        assertThat(internal).startsWith("usr_").hasSize(36);

        VerificationChallengeService.Challenge secondChallenge = challenges.create("REGISTRATION", "EMAIL",
                "second_nickname@example.invalid");
        var second = registrations.register(new AccountLifecycleDtos.RegisterRequest(UUID.randomUUID(),
                UUID.fromString(secondChallenge.challengeId()), AccountLifecycleDtos.IdentityType.EMAIL,
                "second_nickname@example.invalid", CODE, "同名中文昵称", "Initial-password-01", agreements()),
                "127.0.0.1", "trace-second");
        assertThat(second.nickname()).isEqualTo(first.nickname());

        String thirtyVisibleCharacters = "😀".repeat(30);
        VerificationChallengeService.Challenge unicodeChallenge = challenges.create("REGISTRATION", "EMAIL",
                "unicode_nickname@example.invalid");
        var unicode = registrations.register(new AccountLifecycleDtos.RegisterRequest(UUID.randomUUID(),
                UUID.fromString(unicodeChallenge.challengeId()), AccountLifecycleDtos.IdentityType.EMAIL,
                "unicode_nickname@example.invalid", CODE, thirtyVisibleCharacters,
                "Initial-password-01", agreements()), "127.0.0.1", "trace-unicode");
        assertThat(unicode.nickname()).isEqualTo(thirtyVisibleCharacters);
        assertThatThrownBy(() -> registrations.register(new AccountLifecycleDtos.RegisterRequest(UUID.randomUUID(),
                UUID.randomUUID(), AccountLifecycleDtos.IdentityType.EMAIL, "too_long@example.invalid", CODE,
                "😀".repeat(31), "Initial-password-01", agreements()), "127.0.0.1", "trace-too-long"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMON.VALIDATION_FAILED"));

        VerificationChallengeService.Challenge duplicate = challenges.create("REGISTRATION", "EMAIL",
                " STALE_PROTOCOL@EXAMPLE.INVALID ", false);
        assertThatThrownBy(() -> registrations.register(new AccountLifecycleDtos.RegisterRequest(UUID.randomUUID(),
                UUID.fromString(duplicate.challengeId()), AccountLifecycleDtos.IdentityType.EMAIL,
                " STALE_PROTOCOL@EXAMPLE.INVALID ", CODE, "另一昵称", "Initial-password-01", agreements()),
                "127.0.0.1", "trace-duplicate"))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("IAM.IDENTITY_OCCUPIED");
                });
    }

    @Test
    /** Given 普通学生和 READY 资质 mediaId 绑定端口；When 提交与幂等重放；Then 只保存 SUBMITTED 事实，不授予教师能力。 */
    void teacherApplicationBindsStableMediaAndDoesNotImplementReview() {
        Registered registered = register("teacher_candidate", "teacher_candidate@example.invalid");
        UUID mediaId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var request = new AccountLifecycleDtos.SubmitTeacherApplicationRequest(requestId, null, mediaId,
                "这是一份仅用于自动化验收的教师申请说明，不包含真实个人信息。");

        TeacherApplicationService.Result created = applications.submit(registered.userId(), request,
                "127.0.0.1", "trace-application");
        TeacherApplicationService.Result replayed = applications.submit(registered.userId(), request,
                "127.0.0.1", "trace-application-replay");

        assertThat(created.created()).isTrue();
        assertThat(created.application().status()).isEqualTo("SUBMITTED");
        assertThat(replayed.application().id()).isEqualTo(created.application().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_application", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN `role` r ON r.id=ur.role_id "
                + "WHERE ur.user_id=? AND r.code='TEACHER'", Integer.class, registered.userId())).isZero();
        verify(media, times(1)).requireBindable(mediaId.toString(), registered.userId(), "trace-application");
    }

    @Test
    /** Given 已登录学生与管理员；When 解锁、强制下线、禁用并重放；Then 乐观修订和幂等审计事实一致。 */
    void adminCommandsUnlockRevokeDisableAndReplaySafely() {
        Registered registered = register("admin_target", "admin_target@example.invalid");
        insertAdmin();
        TokenPair active = auth.login(registered.email(), "Initial-password-01", "127.0.0.9", "trace-login");
        for (int attempt = 1; attempt <= 5; attempt++) {
            String captchaId = attempt >= 4 ? captcha("7") : null;
            try { auth.login(registered.email(), "wrong-password", "127.0.0.9", "trace-failure",
                    null, null, captchaId, captchaId == null ? null : "7"); }
            catch (AuthException ignored) { }
        }
        assertThat(admins.get(registered.userId()).loginLocked()).isTrue();

        var unlocked = admins.unlock(ADMIN_ID, registered.userId(), command(0L, "解锁自动化验收中的测试账号"),
                "127.0.0.1", "trace-unlock");
        assertThat(unlocked.user().loginLocked()).isFalse();
        var revokedRequest = command(1L, "强制下线自动化验收中的测试账号");
        var revoked = admins.forceLogout(ADMIN_ID, registered.userId(), revokedRequest,
                "127.0.0.1", "trace-revoke");
        assertThat(revoked.user().sessionVersion()).isEqualTo(1L);
        assertInvalidRefresh(active.refreshToken());

        var disableRequest = command(2L, "禁用自动化验收中的测试账号");
        var disabled = admins.disable(ADMIN_ID, registered.userId(), disableRequest,
                "127.0.0.1", "trace-disable");
        var replayed = admins.disable(ADMIN_ID, registered.userId(), disableRequest,
                "127.0.0.1", "trace-disable-replay");
        assertThat(disabled.user().status()).isEqualTo("DISABLED");
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.user().revision()).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_account_command", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_security_audit WHERE actor_id = ?",
                Integer.class, ADMIN_ID)).isEqualTo(3);
    }

    private Registered register(String account, String email) {
        VerificationChallengeService.Challenge challenge = challenges.create("REGISTRATION", "EMAIL", email);
        UUID requestId = UUID.randomUUID();
        AccountLifecycleDtos.RegisterRequest request = new AccountLifecycleDtos.RegisterRequest(requestId,
                UUID.fromString(challenge.challengeId()), AccountLifecycleDtos.IdentityType.EMAIL, email, CODE,
                "生命周期测试学生", "Initial-password-01", agreements());
        String userId = registrations.register(request, "127.0.0.1", "trace-register").userId().toString();
        return new Registered(userId, email, requestId, UUID.fromString(challenge.challengeId()));
    }

    private static List<AccountLifecycleDtos.Agreement> agreements() {
        return List.of(new AccountLifecycleDtos.Agreement("TERMS", "test-v1"),
                new AccountLifecycleDtos.Agreement("PRIVACY", "test-v1"));
    }

    private void insertAdmin() {
        jdbc.update("INSERT INTO `role` (code, name) VALUES ('ADMIN', 'ADMIN')");
        jdbc.update("INSERT INTO permission (code, name) VALUES ('administration:access', 'administration:access')");
        jdbc.update("INSERT INTO role_permission (role_id, permission_id) SELECT r.id,p.id FROM `role` r, permission p "
                + "WHERE r.code='ADMIN' AND p.code='administration:access'");
        jdbc.update("INSERT INTO `user` (id, account, display_name, password_hash, status) VALUES (?,?,?,?, 'ACTIVE')",
                ADMIN_ID, "admin_t12d", "T12D 测试管理员", encoder.encode("Admin-password-01"));
        jdbc.update("INSERT INTO user_role (user_id, role_id) SELECT ?,id FROM `role` WHERE code='ADMIN'", ADMIN_ID);
    }

    private void insertCurrentProtocol(String id, String type) {
        jdbc.update("INSERT INTO protocol_document (id,document_type,document_version,body,effective_at,is_current,"
                + "requires_reconsent,created_at) VALUES (?,?,?,'仅用于自动化测试，不可用于正式发布',"
                + "CURRENT_TIMESTAMP,TRUE,FALSE,CURRENT_TIMESTAMP)", id, type, "test-v1");
    }

    private String captcha(String answer) {
        String id = UUID.randomUUID().toString();
        securityStore.createCaptcha(id, digests.digest("captcha:" + id, answer), properties.captcha().ttl(),
                properties.captcha().maxRefreshes());
        return id;
    }

    private static AccountLifecycleDtos.AdminCommandRequest command(long revision, String reason) {
        return new AccountLifecycleDtos.AdminCommandRequest(UUID.randomUUID(), revision, reason);
    }

    private void assertVersionAndNoActiveSessions(String userId, long expectedVersion) {
        assertThat(jdbc.queryForObject("SELECT session_version FROM `user` WHERE id = ?", Long.class, userId))
                .isEqualTo(expectedVersion);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_session WHERE user_id = ? AND status='ACTIVE'",
                Integer.class, userId)).isZero();
    }

    private void assertInvalidRefresh(String refreshToken) {
        assertThatThrownBy(() -> auth.refresh(refreshToken)).isInstanceOf(AuthException.class);
    }

    private record Registered(String userId, String email, UUID requestId, UUID challengeId) {}
}
