package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.learning.config.ProgressBufferProperties;
import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.ProgressRow;
import com.eduplatform.learning.progress.ProgressBufferStore;
import com.eduplatform.learning.progress.ProgressBufferUnavailableException;
import com.eduplatform.learning.progress.ProgressBufferWorker;
import com.eduplatform.learning.progress.RedisProgressBufferStore;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.service.LearningAssessmentService;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.LearningDtos.ProgressEventType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * T12G.1 专用真实环境验收：只在显式开关下连接 learning 开发库和 Redis DB 4。
 *
 * <p>用随机 run ID 生成本轮学习者，覆盖 Redis Lua、dirty、租约、Worker、故障回退和 HTTP 检查点；
 * 错误连接只在测试对象内部使用，不停止或修改共享 MySQL/Redis 进程。finally 按本轮 ID 精确删除进度、事件、Hash、dirty 和租约。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "platform.learning.import.messaging-enabled=false",
        "platform.learning.progress-buffer.worker-delay=1h",
        "platform.learning.progress-buffer.backlog-threshold=2",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12G1_REAL_ACCEPTANCE_ENABLED", matches = "true")
class T12G1ProgressBufferRealEnvironmentAcceptanceTest {
    private static final String COURSE = "5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057";
    private static final String LESSON = "70775426-c55f-4662-bbc7-14750ce183e6";
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String REDIS_DATABASE = "4";
    private static final String HASH_PREFIX = "edu:learning:progress:";
    private static final String DIRTY_KEY = "edu:learning:progress:dirty";

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisProgressBufferStore store;
    @Autowired
    private ProgressBufferProperties properties;
    @Autowired
    private LearningAssessmentRepository repository;
    @Autowired
    private CourseFactsClient courses;
    @Autowired
    private LearningEvents events;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionService questionService;
    @Autowired
    private MediaClient media;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private MeterRegistry meterRegistry;
    private final HttpClient http = HttpClient.newHttpClient();

    private static String runPrefix;
    private String contentRevisionId;
    private final Set<String> learners = new HashSet<>();
    private final Set<String> keys = new HashSet<>();

    @BeforeAll
    static void verifyDedicatedEnvironment() {
        if (!DATABASE.equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12G.1 real acceptance requires edu_platform_learning_dev");
        }
        if (!REDIS_DATABASE.equals(System.getenv("LEARNING_REDIS_DATABASE"))) {
            throw new IllegalStateException("T12G.1 real acceptance requires Learning Redis DB 4");
        }
        runPrefix = "t12g1-real-" + UUID.randomUUID() + "-";
    }

    @AfterAll
    static void verifyRunPrefixWasCreated() {
        if (runPrefix == null || runPrefix.isBlank()) throw new IllegalStateException("missing T12G.1 run ID");
    }

