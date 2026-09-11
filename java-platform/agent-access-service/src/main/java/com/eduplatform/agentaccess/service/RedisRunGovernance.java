package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.config.AgentGovernanceProperties;
import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 使用 Redis 5 兼容命令和短 Lua 管理租约、取消、最近安全元数据及用户额度。
 *
 * <p>Key/值只包含 UUID、实例标识、状态、序号和时间；禁止传入正文、回答、提示词、工具结果、
 * 临时 URL 或秘密。任何 Redis 异常都转换为 503，新运行不得无治理继续执行。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.agent-governance", name = "enabled", havingValue = "true")
public class RedisRunGovernance implements RunGovernance {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DefaultRedisScript<Long> RESERVE = script("""
            if redis.call('EXISTS',KEYS[1]) == 1 then return 2 end
            redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',ARGV[1])
            if redis.call('ZCARD',KEYS[2]) >= tonumber(ARGV[3]) then return -1 end
            if tonumber(redis.call('GET',KEYS[3]) or '0') >= tonumber(ARGV[4]) then return -2 end
            if tonumber(redis.call('GET',KEYS[4]) or '0') + tonumber(ARGV[5]) > tonumber(ARGV[6]) then return -3 end
            redis.call('SET',KEYS[1],ARGV[2],'PX',ARGV[7])
            redis.call('ZADD',KEYS[2],ARGV[8],ARGV[2])
            redis.call('PEXPIRE',KEYS[2],ARGV[9])
            redis.call('INCR',KEYS[3]); redis.call('PEXPIRE',KEYS[3],ARGV[10])
            redis.call('INCRBY',KEYS[4],ARGV[5]); redis.call('PEXPIRE',KEYS[4],ARGV[11])
            return 1
            """);
    private static final DefaultRedisScript<Long> BIND = script("""
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('SET',KEYS[2],ARGV[2],'NX','PX',ARGV[3]) == false then return 0 end
            redis.call('SET',KEYS[1],'BOUND:' .. ARGV[4],'PX',ARGV[5])
            redis.call('ZREM',KEYS[3],ARGV[1]); redis.call('ZADD',KEYS[3],ARGV[6],ARGV[4])
            redis.call('HSET',KEYS[4],'runId',ARGV[4],'conversationId',ARGV[7],'ownerUserId',ARGV[8],
              'status','RUNNING','lastSequence','0','instanceId',ARGV[9],'expiresAt',ARGV[10])
            redis.call('PEXPIRE',KEYS[4],ARGV[5]); return 1
            """);
    private static final DefaultRedisScript<Long> RENEW = script("""
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            if redis.call('EXISTS',KEYS[2]) == 1 then return -1 end
            redis.call('PEXPIRE',KEYS[1],ARGV[2]); redis.call('ZADD',KEYS[3],ARGV[3],ARGV[4])
            redis.call('PEXPIRE',KEYS[3],ARGV[5]); redis.call('HSET',KEYS[4],'expiresAt',ARGV[6])
            redis.call('PEXPIRE',KEYS[4],ARGV[5]); return 1
            """);
    private static final DefaultRedisScript<Long> RELEASE = script("""
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('DEL',KEYS[1]); redis.call('ZREM',KEYS[2],ARGV[2]); redis.call('DEL',KEYS[3]);
            redis.call('HSET',KEYS[4],'status',ARGV[3],'expiresAt',ARGV[4]); redis.call('PEXPIRE',KEYS[4],ARGV[5]);
            return 1
            """);
    private static final DefaultRedisScript<Long> PROGRESS = script("""
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('HSET',KEYS[2],'status',ARGV[2],'lastSequence',ARGV[3])
            redis.call('PEXPIRE',KEYS[2],ARGV[4]); return 1
            """);
    private static final DefaultRedisScript<Long> ROLLBACK = script("""
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('DEL',KEYS[1]); redis.call('ZREM',KEYS[2],ARGV[1])
            local m=tonumber(redis.call('GET',KEYS[3]) or '0'); if m>0 then redis.call('DECR',KEYS[3]) end
            local d=tonumber(redis.call('GET',KEYS[4]) or '0'); local n=d-tonumber(ARGV[2]);
            if n>0 then local ttl=redis.call('PTTL',KEYS[4]); redis.call('SET',KEYS[4],n);
              if ttl>0 then redis.call('PEXPIRE',KEYS[4],ttl) end else redis.call('DEL',KEYS[4]) end
            return 1
            """);

    private final StringRedisTemplate redis;
    private final AgentGovernanceProperties properties;

    public RedisRunGovernance(StringRedisTemplate redis, AgentGovernanceProperties properties) {
        this.redis = redis;
        this.properties = properties;
        if (properties.leaseTtl().isZero() || properties.leaseTtl().isNegative()
                || properties.renewInterval().isZero() || properties.renewInterval().isNegative()
                || properties.renewInterval().compareTo(properties.leaseTtl()) >= 0
                || properties.cancellationTtl().isZero() || properties.cancellationTtl().isNegative()
                || properties.recentRunTtl().isZero() || properties.recentRunTtl().isNegative()
                || properties.orphanScanInterval().isZero() || properties.orphanScanInterval().isNegative()
                || properties.followerPollInterval().isZero() || properties.followerPollInterval().isNegative()
                || properties.followerMaxDuration().isZero() || properties.followerMaxDuration().isNegative()
                || properties.maxConcurrentRunsPerUser() < 1 || properties.maxRunsPerMinute() < 1
                || properties.reservedTokensPerRun() < 1
                || properties.maxReservedTokensPerDay() < properties.reservedTokensPerRun()) {
            throw new IllegalArgumentException("invalid agent governance configuration");
        }
    }

