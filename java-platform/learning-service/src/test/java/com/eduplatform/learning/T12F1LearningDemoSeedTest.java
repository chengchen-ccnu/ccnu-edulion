package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** T12F.1 学习演示 Seed，只在 learning 开发库维护图文课时初始 0% 的本域进度行。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "platform.learning.import.messaging-enabled=false")
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12F1_DEMO_SEED_ENABLED", matches = "true")
class T12F1LearningDemoSeedTest {
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String STUDENT = "00000000-0000-4000-8000-000000000101";
    private static final String COURSE = "00000000-0000-4000-8000-00000000f201";
    private static final String ARTICLE_LESSON = "00000000-0000-4000-8000-00000000f232";
    private static final String PROGRESS = "00000000-0000-4000-8000-00000000f401";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12F.1 learning demo seed requires edu_platform_learning_dev");
        }
    }

    @Test
    void applyRequestedDemoSeedAction() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        String action = System.getenv().getOrDefault("T12F1_DEMO_SEED_ACTION", "").toUpperCase(Locale.ROOT);
        transactions.executeWithoutResult(status -> {
            switch (action) {
                case "INITIALIZE" -> initialize();
                case "CLEANUP" -> cleanup();
                case "REBUILD" -> { cleanup(); initialize(); }
                default -> throw new IllegalArgumentException("T12F1_DEMO_SEED_ACTION must be INITIALIZE, CLEANUP or REBUILD");
            }
        });
        System.out.printf("T12F.1 learning demo action %s completed; article progress is 0%%%n", action);
    }

    /** Initialize 与 Rebuild 都强制恢复 0%；完成事实和请求幂等映射会被精确删除。 */
    private void initialize() {
        cleanup();
        jdbc.update("""
                INSERT INTO lesson_progress(id,learner_id,course_id,lesson_id,position_seconds,watched_seconds,
                    completed,last_event_at,revision,created_at,updated_at)
                VALUES(?,?,?,?,0,0,FALSE,CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                """, PROGRESS, STUDENT, COURSE, ARTICLE_LESSON);
        validateZero();
    }

    /** 依稳定 learner/course/lesson 精确清理 ARTICLE 完成请求、事实和 0% 聚合，不触碰其他学习记录。 */
    private void cleanup() {
        jdbc.update("DELETE FROM article_completion_request WHERE learner_id=? AND course_id=? AND lesson_id=?",
                STUDENT, COURSE, ARTICLE_LESSON);
        jdbc.update("DELETE FROM article_lesson_completion WHERE learner_id=? AND course_id=? AND lesson_id=?",
                STUDENT, COURSE, ARTICLE_LESSON);
        jdbc.update("DELETE FROM lesson_progress WHERE id=? AND learner_id=? AND course_id=? AND lesson_id=?",
                PROGRESS, STUDENT, COURSE, ARTICLE_LESSON);
    }

    private void validateZero() {
        assertThat(jdbc.queryForObject("SELECT completed FROM lesson_progress WHERE id=?", Boolean.class, PROGRESS)).isFalse();
        assertThat(jdbc.queryForObject("SELECT position_seconds+watched_seconds FROM lesson_progress WHERE id=?",
                Long.class, PROGRESS)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_lesson_completion WHERE learner_id=? AND course_id=? AND lesson_id=?",
                Integer.class, STUDENT, COURSE, ARTICLE_LESSON)).isZero();
    }
}