    @Test
    void realProgressBufferContractAndRecovery() throws Exception {
        var publishedCourse = courses.requirePublished(COURSE, runPrefix);
        assertThat(publishedCourse.lessonIds()).contains(LESSON);
        contentRevisionId = publishedCourse.contentRevisionId(LESSON);
        assertThat(contentRevisionId).isNotBlank();
        String learnerId = learner("primary");
        String key = key(learnerId);
        try {
            assertThat(redis.opsForZSet().zCard(DIRTY_KEY)).as("shared Redis DB 4 must start without dirty progress").isZero();

            ResponseEntity<Map> heartbeat = report(learnerId, ProgressEventType.HEARTBEAT, 10, 4, false);
            assertThat(heartbeat.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(heartbeat.getBody()).containsEntry("persistenceStatus", "BUFFERED");
            assertThat(redis.hasKey(key)).isTrue();
            assertThat(redis.opsForZSet().score(DIRTY_KEY, key)).isNotNull();
            assertThat(redis.opsForHash().get(key, "bufferVersion")).isEqualTo("1");

            // Lua 的去重/乱序保护：重复 clientEventId 和旧时间戳都不得推进版本或累计时长。
            ResponseEntity<Map> replay = reportWithEventId(learnerId, ProgressEventType.HEARTBEAT, 10, 4, false,
                    UUID.randomUUID().toString(), Instant.now().minusSeconds(1));
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(redis.opsForHash().get(key, "bufferVersion")).isEqualTo("1");
            assertThat(redis.opsForHash().get(key, "watchedSeconds")).isEqualTo("4");

            // 两个独立 Store 代表两个实例，只有一个可以拿到同一 dirty Hash 的租约。
            RedisProgressBufferStore secondInstance = new RedisProgressBufferStore(redis, properties, meterRegistry);
            ProgressBufferStore.BufferedProgress oldSnapshot = store.leasedSnapshot(key).orElseThrow();
            List<String> firstClaims = store.claimDue(Instant.now().plusSeconds(1));
            List<String> secondClaims = secondInstance.claimDue(Instant.now().plusSeconds(1));
            assertThat(firstClaims).contains(key);
            assertThat(secondClaims).doesNotContain(key);

            ResponseEntity<Map> newer = report(learnerId, ProgressEventType.HEARTBEAT, 20, 4, false);
            assertThat(newer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(oldSnapshot.version()).isEqualTo(1);
            assertThat(store.acknowledge(key, oldSnapshot)).isFalse();
            assertThat(redis.hasKey(key)).isTrue();
            store.release(key, Instant.now());

            new ProgressBufferWorker(store, repository, meterRegistry).flushDue();
            await(() -> progressRow(learnerId).bufferVersion() == 2 && !redis.hasKey(key));
            assertThat(progressRow(learnerId).positionSeconds()).isEqualTo(20);
            assertThat(progressRow(learnerId).watchedSeconds()).isEqualTo(8);

            // 强制检查点绕过 Redis，直接形成 MySQL 事件和聚合事实；只有 ENDED 可以完成。
            ResponseEntity<Map> pause = report(learnerId, ProgressEventType.PAUSE, 30, 5, false);
            assertThat(pause.getBody()).containsEntry("persistenceStatus", "PERSISTED");
            assertThat(jdbc.queryForObject("SELECT event_type FROM lesson_progress_event WHERE learner_id=? AND course_id=? AND lesson_id=? ORDER BY created_at DESC LIMIT 1", String.class, learnerId, COURSE, LESSON)).isEqualTo("PAUSE");
            ResponseEntity<Map> ended = report(learnerId, ProgressEventType.ENDED, 35, 5, true);
            assertThat(ended.getBody()).containsEntry("persistenceStatus", "PERSISTED");
            assertThat(progressRow(learnerId).completed()).isTrue();

            // 错误 MySQL 连接模拟短暂故障：Worker 释放租约并延后 dirty，而不是清除 Redis 事实。
            String outageLearner = learner("mysql-outage");
            String outageKey = key(outageLearner);
            assertThat(report(outageLearner, ProgressEventType.HEARTBEAT, 11, 3, false).getStatusCode()).isEqualTo(HttpStatus.OK);
            JdbcTemplate unavailableJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/edu_platform_learning_dev?connectTimeout=200&socketTimeout=200", "invalid", "invalid"));
            LearningAssessmentRepository unavailableRepository = new LearningAssessmentRepository(unavailableJdbc, JsonMapper.builder().build());
            new ProgressBufferWorker(store, unavailableRepository, meterRegistry).flushDue();
            assertThat(redis.hasKey(outageKey)).isTrue();
            assertThat(redis.opsForZSet().score(DIRTY_KEY, outageKey)).isGreaterThan((double) Instant.now().minusSeconds(1).toEpochMilli());
            redis.opsForZSet().add(DIRTY_KEY, outageKey, Instant.now().toEpochMilli());
            new ProgressBufferWorker(store, repository, meterRegistry).flushDue();
            await(() -> progressRow(outageLearner).bufferVersion() == 1 && !redis.hasKey(outageKey));

            // Redis 错误连接只用于验证降级读取；PERSISTED_ONLY 仍返回 MySQL 已提交事实。
            LettuceConnectionFactory badFactory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("127.0.0.1", 1));
            badFactory.afterPropertiesSet();
            StringRedisTemplate badRedis = new StringRedisTemplate(badFactory);
            badRedis.afterPropertiesSet();
            RedisProgressBufferStore unavailableStore = new RedisProgressBufferStore(badRedis, properties, meterRegistry);
            assertThat(unavailableStore.read(learnerId, COURSE, contentRevisionId).available()).isFalse();
            assertThatThrownBy(() -> unavailableStore.buffer(learnerId, COURSE, LESSON, contentRevisionId,
                    progressRow(learnerId), request(ProgressEventType.HEARTBEAT, 40, 1, false, Instant.now()), Instant.now()))
                    .isInstanceOf(ProgressBufferUnavailableException.class);
            LearningAssessmentService degradedLearning = new LearningAssessmentService(repository, courses, events,
                    questions, questionService, media, mapper, unavailableStore, meterRegistry);
            assertThat(degradedLearning.progress(learnerId, COURSE, runPrefix).lessons().stream()
                    .filter(value -> LESSON.equals(value.lessonId())).findFirst().orElseThrow().persistenceStatus())
                    .isEqualTo(LearningDtos.ProgressPersistenceStatus.PERSISTED_ONLY);
            badFactory.destroy();

            // 受控积压保护：仅放入本轮随机 Key，HEARTBEAT 在 Redis 层达到阈值后返回 503。
            String backlogOne = fakeDirty("backlog-1");
            String backlogTwo = fakeDirty("backlog-2");
            ResponseEntity<Map> rejected = report(learner("backlog-rejected"), ProgressEventType.HEARTBEAT, 1, 1, false);
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(rejected.getBody()).containsEntry("code", "LEARNING.PROGRESS_BUFFER_BACKLOG");
            redis.delete(List.of(backlogOne, backlogTwo));
            redis.opsForZSet().remove(DIRTY_KEY, backlogOne, backlogTwo);
        } finally {
            cleanup();
        }
    }

    private ResponseEntity<Map> report(String learnerId, ProgressEventType eventType, long position, int watched,
            boolean completed) throws Exception {
        return reportWithEventId(learnerId, eventType, position, watched, completed, UUID.randomUUID().toString(), Instant.now());
    }

    private ResponseEntity<Map> reportWithEventId(String learnerId, ProgressEventType eventType, long position, int watched,
            boolean completed, String eventId, Instant occurredAt) throws Exception {
        String body = mapper.writeValueAsString(Map.of("clientEventId", eventId,
                "contentRevisionId", contentRevisionId, "occurredAt", occurredAt.toString(),
                "positionSeconds", position, "watchedSeconds", watched, "completed", completed,
                "eventType", eventType.name()));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/learning/courses/" + COURSE + "/lessons/" + LESSON + "/progress-events"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", learnerId)
                .header("X-User-Roles", "STUDENT")
                .header("X-User-Permissions", "learning:access")
                .header("X-Trace-Id", runPrefix)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Map parsed = response.body().isBlank() ? Map.of() : mapper.readValue(response.body(), Map.class);
        return ResponseEntity.status(response.statusCode()).body(parsed);
    }

