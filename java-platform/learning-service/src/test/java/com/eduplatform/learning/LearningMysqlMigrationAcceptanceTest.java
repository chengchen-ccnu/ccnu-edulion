package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.web.QuestionDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 专用 MySQL 的显式迁移验收；普通构建不会连接本地数据库。
 *
 * <p>只有安全包装脚本同时提供开关且目标严格为 edu_platform_learning_dev 才加载上下文。
 * 用例创建随机的单个知识点和题目，最终按精确 ID 逆序清理，不枚举或删除其他数据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "platform.learning.import.messaging-enabled=false")
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "LEARNING_MYSQL_ACCEPTANCE", matches = "true")
class LearningMysqlMigrationAcceptanceTest {
    private static final Set<String> TABLES = Set.of("knowledge_point", "question_aggregate", "question_version",
            "question_version_knowledge_point", "question_version_media", "question_idempotency_request",
            "question_import_task", "question_import_row_error", "learning_outbox_event", "learning_consumed_event");

    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionService questions;

    /** 在 Spring 建立连接前拒绝错误或不明确的目标 Schema。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_learning_dev".equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("MySQL acceptance requires edu_platform_learning_dev");
        }
    }

    @Test
    void flywayV1WorksOnMysqlAndFixtureIsPreciselyRemoved() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_learning_dev");
        List<String> actual = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class);
        assertThat(actual).containsAll(TABLES);

        String ownerId = UUID.randomUUID().toString();
        String pointId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        String questionId = null;
        try {
            Instant now = Instant.now();
            jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    pointId, "JUNIOR_MATH_ACCEPTANCE_" + pointId.replace("-", "").substring(0, 12).toUpperCase(),
                    "T09迁移验收知识点", null, "JUNIOR", "SEVEN", "MATHEMATICS", 99999, true, 0,
                    Timestamp.from(now), Timestamp.from(now));
            QuestionDtos.VersionInput input = new QuestionDtos.VersionInput(QuestionModels.Type.SINGLE_CHOICE,
                    QuestionModels.Difficulty.EASY, QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN,
                    QuestionModels.Subject.MATHEMATICS, "T09 合成迁移验收题干",
                    List.of(new QuestionDtos.OptionInput("A", "1"), new QuestionDtos.OptionInput("B", "2")),
                    List.of("A"), "T09 合成解析", List.of(UUID.fromString(pointId)), List.of());
            questionId = questions.create(ownerId, requestId, input, "t09-mysql-acceptance").response().id();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_version WHERE question_id=?",
                    Integer.class, questionId)).isEqualTo(1);
        } finally {
            if (questionId != null) {
                jdbc.update("DELETE FROM question_version_knowledge_point WHERE question_version_id IN (SELECT id FROM question_version WHERE question_id=?)", questionId);
                jdbc.update("DELETE FROM question_version_media WHERE question_version_id IN (SELECT id FROM question_version WHERE question_id=?)", questionId);
                jdbc.update("DELETE FROM question_idempotency_request WHERE owner_id=? AND client_request_id=?", ownerId, requestId);
                jdbc.update("DELETE FROM question_version WHERE question_id=?", questionId);
                jdbc.update("DELETE FROM question_aggregate WHERE id=? AND owner_id=?", questionId, ownerId);
            }
            jdbc.update("DELETE FROM knowledge_point WHERE id=?", pointId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point WHERE id=?", Integer.class, pointId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_idempotency_request WHERE owner_id=? AND client_request_id=?",
                Integer.class, ownerId, requestId)).isZero();
    }
}
