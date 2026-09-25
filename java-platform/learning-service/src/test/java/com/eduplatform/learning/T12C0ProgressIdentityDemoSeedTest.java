package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T12C.0 专用 local Seed。只在 Learning 开发库和 Redis DB 4 创建固定 test 标识事实，
 * 不连接或修改 Course Schema，因此绝不会改变手工课程《线性代数》的结构、版本或媒体绑定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "platform.learning.import.messaging-enabled=false")
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12C0_DEMO_SEED_ENABLED", matches = "true")
class T12C0ProgressIdentityDemoSeedTest {
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String REDIS_DATABASE = "4";
    private static final String LEARNER = "00000000-0000-4000-8000-00000000c121";
    private static final String COURSE = "00000000-0000-4000-8000-00000000c120";
    private static final List<String> LESSONS = List.of(
            "00000000-0000-4000-8000-00000000c130", "00000000-0000-4000-8000-00000000c131",
            "00000000-0000-4000-8000-00000000c132", "00000000-0000-4000-8000-00000000c133",
            "00000000-0000-4000-8000-00000000c134", "00000000-0000-4000-8000-00000000c135");
    private static final List<String> CURRENT_REVISIONS = List.of(
            "00000000-0000-4000-8000-00000000c140", "00000000-0000-4000-8000-00000000c142",
            "00000000-0000-4000-8000-00000000c145", "00000000-0000-4000-8000-00000000c146",
            "00000000-0000-4000-8000-00000000c148", "00000000-0000-4000-8000-00000000c150");
    private static final List<String> HISTORICAL_REVISIONS = List.of(
            "00000000-0000-4000-8000-00000000c141", "00000000-0000-4000-8000-00000000c143",
            "00000000-0000-4000-8000-00000000c144", "00000000-0000-4000-8000-00000000c147",
            "00000000-0000-4000-8000-00000000c149");
    private static final String DIRTY_KEY = "edu:learning:progress:dirty";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired StringRedisTemplate redis;

