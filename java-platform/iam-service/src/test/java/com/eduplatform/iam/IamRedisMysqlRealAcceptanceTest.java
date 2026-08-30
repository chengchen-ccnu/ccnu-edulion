package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.RegistrationService;
import com.eduplatform.iam.service.VerificationChallengeService;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 显式开关下对专用 IAM MySQL 与 Redis 执行 T12D 真实环境验收。 */
@SpringBootTest(properties = {
        "platform.iam.seed.enabled=false",
        "platform.iam.jwt.secret=test-only-real-iam-secret-with-at-least-32-bytes",
        "platform.iam.verification.delivery-mode=fixed-local",
        "platform.iam.verification.fixed-code=246810",
        "platform.iam.verification.cooldown=1ms",
        "platform.iam.verification.hmac-secret=test-only-real-verification-hmac-32-bytes"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "IAM_REDIS_MYSQL_ACCEPTANCE", matches = "true")
class IamRedisMysqlRealAcceptanceTest {
    @Autowired VerificationChallengeService challenges;
    @Autowired RegistrationService registrations;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired SecurityDigests digests;

    /** 在 Spring 连接前限定专用 dev Schema，防止验收误连其他数据库。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_iam_dev".equals(System.getenv("IAM_MYSQL_DATABASE"))) {
            throw new IllegalStateException("IAM acceptance requires edu_platform_iam_dev");
        }
    }

    @Test
    /** 创建单个随机测试账号，验证 Redis 挑战/锁定/会话缓存与 MySQL 事实后精确清理。 */
    void realMysqlAndRedisPreserveAuthorityAndEphemeralSecurityState() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_iam_dev");
        assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String account = "t12d_" + suffix;
        String email = account + "@example.invalid";
        UUID requestId = UUID.randomUUID();
        String userId = null;
        String challengeId = null;
        String prefix = "edu:iam";
        String accountDigest = digests.digest("login-account", account);
        String addressDigest = digests.digest("login-address", "127.0.0.77");
        String targetDigest = digests.digest("target", email);
        try {
            VerificationChallengeService.Challenge challenge = challenges.create("REGISTRATION", "EMAIL", email);
            challengeId = challenge.challengeId();
            var request = new AccountLifecycleDtos.RegisterRequest(requestId, UUID.fromString(challengeId),
                    AccountLifecycleDtos.IdentityType.EMAIL, email, "246810", "T12D 真实验收学生",
                    "Acceptance-password-01", List.of(
                            new AccountLifecycleDtos.Agreement("TERMS", "acceptance-v1"),
                            new AccountLifecycleDtos.Agreement("PRIVACY", "acceptance-v1")));
            userId = registrations.register(request, "127.0.0.77", "t12d-real-register").userId().toString();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identity WHERE user_id=?", Integer.class, userId))
                    .isEqualTo(1);
            assertThat(redis.hasKey(prefix + ":challenge:" + challengeId)).isFalse();

            var tokens = auth.login(account, "Acceptance-password-01", "127.0.0.77", "t12d-real-login");
            assertThat(redis.opsForValue().get(prefix + ":session-version:" + userId)).isEqualTo("0");
            for (int attempt = 0; attempt < 5; attempt++) {
                try { auth.login(account, "wrong-password", "127.0.0.77", "t12d-real-failure"); }
                catch (AuthException ignored) { }
            }
            assertThatThrownBy(() -> auth.login(account, "Acceptance-password-01", "127.0.0.77", "t12d-real-locked"))
                    .isInstanceOfSatisfying(AuthException.class,
                            error -> assertThat(error.status().value()).isEqualTo(429));
            redis.delete(List.of(prefix + ":login-failure:account:" + accountDigest,
                    prefix + ":login-lock:account:" + accountDigest,
                    prefix + ":login-failure:ip:" + addressDigest,
                    prefix + ":login-lock:ip:" + addressDigest));
            auth.logoutAll(userId, 0L, "127.0.0.77", "t12d-real-logout-all");
            assertThat(redis.hasKey(prefix + ":session-version:" + userId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT session_version FROM `user` WHERE id=?", Long.class, userId))
                    .isEqualTo(1L);
            assertThatThrownBy(() -> auth.refresh(tokens.refreshToken())).isInstanceOf(AuthException.class);
        } finally {
            redis.delete(List.of(prefix + ":challenge:" + (challengeId == null ? "none" : challengeId),
                    prefix + ":challenge-cooldown:REGISTRATION:" + targetDigest,
                    prefix + ":login-failure:account:" + accountDigest,
                    prefix + ":login-lock:account:" + accountDigest,
                    prefix + ":login-failure:ip:" + addressDigest,
                    prefix + ":login-lock:ip:" + addressDigest,
                    prefix + ":session-version:" + (userId == null ? "none" : userId)));
            if (userId != null) {
                jdbc.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?", userId, userId);
                jdbc.update("DELETE FROM protocol_agreement WHERE user_id=?", userId);
                jdbc.update("DELETE FROM registration_request WHERE user_id=?", userId);
                jdbc.update("DELETE FROM user_identity WHERE user_id=?", userId);
                jdbc.update("DELETE FROM login_audit WHERE user_id=? OR account=?", userId, account);
                jdbc.update("DELETE FROM refresh_session WHERE user_id=?", userId);
                jdbc.update("DELETE FROM user_role WHERE user_id=?", userId);
                jdbc.update("DELETE FROM `user` WHERE id=?", userId);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE account=?", Integer.class, account)).isZero();
    }
}
