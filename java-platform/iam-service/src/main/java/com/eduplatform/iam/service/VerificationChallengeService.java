package com.eduplatform.iam.service;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.verification.VerificationDeliveryPort;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 创建和一次性消费注册/重置挑战；不保存明文验证码、手机号或邮箱到 Redis。 */
@Service
public class VerificationChallengeService {
    private final IdentitySecurityStore store;
    private final SecurityDigests digests;
    private final IamProperties properties;
    private final List<VerificationDeliveryPort> deliveryPorts;
    private final SecureRandom random = new SecureRandom();

    public VerificationChallengeService(IdentitySecurityStore store, SecurityDigests digests,
            IamProperties properties, List<VerificationDeliveryPort> deliveryPorts) {
        this.store = store;
        this.digests = digests;
        this.properties = properties;
        this.deliveryPorts = List.copyOf(deliveryPorts);
    }

    /**
     * 为规范化目标创建带冷却的挑战。T12D 不接供应商，只有 local/test 可用固定非日志适配器。
     * @return challengeId、有效秒数、重发冷却和掩码目标
     */
    public Challenge create(String purpose, String identityType, String target) {
        return create(purpose, identityType, target, true);
    }

    /**
     * 创建挑战并按需发送。未知身份场景传入 {@code deliver=false}，仍创建同形态挑战和返回同一响应，
     * 但不向外部联系地址发送，从而兼顾不可枚举和防骚扰。
     *
     * @param purpose 注册、找回、验证码登录或身份变更用途
     * @param identityType EMAIL 或 PHONE
     * @param target 用户输入的身份值
     * @param deliver 是否应向已经确认存在或允许注册的目标真实发送
     * @return 不含明文验证码的挑战投影
     */
    public Challenge create(String purpose, String identityType, String target, boolean deliver) {
        String normalized = IdentityNormalizer.normalize(identityType, target);
        VerificationDeliveryPort delivery = deliver ? findDelivery(identityType) : null;
        String code = createCode();
        String id = UUID.randomUUID().toString();
        String targetDigest = digests.digest("target", normalized);
        try {
            IdentitySecurityStore.ChallengeCreation result = store.createChallenge(id, purpose, identityType,
                    targetDigest, digests.digest("code:" + id, code),
                    properties.verification().ttl(), properties.verification().cooldown());
            if (!result.created()) throw new AuthException(HttpStatus.TOO_MANY_REQUESTS,
                    "IAM.VERIFICATION_COOLDOWN", "验证码发送过于频繁，请稍后再试", result.retryAfterSeconds());
            if (delivery != null) {
                try {
                    delivery.deliver(new VerificationDeliveryPort.Message(identityType, normalized, purpose, code));
                } catch (RuntimeException exception) {
                    compensate(id, purpose, targetDigest);
                    throw deliveryUnavailable();
                }
            }
            return new Challenge(id, purpose, properties.verification().ttl().toSeconds(),
                    properties.verification().cooldown().toSeconds(), IdentityNormalizer.mask(identityType, normalized));
        } catch (AuthException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 原子校验并删除挑战，错误/过期/重放共享同一 422；返回规范化身份供 MySQL 唯一约束使用。
     */
    public String consume(String challengeId, String purpose, String identityType,
            String target, String code) {
        String normalized = IdentityNormalizer.normalize(identityType, target);
        try {
            boolean consumed = store.consumeChallenge(challengeId, purpose, identityType,
                    digests.digest("target", normalized), digests.digest("code:" + challengeId, code),
                    properties.verification().maxAttempts());
            if (!consumed) throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "IAM.VERIFICATION_FAILED", "验证码无效或已过期");
            return normalized;
        } catch (AuthException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /** @return 当前环境是否有可用发送实现；能力接口据此隐藏未配置的手机入口。 */
    public boolean supports(String identityType) {
        return deliveryPorts.stream().anyMatch(port -> port.supports(identityType));
    }

    private VerificationDeliveryPort findDelivery(String identityType) {
        return deliveryPorts.stream().filter(port -> port.supports(identityType)).findFirst()
                .orElseThrow(VerificationChallengeService::deliveryUnavailable);
    }

    private String createCode() {
        if ("fixed-local".equals(properties.verification().deliveryMode())) {
            String fixed = properties.verification().fixedCode();
            if (fixed == null || !fixed.matches("[0-9]{6}")) throw deliveryUnavailable();
            return fixed;
        }
        return "%06d".formatted(random.nextInt(1_000_000));
    }

    private void compensate(String challengeId, String purpose, String targetDigest) {
        try {
            store.cancelChallenge(challengeId, purpose, targetDigest);
        } catch (RuntimeException ignored) {
            // 原始发送失败优先返回统一 503；Redis 补偿失败只能等待短 TTL，不记录目标或验证码。
        }
    }

    private static AuthException deliveryUnavailable() {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "IAM.VERIFICATION_DELIVERY_UNAVAILABLE",
                "验证码发送服务暂时不可用，请稍后重试", 60L);
    }

    private static AuthException unavailable() {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "IAM.SECURITY_STATE_UNAVAILABLE",
                "身份安全服务暂时不可用，请稍后重试", 2L);
    }

    public record Challenge(String challengeId, String purpose, long expiresIn,
            long resendAfter, String deliveryHint) {}
}