    @Override
    public Reservation reserve(UUID owner, UUID conversation, UUID request) {
        UUID reservationId = UUID.randomUUID();
        String prefix = prefix();
        String idem = prefix + ":idem:" + owner + ":" + conversation + ":" + request;
        String active = prefix + ":user:" + owner + ":active";
        Instant now = Instant.now();
        String minute = prefix + ":quota:minute:" + owner + ":" + now.getEpochSecond() / 60;
        String day = prefix + ":quota:day:" + owner + ":" + DAY.format(now);
        long nowMs = now.toEpochMilli();
        Long result = execute(RESERVE, List.of(idem, active, minute, day), Long.toString(nowMs),
                reservationId.toString(), Integer.toString(properties.maxConcurrentRunsPerUser()),
                Integer.toString(properties.maxRunsPerMinute()), Long.toString(properties.reservedTokensPerRun()),
                Long.toString(properties.maxReservedTokensPerDay()),
                Long.toString(properties.leaseTtl().toMillis()),
                Long.toString(nowMs + properties.leaseTtl().toMillis()),
                Long.toString(properties.recentRunTtl().toMillis()), "120000", "172800000");
        if (result == null) throw unavailable();
        if (result < 0) throw quota(result);
        return new Reservation(reservationId, owner, conversation, request, idem, active, minute, day, result == 1);
    }

    @Override
    public Lease bind(Reservation reservation, RunRow run) {
        if (!reservation.acquired()) throw unavailable();
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        String leaseKey = prefix() + ":run:" + run.id() + ":lease";
        String metaKey = prefix() + ":run:" + run.id() + ":meta";
        long expiry = Instant.now().plus(properties.leaseTtl()).toEpochMilli();
        Long result = execute(BIND, List.of(reservation.idempotencyKey(), leaseKey, reservation.activeKey(), metaKey),
                reservation.id().toString(), token, Long.toString(properties.leaseTtl().toMillis()),
                run.id().toString(), Long.toString(properties.recentRunTtl().toMillis()), Long.toString(expiry),
                run.conversationId().toString(), run.ownerUserId().toString(), properties.instanceId(),
                Long.toString(expiry));
        if (result == null || result != 1) throw unavailable();
        return new Lease(run.id(), run.ownerUserId(), token, leaseKey, reservation.activeKey());
    }

    @Override
    public boolean renew(Lease lease) {
        long expiry = Instant.now().plus(properties.leaseTtl()).toEpochMilli();
        Long result = execute(RENEW, List.of(lease.leaseKey(), cancellationKey(lease.runId()), lease.activeKey(),
                        prefix() + ":run:" + lease.runId() + ":meta"), lease.ownerToken(),
                Long.toString(properties.leaseTtl().toMillis()), Long.toString(expiry), lease.runId().toString(),
                Long.toString(properties.recentRunTtl().toMillis()), Long.toString(expiry));
        return result != null && result == 1;
    }

    @Override
    public boolean hasValidLease(UUID runId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(prefix() + ":run:" + runId + ":lease"));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @Override
    public boolean cancellationRequested(UUID runId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(cancellationKey(runId)));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @Override
    public void recordProgress(Lease lease, String status, long lastSequence) {
        Long result = execute(PROGRESS, List.of(lease.leaseKey(), prefix() + ":run:" + lease.runId() + ":meta"),
                lease.ownerToken(), status, Long.toString(lastSequence),
                Long.toString(properties.recentRunTtl().toMillis()));
        if (result == null || result != 1) throw unavailable();
    }

    @Override
    public void requestCancellation(UUID runId) {
        try {
            redis.opsForValue().set(cancellationKey(runId), "1", properties.cancellationTtl());
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @Override
    public void release(Lease lease) {
        execute(RELEASE, List.of(lease.leaseKey(), lease.activeKey(), cancellationKey(lease.runId()),
                        prefix() + ":run:" + lease.runId() + ":meta"), lease.ownerToken(), lease.runId().toString(),
                "TERMINAL", Long.toString(Instant.now().toEpochMilli()),
                Long.toString(properties.recentRunTtl().toMillis()));
    }

    @Override
    public void rollback(Reservation reservation) {
        if (!reservation.acquired()) return;
        execute(ROLLBACK, List.of(reservation.idempotencyKey(), reservation.activeKey(), reservation.minuteKey(),
                reservation.dayKey()), reservation.id().toString(), Long.toString(properties.reservedTokensPerRun()));
    }

    private String cancellationKey(UUID runId) { return prefix() + ":run:" + runId + ":cancel"; }
    private String prefix() {
        String value = properties.keyPrefix();
        return value == null || value.isBlank() ? "edu:agent:runtime:v1" : value;
    }

    private Long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        try {
            return redis.execute(script, keys, (Object[]) args);
        } catch (RedisConnectionFailureException failure) {
            throw unavailable();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static AgentException quota(long result) {
        String code = result == -1 ? "AGENT.CONCURRENCY_LIMIT" : "AGENT.QUOTA_EXCEEDED";
        return new AgentException(HttpStatus.TOO_MANY_REQUESTS, code, "AI 使用额度已达上限，请稍后重试");
    }

    private static AgentException unavailable() {
        return new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT.GOVERNANCE_UNAVAILABLE",
                "AI 服务暂时不可用，请稍后重试");
    }

    private static DefaultRedisScript<Long> script(String body) {
        return new DefaultRedisScript<>(body, Long.class);
    }
}
