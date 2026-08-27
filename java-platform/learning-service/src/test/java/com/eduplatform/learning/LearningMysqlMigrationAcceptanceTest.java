package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
            "question_version_child", "question_legacy_review_item", "question_legacy_item_resolution",
            "enrollment", "lesson_progress", "lesson_progress_event", "learning_note", "assessment",
            "assessment_question", "assessment_assignment", "assessment_attempt", "assessment_answer",
            "manual_grading_record", "wrong_question", "wrong_question_occurrence", "wrong_question_redo",
            "learning_idempotency_request");

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

    /**
     * 验证真实开发库按 V8 -> V9 -> V10 正常向前演进，且没有用补写 V4 或 baseline 绕过顺序。
     * 该断言只读 Flyway 历史与表元数据，不创建任何 Fixture。
     */
    @Test
    void flywayV10NormallyAdvancesTheExistingV9Schema() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",
                String.class);
        assertThat(versions).contains("8", "9", "10").doesNotContain("4");
        List<String> actual = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class);
        assertThat(actual).containsAll(TABLES);
    }

    /**
     * 在真实 MySQL 上写入一组最小 T11A 时间/错题重做事实，验证约束后按精确随机 ID 逆序清理。
     * 不创建课程、用户或跨 Schema 记录，也不读取任何存量业务行。
     */
    @Test
    void t11aTimingAndFiniteRedoSchemaWorksOnMysqlAndFixtureIsPreciselyRemoved() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_learning_dev");
        String assessmentId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String wrongId = UUID.randomUUID().toString();
        String redoId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try {
            jdbc.update("INSERT INTO assessment(id,owner_teacher_id,course_id,lesson_id,title,description,assessment_type,status,answer_publication_policy,opens_at,closes_at,time_limit_seconds,revision,published_at,archived_at,created_at,updated_at) VALUES(?,?,?,?,?,?,'EXAM','PUBLISHED','NEVER',?,?,120,1,?,NULL,?,?)",
                    assessmentId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
                    "T11A MySQL 验收考试", "仅验证真实迁移", Timestamp.from(now.minusSeconds(30)),
                    Timestamp.from(now.plusSeconds(300)), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            jdbc.update("INSERT INTO assessment_attempt(id,assessment_id,learner_id,status,revision,total_points,started_at,deadline_at,created_at,updated_at) VALUES(?,? ,?,'GRADED',1,1.00,?,?,?,?)",
                    attemptId, assessmentId, UUID.randomUUID().toString(), Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(120)), Timestamp.from(now), Timestamp.from(now));
            String learnerId = jdbc.queryForObject("SELECT learner_id FROM assessment_attempt WHERE id=?", String.class,
                    attemptId);
            jdbc.update("INSERT INTO wrong_question(id,learner_id,course_id,assessment_id,question_version_id,wrong_count,mastery_status,revision,redo_attempts_used,first_wrong_at,last_wrong_at,last_attempt_id,updated_at) VALUES(?,?,?,?,?,1,'LEARNING',0,0,?,?,?,?)",
                    wrongId, learnerId, UUID.randomUUID().toString(), assessmentId, UUID.randomUUID().toString(),
                    Timestamp.from(now), Timestamp.from(now), attemptId, Timestamp.from(now));
            jdbc.update("INSERT INTO wrong_question_redo(id,wrong_question_id,learner_id,client_request_id,request_fingerprint,sequence_no,answer_json,score,correct,submitted_at) VALUES(?,?,?,?,?,1,'[\"A\"]',1.00,TRUE,?)",
                    redoId, wrongId, learnerId, UUID.randomUUID().toString(), "a".repeat(64), Timestamp.from(now));
            assertThat(jdbc.queryForObject("SELECT time_limit_seconds FROM assessment WHERE id=?", Integer.class,
                    assessmentId)).isEqualTo(120);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question_redo WHERE wrong_question_id=?",
                    Integer.class, wrongId)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("UPDATE assessment SET assessment_type='PRACTICE' WHERE id=?",
                    assessmentId)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.update("DELETE FROM wrong_question_redo WHERE id=?", redoId);
            jdbc.update("DELETE FROM wrong_question WHERE id=?", wrongId);
            jdbc.update("DELETE FROM assessment_attempt WHERE id=?", attemptId);
            jdbc.update("DELETE FROM assessment WHERE id=?", assessmentId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=?", Integer.class, assessmentId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt WHERE id=?", Integer.class, attemptId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question WHERE id=?", Integer.class, wrongId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question_redo WHERE id=?", Integer.class, redoId))
                .isZero();
    }

    /** 在既有专用 MySQL 上验证 V3 组合历史与人工复核，并按本轮随机 ID 精确清理全部 Fixture。 */
    @Test
    void flywayV3CompositeHistoryAndReviewResolutionWorkOnMysqlAndFixturesArePreciselyRemoved() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_learning_dev");
        List<String> actual = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class);
        assertThat(actual).containsAll(TABLES);
        // V7 只开放小学/初中来源节点，高中必须继续禁用；断言不会修改现有业务数据。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point WHERE source_system IS NOT NULL "
                + "AND normalized_stage IN ('PRIMARY','JUNIOR') AND enabled=FALSE", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point WHERE source_system IS NOT NULL "
                + "AND normalized_stage='SENIOR' AND enabled=TRUE", Integer.class)).isZero();

        String ownerId = UUID.randomUUID().toString();
        String pointId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        String questionId = null; String reviewTaskId = null;
        try {
            Instant now = Instant.now();
            jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    pointId, "JUNIOR_MATH_ACCEPTANCE_" + pointId.replace("-", "").substring(0, 12).toUpperCase(),
                    "T09迁移验收知识点", null, "JUNIOR", "SEVEN", "MATHEMATICS", 99999, true, 0,
                    Timestamp.from(now), Timestamp.from(now));
            QuestionDtos.ChildInput child = new QuestionDtos.ChildInput(QuestionModels.Type.SINGLE_CHOICE,
                    QuestionModels.Difficulty.EASY, QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN,
                    QuestionModels.Subject.MATHEMATICS, "T09B 合成子题",
                    List.of(new QuestionDtos.OptionInput("A", "1"), new QuestionDtos.OptionInput("B", "2")),
                    List.of("A"), "T09B 子题解析", List.of(UUID.fromString(pointId)), List.of());
            QuestionDtos.VersionInput input = new QuestionDtos.VersionInput(QuestionModels.Type.COMPOSITE,
                    QuestionModels.Difficulty.EASY, QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN,
                    QuestionModels.Subject.MATHEMATICS, "T09B 合成迁移验收父题",
                    List.of(), List.of("综合作答"), "T09B 合成解析", List.of(UUID.fromString(pointId)), List.of(),
                    List.of(child));
            questionId = questions.create(ownerId, requestId, input, "t09-mysql-acceptance").response().id();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_version WHERE question_id=?",
                    Integer.class, questionId)).isEqualTo(1);
            String oldVersion = questions.get(ownerId, questionId).currentVersion().id();
            questions.update(ownerId, questionId, 0, input, "t09b-mysql-update");
            assertThat(questions.version(ownerId, questionId, oldVersion).children()).hasSize(1);
            assertThat(questions.versions(ownerId, questionId, 1, 20).total()).isEqualTo(2);

            reviewTaskId = legacyImports.create(ownerId, new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                    "LEGACY_T09B_MYSQL", "e".repeat(64), true, 1)).response().id();
            JsonNode ambiguous = mapper.readTree("""
                    {"exerciseID":"t09b-mysql-review","exerciseType":"select_single","difficulty":1,
                     "gradeIndex":7,"gradeName":"七年级","question":"含糊选项 A 1 B 2","queAns":"A",
                     "solution":"","optCount":2,"subQues":[]}
                    """);
            QuestionDtos.LegacyImportItem item = new QuestionDtos.LegacyImportItem("t09b-mysql-review",
                    "虚构版/七年级上/text/t09b-mysql-review.json", "虚构版", "七年级上",
                    sha256(mapper.writeValueAsString(ambiguous)), ambiguous);
            legacyImports.submit(ownerId, reviewTaskId, new QuestionDtos.LegacyImportBatchRequest(UUID.randomUUID(),
                    true, List.of(item)), "t09b-mysql-review");
            var review = legacyImports.items(ownerId, reviewTaskId, 1, 20,
                    QuestionModels.ImportOutcome.NEEDS_REVIEW).items().get(0);
            var corrected = new QuestionDtos.VersionInput(QuestionModels.Type.SINGLE_CHOICE,
                    QuestionModels.Difficulty.EASY, QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN,
                    QuestionModels.Subject.MATHEMATICS, "T09B 并发幂等合成题",
                    List.of(new QuestionDtos.OptionInput("A", "1"), new QuestionDtos.OptionInput("B", "2")),
                    List.of("A"), "T09B 并发幂等解析", List.of(UUID.fromString(pointId)), List.of());
            var resolution = new QuestionDtos.ResolveLegacyItemRequest(UUID.randomUUID(), 0,
                    QuestionDtos.ResolutionAction.IMPORT_CORRECTED, corrected);
            int questionsBeforeResolution = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM question_aggregate WHERE owner_id=?", Integer.class, ownerId);
            String concurrentTaskId = reviewTaskId;
            var executor = Executors.newFixedThreadPool(2);
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            try {
                var first = executor.submit(() -> {
                    ready.countDown(); start.await();
                    return legacyImports.resolve(ownerId, concurrentTaskId, review.id(), resolution, "t09b-mysql-concurrent-01");
                });
                var second = executor.submit(() -> {
                    ready.countDown(); start.await();
                    return legacyImports.resolve(ownerId, concurrentTaskId, review.id(), resolution, "t09b-mysql-concurrent-02");
                });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
                var firstResult = first.get(10, TimeUnit.SECONDS);
                var secondResult = second.get(10, TimeUnit.SECONDS);
                assertThat(secondResult).isEqualTo(firstResult);
            } finally {
                executor.shutdownNow();
            }
            assertThat(legacyImports.item(ownerId, reviewTaskId, review.id()).revision()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate WHERE owner_id=?",
                    Integer.class, ownerId)).isEqualTo(questionsBeforeResolution + 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_item_resolution WHERE import_task_id=?",
                    Integer.class, reviewTaskId)).isEqualTo(1);
        } finally {
            if (reviewTaskId != null) {
                legacyImports.cleanup(ownerId, reviewTaskId, "t09b-mysql-review-cleanup");
                jdbc.update("DELETE FROM question_legacy_import_issue WHERE import_task_id=?", reviewTaskId);
                jdbc.update("DELETE FROM question_legacy_import_batch WHERE import_task_id=?", reviewTaskId);
                jdbc.update("DELETE FROM learning_outbox_event WHERE aggregate_id=?", reviewTaskId);
                jdbc.update("DELETE FROM question_import_task WHERE id=?", reviewTaskId);
            }
            if (questionId != null) {
                List<String> ids = new java.util.ArrayList<>(); ids.add(questionId);
                ids.addAll(jdbc.queryForList("SELECT id FROM question_aggregate WHERE parent_question_id=?", String.class, questionId));
                jdbc.update("DELETE FROM question_version_child WHERE parent_question_version_id IN (SELECT id FROM question_version WHERE question_id=?)", questionId);
                for (String id : ids) {
                    jdbc.update("DELETE FROM question_version_knowledge_point WHERE question_version_id IN (SELECT id FROM question_version WHERE question_id=?)", id);
                    jdbc.update("DELETE FROM question_version_media WHERE question_version_id IN (SELECT id FROM question_version WHERE question_id=?)", id);
                    jdbc.update("DELETE FROM question_version WHERE question_id=?", id);
                }
                jdbc.update("DELETE FROM question_idempotency_request WHERE owner_id=? AND client_request_id=?", ownerId, requestId);
                jdbc.update("DELETE FROM question_aggregate WHERE parent_question_id=?", questionId);
                jdbc.update("DELETE FROM question_aggregate WHERE id=? AND owner_id=?", questionId, ownerId);
            }
            jdbc.update("DELETE FROM knowledge_point WHERE id=?", pointId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point WHERE id=?", Integer.class, pointId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_idempotency_request WHERE owner_id=? AND client_request_id=?",
                Integer.class, ownerId, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate WHERE owner_id=?", Integer.class, ownerId)).isZero();
        if (reviewTaskId != null) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_import_task WHERE id=?", Integer.class, reviewTaskId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_review_item WHERE import_task_id=?",
                    Integer.class, reviewTaskId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_item_resolution WHERE import_task_id=?",
                    Integer.class, reviewTaskId)).isZero();
        }
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
                jdbc.update("DELETE FROM question_legacy_item_resolution WHERE import_task_id=?", taskId);
                jdbc.update("DELETE FROM question_legacy_review_item WHERE import_task_id=?", taskId);
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
