package com.eduplatform.iam.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 仅供自动化 test profile 使用的确定性短期状态替身，不装配到 local/production。 */
@Component
@ConditionalOnProperty(prefix = "platform.iam", name = "security-store", havingValue = "memory")
public class InMemoryIdentitySecurityStore implements IdentitySecurityStore {
    private final Map<String, TimedValue> values = new ConcurrentHashMap<>();

    @Override
    public ChallengeCreation createChallenge(String id, String purpose, String type, String target,
            String code, Duration ttl, Duration cooldown) {
        String cooldownKey = "cooldown:" + purpose + ":" + target;
        TimedValue current = active(cooldownKey);
        if (current != null) return new ChallengeCreation(false,
                Math.max(1L, Duration.between(Instant.now(), current.expiresAt()).toSeconds()));
        values.put(cooldownKey, new TimedValue("1", Instant.now().plus(cooldown)));
        values.put("challenge:" + id, new TimedValue(String.join("|", purpose, type, target, code, "0"),
                Instant.now().plus(ttl)));
        return new ChallengeCreation(true, cooldown.toSeconds());
    }

    @Override
    public synchronized boolean consumeChallenge(String id, String purpose, String type, String target,
            String code, int maxAttempts) {
        String key = "challenge:" + id;
        TimedValue value = active(key);
        if (value == null) return false;
        String[] fields = value.value().split("\\|", -1);
        if (fields[0].equals(purpose) && fields[1].equals(type) && fields[2].equals(target)
                && fields[3].equals(code)) {
            values.remove(key); return true;
        }
        int failures = Integer.parseInt(fields[4]) + 1;
        if (failures >= maxAttempts) values.remove(key);
        else values.put(key, new TimedValue(String.join("|", fields[0], fields[1], fields[2], fields[3],
                Integer.toString(failures)), value.expiresAt()));
        return false;
    }

    @Override public long loginLockRetrySeconds(String account, String address) {
        return Math.max(ttl("lock:a:" + account), ttl("lock:i:" + address));
    }

    @Override public synchronized long recordLoginFailure(String account, String address, int attempts, Duration window) {
        long accountRetry = increment("fail:a:" + account, "lock:a:" + account, attempts, window);
        long addressRetry = increment("fail:i:" + address, "lock:i:" + address, attempts * 4, window);
        return Math.max(accountRetry, addressRetry);
    }

    @Override public void clearLoginFailures(String account, String address) {
        values.remove("fail:a:" + account); values.remove("lock:a:" + account);
    }
    @Override public void unlockAccount(String account) { values.remove("fail:a:" + account); values.remove("lock:a:" + account); }
    @Override public void cacheSessionVersion(String userId, long version, Duration ttl) {
        values.put("session:" + userId, new TimedValue(Long.toString(version), Instant.now().plus(ttl)));
    }
    @Override public void invalidateSessionVersion(String userId) { values.remove("session:" + userId); }

    private long increment(String counter, String lock, int limit, Duration window) {
        TimedValue current = active(counter);
        int value = current == null ? 1 : Integer.parseInt(current.value()) + 1;
        Instant expiry = current == null ? Instant.now().plus(window) : current.expiresAt();
        values.put(counter, new TimedValue(Integer.toString(value), expiry));
        if (value >= limit) values.put(lock, new TimedValue("1", Instant.now().plus(window)));
        return ttl(lock);
    }
    private TimedValue active(String key) {
        TimedValue value = values.get(key);
        if (value != null && !value.expiresAt().isAfter(Instant.now())) { values.remove(key); return null; }
        return value;
    }
    private long ttl(String key) {
        TimedValue value = active(key);
        return value == null ? 0L : Math.max(1L, Duration.between(Instant.now(), value.expiresAt()).toSeconds());
    }
    private record TimedValue(String value, Instant expiresAt) {}
}
