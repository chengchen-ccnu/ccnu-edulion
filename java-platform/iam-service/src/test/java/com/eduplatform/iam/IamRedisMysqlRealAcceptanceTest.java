package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.RegistrationService;
import com.eduplatform.iam.service.VerificationChallengeService;
import com.eduplatform.iam.service.ProtocolDocumentService;
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
    @Autowired IdentitySecurityStore securityStore;
    @Autowired IamProperties properties;
    @Autowired ProtocolDocumentService protocols;

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
        String email = "t12d1_" + suffix + "@example.invalid";
        UUID requestId = UUID.randomUUID();
        String userId = null;
        String challengeId = null;
        String prefix = "edu:iam";
        String accountDigest = null;
        String addressDigest = digests.digest("login-address", "127.0.0.77");
        String targetDigest = digests.digest("target", email);
        java.util.ArrayList<String> captchaIds = new java.util.ArrayList<>();
        java.util.ArrayList<String> protocolIds = new java.util.ArrayList<>();
        try {
            String oneUseCaptcha = UUID.randomUUID().toString();
            captchaIds.add(oneUseCaptcha);
            securityStore.createCaptcha(oneUseCaptcha, "answer-one", properties.captcha().ttl(), 2);
            assertThat(securityStore.consumeCaptcha(oneUseCaptcha, "answer-one", 2)).isTrue();
            assertThat(securityStore.consumeCaptcha(oneUseCaptcha, "answer-one", 2)).isFalse();

            String errorLimitedCaptcha = UUID.randomUUID().toString();
            captchaIds.add(errorLimitedCaptcha);
            securityStore.createCaptcha(errorLimitedCaptcha, "answer-two", properties.captcha().ttl(), 2);
            assertThat(securityStore.consumeCaptcha(errorLimitedCaptcha, "wrong", 2)).isFalse();
            assertThat(securityStore.consumeCaptcha(errorLimitedCaptcha, "wrong", 2)).isFalse();
            assertThat(securityStore.consumeCaptcha(errorLimitedCaptcha, "answer-two", 2)).isFalse();

            String refreshRoot = UUID.randomUUID().toString();
            String refreshSecond = UUID.randomUUID().toString();
            String refreshThird = UUID.randomUUID().toString();
            String refreshRejected = UUID.randomUUID().toString();
            captchaIds.addAll(List.of(refreshRoot, refreshSecond, refreshThird, refreshRejected));
            securityStore.createCaptcha(refreshRoot, "refresh-one", properties.captcha().ttl(), 2);
            var firstRefresh = securityStore.refreshCaptcha(refreshRoot, refreshSecond, "refresh-two",
                    properties.captcha().ttl(), 2);
            assertThat(firstRefresh.refreshed()).isTrue();
            assertThat(firstRefresh.refreshesRemaining()).isEqualTo(1);
            assertThat(securityStore.consumeCaptcha(refreshRoot, "refresh-one", 2)).isFalse();
            var secondRefresh = securityStore.refreshCaptcha(refreshSecond, refreshThird, "refresh-three",
                    properties.captcha().ttl(), 2);
            assertThat(secondRefresh.refreshed()).isTrue();
            assertThat(secondRefresh.refreshesRemaining()).isZero();
            var rejectedRefresh = securityStore.refreshCaptcha(refreshThird, refreshRejected, "refresh-four",
                    properties.captcha().ttl(), 2);
            assertThat(rejectedRefresh.refreshed()).isFalse();
            assertThat(rejectedRefresh.exhausted()).isTrue();

            ensureCurrentProtocol("TERMS", protocolIds);
            ensureCurrentProtocol("PRIVACY", protocolIds);
            VerificationChallengeService.Challenge challenge = challenges.create("REGISTRATION", "EMAIL", email);
            challengeId = challenge.challengeId();
            var request = new AccountLifecycleDtos.RegisterRequest(requestId, UUID.fromString(challengeId),
                    AccountLifecycleDtos.IdentityType.EMAIL, email, "246810", "T12D 真实验收学生",
                    "Acceptance-password-01", protocols.current().stream().map(value ->
                            new AccountLifecycleDtos.Agreement(value.getDocumentType(), value.getDocumentVersion()))
                            .toList());
            userId = registrations.register(request, "127.0.0.77", "t12d-real-register").userId().toString();
            accountDigest = digests.digest("login-user", userId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identity WHERE user_id=?", Integer.class, userId))
                    .isEqualTo(1);
            assertThat(redis.hasKey(prefix + ":challenge:" + challengeId)).isFalse();

            var tokens = auth.login(email, "Acceptance-password-01", "127.0.0.77", "t12d-real-login");
            assertThat(redis.opsForValue().get(prefix + ":session-version:" + userId)).isEqualTo("0");
            for (int attempt = 1; attempt <= 5; attempt++) {
                String captchaId = null;
                if (attempt >= 4) {
                    captchaId = UUID.randomUUID().toString();
                    captchaIds.add(captchaId);
                    securityStore.createCaptcha(captchaId, digests.digest("captcha:" + captchaId, "7"),
                            properties.captcha().ttl(), properties.captcha().maxRefreshes());
                }
                try { auth.login(email, "wrong-password", "127.0.0.77", "t12d-real-failure",
                        null, null, captchaId, captchaId == null ? null : "7"); }
                catch (AuthException ignored) { }
            }
            assertThatThrownBy(() -> auth.login(email, "Acceptance-password-01", "127.0.0.77", "t12d-real-locked"))
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
            java.util.ArrayList<String> redisKeys = new java.util.ArrayList<>(List.of(
                    prefix + ":challenge:" + (challengeId == null ? "none" : challengeId),
                    prefix + ":challenge-cooldown:REGISTRATION:" + targetDigest,
                    prefix + ":login-failure:ip:" + addressDigest,
                    prefix + ":login-lock:ip:" + addressDigest,
                    prefix + ":session-version:" + (userId == null ? "none" : userId)));
            if (accountDigest != null) {
                redisKeys.add(prefix + ":login-failure:account:" + accountDigest);
                redisKeys.add(prefix + ":login-lock:account:" + accountDigest);
            }
            captchaIds.forEach(id -> redisKeys.add(prefix + ":captcha:" + id));
            redis.delete(redisKeys);
            if (userId != null) {
                jdbc.update("DELETE FROM identity_change_command WHERE user_id=?", userId);
                jdbc.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?", userId, userId);
                jdbc.update("DELETE FROM protocol_agreement WHERE user_id=?", userId);
                jdbc.update("DELETE FROM registration_request WHERE user_id=?", userId);
                jdbc.update("DELETE FROM user_identity WHERE user_id=?", userId);
                jdbc.update("DELETE FROM login_audit WHERE user_id=?", userId);
                jdbc.update("DELETE FROM refresh_session WHERE user_id=?", userId);
                jdbc.update("DELETE FROM user_role WHERE user_id=?", userId);
                jdbc.update("DELETE FROM `user` WHERE id=?", userId);
            }
            protocolIds.forEach(id -> jdbc.update("DELETE FROM protocol_document WHERE id=?", id));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identity WHERE normalized_value=?",
                Integer.class, email)).isZero();
    }

    private void ensureCurrentProtocol(String type, List<String> insertedIds) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM protocol_document WHERE document_type=? "
                + "AND is_current=TRUE AND effective_at<=CURRENT_TIMESTAMP", Integer.class, type);
        if (count == 1) return;
        if (count != 0) throw new IllegalStateException("Dedicated IAM dev schema has duplicate current protocols");
        String id = UUID.randomUUID().toString();
        insertedIds.add(id);
        jdbc.update("INSERT INTO protocol_document (id,document_type,document_version,body,effective_at,is_current,"
                + "requires_reconsent,created_at) VALUES (?,?,?,'真实验收测试正文，不可用于正式发布',"
                + "CURRENT_TIMESTAMP,TRUE,FALSE,CURRENT_TIMESTAMP)", id, type, "acceptance-" + id.substring(0, 8));
    }
}
