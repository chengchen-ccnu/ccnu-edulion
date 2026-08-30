package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.IdentityService;
import com.eduplatform.iam.service.TokenPair;
import com.eduplatform.iam.service.VerificationChallengeService;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** T12D.1 身份状态机集成测试：revision、唯一性、会话撤销范围和最后身份保护。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:iam_identity_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class IdentityServiceIntegrationTest {
    private static final String USER_ID = "00000000-0000-4000-8000-000000008001";
    private static final String EMAIL = "identity_user@example.invalid";
    private static final String PASSWORD = "Identity-password-01";
    private static final String CODE = "246810";

    @Autowired IdentityService identities;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM identity_change_command");
        jdbc.update("DELETE FROM account_security_audit");
        jdbc.update("DELETE FROM user_identity");
        jdbc.update("DELETE FROM login_audit");
        jdbc.update("DELETE FROM refresh_session");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("DELETE FROM `role`");
        jdbc.update("DELETE FROM permission");
        jdbc.update("INSERT INTO `role` (id,code,name) VALUES (1,'STUDENT','STUDENT')");
        jdbc.update("INSERT INTO permission (id,code,name) VALUES (1,'learning:access','learning:access')");
        jdbc.update("INSERT INTO role_permission (role_id,permission_id) VALUES (1,1)");
        jdbc.update("INSERT INTO `user` (id,account,display_name,password_hash,status) VALUES (?,?,?,?, 'ACTIVE')",
                USER_ID, "usr_identity_test", "身份测试用户", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_role (user_id,role_id) VALUES (?,1)", USER_ID);
        jdbc.update("INSERT INTO user_identity (id,user_id,identity_type,normalized_value,verified_at,created_at) "
                + "VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), USER_ID,
                "EMAIL", EMAIL);
    }

    @Test
    /** 绑定保留当前设备，换绑/解绑全退，所有身份投影脱敏且最后身份不可删除。 */
    void bindReplaceAndUnbindApplyAtomicSessionScopes() {
        TokenPair current = auth.login(EMAIL, PASSWORD, "127.0.0.1", "trace-current", "device-a", "设备 A");
        TokenPair other = auth.login(EMAIL, PASSWORD, "127.0.0.2", "trace-other", "device-b", "设备 B");

        var bindChallenge = identities.createChallenge(USER_ID, 0L, new AccountLifecycleDtos.IdentityChallengeRequest(
                AccountLifecycleDtos.IdentityChangeOperation.BIND, AccountLifecycleDtos.IdentityType.PHONE,
                "13800138000", PASSWORD, 0L));
        UUID bindRequestId = UUID.randomUUID();
        var bind = identities.bind(USER_ID, 0L, new AccountLifecycleDtos.BindIdentityRequest(bindRequestId, 0L,
                UUID.fromString(bindChallenge.challengeId()), AccountLifecycleDtos.IdentityType.PHONE,
                "13800138000", CODE, PASSWORD), current.refreshToken(), "127.0.0.1", "trace-bind");

        assertThat(bind.revision()).isEqualTo(1L);
        assertThat(bind.sessionVersion()).isEqualTo(1L);
        assertThat(bind.revokedSessionScope()).isEqualTo("OTHER_DEVICES");
        assertThat(bind.session()).isNotNull();
        assertThat(bind.identities()).hasSize(2).allSatisfy(value ->
                assertThat(value.maskedValue()).doesNotContain(EMAIL, "13800138000", "+8613800138000"));
        assertThat(jdbc.queryForObject("SELECT normalized_value FROM user_identity WHERE identity_type='PHONE'",
                String.class)).isEqualTo("+8613800138000");
        assertThatThrownBy(() -> auth.refresh(other.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT session_version FROM refresh_session WHERE token_hash=?", Long.class,
                AuthService.hash(current.refreshToken()))).isEqualTo(1L);

        var replay = identities.bind(USER_ID, 1L, new AccountLifecycleDtos.BindIdentityRequest(bindRequestId, 0L,
                UUID.fromString(bindChallenge.challengeId()), AccountLifecycleDtos.IdentityType.PHONE,
                "13800138000", CODE, PASSWORD), current.refreshToken(), "127.0.0.1", "trace-replay");
        assertThat(replay.revision()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_change_command", Integer.class)).isEqualTo(1);

        var replaceChallenge = identities.createChallenge(USER_ID, 1L,
                new AccountLifecycleDtos.IdentityChallengeRequest(
                        AccountLifecycleDtos.IdentityChangeOperation.REPLACE, AccountLifecycleDtos.IdentityType.EMAIL,
                        "replacement@example.invalid", PASSWORD, 1L));
        var replace = identities.replace(USER_ID, 1L, "EMAIL", new AccountLifecycleDtos.BindIdentityRequest(
                UUID.randomUUID(), 1L, UUID.fromString(replaceChallenge.challengeId()),
                AccountLifecycleDtos.IdentityType.EMAIL, "replacement@example.invalid", CODE, PASSWORD),
                "127.0.0.1", "trace-replace");
        assertThat(replace.revokedSessionScope()).isEqualTo("ALL_DEVICES");
        assertThat(replace.reauthenticationRequired()).isTrue();
        assertThat(replace.session()).isNull();
        assertThatThrownBy(() -> auth.login(EMAIL, PASSWORD, "127.0.0.3", "trace-old"))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("IAM.BAD_CREDENTIALS"));

        TokenPair afterReplace = auth.login("REPLACEMENT@EXAMPLE.INVALID", PASSWORD,
                "127.0.0.4", "trace-new");
        var unbind = identities.unbind(USER_ID, 2L, "PHONE", new AccountLifecycleDtos.UnbindIdentityRequest(
                UUID.randomUUID(), 2L, PASSWORD), "127.0.0.1", "trace-unbind");
        assertThat(unbind.identities()).singleElement().satisfies(value ->
                assertThat(value.identityType()).isEqualTo(AccountLifecycleDtos.IdentityType.EMAIL));
        assertThat(unbind.revision()).isEqualTo(3L);
        assertThatThrownBy(() -> auth.refresh(afterReplace.refreshToken())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> identities.unbind(USER_ID, 3L, "EMAIL",
                new AccountLifecycleDtos.UnbindIdentityRequest(UUID.randomUUID(), 3L, PASSWORD),
                "127.0.0.1", "trace-last"))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("IAM.IDENTITY_CONFLICT");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_security_audit WHERE user_id=?",
                Integer.class, USER_ID)).isEqualTo(3);
    }

    @Test
    /** 旧 revision 和已被其他账号占用的目标均返回 409，不消费或覆盖现有身份。 */
    void revisionAndGlobalUniquenessConflictsAreRejected() {
        assertThatThrownBy(() -> identities.createChallenge(USER_ID, 0L,
                new AccountLifecycleDtos.IdentityChallengeRequest(AccountLifecycleDtos.IdentityChangeOperation.BIND,
                        AccountLifecycleDtos.IdentityType.PHONE, "13800138000", PASSWORD, 9L)))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.code()).isEqualTo("IAM.REVISION_CONFLICT"));
        String otherId = "00000000-0000-4000-8000-000000008002";
        jdbc.update("INSERT INTO `user` (id,account,display_name,password_hash,status) VALUES (?,?,?,?, 'ACTIVE')",
                otherId, "usr_other", "其他用户", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_identity (id,user_id,identity_type,normalized_value,verified_at,created_at) "
                + "VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), otherId,
                "PHONE", "+8613800138000");
        assertThatThrownBy(() -> identities.createChallenge(USER_ID, 0L,
                new AccountLifecycleDtos.IdentityChallengeRequest(AccountLifecycleDtos.IdentityChangeOperation.BIND,
                        AccountLifecycleDtos.IdentityType.PHONE, "13800138000", PASSWORD, 0L)))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("IAM.IDENTITY_CONFLICT");
                });
    }
}
