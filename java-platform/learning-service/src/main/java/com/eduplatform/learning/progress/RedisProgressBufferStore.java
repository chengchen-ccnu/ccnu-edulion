package com.eduplatform.learning.progress;

import com.eduplatform.learning.config.ProgressBufferProperties;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.ProgressRow;
import com.eduplatform.learning.web.LearningDtos;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * T12G.1 Redis Hash＋dirty ZSet 实现。
 *
 * <p>Hash Key 按学习者/课程/内容修订隔离，并在值内保留对应 lessonId；ZSet 只保存待落库成员。Lua 保证基线初始化、乱序判断、
 * 累计观看时长和 dirty 标记在一个 Redis 原子操作内完成；这里不保存逐心跳历史，也不使用 Redis 锁代替 MySQL 事务。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.learning.progress-buffer", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class RedisProgressBufferStore implements ProgressBufferStore {
    private static final String HASH_PREFIX = "edu:learning:progress:";
    private static final String DIRTY_KEY = "edu:learning:progress:dirty";
    private static final String SCRIPT_BUFFER = """
            local exists = redis.call('EXISTS', KEYS[1])
            local currentAt = 0
            if exists == 1 then currentAt = tonumber(redis.call('HGET', KEYS[1], 'lastEventAt')) or 0 end
            local eventAt = tonumber(ARGV[2])
            if exists == 0 and eventAt <= tonumber(ARGV[9]) then return 1 end
            if exists == 1 and redis.call('HGET', KEYS[1], 'lastClientEventId') == ARGV[1] then return 0 end
            if exists == 1 and eventAt <= currentAt then return 1 end
            if exists == 0 then
              redis.call('HSET', KEYS[1], 'positionSeconds', ARGV[6], 'watchedSeconds', ARGV[7],
                'completed', ARGV[8], 'lastEventAt', ARGV[9], 'lastClientEventId', '',
                'bufferVersion', ARGV[12], 'lessonId', ARGV[13])
              currentAt = tonumber(ARGV[9])
            end
            local oldPosition = tonumber(redis.call('HGET', KEYS[1], 'positionSeconds')) or 0
            local oldWatched = tonumber(redis.call('HGET', KEYS[1], 'watchedSeconds')) or 0
            local newPosition = math.max(oldPosition, tonumber(ARGV[3]))
            local candidateWatched = oldWatched + tonumber(ARGV[4])
            local watchedLimit = math.max(300, newPosition * 2 + 300)
            local newWatched = math.min(candidateWatched, watchedLimit)
            local oldCompleted = redis.call('HGET', KEYS[1], 'completed') == '1'
            local newCompleted = oldCompleted or ARGV[5] == '1'
            local version = (tonumber(redis.call('HGET', KEYS[1], 'bufferVersion')) or 0) + 1
            redis.call('HSET', KEYS[1], 'positionSeconds', newPosition, 'watchedSeconds', newWatched,
                'completed', newCompleted and '1' or '0', 'lastEventAt', eventAt,
                'lastClientEventId', ARGV[1], 'bufferVersion', version, 'lessonId', ARGV[13])
            redis.call('ZADD', KEYS[2], ARGV[10], ARGV[11])
            return 2
            """;
    private static final String SCRIPT_CLAIM = """
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not score or tonumber(score) > tonumber(ARGV[2]) then return 0 end
            local owner = redis.call('HGET', KEYS[2], 'leaseOwner')
            local leaseUntilValue = tonumber(redis.call('HGET', KEYS[2], 'leaseUntil')) or 0
            if owner and owner ~= ARGV[3] and leaseUntilValue > tonumber(ARGV[2]) then return 0 end
            redis.call('HSET', KEYS[2], 'leaseOwner', ARGV[3], 'leaseUntil', tonumber(ARGV[2]) + tonumber(ARGV[4]))
            return 1
            """;
    private static final String SCRIPT_ACK = """
            if redis.call('HGET', KEYS[1], 'bufferVersion') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'leaseOwner') ~= ARGV[2] then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[3])
            return 1
            """;
    private static final String SCRIPT_RELEASE = """
            if redis.call('HGET', KEYS[1], 'leaseOwner') ~= ARGV[1] then return 0 end
            redis.call('HDEL', KEYS[1], 'leaseOwner', 'leaseUntil')
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
            return 1
            """;

    private final StringRedisTemplate redis;
    private final ProgressBufferProperties properties;
    private final MeterRegistry meterRegistry;
    private final String instanceId = java.util.UUID.randomUUID().toString();

    public RedisProgressBufferStore(StringRedisTemplate redis, ProgressBufferProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        Gauge.builder("learning.progress.dirty.count", this, RedisProgressBufferStore::dirtyCount)
                .description("当前待落库的进度 Hash 数量").register(meterRegistry);
        Gauge.builder("learning.progress.dirty.oldest.age.seconds", this,
                RedisProgressBufferStore::oldestDirtyAgeSeconds)
                .description("最老待落库进度的年龄，不包含学习者标识").register(meterRegistry);
    }

    @Override
    public BufferResult buffer(String learnerId, String courseId, String lessonId, String contentRevisionId,
            ProgressRow persisted,
            LearningDtos.ReportProgressRequest input, Instant now) {
        String key = key(learnerId, courseId, contentRevisionId);
        ProgressRow base = persisted;
        List<String> args = List.of(input.clientEventId().toString(), String.valueOf(input.occurredAt().toEpochMilli()),
                String.valueOf(input.positionSeconds()), String.valueOf(input.watchedSeconds()),
                input.completed() ? "1" : "0", String.valueOf(base == null ? 0 : base.positionSeconds()),
                String.valueOf(base == null ? 0 : base.watchedSeconds()), base != null && base.completed() ? "1" : "0",
                String.valueOf(base == null ? 0 : base.lastEventAt().toEpochMilli()), String.valueOf(now.toEpochMilli()), key,
                String.valueOf(base == null ? 0 : base.bufferVersion()), lessonId);
        try {
            Long result = redis.execute(new DefaultRedisScript<>(SCRIPT_BUFFER, Long.class), List.of(key, DIRTY_KEY),
                    args.toArray());
            if (result == null) throw new IllegalStateException("Redis Lua returned no result");
            Optional<BufferedProgress> current = readAvailable(key);
            BufferedProgress value = current.orElseGet(() -> base == null
                    ? new BufferedProgress(learnerId, courseId, lessonId, contentRevisionId, input.positionSeconds(),
                            input.watchedSeconds(), input.completed(), input.occurredAt(), 0)
                    : new BufferedProgress(learnerId, courseId, lessonId, contentRevisionId,
                            base.positionSeconds(), base.watchedSeconds(),
                            base.completed(), base.lastEventAt(), base.bufferVersion()));
            if (result == 2) meterRegistry.counter("learning.progress.heartbeat.buffered").increment();
            return new BufferResult(value, result == 0 || result == 1);
        } catch (Exception exception) {
            meterRegistry.counter("learning.progress.redis.errors").increment();
            throw new ProgressBufferUnavailableException("进度缓冲暂时不可用", exception);
        }
    }

    @Override
    public ReadResult read(String learnerId, String courseId, String contentRevisionId) {
        try {
            return new ReadResult(true, readAvailable(key(learnerId, courseId, contentRevisionId)));
        } catch (Exception exception) {
            meterRegistry.counter("learning.progress.redis.read.errors").increment();
            return new ReadResult(false, Optional.empty());
        }
    }

    @Override
    public boolean backlogExceedsLimit() {
        try {
            Long count = redis.opsForZSet().zCard(DIRTY_KEY);
            return count != null && count >= properties.backlogThreshold();
        } catch (Exception exception) {
            meterRegistry.counter("learning.progress.redis.errors").increment();
            throw new ProgressBufferUnavailableException("进度缓冲暂时不可用", exception);
        }
    }

    /** Actuator/Micrometer 使用的无敏感积压数量；Redis 故障时返回 0，故障计数由调用方另行记录。 */
    public double dirtyCount() {
        try {
            Long count = redis.opsForZSet().zCard(DIRTY_KEY);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Actuator/Micrometer 使用的最老 dirty 年龄，绝不输出 Hash member 或用户 ID。 */
    public double oldestDirtyAgeSeconds() {
        try {
            var oldest = redis.opsForZSet().rangeWithScores(DIRTY_KEY, 0, 0);
            if (oldest == null || oldest.isEmpty() || oldest.iterator().next().getScore() == null) return 0;
            return Math.max(0, (System.currentTimeMillis() - oldest.iterator().next().getScore()) / 1000.0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 供 Actuator 健康检查使用；只探测 Redis 连通性，不返回 Key 内容。 */
    public boolean ping() {
        try {
            redis.hasKey(DIRTY_KEY);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Worker 领取到期 dirty 成员；领取本身用 Hash 租约，不依赖 Redis 分布式锁。 */
    public List<String> claimDue(Instant now) {
        try {
            var candidates = redis.opsForZSet().rangeByScore(DIRTY_KEY, 0, now.toEpochMilli(), 0,
                    properties.batchSize());
            List<String> claimed = new ArrayList<>();
            if (candidates == null) return claimed;
            for (String key : candidates) {
                Long accepted = redis.execute(new DefaultRedisScript<>(SCRIPT_CLAIM, Long.class),
                        List.of(DIRTY_KEY, key), key, String.valueOf(now.toEpochMilli()), instanceId,
                        String.valueOf(properties.leaseDuration().toMillis()));
                if (Long.valueOf(1).equals(accepted)) claimed.add(key);
            }
            return claimed;
        } catch (Exception exception) {
            meterRegistry.counter("learning.progress.worker.redis.errors").increment();
            throw new ProgressBufferUnavailableException("进度缓冲暂时不可用", exception);
        }
    }

    /** 返回带租约的快照；快照版本用于防止落库后误删并发新心跳。 */
    public Optional<BufferedProgress> leasedSnapshot(String key) {
        try { return readAvailable(key); }
        catch (Exception exception) { throw new ProgressBufferUnavailableException("进度缓冲暂时不可用", exception); }
    }

    /** 仅在 MySQL 事务成功且 Redis 版本未变化时清理 Hash 与 dirty 成员。 */
    public boolean acknowledge(String key, BufferedProgress snapshot) {
        try {
            Long result = redis.execute(new DefaultRedisScript<>(SCRIPT_ACK, Long.class), List.of(key, DIRTY_KEY),
                    String.valueOf(snapshot.version()), instanceId, key);
            return Long.valueOf(1).equals(result);
        } catch (Exception exception) {
            throw new ProgressBufferUnavailableException("进度缓冲暂时不可用", exception);
        }
    }

    /** MySQL 暂时失败时保留 dirty，并把下一次领取时间向后推移。 */
    public void release(String key, Instant nextAttemptAt) {
        try {
            redis.execute(new DefaultRedisScript<>(SCRIPT_RELEASE, Long.class), List.of(key, DIRTY_KEY),
                    instanceId, String.valueOf(nextAttemptAt.toEpochMilli()), key);
        } catch (Exception exception) {
            throw new ProgressBufferUnavailableException("进度缓冲暂时不可用", exception);
        }
    }

    public String instanceId() { return instanceId; }

    /** 以服务端内容修订生成唯一 Hash Key；lessonId 只存入 Hash 值，不能参与隔离。 */
    public static String key(String learnerId, String courseId, String contentRevisionId) {
        return HASH_PREFIX + learnerId + ":" + courseId + ":" + contentRevisionId;
    }

    public static String[] ids(String key) {
        String value = key.substring(HASH_PREFIX.length());
        String[] ids = value.split(":", -1);
        if (ids.length != 3 || List.of(ids).stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("invalid progress buffer key");
        }
        return ids;
    }

    private Optional<BufferedProgress> readAvailable(String key) {
        Map<Object, Object> values = redis.opsForHash().entries(key);
        if (values.isEmpty()) return Optional.empty();
        String[] ids = ids(key);
        Object lessonId = values.get("lessonId");
        if (lessonId == null) throw new IllegalStateException("missing progress buffer lessonId");
        return Optional.of(new BufferedProgress(ids[0], ids[1], String.valueOf(lessonId), ids[2],
                number(values, "positionSeconds"),
                number(values, "watchedSeconds"), "1".equals(String.valueOf(values.get("completed"))),
                Instant.ofEpochMilli(number(values, "lastEventAt")), number(values, "bufferVersion")));
    }

    private static long number(Map<Object, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) throw new IllegalStateException("missing progress buffer field");
        return Long.parseLong(String.valueOf(value));
    }
}
