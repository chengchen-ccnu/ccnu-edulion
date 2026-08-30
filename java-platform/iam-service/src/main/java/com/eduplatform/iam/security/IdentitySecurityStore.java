package com.eduplatform.iam.security;

import java.time.Duration;

/**
 * Redis 短期身份安全状态端口。
 *
 * <p>实现只能保存摘要、计数和带 TTL 的会话版本缓存；用户、唯一身份、Refresh Session、
 * 协议、教师申请与审计事实必须写 MySQL。</p>
 */
public interface IdentitySecurityStore {
    /** 创建只保存答案摘要的短期算式验证码，并初始化刷新链预算。 */
    void createCaptcha(String captchaId, String answerDigest, Duration ttl, int maxRefreshes);

    /** 原子废弃旧验证码并继承刷新次数；返回是否成功以及剩余次数。 */
    CaptchaRefresh refreshCaptcha(String previousCaptchaId, String replacementCaptchaId,
            String answerDigest, Duration ttl, int maxRefreshes);

    /** 原子核对并一次消费验证码；错误会单独累计且达到上限即删除。 */
    boolean consumeCaptcha(String captchaId, String answerDigest, int maxAttempts);

    /** 创建绑定用途、身份类型和目标摘要的一次性验证码及发送冷却。 */
    ChallengeCreation createChallenge(String challengeId, String purpose, String identityType,
            String targetDigest, String codeDigest, Duration ttl, Duration cooldown);

    /** 发送失败时精确取消对应挑战和冷却，让调用者可以有限重试。 */
    void cancelChallenge(String challengeId, String purpose, String targetDigest);

    /** 原子核对用途、类型、目标和验证码摘要，成功后删除挑战。 */
    boolean consumeChallenge(String challengeId, String purpose, String identityType,
            String targetDigest, String codeDigest, int maxAttempts);

    /** @return 账号或地址锁定的较长剩余秒数；未锁定返回零。 */
    long loginLockRetrySeconds(String accountDigest, String addressDigest);

    /** @return 当前账号摘要在窗口内的密码失败次数。 */
    long loginFailureCount(String accountDigest);

    /** 记录一次真正执行过的错误密码，并在阈值处建立短期锁。 */
    LoginFailure recordLoginFailure(String accountDigest, String addressDigest, int attempts, Duration window);

    /** 密码登录成功后只清理该账号失败/锁定状态，不清洗共享 IP 风险累计。 */
    void clearLoginFailures(String accountDigest, String addressDigest);

    /** 管理命令按账号摘要精确解除密码失败和账号锁定。 */
    void unlockAccount(String accountDigest);

    /** 写入 Gateway 校验用的短期 sessionVersion 缓存；MySQL 仍是权威事实。 */
    void cacheSessionVersion(String userId, long sessionVersion, Duration ttl);

    /** 高风险变更先删除缓存，使旧 Access JWT 不能在故障时被静默接受。 */
    void invalidateSessionVersion(String userId);

    record ChallengeCreation(boolean created, long retryAfterSeconds) {}
    record CaptchaRefresh(boolean refreshed, boolean exhausted, int refreshesRemaining) {}
    record LoginFailure(long accountFailures, long retryAfterSeconds) {}
}
