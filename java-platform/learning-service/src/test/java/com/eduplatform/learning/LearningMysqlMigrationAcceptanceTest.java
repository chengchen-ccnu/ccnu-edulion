package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.service.LegacyQuestionImportService;
import com.eduplatform.learning.importing.LegacyQuestionJsonParser;
import com.eduplatform.learning.web.QuestionDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
            "question_import_task", "question_import_row_error", "learning_outbox_event", "learning_consumed_event",
            "question_legacy_import_batch", "question_legacy_source_ref", "question_legacy_import_issue",
            "question_version_child");

    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionService questions;
    @Autowired LegacyQuestionImportService legacyImports;
    @Autowired LegacyQuestionJsonParser legacyParser;
    @Autowired ObjectMapper mapper;

    /** 在 Spring 建立连接前拒绝错误或不明确的目标 Schema。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_learning_dev".equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("MySQL acceptance requires edu_platform_learning_dev");
        }
    }

    @Test
    void flywayV2WorksOnMysqlAndFixtureIsPreciselyRemoved() {
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

    @Test
    @EnabledIfEnvironmentVariable(named = "LEGACY_QUESTION_ROOT", matches = ".+")
    void controlledRealLegacyJsonDryRunImportAndCleanup() throws Exception {
        Path root = Path.of(System.getenv("LEGACY_QUESTION_ROOT")).toRealPath();
        Selected selected = selectOneSafeItem(root);
        String rootHash = sha256(root.toString().replace('\\', '/').toLowerCase());
        String dryTask = null; String importTask = null;
        try {
            dryTask = legacyImports.create(selected.ownerId(), new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                    "LEGACY_ACCEPTANCE", rootHash, true, 1)).response().id();
            var dry = legacyImports.submit(selected.ownerId(), dryTask, new QuestionDtos.LegacyImportBatchRequest(
                    UUID.randomUUID(), true, List.of(selected.item())), "t09a-real-dry-run");
            assertThat(dry.validCount()).isEqualTo(1); assertThat(dry.importedCount()).isZero();

            importTask = legacyImports.create(selected.ownerId(), new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                    "LEGACY_ACCEPTANCE", rootHash, false, 1)).response().id();
            var imported = legacyImports.submit(selected.ownerId(), importTask, new QuestionDtos.LegacyImportBatchRequest(
                    UUID.randomUUID(), true, List.of(selected.item())), "t09a-real-import");
            assertThat(imported.importedCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate WHERE id=?",
                    Integer.class, imported.items().get(0).questionId())).isEqualTo(1);
            var cleaned = legacyImports.cleanup(selected.ownerId(), importTask, "t09a-real-cleanup");
            assertThat(cleaned.questionsDeleted()).isEqualTo(1);
            legacyImports.cleanup(selected.ownerId(), dryTask, "t09a-real-dry-cleanup");
        } finally {
            for (String taskId : List.of(dryTask == null ? "" : dryTask, importTask == null ? "" : importTask)) {
                if (taskId.isBlank()) continue;
                jdbc.update("DELETE FROM question_legacy_import_issue WHERE import_task_id=?", taskId);
                jdbc.update("DELETE FROM question_legacy_source_ref WHERE import_task_id=?", taskId);
                jdbc.update("DELETE FROM question_legacy_import_batch WHERE import_task_id=?", taskId);
                jdbc.update("DELETE FROM learning_outbox_event WHERE aggregate_id=?", taskId);
                jdbc.update("DELETE FROM question_import_task WHERE id=?", taskId);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_import_task WHERE id IN (?,?)", Integer.class,
                dryTask, importTask)).isZero();
    }

    /** 程序化选择一份无图片、无需复核的真实题目；不输出路径、题干、答案或 JSON。 */
    private Selected selectOneSafeItem(Path root) throws Exception {
        String owner = UUID.randomUUID().toString(); int inspected = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.filter(value -> value.toString().endsWith(".json"))::iterator) {
                if (++inspected > 5000) break;
                JsonNode json = mapper.readTree(Files.readString(path));
                Path relative = root.relativize(path); if (relative.getNameCount() < 3) continue;
                String grade = relative.getName(1).toString();
                var parsed = legacyParser.parse(json, relative.getName(0).toString(), grade);
                if (parsed.review() || parsed.candidate().imageUrls().size() > 0 || !parsed.candidate().children().isEmpty()) continue;
                String canonical = mapper.writeValueAsString(json);
                String externalId = json.path("exerciseID").asString(); if (externalId.isBlank()) continue;
                return new Selected(owner, new QuestionDtos.LegacyImportItem(externalId,
                        relative.toString().replace('\\', '/'), relative.getName(0).toString(), grade,
                        sha256(canonical), json));
            }
        }
        throw new IllegalStateException("No safe controlled legacy acceptance item found within 5000 files");
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private record Selected(String ownerId, QuestionDtos.LegacyImportItem item) {}
}