    private LearningDtos.ReportProgressRequest request(ProgressEventType eventType, long position, int watched,
            boolean completed, Instant occurredAt) {
        return new LearningDtos.ReportProgressRequest(UUID.randomUUID(), UUID.fromString(contentRevisionId), occurredAt,
                position, watched, completed, eventType);
    }

    private ProgressRow progressRow(String learnerId) {
        return repository.findProgress(learnerId, COURSE, contentRevisionId).orElseThrow();
    }

    private String learner(String label) {
        // 身份边界要求 UUID；本轮所有 UUID 均只保存在内存集合中，finally 按集合精确清理。
        String value = UUID.randomUUID().toString();
        learners.add(value);
        keys.add(key(value));
        return value;
    }

    private String key(String learnerId) {
        String value = RedisProgressBufferStore.key(learnerId, COURSE, contentRevisionId);
        keys.add(value);
        return value;
    }

    private String fakeDirty(String label) {
        String value = HASH_PREFIX + runPrefix + label + ":" + COURSE + ":" + LESSON;
        keys.add(value);
        redis.opsForHash().put(value, "positionSeconds", "1");
        redis.opsForHash().put(value, "watchedSeconds", "1");
        redis.opsForHash().put(value, "completed", "0");
        redis.opsForHash().put(value, "lastEventAt", String.valueOf(System.currentTimeMillis()));
        redis.opsForHash().put(value, "bufferVersion", "1");
        redis.opsForZSet().add(DIRTY_KEY, value, System.currentTimeMillis());
        return value;
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).as("condition did not become true before timeout").isTrue();
    }

    private void cleanup() {
        for (String learnerId : learners) {
            jdbc.update("DELETE FROM lesson_progress_event WHERE learner_id=? AND course_id=? AND lesson_id=?",
                    learnerId, COURSE, LESSON);
            jdbc.update("DELETE FROM lesson_progress WHERE learner_id=? AND course_id=? AND lesson_id=?",
                    learnerId, COURSE, LESSON);
        }
        if (!keys.isEmpty()) redis.delete(new ArrayList<>(keys));
        if (!keys.isEmpty()) redis.opsForZSet().remove(DIRTY_KEY, keys.toArray());
        assertThat(redis.opsForZSet().range(DIRTY_KEY, 0, -1))
                .as("this run must not leave dirty members")
                .noneMatch(value -> value != null && value.contains(runPrefix));
        for (String learnerId : learners) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress WHERE learner_id=? AND course_id=? AND lesson_id=?",
                    Integer.class, learnerId, COURSE, LESSON)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress_event WHERE learner_id=? AND course_id=? AND lesson_id=?",
                    Integer.class, learnerId, COURSE, LESSON)).isZero();
        }
    }
}
