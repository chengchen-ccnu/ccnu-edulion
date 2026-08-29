package com.eduplatform.iam.security;

import com.eduplatform.iam.config.IamProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis 原子脚本实现；所有 Key 有 TTL，验证码和身份目标只保存 HMAC 摘要。 */
@Component
@ConditionalOnProperty(prefix = "platform.iam", name = "security-store", havingValue = "redis",
        matchIfMissing = true)
public class RedisIdentitySecurityStore implements IdentitySecurityStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'purpose') ~= ARGV[1]
              or redis.call('HGET', KEYS[1], 'identityType') ~= ARGV[2]
              or redis.call('HGET', KEYS[1], 'targetDigest') ~= ARGV[3]
              or redis.call('HGET', KEYS[1], 'codeDigest') ~= ARGV[4] then
                local failures = redis.call('HINCRBY', KEYS[1], 'failures', 1)
                if failures >= tonumber(ARGV[5]) then redis.call('DEL', KEYS[1]) end
                return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            if count >= tonumber(ARGV[1]) then
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
                return redis.call('PTTL', KEYS[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisIdentitySecurityStore(StringRedisTemplate redis, IamProperties properties) {
        this.redis = redis;
        this.prefix = properties.sessionCache().keyPrefix().replace(":session-version", "");
    }

    @Override
    public ChallengeCreation createChallenge(String challengeId, String purpose, String identityType,
            String targetDigest, String codeDigest, Duration ttl, Duration cooldown) {
        String cooldownKey = prefix + ":challenge-cooldown:" + purpose + ":" + targetDigest;
        Boolean accepted = redis.opsForValue().setIfAbsent(cooldownKey, "1", cooldown);
        if (!Boolean.TRUE.equals(accepted)) {
            Long remaining = redis.getExpire(cooldownKey);
            return new ChallengeCreation(false, Math.max(1L, remaining == null ? cooldown.toSeconds() : remaining));
        }
        String key = prefix + ":challenge:" + challengeId;
        redis.opsForHash().putAll(key, Map.of("purpose", purpose, "identityType", identityType,
                "targetDigest", targetDigest, "codeDigest", codeDigest, "failures", "0"));
        redis.expire(key, ttl);
        return new ChallengeCreation(true, cooldown.toSeconds());
    }

    @Override
    public boolean consumeChallenge(String challengeId, String purpose, String identityType,
            String targetDigest, String codeDigest, int maxAttempts) {
        Long result = redis.execute(CONSUME, List.of(prefix + ":challenge:" + challengeId),
                purpose, identityType, targetDigest, codeDigest, Integer.toString(maxAttempts));
        return result != null && result == 1L;
    }

    @Override
    public long loginLockRetrySeconds(String accountDigest, String addressDigest) {
        return Math.max(ttl(lockAccount(accountDigest)), ttl(lockAddress(addressDigest)));
    }

    @Override
    public long recordLoginFailure(String accountDigest, String addressDigest, int attempts, Duration window) {
        long account = failure(failAccount(accountDigest), lockAccount(accountDigest), attempts, window);
        long address = failure(failAddress(addressDigest), lockAddress(addressDigest), attempts * 4, window);
        return Math.max(account, address);
    }

    @Override
    public void clearLoginFailures(String accountDigest, String addressDigest) {
        redis.delete(List.of(failAccount(accountDigest), lockAccount(accountDigest)));
    }

    @Override public void unlockAccount(String accountDigest) {
        redis.delete(List.of(failAccount(accountDigest), lockAccount(accountDigest)));
    }

    @Override public void cacheSessionVersion(String userId, long version, Duration ttl) {
        redis.opsForValue().set(sessionKey(userId), Long.toString(version), ttl);
    }

    @Override public void invalidateSessionVersion(String userId) { redis.delete(sessionKey(userId)); }

    private long failure(String failureKey, String lockKey, int attempts, Duration window) {
        Long millis = redis.execute(FAILURE, List.of(failureKey, lockKey),
                Integer.toString(attempts), Long.toString(window.toMillis()));
        return millis == null ? 0L : (Math.max(0L, millis) + 999L) / 1000L;
    }

    private long ttl(String key) {
        Long value = redis.getExpire(key);
        return value == null || value < 0 ? 0L : value;
    }

    private String failAccount(String digest) { return prefix + ":login-failure:account:" + digest; }
    private String failAddress(String digest) { return prefix + ":login-failure:ip:" + digest; }
    private String lockAccount(String digest) { return prefix + ":login-lock:account:" + digest; }
    private String lockAddress(String digest) { return prefix + ":login-lock:ip:" + digest; }
    private String sessionKey(String userId) { return prefix + ":session-version:" + userId; }
}
