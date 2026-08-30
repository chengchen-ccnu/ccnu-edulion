package com.eduplatform.iam.service;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 创建和一次性消费注册/重置挑战；不保存明文验证码、手机号或邮箱到 Redis。 */
@Service
public class VerificationChallengeService {
    private final IdentitySecurityStore store;
    private final SecurityDigests digests;
    private final IamProperties properties;
    private final Environment environment;

    public VerificationChallengeService(IdentitySecurityStore store, SecurityDigests digests,
            IamProperties properties, Environment environment) {
        this.store = store; this.digests = digests; this.properties = properties; this.environment = environment;
    }

    /**
     * 为规范化目标创建带冷却的挑战。T12D 不接供应商，只有 local/test 可用固定非日志适配器。
     * @return challengeId、有效秒数、重发冷却和掩码目标
     */
    public Challenge create(String purpose, String identityType, String target) {
        String normalized = IdentityNormalizer.normalize(identityType, target);
        String code = deliveryCode();
        String id = UUID.randomUUID().toString();
        try {
            IdentitySecurityStore.ChallengeCreation result = store.createChallenge(id, purpose, identityType,
                    digests.digest("target", normalized), digests.digest("code:" + id, code),
                    properties.verification().ttl(), properties.verification().cooldown());
            if (!result.created()) throw new AuthException(HttpStatus.TOO_MANY_REQUESTS,
                    "IAM.VERIFICATION_COOLDOWN", "验证码发送过于频繁，请稍后再试", result.retryAfterSeconds());
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

    private String deliveryCode() {
        boolean localOrTest = environment.acceptsProfiles(Profiles.of("local", "test"));
        String code = properties.verification().fixedCode();
        if (!localOrTest || !"fixed-local".equals(properties.verification().deliveryMode())
                || code == null || !code.matches("[0-9]{6}")) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "IAM.VERIFICATION_DELIVERY_UNAVAILABLE",
                    "验证码发送服务暂时不可用，请稍后重试", 60L);
        }
        return code;
    }

    private static AuthException unavailable() {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "IAM.SECURITY_STATE_UNAVAILABLE",
                "身份安全服务暂时不可用，请稍后重试", 2L);
    }

    public record Challenge(String challengeId, String purpose, long expiresIn,
            long resendAfter, String deliveryHint) {}
}
