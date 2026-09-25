package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.TeacherAnalyticsRepository;
import com.eduplatform.learning.progress.RedisProgressBufferStore;
import com.eduplatform.learning.web.LearningDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 专用 edu_platform_learning_dev 的真实 MySQL V18 验收。只插入本测试随机 UUID 的 Learning 事实，
 * finally 精确删除；不读取或改动用户手工课程的结构、发布和媒体绑定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "platform.learning.import.messaging-enabled=false")
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12C1_MYSQL_ACCEPTANCE", matches = "true")
class T12C1MysqlAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository learning;
    @Autowired TeacherAnalyticsRepository analytics;
    @Autowired RedisProgressBufferStore buffer;
    @Autowired StringRedisTemplate redis;

    /** 连接前必须明确确认专用 Learning dev Schema，防止测试开关误指向来源不明数据库。 */
    @DynamicPropertySource
    static void dedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_learning_dev".equals(System.getenv("LEARNING_MYSQL_DATABASE")))
            throw new IllegalStateException("T12C.1 acceptance requires edu_platform_learning_dev");
    }

    @Test
    /** V18 已迁移、当前/旧修订隔离、重复 Worker 快照不重复计日秒数，最后逐表按 UUID 清理。 */
    void realMysqlMigrationAndDailyFact() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_learning_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version='18' AND success=TRUE", Integer.class)).isEqualTo(1);
        String course = UUID.randomUUID().toString(), lesson = UUID.randomUUID().toString();
        String current = UUID.randomUUID().toString(), old = UUID.randomUUID().toString();
        String learner = UUID.randomUUID().toString();
        Instant now = Instant.now();
        LocalDate day = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        try {
            jdbc.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) "
                    + "VALUES(?,?,?,'t12c1-test','ACTIVE',?,?,?)", UUID.randomUUID().toString(), learner, course,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            jdbc.update("INSERT INTO course_student_number(id,course_id,learner_id,student_number,created_at) "
                    + "VALUES(?,?,?,1,?)", UUID.randomUUID().toString(), course, learner, Timestamp.from(now));
            jdbc.update("INSERT INTO published_course_content_projection(course_id,course_version_id,"
                    + "publication_revision,projection_source,updated_at) VALUES(?,'t12c1-test',1,'COURSE_HTTP',?)",
                    course, Timestamp.from(now));
            jdbc.update("INSERT INTO published_lesson_content_projection(course_id,lesson_id,content_revision_id,"
                    + "lesson_type,publication_revision,updated_at) VALUES(?,?,?,'VIDEO',1,?)",
                    course, lesson, current, Timestamp.from(now));
            learning.applyBufferedProgress(learner, course, lesson, old, 25, 25, false, now, 1, now,
                    Map.of(day, 25L), day);
            learning.applyBufferedProgress(learner, course, lesson, current, 10, 10, false, now, 1, now,
                    Map.of(day, 10L), day);
            learning.applyBufferedProgress(learner, course, lesson, current, 10, 10, false, now, 1, now,
                    Map.of(day, 10L), day);
            assertThat(analytics.student(course, learner).get("watched_seconds").toString()).isEqualTo("10");
            assertThat(jdbc.queryForObject("SELECT watched_seconds FROM lesson_learning_day "
                    + "WHERE course_id=? AND content_revision_id=?", Long.class, course, current)).isEqualTo(10);
            assertThat(analytics.courseTotals(course).get("watched_seconds").toString()).isEqualTo("10");
        } finally {
            jdbc.update("DELETE FROM lesson_learning_day WHERE course_id=? AND learner_id=?", course, learner);
            jdbc.update("DELETE FROM learning_activity_day WHERE course_id=? AND learner_id=?", course, learner);
            jdbc.update("DELETE FROM lesson_progress_event WHERE course_id=? AND learner_id=?", course, learner);
            jdbc.update("DELETE FROM lesson_progress WHERE course_id=? AND learner_id=?", course, learner);
            jdbc.update("DELETE FROM published_lesson_content_projection WHERE course_id=?", course);
            jdbc.update("DELETE FROM published_course_content_projection WHERE course_id=?", course);
            jdbc.update("DELETE FROM course_student_number WHERE course_id=? AND learner_id=?", course, learner);
            jdbc.update("DELETE FROM enrollment WHERE course_id=? AND learner_id=?", course, learner);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_learning_day WHERE course_id=?", Integer.class,
                    course)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE course_id=?", Integer.class,
                    course)).isZero();
        }
    }

    @Test
    /** 真实 MySQL 1000 名 ACTIVE 学员分页聚合，只取 20 行，不构造学生×课时矩阵；随机 Fixture 精确清理。 */
    void largeCoursePagedQuery() {
        String course = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 1000; i++) ids.add(UUID.randomUUID().toString());
        try {
            jdbc.batchUpdate("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) "
                    + "VALUES(?,?,?,'t12c1-capacity','ACTIVE',?,?,?)", ids, 100,
                    (ps, learner) -> {
                        ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, learner);
                        ps.setString(3, course); ps.setTimestamp(4, Timestamp.from(now));
                        ps.setTimestamp(5, Timestamp.from(now)); ps.setTimestamp(6, Timestamp.from(now));
                    });
            List<Integer> numbers = java.util.stream.IntStream.rangeClosed(1, 1000).boxed().toList();
            jdbc.batchUpdate("INSERT INTO course_student_number(id,course_id,learner_id,student_number,created_at) "
                    + "VALUES(?,?,?,?,?)", numbers, 100, (ps, number) -> {
                        ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, course);
                        ps.setString(3, ids.get(number - 1)); ps.setInt(4, number);
                        ps.setTimestamp(5, Timestamp.from(now));
                    });
            long started = System.nanoTime();
            assertThat(analytics.countStudents(course, 10, null, List.of(), null, null, false, false)).isEqualTo(1000);
            var page = analytics.students(course, 10, "WATCHED_SECONDS", true, null, List.of(), null, null,
                    false, false, 1, 20);
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(page).hasSize(20);
            assertThat(elapsedMs).isLessThan(10_000);
            System.out.println("T12C1_MYSQL_1000_STUDENT_QUERY_MS=" + elapsedMs);
        } finally {
            jdbc.update("DELETE FROM course_student_number WHERE course_id=?", course);
            jdbc.update("DELETE FROM enrollment WHERE course_id=?", course);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE course_id=?", Integer.class,
                    course)).isZero();
        }
    }

    @Test
    /** 真实 Redis Lua 记录上海自然日有效增量；重复 clientEventId 和 MySQL 重试都不能重复累计。 */
    void realRedisBufferAndMysqlRetry() {
        if (!"4".equals(System.getenv("LEARNING_REDIS_DATABASE")))
            throw new IllegalStateException("T12C.1 Redis acceptance requires Learning DB 4");
        String course = UUID.randomUUID().toString(), lesson = UUID.randomUUID().toString();
        String revision = UUID.randomUUID().toString(), learner = UUID.randomUUID().toString();
        String key = RedisProgressBufferStore.key(learner, course, revision);
        Instant now = Instant.now();
        LocalDate day = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        UUID event = UUID.randomUUID();
        try {
            var input = new LearningDtos.ReportProgressRequest(event, UUID.fromString(revision), now,
                    20, 7, false, LearningDtos.ProgressEventType.HEARTBEAT);
            assertThat(buffer.buffer(learner, course, lesson, revision, null, input, now).replayed()).isFalse();
            assertThat(buffer.buffer(learner, course, lesson, revision, null, input, now).replayed()).isTrue();
            var snapshot = buffer.read(learner, course, revision).value().orElseThrow();
            assertThat(snapshot.watchedByDay()).containsEntry(day, 7L);
            learning.applyBufferedProgress(learner, course, lesson, revision, snapshot.positionSeconds(),
                    snapshot.watchedSeconds(), false, snapshot.lastEventAt(), snapshot.version(), now,
                    snapshot.watchedByDay(), snapshot.firstStartedDay());
            learning.applyBufferedProgress(learner, course, lesson, revision, snapshot.positionSeconds(),
                    snapshot.watchedSeconds(), false, snapshot.lastEventAt(), snapshot.version(), now,
                    snapshot.watchedByDay(), snapshot.firstStartedDay());
            assertThat(jdbc.queryForObject("SELECT watched_seconds FROM lesson_learning_day WHERE course_id=?",
                    Long.class, course)).isEqualTo(7);
        } finally {
            jdbc.update("DELETE FROM lesson_learning_day WHERE course_id=? AND learner_id=?", course, learner);
            jdbc.update("DELETE FROM lesson_progress WHERE course_id=? AND learner_id=?", course, learner);
            redis.delete(key);
            redis.opsForZSet().remove("edu:learning:progress:dirty", key);
            assertThat(redis.hasKey(key)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_learning_day WHERE course_id=?", Integer.class,
                    course)).isZero();
        }
    }
}