    @DynamicPropertySource
    static void requireDedicatedEnvironment(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12C.0 seed requires edu_platform_learning_dev");
        }
        if (!REDIS_DATABASE.equals(System.getenv("LEARNING_REDIS_DATABASE"))) {
            throw new IllegalStateException("T12C.0 seed requires Learning Redis DB 4");
        }
    }

    @Test
    void applyRequestedSeedAction() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        String action = System.getenv().getOrDefault("T12C0_DEMO_SEED_ACTION", "").toUpperCase(Locale.ROOT);
        switch (action) {
            case "INITIALIZE" -> initialize();
            case "CLEANUP" -> cleanup();
            case "REBUILD" -> { cleanup(); initialize(); }
            default -> throw new IllegalArgumentException(
                    "T12C0_DEMO_SEED_ACTION must be INITIALIZE, CLEANUP or REBUILD");
        }
        System.out.printf("T12C.0 demo seed action %s completed; course=%s learner=%s%n",
                action, COURSE, LEARNER);
    }

    /**
     * 创建六个当前课时和五个历史修订：未变 VIDEO/普通元数据继承进度，A→B 与 A→B→A 当前为零，
     * ARTICLE 正文变化保留完成，类型变化及删除重建只保留历史事实。
     */
    private void initialize() {
        cleanup();
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.parse("2026-09-22T00:00:00Z");
            jdbc.update("INSERT INTO published_course_content_projection(course_id,course_version_id,publication_revision,projection_source,updated_at) VALUES(?,?,7,'EVENT',?)",
                    COURSE, "t12c0-seed-v7", Timestamp.from(now));
            String[] types = {"VIDEO", "VIDEO", "VIDEO", "VIDEO", "ARTICLE", "VIDEO"};
            for (int index = 0; index < LESSONS.size(); index++) {
                jdbc.update("INSERT INTO published_lesson_content_projection(course_id,lesson_id,content_revision_id,lesson_type,publication_revision,updated_at) VALUES(?,?,?,?,7,?)",
                        COURSE, LESSONS.get(index), CURRENT_REVISIONS.get(index), types[index], Timestamp.from(now));
            }
            // VIDEO 未变与只改标题/排序/说明/海报/附件：当前修订直接保留原聚合。
            insertProgress("00000000-0000-4000-8000-00000000c160", LESSONS.get(0), CURRENT_REVISIONS.get(0), 80, 40, false, now);
            insertProgress("00000000-0000-4000-8000-00000000c161", LESSONS.get(3), CURRENT_REVISIONS.get(3), 120, 60, true, now);
            // A→B、A→B→A、类型变化和删除重建：旧修订保留，当前修订没有聚合，因此查询为零。
            insertProgress("00000000-0000-4000-8000-00000000c162", LESSONS.get(1), HISTORICAL_REVISIONS.get(0), 100, 90, true, now);
            insertProgress("00000000-0000-4000-8000-00000000c163", LESSONS.get(2), HISTORICAL_REVISIONS.get(1), 100, 80, true, now);
            insertProgress("00000000-0000-4000-8000-00000000c164", LESSONS.get(2), HISTORICAL_REVISIONS.get(2), 30, 20, false, now);
            insertProgress("00000000-0000-4000-8000-00000000c165", LESSONS.get(4), CURRENT_REVISIONS.get(4), 0, 0, true, now);
            insertProgress("00000000-0000-4000-8000-00000000c166", LESSONS.get(5), HISTORICAL_REVISIONS.get(3), 50, 30, true, now);
            insertProgress("00000000-0000-4000-8000-00000000c167", "00000000-0000-4000-8000-00000000c136",
                    HISTORICAL_REVISIONS.get(4), 70, 40, true, now);
            jdbc.update("INSERT INTO article_lesson_completion(id,learner_id,course_id,lesson_id,content_revision_id,completed_at,revision,created_at) VALUES(?,?,?,?,?,?,1,?)",
                    "00000000-0000-4000-8000-00000000c170", LEARNER, COURSE, LESSONS.get(4),
                    CURRENT_REVISIONS.get(4), Timestamp.from(now), Timestamp.from(now));
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress WHERE learner_id=? AND course_id=?",
                Integer.class, LEARNER, COURSE)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM published_lesson_content_projection WHERE course_id=?",
                Integer.class, COURSE)).isEqualTo(6);
    }

    /** 只按固定 test learner/course 和其修订 Key 清理；不会匹配任何来源不明的数据。 */
    private void cleanup() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM lesson_learning_day WHERE learner_id=? AND course_id=?", LEARNER, COURSE);
            jdbc.update("DELETE FROM learning_activity_day WHERE learner_id=? AND course_id=?", LEARNER, COURSE);
            jdbc.update("DELETE FROM article_completion_request WHERE learner_id=? AND course_id=?", LEARNER, COURSE);
            jdbc.update("DELETE FROM article_lesson_completion WHERE learner_id=? AND course_id=?", LEARNER, COURSE);
            jdbc.update("DELETE FROM lesson_progress_event WHERE learner_id=? AND course_id=?", LEARNER, COURSE);
            jdbc.update("DELETE FROM lesson_progress WHERE learner_id=? AND course_id=?", LEARNER, COURSE);
            jdbc.update("DELETE FROM published_lesson_content_projection WHERE course_id=?", COURSE);
            jdbc.update("DELETE FROM published_course_content_projection WHERE course_id=?", COURSE);
        });
        List<String> keys = java.util.stream.Stream.concat(CURRENT_REVISIONS.stream(), HISTORICAL_REVISIONS.stream())
                .map(revision -> "edu:learning:progress:" + LEARNER + ":" + COURSE + ":" + revision).toList();
        redis.delete(keys);
        redis.opsForZSet().remove(DIRTY_KEY, keys.toArray());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress WHERE learner_id=? AND course_id=?",
                Integer.class, LEARNER, COURSE)).isZero();
        assertThat(keys.stream().filter(redis::hasKey)).isEmpty();
    }

    /** 写入一个修订隔离的 MySQL 聚合和对应强制检查点，便于演示旧事实仍在但不进入当前映射。 */
    private void insertProgress(String id, String lessonId, String revisionId, long position, long watched,
            boolean completed, Instant now) {
        jdbc.update("INSERT INTO lesson_progress(id,learner_id,course_id,lesson_id,content_revision_id,position_seconds,watched_seconds,completed,last_event_at,revision,buffer_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,1,0,?,?)",
                id, LEARNER, COURSE, lessonId, revisionId, position, watched, completed,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO lesson_progress_event(client_event_id,learner_id,course_id,lesson_id,content_revision_id,occurred_at,position_seconds,watched_seconds,completed,event_type,created_at) VALUES(?,?,?,?,?,?,?,?,?,'PAUSE',?)",
                id, LEARNER, COURSE, lessonId, revisionId, Timestamp.from(now), position,
                Math.min(300, watched), completed, Timestamp.from(now));
    }
}
