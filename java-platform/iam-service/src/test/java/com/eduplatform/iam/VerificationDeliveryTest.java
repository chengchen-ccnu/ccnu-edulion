package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.VerificationChallengeService;
import com.eduplatform.iam.verification.SmtpVerificationDelivery;
import com.eduplatform.iam.verification.VerificationDeliveryPort;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** 供应商无关发送与 SMTP 补偿测试；任何断言都不输出明文验证码。 */
class VerificationDeliveryTest {
    private final IamProperties properties = properties("smtp", "noreply@example.test");
    private final SecurityDigests digests = new SecurityDigests(properties);

    @Test
    /** SMTP 成功后保留 Redis 挑战；发送端口只接收规范化小写邮箱。 */
    void successfulDeliveryKeepsConsumableChallenge() {
        IdentitySecurityStore store = mock(IdentitySecurityStore.class);
        when(store.createChallenge(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdentitySecurityStore.ChallengeCreation(true, 60));
        CapturingPort port = new CapturingPort(false);
        VerificationChallengeService service = new VerificationChallengeService(store, digests, properties,
                List.of(port));

        service.create("REGISTRATION", "EMAIL", "  NewUser@Example.COM ");

        assertThat(port.target).isEqualTo("newuser@example.com");
        verify(store, never()).cancelChallenge(any(), any(), any());
    }

    @Test
    /** 供应商失败返回 503，并精确删除挑战和冷却占位，允许后续有限重试。 */
    void failedDeliveryCompensatesChallengeAndCooldown() {
        IdentitySecurityStore store = mock(IdentitySecurityStore.class);
        when(store.createChallenge(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdentitySecurityStore.ChallengeCreation(true, 60));
        VerificationChallengeService service = new VerificationChallengeService(store, digests, properties,
                List.of(new CapturingPort(true)));

        assertThatThrownBy(() -> service.create("PASSWORD_RESET", "EMAIL", "known@example.com"))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("IAM.VERIFICATION_DELIVERY_UNAVAILABLE");
                });
        verify(store).cancelChallenge(any(), any(), any());
    }

    @Test
    /** 未知身份可以创建同形挑战但绝不调用外部发送端口。 */
    void unknownIdentityDoesNotSendAndKeepsSameShape() {
        IdentitySecurityStore store = mock(IdentitySecurityStore.class);
        when(store.createChallenge(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new IdentitySecurityStore.ChallengeCreation(true, 60));
        CapturingPort port = new CapturingPort(false);
        VerificationChallengeService service = new VerificationChallengeService(store, digests, properties,
                List.of(port));
        var challenge = service.create("VERIFICATION_LOGIN", "EMAIL", "unknown@example.com", false);
        assertThat(challenge.deliveryHint()).contains("***@");
        assertThat(port.target).isNull();
    }

    @Test
    /** 标准 SMTP 邮件以自然语言写明操作、有效期和一次性约束，并硬拒绝 example.invalid Seed 域。 */
    void smtpAdapterSendsStandardMailButRejectsReservedSeedDomain() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpVerificationDelivery smtp = new SmtpVerificationDelivery(sender, properties);
        smtp.deliver(new VerificationDeliveryPort.Message("EMAIL", "known@example.com", "REGISTRATION", "123456"));
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("Edu Lion 注册验证码");
        assertThat(mail.getValue().getText())
                .contains("你正在注册 Edu Lion")
                .contains("请在 10 分钟内输入验证码完成注册")
                .contains("验证码只能使用一次")
                .doesNotContain("用途：");

        assertThatThrownBy(() -> smtp.deliver(new VerificationDeliveryPort.Message(
                "EMAIL", "student1@example.invalid", "REGISTRATION", "123456")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IamProperties properties(String mode, String from) {
        return new IamProperties(new IamProperties.Jwt("issuer", "audience",
                "test-only-jwt-secret-at-least-32-bytes", Duration.ofMinutes(15)),
                new IamProperties.Refresh(Duration.ofDays(7), false),
                new IamProperties.LoginRateLimit(5, Duration.ofMinutes(15)),
                new IamProperties.Verification(Duration.ofMinutes(10), Duration.ofMinutes(1), 5, mode,
                        "246810", "test-only-verification-hmac-secret-32-bytes", from),
                new IamProperties.Captcha(Duration.ofMinutes(2), 5, 3),
                new IamProperties.SessionCache("edu:iam:session-version", Duration.ofMinutes(20)),
                new IamProperties.Media("http://127.0.0.1:8083", Duration.ofSeconds(5)),
                new IamProperties.Seed(false, ""));
    }

    private static final class CapturingPort implements VerificationDeliveryPort {
        private final boolean fail;
        private String target;
        private CapturingPort(boolean fail) { this.fail = fail; }
        @Override public boolean supports(String identityType) { return "EMAIL".equals(identityType); }
        @Override public void deliver(Message message) {
            target = message.target();
            if (fail) throw new IllegalStateException("synthetic provider failure");
        }
    }
}
