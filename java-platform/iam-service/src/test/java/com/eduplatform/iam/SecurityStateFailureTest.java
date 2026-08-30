package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.service.ArithmeticCaptchaService;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.VerificationChallengeService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Redis 连接或脚本异常必须安全关闭为 503，不能跳过 CAPTCHA 或一次性验证码。 */
class SecurityStateFailureTest {
    @Test
    void captchaRedisFailureReturnsExplicit503() {
        IdentitySecurityStore store = mock(IdentitySecurityStore.class);
        when(store.consumeCaptcha(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("synthetic Redis outage"));
        IamProperties properties = properties();
        ArithmeticCaptchaService captchas = new ArithmeticCaptchaService(store,
                new SecurityDigests(properties), properties);

        assertThatThrownBy(() -> captchas.consume("00000000-0000-4000-8000-000000000001", "7"))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("IAM.SECURITY_STATE_UNAVAILABLE");
                });
    }

    @Test
    void verificationChallengeRedisFailureReturnsExplicit503() {
        IdentitySecurityStore store = mock(IdentitySecurityStore.class);
        when(store.createChallenge(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("synthetic Redis outage"));
        IamProperties properties = properties();
        VerificationChallengeService challenges = new VerificationChallengeService(store,
                new SecurityDigests(properties), properties, List.of());

        assertThatThrownBy(() -> challenges.create("VERIFICATION_LOGIN", "EMAIL", "user@example.invalid", false))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("IAM.SECURITY_STATE_UNAVAILABLE");
                });
    }

    private static IamProperties properties() {
        return new IamProperties(
                new IamProperties.Jwt("issuer", "audience", "test-only-jwt-secret-with-at-least-32-bytes",
                        Duration.ofMinutes(15)),
                new IamProperties.Refresh(Duration.ofDays(7), false),
                new IamProperties.LoginRateLimit(5, Duration.ofMinutes(15)),
                new IamProperties.Verification(Duration.ofMinutes(10), Duration.ofSeconds(60), 5,
                        "fixed-local", "246810", "test-only-verification-hmac-secret-32-bytes", ""),
                new IamProperties.Captcha(Duration.ofMinutes(2), 5, 3),
                new IamProperties.SessionCache("edu:iam:session-version", Duration.ofMinutes(20)),
                new IamProperties.Media("http://127.0.0.1:9", Duration.ofSeconds(1)),
                new IamProperties.Seed(false, ""));
    }
}
