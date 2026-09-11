package com.eduplatform.agentaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.agentaccess.config.AgentGovernanceProperties;
import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import com.eduplatform.agentaccess.service.AgentException;
import com.eduplatform.agentaccess.service.RedisRunGovernance;
import com.eduplatform.agentaccess.service.RunGovernance.Lease;
import com.eduplatform.agentaccess.service.RunGovernance.Reservation;
import com.eduplatform.agentaccess.web.AgentDtos;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用独立随机前缀验证本机 Redis 5 原子租约、额度、取消、TTL 和精确清理。 */
@EnabledIfEnvironmentVariable(named = "AGENT_RUNTIME_REDIS_ACCEPTANCE", matches = "true")
class AgentRedisGovernanceRealAcceptanceTest {
    @Test
    /** 低阈值覆盖幂等不重复计数、并发/分钟/日 429、错误 token、续租、取消及 TTL 回收。 */
    void redisFiveCompatibleGovernanceIsAtomicAndBounded() throws Exception {
        String prefix = "edu:agent:acceptance:" + UUID.randomUUID();
        LettuceConnectionFactory connection = connection(port(), database());
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        RedisRunGovernance governance = new RedisRunGovernance(redis, properties(prefix, 1, 2, 16, 8,
                Duration.ofMillis(350)));
        Set<String> cleanup = new LinkedHashSet<>();
        try {
            UUID owner = UUID.randomUUID();
            Reservation first = governance.reserve(owner, UUID.randomUUID(), UUID.randomUUID());
            cleanup(first, cleanup);
            Reservation replay = governance.reserve(first.ownerUserId(), first.conversationId(), first.clientRequestId());
            assertThat(first.acquired()).isTrue();
            assertThat(replay.acquired()).isFalse();
            RunRow run = run(owner);
            Lease lease = governance.bind(first, run);
            cleanup.add(lease.leaseKey());
            cleanup.add(prefix + ":run:" + run.id() + ":meta");
            cleanup.add(prefix + ":run:" + run.id() + ":cancel");
            assertThat(governance.hasValidLease(run.id())).isTrue();

            governance.release(new Lease(run.id(), owner, "wrong-token", lease.leaseKey(), lease.activeKey()));
            assertThat(governance.hasValidLease(run.id())).isTrue();
            assertThat(governance.renew(lease)).isTrue();
            assertThatThrownBy(() -> governance.reserve(owner, UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(AgentException.class,
                            error -> assertThat(error.status().value()).isEqualTo(429));
            governance.requestCancellation(run.id());
            assertThat(governance.cancellationRequested(run.id())).isTrue();
            assertThat(governance.renew(lease)).isFalse();
            governance.release(lease);

            Reservation second = governance.reserve(owner, UUID.randomUUID(), UUID.randomUUID());
            cleanup(second, cleanup);
            assertThat(second.acquired()).isTrue();
            assertThatThrownBy(() -> governance.reserve(owner, UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(AgentException.class,
                            error -> assertThat(error.status().value()).isEqualTo(429));
            governance.rollback(second);

            UUID ttlOwner = UUID.randomUUID();
            Reservation ttlReservation = governance.reserve(ttlOwner, UUID.randomUUID(), UUID.randomUUID());
            cleanup(ttlReservation, cleanup);
            RunRow ttlRun = run(ttlOwner);
            Lease ttlLease = governance.bind(ttlReservation, ttlRun);
            cleanup.add(ttlLease.leaseKey());
            cleanup.add(prefix + ":run:" + ttlRun.id() + ":meta");
            Thread.sleep(500);
            assertThat(governance.hasValidLease(ttlRun.id())).isFalse();

            UUID dailyOwner = UUID.randomUUID();
            RedisRunGovernance daily = new RedisRunGovernance(redis, properties(prefix, 4, 20, 8, 8,
                    Duration.ofSeconds(2)));
            Reservation dailyFirst = daily.reserve(dailyOwner, UUID.randomUUID(), UUID.randomUUID());
            cleanup(dailyFirst, cleanup);
            assertThatThrownBy(() -> daily.reserve(dailyOwner, UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(AgentException.class,
                            error -> assertThat(error.status().value()).isEqualTo(429));

            assertThat(redis.opsForValue().get(lease.leaseKey())).isNull();
            assertThat(cleanup).allSatisfy(key -> assertThat(key)
                    .doesNotContain("prompt", "answer", "token=", "http://", "https://"));
            assertSafeRedisPayloads(redis, cleanup);
        } finally {
            redis.delete(cleanup);
            assertThat(cleanup).allSatisfy(key -> assertThat(redis.hasKey(key)).isFalse());
            connection.destroy();
        }
        assertUnavailable(prefix);
    }

    private static void assertUnavailable(String prefix) {
        LettuceConnectionFactory broken = connection(1, database());
        try {
            RedisRunGovernance governance = new RedisRunGovernance(new StringRedisTemplate(broken),
                    properties(prefix + ":broken", 1, 1, 8, 8, Duration.ofSeconds(1)));
            assertThatThrownBy(() -> governance.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOfSatisfying(AgentException.class, error -> assertThat(error.status().value()).isEqualTo(503));
        } finally {
            broken.destroy();
        }
    }

    private static AgentGovernanceProperties properties(String prefix, int concurrent, int minute,
            long daily, long reserve, Duration lease) {
        return new AgentGovernanceProperties(true, prefix, "acceptance-instance", lease,
                Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMinutes(2), concurrent, minute,
                daily, reserve, Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2));
    }

    private static RunRow run(UUID owner) {
        Instant now = Instant.now();
        return new RunRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), owner,
                "trace-redis-acceptance", AgentDtos.RunStatus.ACCEPTED, 0L, null, null, null, now, now);
    }

    private static void cleanup(Reservation reservation, Set<String> keys) {
        keys.add(reservation.idempotencyKey()); keys.add(reservation.activeKey());
        keys.add(reservation.minuteKey()); keys.add(reservation.dayKey());
    }

    /** 逐类型读取本轮已知 Key，证明值中也没有正文、回答、供应商 Token 或临时 URL。 */
    private static void assertSafeRedisPayloads(StringRedisTemplate redis, Set<String> keys) {
        for (String key : keys) {
            DataType type = redis.type(key);
            if (type == null || type == DataType.NONE) continue;
            Object value = switch (type) {
                case STRING -> redis.opsForValue().get(key);
                case HASH -> redis.opsForHash().entries(key);
                case ZSET -> redis.opsForZSet().rangeWithScores(key, 0, -1);
                default -> type;
            };
            assertThat(String.valueOf(value)).doesNotContainIgnoringCase(
                    "prompt", "answer", "Bearer ", "token=", "api_key", "http://", "https://");
        }
    }

    private static LettuceConnectionFactory connection(int port, int database) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"), port);
        configuration.setDatabase(database);
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isBlank()) configuration.setPassword(RedisPassword.of(password));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet(); factory.start();
        return factory;
    }

    private static int port() { return Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")); }
    private static int database() { return Integer.parseInt(System.getenv().getOrDefault("AGENT_REDIS_DATABASE", "6")); }
}
