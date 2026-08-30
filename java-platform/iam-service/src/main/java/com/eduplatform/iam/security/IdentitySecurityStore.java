package com.eduplatform.iam.security;

import java.time.Duration;

/**
 * Redis 短期身份安全状态端口。
 *
 * <p>实现只能保存摘要、计数和带 TTL 的会话版本缓存；用户、唯一身份、Refresh Session、
 * 协议、教师申请与审计事实必须写 MySQL。</p>
 */
public interface IdentitySecurityStore {
    void createCaptcha(String captchaId, String answerDigest, Duration ttl, int maxRefreshes);

    CaptchaRefresh refreshCaptcha(String previousCaptchaId, String replacementCaptchaId,
            String answerDigest, Duration ttl, int maxRefreshes);

    boolean consumeCaptcha(String captchaId, String answerDigest, int maxAttempts);

    ChallengeCreation createChallenge(String challengeId, String purpose, String identityType,
            String targetDigest, String codeDigest, Duration ttl, Duration cooldown);

    void cancelChallenge(String challengeId, String purpose, String targetDigest);

    boolean consumeChallenge(String challengeId, String purpose, String identityType,
            String targetDigest, String codeDigest, int maxAttempts);

    long loginLockRetrySeconds(String accountDigest, String addressDigest);

    long loginFailureCount(String accountDigest);

    LoginFailure recordLoginFailure(String accountDigest, String addressDigest, int attempts, Duration window);

    void clearLoginFailures(String accountDigest, String addressDigest);

    void unlockAccount(String accountDigest);

    void cacheSessionVersion(String userId, long sessionVersion, Duration ttl);

    void invalidateSessionVersion(String userId);

    record ChallengeCreation(boolean created, long retryAfterSeconds) {}
    record CaptchaRefresh(boolean refreshed, boolean exhausted, int refreshesRemaining) {}
    record LoginFailure(long accountFailures, long retryAfterSeconds) {}
}
