package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.importing.LegacyQuestionJsonParser;
import com.eduplatform.learning.importing.QuestionImportProcessor;
import com.eduplatform.learning.importing.QuestionImportParser;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.service.QuestionImportService;
import com.eduplatform.learning.service.LegacyQuestionImportService;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.web.QuestionDtos;
import com.eduplatform.learning.web.LearningDtos;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * H2 MySQL 模式集成验证不可变版本、幂等、所有权、媒体 Outbox 与导入重复消费。
 * 每项测试使用固定合成 UUID 和知识点，不接触真实 MySQL、OSS 或 RabbitMQ。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QuestionBankIntegrationTest {
    private static final String TEACHER = "00000000-0000-4000-8000-000000009101";
    private static final String OTHER = "00000000-0000-4000-8000-000000009102";
    private static final String ADMIN = "00000000-0000-4000-8000-000000009103";
    private static final String KP = "00000000-0000-4000-8000-000000009201";
    private static final String PRIMARY_KP = "00000000-0000-4000-8000-000000009202";
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionService questions;
    @Autowired QuestionImportService imports;
    @Autowired LegacyQuestionImportService legacyImports;
    @Autowired QuestionImportProcessor processor;
    @Autowired QuestionImportParser parser;
    @Autowired LegacyQuestionJsonParser legacyParser;
    @Autowired QuestionRepository repository;
    @Autowired ObjectMapper objectMapper;
    @Value("${local.server.port}") int port;
    private final HttpClient http = HttpClient.newHttpClient();
    @MockitoBean MediaClient media;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM knowledge_point_prerequisite"); jdbc.update("DELETE FROM knowledge_graph_import_issue");
        jdbc.update("DELETE FROM knowledge_graph_import_relation"); jdbc.update("DELETE FROM knowledge_graph_import_node");
        jdbc.update("DELETE FROM knowledge_graph_import_batch"); jdbc.update("DELETE FROM knowledge_graph_import_task");
        jdbc.update("DELETE FROM learning_consumed_event"); jdbc.update("DELETE FROM learning_outbox_event");
        jdbc.update("DELETE FROM question_legacy_item_resolution"); jdbc.update("DELETE FROM question_legacy_review_item");
        jdbc.update("DELETE FROM question_legacy_import_issue"); jdbc.update("DELETE FROM question_legacy_source_ref");
        jdbc.update("DELETE FROM question_legacy_import_batch"); jdbc.update("DELETE FROM question_version_child");
        jdbc.update("DELETE FROM question_import_row_error"); jdbc.update("DELETE FROM question_import_task");
        jdbc.update("DELETE FROM question_version_media"); jdbc.update("DELETE FROM question_version_knowledge_point");
        jdbc.update("DELETE FROM question_index_revision");
        jdbc.update("DELETE FROM question_idempotency_request"); jdbc.update("DELETE FROM question_version");
        jdbc.update("UPDATE question_aggregate SET parent_question_id=NULL");
        jdbc.update("DELETE FROM question_aggregate"); jdbc.update("DELETE FROM knowledge_point");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                KP, "JUNIOR_MATH_NUMBER", "数与式", null, "JUNIOR", "SEVEN", "MATHEMATICS", 1, true, 0,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                PRIMARY_KP, "PRIMARY_MATH_NUMBER", "整数认识", null, "PRIMARY", "ONE", "MATHEMATICS", 1, true, 0,
                Timestamp.from(now), Timestamp.from(now));
        doNothing().when(media).requireBindable(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createIsIdempotentOwnerOnlyAndListProjectionDoesNotReadAnswers() {
        String requestId = UUID.randomUUID().toString();
        var first = questions.create(TEACHER, requestId, single("A", List.of()), "trace-create-01");
        var replay = questions.create(TEACHER, requestId, single("A", List.of()), "trace-create-02");
        assertThat(first.created()).isTrue(); assertThat(replay.created()).isFalse();
        assertThat(replay.response().id()).isEqualTo(first.response().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isEqualTo(1);
        assertThat(questions.list(TEACHER, 1, 20, null, null, null, null, null, null, null).items()).singleElement()
                .satisfies(item -> assertThat(item.stemPreview()).contains("1+1"));
        assertThatThrownBy(() -> questions.get(OTHER, first.response().id())).isInstanceOfSatisfying(QuestionException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void systemQuestionsArePublicReadOnlyAndTeacherListSupportsSourceFilter() {
        var system = questions.createImported(ADMIN, QuestionModels.Scope.SYSTEM, UUID.randomUUID().toString(),
                single("A", List.of()), "trace-system-create").response();
        var own = questions.create(TEACHER, UUID.randomUUID().toString(), single("A", List.of()),
                "trace-teacher-create").response();
        questions.create(OTHER, UUID.randomUUID().toString(), single("A", List.of()),
                "trace-other-create");

        assertThat(system.scope()).isEqualTo(QuestionModels.Scope.SYSTEM);
        assertThat(system.status()).isEqualTo(QuestionModels.Status.ACTIVE);
        assertThat(system.editable()).isFalse();
        String indexEvent = jdbc.queryForObject("""
                SELECT payload_json FROM learning_outbox_event
                WHERE event_type='QuestionIndexProjectionChanged' AND aggregate_id=?
                """, String.class, system.id());
        assertThat(indexEvent).contains("\"operation\":\"UPSERT\"", "\"questionVersionId\"")
                .doesNotContain("answers", "analysis", "1+1=?");
        assertThat(questions.get(TEACHER, system.id())).extracting(QuestionDtos.QuestionResponse::editable)
                .isEqualTo(false);
        assertThat(questions.list(TEACHER, 1, 20, null, null, null, null, null, null, null, null).items())
                .extracting(QuestionDtos.QuestionSummary::id).containsExactlyInAnyOrder(system.id(), own.id());
        assertThat(questions.list(TEACHER, 1, 20, null, null, null, null, null, null, null,
                QuestionModels.Scope.SYSTEM).items()).singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo(system.id()));
        assertThat(questions.list(TEACHER, 1, 20, null, null, null, null, null, null, null,
                QuestionModels.Scope.TEACHER).items()).singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo(own.id()));

        assertThatThrownBy(() -> questions.update(TEACHER, system.id(), system.revision(),
                single("A", List.of()), "trace-system-update"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.code()).isEqualTo("QUESTION.SYSTEM_READ_ONLY");
                });
        assertThat(questions.listSystem(1, 20, null, null, null, null, null).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(system.id());
                    assertThat(item.editable()).isFalse();
                });
        assertThat(questions.getSystem(system.id()).stem()).contains("1+1");
        assertThatThrownBy(() -> questions.getSystem(own.id())).isInstanceOfSatisfying(QuestionException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    /** 公共单题仅返回题干/选项使用的媒体；答案专用图片即使同版本绑定也不能授权。 */
    void systemQuestionMediaProjectionRejectsAnswerOnlyBinding() {
        UUID visible = UUID.randomUUID();
        UUID answerOnly = UUID.randomUUID();
        var base = single("A", List.of(
                new QuestionDtos.MediaInput(visible, QuestionModels.MediaPurpose.QUESTION_IMAGE, 1),
                new QuestionDtos.MediaInput(answerOnly, QuestionModels.MediaPurpose.QUESTION_IMAGE, 2)));
        var input = new QuestionDtos.VersionInput(base.type(), base.difficulty(), base.stage(), base.grade(),
                base.subject(), "观察 {{media:" + visible + "}} 后作答", base.options(), base.answers(),
                "答案解析 {{media:" + answerOnly + "}}", base.knowledgePointIds(), base.media());
        var system = questions.createImported(ADMIN, QuestionModels.Scope.SYSTEM, UUID.randomUUID().toString(),
                input, "trace-system-media-create").response();
        var grant = new LearningDtos.MediaAccessGrant("GET", "https://fixture.invalid/content", java.util.Map.of(), Instant.now());
        org.mockito.Mockito.when(media.issueSystemQuestionAccess(visible.toString(), TEACHER,
                "QUESTION_IMAGE", LearningDtos.MediaDisposition.INLINE, "trace-system-media-grant"))
                .thenReturn(grant);

        assertThat(questions.getSystem(system.id()).media()).extracting(QuestionDtos.MediaResponse::mediaId)
                .containsExactly(visible.toString());
        assertThat(questions.systemMediaAccess(TEACHER, system.id(), visible.toString(),
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.INLINE),
                "trace-system-media-grant").url()).isEqualTo(grant.url());
        assertThatThrownBy(() -> questions.systemMediaAccess(TEACHER, system.id(), answerOnly.toString(),
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.INLINE),
                "trace-answer-media"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    /** 组合题的子题沿用同一媒体投影：题干图片可见，答案专用图片不随父题详情或授权接口泄露。 */
    void systemCompositeChildMediaUsesTheSameSafeProjection() {
        UUID visible = UUID.randomUUID();
        UUID answerOnly = UUID.randomUUID();
        var child = new QuestionDtos.VersionInput(QuestionModels.Type.SINGLE_CHOICE, QuestionModels.Difficulty.EASY,
                QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS,
                "子题观察 {{media:" + visible + "}}", List.of(new QuestionDtos.OptionInput("A", "1"),
                        new QuestionDtos.OptionInput("B", "2")), List.of("A"),
                "子题解析 {{media:" + answerOnly + "}}", List.of(UUID.fromString(KP)), List.of(
                        new QuestionDtos.MediaInput(visible, QuestionModels.MediaPurpose.QUESTION_IMAGE, 1),
                        new QuestionDtos.MediaInput(answerOnly, QuestionModels.MediaPurpose.QUESTION_IMAGE, 2)));
        var childQuestion = questions.createImported(ADMIN, QuestionModels.Scope.SYSTEM,
                UUID.randomUUID().toString(), child, "trace-system-composite-child").response();
        var parent = new QuestionDtos.VersionInput(QuestionModels.Type.COMPOSITE, QuestionModels.Difficulty.MEDIUM,
                QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS,
                "组合题父题", List.of(), List.of("综合作答"), "父题解析", List.of(UUID.fromString(KP)),
                List.of());
        var system = questions.createImportedComposite(ADMIN, QuestionModels.Scope.SYSTEM,
                UUID.randomUUID().toString(), parent, List.of(childQuestion.id()),
                "trace-system-composite-media").response();
        assertThat(jdbc.queryForList("""
                SELECT payload_json FROM learning_outbox_event
                WHERE event_type='QuestionIndexProjectionChanged' AND aggregate_id=?
                ORDER BY occurred_at,event_id
                """, String.class, childQuestion.id())).hasSize(2).satisfies(events -> {
                    assertThat(events.get(0)).contains("\"operation\":\"UPSERT\"", "\"sourceRevision\":1");
                    assertThat(events.get(1)).contains("\"operation\":\"DELETE\"", "\"sourceRevision\":2");
                });
        var grant = new LearningDtos.MediaAccessGrant("GET", "https://fixture.invalid/child", java.util.Map.of(), Instant.now());
        org.mockito.Mockito.when(media.issueSystemQuestionAccess(visible.toString(), TEACHER,
                "QUESTION_IMAGE", LearningDtos.MediaDisposition.INLINE, "trace-child-media"))
                .thenReturn(grant);

        assertThat(questions.getSystem(system.id()).children()).singleElement().satisfies(item ->
                assertThat(item.media()).extracting(QuestionDtos.MediaResponse::mediaId).containsExactly(visible.toString()));
        assertThat(questions.systemMediaAccess(TEACHER, system.id(), visible.toString(),
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.INLINE),
                "trace-child-media").url()).isEqualTo(grant.url());
        assertThatThrownBy(() -> questions.systemMediaAccess(TEACHER, system.id(), answerOnly.toString(),
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.INLINE),
                "trace-child-hidden"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void primaryQuestionCanBeCreatedAndStageGradeMismatchIsRejected() {
        var primary = new QuestionDtos.VersionInput(QuestionModels.Type.FILL_BLANK, QuestionModels.Difficulty.EASY,
                QuestionModels.Stage.PRIMARY, QuestionModels.Grade.ONE, QuestionModels.Subject.MATHEMATICS,
                "填写 $1+1$ 的结果", List.of(), List.of("2"), "整数加法", List.of(UUID.fromString(PRIMARY_KP)), List.of());
        var created = questions.create(TEACHER, UUID.randomUUID().toString(), primary, "trace-primary").response();
        assertThat(created.currentVersion().stage()).isEqualTo(QuestionModels.Stage.PRIMARY);
        assertThat(questions.list(TEACHER, 1, 20, null, QuestionModels.Stage.PRIMARY,
                QuestionModels.Grade.ONE, null, null, null, null).items()).singleElement()
                .satisfies(item -> assertThat(item.stage()).isEqualTo(QuestionModels.Stage.PRIMARY));

        var mismatch = new QuestionDtos.VersionInput(QuestionModels.Type.FILL_BLANK, QuestionModels.Difficulty.EASY,
                QuestionModels.Stage.PRIMARY, QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS,
                "错误组合", List.of(), List.of("2"), "", List.of(), List.of());
        assertThatThrownBy(() -> questions.create(TEACHER, UUID.randomUUID().toString(), mismatch, "trace-mismatch"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMON.VALIDATION_FAILED"));
    }

    @Test
    void updateInsertsImmutableVersionAndOptimisticLockRejectsStaleWriter() {
        var created = questions.create(TEACHER, UUID.randomUUID().toString(), single("A", List.of()), "trace-version-01").response();
        var updated = questions.update(TEACHER, created.id(), 0, single("B", List.of()), "trace-version-02");
        assertThat(updated.revision()).isEqualTo(1); assertThat(updated.currentVersion().versionNumber()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_version WHERE question_id=?", Integer.class, created.id())).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT answers_json FROM question_version WHERE question_id=? ORDER BY version_number", String.class, created.id()))
                .containsExactly("[\"A\"]", "[\"B\"]");
        assertThatThrownBy(() -> questions.update(TEACHER, created.id(), 0, single("A", List.of()), "trace-version-03"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.code()).isEqualTo("QUESTION.REVISION_CONFLICT"));
    }

    @Test
    void mediaValidationFailureRollsBackAndNewVersionsRetainHistoricalReferences() {
        UUID firstMedia = UUID.randomUUID(); UUID secondMedia = UUID.randomUUID();
        var created = questions.create(TEACHER, UUID.randomUUID().toString(), single("A",
                List.of(new QuestionDtos.MediaInput(firstMedia, QuestionModels.MediaPurpose.QUESTION_IMAGE, 1))), "trace-media-01").response();
        questions.update(TEACHER, created.id(), 0, single("B",
                List.of(new QuestionDtos.MediaInput(secondMedia, QuestionModels.MediaPurpose.QUESTION_IMAGE, 1))), "trace-media-02");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_version_media", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE event_type='MediaReferenceBound'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE event_type='MediaReferenceReleased'", Integer.class)).isZero();
        doThrow(new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "QUESTION.MEDIA_NOT_BINDABLE", "媒体不可绑定"))
                .when(media).requireBindable(anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> questions.create(TEACHER, UUID.randomUUID().toString(), single("A",
                List.of(new QuestionDtos.MediaInput(UUID.randomUUID(), QuestionModels.MediaPurpose.QUESTION_AUDIO, 1))), "trace-media-03"))
                .isInstanceOf(QuestionException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isEqualTo(1);
    }

    /** 内联占位符不能借机读取未绑定或非图片媒体。 */
    @Test
    void inlineImageMarkerMustReferenceBoundQuestionImage() {
        var base = single("A", List.of()); String foreignMedia = UUID.randomUUID().toString();
        var unbound = new QuestionDtos.VersionInput(base.type(), base.difficulty(), base.stage(), base.grade(),
                base.subject(), "观察 {{media:" + foreignMedia + "}}", base.options(), base.answers(), base.analysis(),
                base.knowledgePointIds(), List.of());

        assertThatThrownBy(() -> questions.create(TEACHER, UUID.randomUUID().toString(), unbound, "trace-inline-media-01"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("COMMON.VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isZero();
    }

    @Test
    void importPartiallySucceedsAndDuplicateEventCreatesNoDuplicateQuestion() {
        UUID source = UUID.randomUUID(); UUID request = UUID.randomUUID();
        var task = imports.create(TEACHER, new QuestionDtos.CreateImportRequest(request, source, QuestionModels.ImportFormat.CSV),
                "trace-import-01").response();
        String csv = "type,difficulty,grade,stem,optionsJson,answersJson,analysis,knowledgePointCodesJson,mediaJson\n"
                + row("SINGLE_CHOICE", "EASY", "SEVEN", "1+1=?", "[{\"key\":\"A\",\"content\":\"2\"},{\"key\":\"B\",\"content\":\"3\"}]", "[\"A\"]", "解析", "[\"JUNIOR_MATH_NUMBER\"]", "[]") + "\n"
                + row("SINGLE_CHOICE", "EASY", "SEVEN", "<img src='https://invalid.example/a.png'>", "[]", "[\"A\"]", "", "[\"JUNIOR_MATH_NUMBER\"]", "[]") + "\n";
        org.mockito.Mockito.when(media.openOriginal(source.toString(), TEACHER, "trace-import-01"))
                .thenAnswer(ignored -> new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        String event = jdbc.queryForObject("SELECT payload_json FROM learning_outbox_event WHERE event_type='QuestionImportRequested'", String.class);
        processor.process(event); processor.process(event);
        var result = imports.get(TEACHER, task.id());
        assertThat(result.status()).isEqualTo(QuestionModels.ImportStatus.PARTIAL_SUCCESS);
        assertThat(result.progress()).isEqualTo(100); assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.processedRows()).isEqualTo(2); assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("QUESTION_IMPORT.EXTERNAL_IMAGE_REQUIRES_UPLOAD");
            assertThat(error.message()).contains("QUESTION_IMAGE");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_consumed_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void retainedImportSourceReleasesThroughOutboxOnlyAfterDeadline() {
        var task = imports.create(TEACHER, new QuestionDtos.CreateImportRequest(UUID.randomUUID(), UUID.randomUUID(),
                QuestionModels.ImportFormat.CSV), "trace-release-01").response();
        jdbc.update("UPDATE question_import_task SET status='COMPLETED',retain_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), task.id());
        var due = repository.releasableImports(Instant.now(), 10);
        assertThat(due).singleElement(); imports.release(due.get(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE event_type='MediaReferenceReleased'", Integer.class)).isEqualTo(1);
        assertThat(repository.findImportTask(task.id()).orElseThrow().sourceReleasedAt()).isNotNull();
    }

    @Test
    void httpPermissionBoundaryAndTeacherListPreventAnswerLeakage() throws Exception {
        var created = questions.create(TEACHER, UUID.randomUUID().toString(), single("A", List.of()), "trace-http-01").response();
        HttpResponse<String> listed = send(HttpRequest.newBuilder(uri("/api/v1/teacher/questions"))
                .header("X-User-Id", TEACHER).header("X-User-Roles", "TEACHER").GET().build());
        assertThat(listed.statusCode()).isEqualTo(200);
        var item = objectMapper.readTree(listed.body()).get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(created.id()); assertThat(item.has("answers")).isFalse(); assertThat(item.has("analysis")).isFalse();
        HttpResponse<String> detail = send(HttpRequest.newBuilder(uri("/api/v1/teacher/questions/" + created.id()))
                .header("X-User-Id", TEACHER).header("X-User-Roles", "TEACHER").GET().build());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(detail.body()).get("currentVersion").get("answers").get(0).asString()).isEqualTo("A");
        assertThat(objectMapper.readTree(detail.body()).get("currentVersion").get("analysis").asString()).isEqualTo("完整解析");

        String adminBody = objectMapper.writeValueAsString(new QuestionDtos.CreateKnowledgePointRequest(
                "JUNIOR_MATH_GEOMETRY", "图形", null, QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN,
                QuestionModels.Subject.MATHEMATICS, 2, true))
                .replace("\"name\":\"图形\",", "\"name\":\"图形\",\"parentId\":null,");
        HttpResponse<String> forbidden = send(HttpRequest.newBuilder(uri("/api/v1/admin/knowledge-points"))
                .header("Content-Type", "application/json").header("X-User-Id", OTHER).header("X-User-Roles", "ADMIN")
                .POST(HttpRequest.BodyPublishers.ofString(adminBody)).build());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        HttpResponse<String> admin = send(HttpRequest.newBuilder(uri("/api/v1/admin/knowledge-points"))
                .header("Content-Type", "application/json").header("X-User-Id", OTHER).header("X-User-Roles", "ADMIN")
                .header("X-User-Permissions", "administration:access").POST(HttpRequest.BodyPublishers.ofString(adminBody)).build());
        assertThat(admin.statusCode()).isEqualTo(201); assertThat(objectMapper.readTree(admin.body()).get("code").asString()).isEqualTo("JUNIOR_MATH_GEOMETRY");

        String unknownBody = adminBody.substring(0, adminBody.length() - 1) + ",\"unexpected\":true}";
        HttpResponse<String> unknown = send(HttpRequest.newBuilder(uri("/api/v1/admin/knowledge-points"))
                .header("Content-Type", "application/json").header("X-User-Id", OTHER).header("X-User-Roles", "ADMIN")
                .header("X-User-Permissions", "administration:access").POST(HttpRequest.BodyPublishers.ofString(unknownBody)).build());
        assertThat(unknown.statusCode()).isEqualTo(422);
        HttpResponse<String> missing = send(HttpRequest.newBuilder(uri("/api/v1/admin/knowledge-points"))
                .header("Content-Type", "application/json").header("X-User-Id", OTHER).header("X-User-Roles", "ADMIN")
                .header("X-User-Permissions", "administration:access")
                .POST(HttpRequest.BodyPublishers.ofString(adminBody.replace("\"parentId\":null,", ""))).build());
        assertThat(missing.statusCode()).isEqualTo(422);
    }

    @Test
    void xlsxRejectsEmbeddedImagesAndFormulaCellsWithStableRowErrors() throws Exception {
        String header = "<row r='1'>" + inlineCells(List.of("type", "difficulty", "grade", "stem", "optionsJson",
                "answersJson", "analysis", "knowledgePointCodesJson", "mediaJson"), 1) + "</row>";
        String formulaRow = "<row r='2'><c r='A2'><f>1+1</f><v>2</v></c></row>";
        String sheet = "<?xml version='1.0' encoding='UTF-8'?><worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData>"
                + header + formulaRow + "</sheetData></worksheet>";
        var formula = parser.parse(new ByteArrayInputStream(xlsx(sheet, false)), QuestionModels.ImportFormat.XLSX);
        assertThat(formula.rows()).singleElement().satisfies(row -> assertThat(row.errorCode()).isEqualTo("QUESTION_IMPORT.FORMULA_UNSUPPORTED"));
        var image = parser.parse(new ByteArrayInputStream(xlsx(sheet, true)), QuestionModels.ImportFormat.XLSX);
        assertThat(image.rows()).singleElement().satisfies(row -> assertThat(row.errorCode()).isEqualTo("QUESTION_IMPORT.EMBEDDED_IMAGE_UNSUPPORTED"));
    }

    @Test
    void supportsAllFiveQuestionTypesWithTypeSpecificAnswerValidation() {
        questions.create(TEACHER, UUID.randomUUID().toString(), version(QuestionModels.Type.MULTIPLE_CHOICE,
                List.of(new QuestionDtos.OptionInput("A", "1"), new QuestionDtos.OptionInput("B", "2"), new QuestionDtos.OptionInput("C", "3")),
                List.of("A", "C")), "trace-types-01");
        questions.create(TEACHER, UUID.randomUUID().toString(), version(QuestionModels.Type.TRUE_FALSE, List.of(), List.of("TRUE")), "trace-types-02");
        questions.create(TEACHER, UUID.randomUUID().toString(), version(QuestionModels.Type.FILL_BLANK, List.of(), List.of("x=1")), "trace-types-03");
        questions.create(TEACHER, UUID.randomUUID().toString(), version(QuestionModels.Type.SHORT_ANSWER, List.of(), List.of("证明过程")), "trace-types-04");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isEqualTo(4);
        assertThatThrownBy(() -> questions.create(TEACHER, UUID.randomUUID().toString(), version(QuestionModels.Type.MULTIPLE_CHOICE,
                List.of(new QuestionDtos.OptionInput("A", "1"), new QuestionDtos.OptionInput("B", "2")),
                List.of("A", "A", "B")), "trace-types-duplicate"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.fieldErrors()).isNotEmpty());
        assertThatThrownBy(() -> questions.create(TEACHER, UUID.randomUUID().toString(),
                version(QuestionModels.Type.TRUE_FALSE, List.of(), List.of("YES")), "trace-types-05"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.fieldErrors()).isNotEmpty());
    }

    @Test
    void lifecycleAllowsDraftActiveArchivedAndBlocksArchivedMutation() {
        var created = questions.create(TEACHER, UUID.randomUUID().toString(), single("A", List.of()), "trace-status-01").response();
        var active = questions.status(TEACHER, created.id(), 0, QuestionModels.Status.ACTIVE);
        assertThat(active.status()).isEqualTo(QuestionModels.Status.ACTIVE); assertThat(active.revision()).isEqualTo(1);
        var archived = questions.status(TEACHER, created.id(), 1, QuestionModels.Status.ARCHIVED);
        assertThat(archived.status()).isEqualTo(QuestionModels.Status.ARCHIVED); assertThat(archived.revision()).isEqualTo(2);
        assertThatThrownBy(() -> questions.update(TEACHER, created.id(), 2, single("B", List.of()), "trace-status-02"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.code()).isEqualTo("QUESTION.ARCHIVED"));
    }

    @Test
    void legacyDryRunIsBoundedIdempotentAndRoutesAmbiguousOptionsToReview() throws Exception {
        var task = legacyImports.create(TEACHER, new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                "LEGACY_FIXTURE", "a".repeat(64), true, 2)).response();
        var validJson = objectMapper.readTree("""
                {"exerciseID":"fixture-fill-01","exerciseType":"fillin","difficulty":1,"gradeIndex":7,
                 "gradeName":"七年级","question":"计算 $x+1$","queAns":"2","solution":"","subQues":[]}
                """);
        var ambiguousJson = objectMapper.readTree("""
                {"exerciseID":"fixture-choice-01","exerciseType":"select_single","difficulty":5,"gradeIndex":7,
                 "gradeName":"七年级","question":"选择正确结果 A 1 B 2","queAns":"A","solution":"","optCount":2,"subQues":[]}
                """);
        var request = new QuestionDtos.LegacyImportBatchRequest(UUID.randomUUID(), true, List.of(
                legacyItem("fixture-fill-01", validJson), legacyItem("fixture-choice-01", ambiguousJson)));
        var first = legacyImports.submit(TEACHER, task.id(), request, "trace-legacy-dry-01");
        var replay = legacyImports.submit(TEACHER, task.id(), request, "trace-legacy-dry-02");
        assertThat(first.validCount()).isEqualTo(1); assertThat(first.reviewCount()).isEqualTo(1);
        assertThat(first.items().get(1).issueCodes()).containsExactly("EMBEDDED_OPTIONS_AMBIGUOUS");
        assertThat(replay.replayed()).isTrue(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_import_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_source_ref", Integer.class)).isEqualTo(2);
    }

    /** 历史填简答的答案也可含图；外部 URL 必须移出答案并进入媒体转存列表。 */
    @Test
    void legacyParserExtractsAnswerImagesWithoutPersistingExternalUrls() throws Exception {
        var source = objectMapper.readTree("""
                {"exerciseID":"fixture-answer-image-01","exerciseType":"fillin","difficulty":1,"gradeIndex":7,
                 "gradeName":"七年级","question":"观察并填空","queAns":"<p>2<img src=\\\"https://images.fixture.test/answer.png\\\"></p>",
                 "solution":"","subQues":[]}
                """);

        var parsed = legacyParser.parse(source, "虚构版", "七年级上");

        assertThat(parsed.issueCodes()).isEmpty();
        assertThat(parsed.candidate().input().answers()).hasSize(1);
        assertThat(parsed.candidate().input().answers().get(0)).contains("{{question-image:1}}")
                .doesNotContain("https://");
        assertThat(parsed.candidate().imageUrls()).containsExactly("https://images.fixture.test/answer.png");
    }

    @Test
    void legacyImportCreatesOrderedCompositeTransfersImagesAndCleansExactTask() throws Exception {
        UUID transferred = UUID.randomUUID();
        org.mockito.Mockito.when(media.transferExternalImage(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("TEACHER"), anyString()))
                .thenReturn(transferred.toString());
        var task = legacyImports.create(TEACHER, new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                "LEGACY_FIXTURE", "b".repeat(64), false, 1)).response();
        var composite = objectMapper.readTree("""
                {"exerciseID":"fixture-composite-01","exerciseType":"writeout","difficulty":5,"gradeIndex":7,
                 "gradeName":"七年级","question":"阅读材料<img src=\\\"https://images.fixture.test/a.png\\\">","queAns":"(1)1 (2)过程",
                 "solution":"<script>bad()</script><p>(1)安全解析；(2)代入计算</p>","subQues":[
                   {"exerciseType":"fillin","question":"第一问","solution":"","sortIndex":1},
                   {"exerciseType":"writeout","question":"第二问","solution":"","sortIndex":2}]}
                """);
        var request = new QuestionDtos.LegacyImportBatchRequest(UUID.randomUUID(), true,
                List.of(legacyItem("fixture-composite-01", composite)));
        var rejected = legacyImports.submit(TEACHER, task.id(), request, "trace-legacy-import-unsafe");
        assertThat(rejected.reviewCount()).isEqualTo(1);
        assertThat(rejected.items().get(0).issueCodes()).contains("HTML_UNSAFE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isZero();

        var importTask = legacyImports.create(TEACHER, new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                "LEGACY_FIXTURE_SAFE", "c".repeat(64), false, 1)).response();
        var safeComposite = objectMapper.readTree(composite.toString().replace("<script>bad()</script>", ""));
        var imported = legacyImports.submit(TEACHER, importTask.id(), new QuestionDtos.LegacyImportBatchRequest(
                UUID.randomUUID(), true, List.of(legacyItem("fixture-composite-01", safeComposite))), "trace-legacy-import-01");
        assertThat(imported.importedCount()).isEqualTo(1);
        String parentId = imported.items().get(0).questionId();
        var parent = questions.get(TEACHER, parentId);
        assertThat(parent.currentVersion().type()).isEqualTo(QuestionModels.Type.COMPOSITE);
        assertThat(parent.currentVersion().children()).extracting(QuestionDtos.ChildResponse::sequence).containsExactly(1, 2);
        assertThat(parent.currentVersion().stem()).contains("{{media:" + transferred + "}}")
                .doesNotContain("https://");
        assertThat(parent.currentVersion().answers()).singleElement().asString().contains("(1)1\n\n(2)过程");
        assertThat(parent.currentVersion().analysis()).contains("(1)安全解析；\n\n(2)代入计算");
        verify(media).transferExternalImage(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("TEACHER"), anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isEqualTo(3);
        var cleaned = legacyImports.cleanup(TEACHER, importTask.id(), "trace-legacy-cleanup-01");
        assertThat(cleaned.questionsDeleted()).isEqualTo(3); assertThat(cleaned.mediaReferencesReleased()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE event_type='MediaReferenceReleased'", Integer.class)).isEqualTo(1);
    }

    /** SYSTEM 历史题清理必须先发更高 revision 的 tombstone，防止派生索引残留已删除公共题。 */
    @Test
    void systemLegacyCleanupEmitsNewerQuestionIndexTombstone() throws Exception {
        var task = legacyImports.create(ADMIN, QuestionModels.Scope.SYSTEM,
                new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                        "SYSTEM_AGENT_INDEX_FIXTURE", "d".repeat(64), false, 1)).response();
        var source = objectMapper.readTree("""
                {"exerciseID":"system-index-cleanup-01","exerciseType":"fillin","difficulty":1,
                 "gradeIndex":7,"gradeName":"七年级","question":"计算 $x+1$", "queAns":"2",
                 "solution":"代入计算","subQues":[]}
                """);
        var imported = legacyImports.submit(ADMIN, task.id(), new QuestionDtos.LegacyImportBatchRequest(
                UUID.randomUUID(), true, List.of(legacyItem("system-index-cleanup-01", source))),
                "trace-system-index-create");
        String questionId = imported.items().get(0).questionId();
        List<String> beforeCleanup = jdbc.queryForList("""
                SELECT payload_json FROM learning_outbox_event
                WHERE event_type='QuestionIndexProjectionChanged' AND aggregate_id=?
                ORDER BY occurred_at,event_id
                """, String.class, questionId);
        assertThat(beforeCleanup).hasSize(1);
        var upsert = objectMapper.readTree(beforeCleanup.get(0)).path("payload");
        assertThat(upsert.path("operation").asText()).isEqualTo("UPSERT");

        legacyImports.cleanup(ADMIN, task.id(), "trace-system-index-cleanup");

        List<String> afterCleanup = jdbc.queryForList("""
                SELECT payload_json FROM learning_outbox_event
                WHERE event_type='QuestionIndexProjectionChanged' AND aggregate_id=?
                ORDER BY occurred_at,event_id
                """, String.class, questionId);
        assertThat(afterCleanup).hasSize(2);
        var tombstone = objectMapper.readTree(afterCleanup.get(1)).path("payload");
        assertThat(tombstone.path("operation").asText()).isEqualTo("DELETE");
        assertThat(tombstone.path("sourceRevision").asLong())
                .isGreaterThan(upsert.path("sourceRevision").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate WHERE id=?",
                Integer.class, questionId)).isZero();
    }

    /** 创建并更新一层组合题，验证根列表隔离及旧父版本仍读取原子题完整内容。 */
    @Test
    void structuredCompositeWritesFreezeFullChildHistoryAndHideChildAggregatesFromList() {
        var created = questions.create(TEACHER, UUID.randomUUID().toString(), composite("父题一", "子题一", "A"),
                "trace-t09b-composite-01").response();
        String firstVersionId = created.currentVersion().id();
        assertThat(created.currentVersion().children()).singleElement().satisfies(child -> {
            assertThat(child.stem()).isEqualTo("子题一");
            assertThat(child.options()).extracting(QuestionDtos.OptionInput::key).containsExactly("A", "B");
            assertThat(child.answers()).containsExactly("A");
            assertThat(child.analysis()).isEqualTo("子题解析");
            assertThat(child.knowledgePoints()).extracting(QuestionDtos.KnowledgePointResponse::id).containsExactly(KP);
        });
        assertThat(questions.list(TEACHER, 1, 20, null, null, null, null, null, null, null).items())
                .singleElement().satisfies(item -> assertThat(item.type()).isEqualTo(QuestionModels.Type.COMPOSITE));

        var updated = questions.update(TEACHER, created.id(), 0, composite("父题二", "子题二", "B"),
                "trace-t09b-composite-02");
        assertThat(updated.currentVersion().children()).singleElement()
                .satisfies(child -> assertThat(child.answers()).containsExactly("B"));
        assertThat(questions.versions(TEACHER, created.id(), 1, 20).items())
                .extracting(QuestionDtos.VersionSummary::versionNumber).containsExactly(2, 1);

        var historical = questions.version(TEACHER, created.id(), firstVersionId);
        assertThat(historical.stem()).isEqualTo("父题一");
        assertThat(historical.children()).singleElement().satisfies(child -> {
            assertThat(child.stem()).isEqualTo("子题一");
            assertThat(child.answers()).containsExactly("A");
        });
        assertThat(questions.get(TEACHER, created.id()).currentVersion().id()).isEqualTo(updated.currentVersion().id());
        assertThatThrownBy(() -> questions.version(OTHER, created.id(), firstVersionId))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** 用两个虚构含糊项验证刷新分页、问题码、修正导入、确认跳过、幂等与旧 revision 冲突。 */
    @Test
    void legacyReviewItemsRecoverByPageAndResolveIdempotentlyWithRevision() throws Exception {
        var task = legacyImports.create(TEACHER, new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                "LEGACY_T09B_FIXTURE", "d".repeat(64), true, 2)).response();
        var firstJson = objectMapper.readTree("""
                {"exerciseID":"review-01","exerciseType":"select_single","difficulty":1,"gradeIndex":7,
                 "gradeName":"七年级","question":"选项含义不清 A 1 B 2","queAns":"A","solution":"","optCount":2,"subQues":[]}
                """);
        var secondJson = objectMapper.readTree("""
                {"exerciseID":"review-02","exerciseType":"select_single","difficulty":1,"gradeIndex":7,
                 "gradeName":"七年级","question":"另一道不清题 A 1 B 2","queAns":"A","solution":"","optCount":2,"subQues":[]}
                """);
        legacyImports.submit(TEACHER, task.id(), new QuestionDtos.LegacyImportBatchRequest(UUID.randomUUID(), true,
                List.of(legacyItem("review-01", firstJson), legacyItem("review-02", secondJson))), "trace-t09b-review-01");
        var page = legacyImports.items(TEACHER, task.id(), 1, 1, QuestionModels.ImportOutcome.NEEDS_REVIEW);
        assertThat(page.total()).isEqualTo(2); assertThat(page.items()).hasSize(1);
        var first = page.items().get(0);
        assertThat(first.revision()).isZero();
        assertThat(first.issues()).extracting(QuestionDtos.LegacyIssue::code).containsExactly("EMBEDDED_OPTIONS_AMBIGUOUS");
        assertThatThrownBy(() -> legacyImports.item(OTHER, task.id(), first.id()))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));

        var importRequest = new QuestionDtos.ResolveLegacyItemRequest(UUID.randomUUID(), 0,
                QuestionDtos.ResolutionAction.IMPORT_CORRECTED, single("A", List.of()));
        var imported = legacyImports.resolve(TEACHER, task.id(), first.id(), importRequest, "trace-t09b-review-02");
        var replay = legacyImports.resolve(TEACHER, task.id(), first.id(), importRequest, "trace-t09b-review-03");
        assertThat(imported.outcome()).isEqualTo(QuestionModels.ImportOutcome.IMPORTED);
        assertThat(imported.revision()).isEqualTo(1); assertThat(replay.questionId()).isEqualTo(imported.questionId());
        assertThat(questions.get(TEACHER, imported.questionId()).currentVersion().legacySource().sourceSystem())
                .isEqualTo("LEGACY_T09B_FIXTURE");

        var second = legacyImports.items(TEACHER, task.id(), 2, 1, null).items().get(0);
        var skipped = legacyImports.resolve(TEACHER, task.id(), second.id(),
                new QuestionDtos.ResolveLegacyItemRequest(UUID.randomUUID(), 0,
                        QuestionDtos.ResolutionAction.CONFIRM_SKIP, null), "trace-t09b-review-04");
        assertThat(skipped.outcome()).isEqualTo(QuestionModels.ImportOutcome.SKIPPED);
        var projection = imports.get(TEACHER, task.id());
        assertThat(projection.reviewCount()).isZero(); assertThat(projection.resolvedCount()).isEqualTo(2);
        assertThat(projection.successCount()).isEqualTo(1); assertThat(projection.skippedCount()).isEqualTo(1);
        assertThatThrownBy(() -> legacyImports.resolve(TEACHER, task.id(), second.id(),
                new QuestionDtos.ResolveLegacyItemRequest(UUID.randomUUID(), 0,
                        QuestionDtos.ResolutionAction.CONFIRM_SKIP, null), "trace-t09b-review-05"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("QUESTION_IMPORT.ITEM_REVISION_CONFLICT"));
    }

    /**
     * 非最终批次即使产生 NEEDS_REVIEW 也不能人工解决；最终批次后，相同解决请求并发重放只提交一次。
     * 前置输入为两批 dry-run 虚构项；预期任务先保持 PROCESSING，最终进入可复核终态并只累计一题一次计数。
     */
    @Test
    void legacyReviewWaitsForFinalBatchAndConcurrentIdenticalResolutionReturnsFirstResult() throws Exception {
        var task = legacyImports.create(TEACHER, new QuestionDtos.CreateLegacyImportRequest(UUID.randomUUID(),
                "LEGACY_T09B_STATE_FIXTURE", "e".repeat(64), true, 3)).response();
        var reviewJson = objectMapper.readTree("""
                {"exerciseID":"state-review-01","exerciseType":"select_single","difficulty":1,"gradeIndex":7,
                 "gradeName":"七年级","question":"无法可靠拆分 A 1 B 2","queAns":"A","solution":"","optCount":2,"subQues":[]}
                """);
        legacyImports.submit(TEACHER, task.id(), new QuestionDtos.LegacyImportBatchRequest(UUID.randomUUID(), false,
                List.of(legacyItem("state-review-01", reviewJson))), "trace-t09b-state-01");
        var reviewItem = legacyImports.items(TEACHER, task.id(), 1, 20,
                QuestionModels.ImportOutcome.NEEDS_REVIEW).items().get(0);
        var request = new QuestionDtos.ResolveLegacyItemRequest(UUID.randomUUID(), 0,
                QuestionDtos.ResolutionAction.IMPORT_CORRECTED, single("A", List.of()));

        assertThatThrownBy(() -> legacyImports.resolve(TEACHER, task.id(), reviewItem.id(), request,
                "trace-t09b-state-early"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("QUESTION_IMPORT.TASK_NOT_REVIEWABLE");
                });
        var processing = imports.get(TEACHER, task.id());
        assertThat(processing.status()).isEqualTo(QuestionModels.ImportStatus.PROCESSING);
        assertThat(processing.reviewCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_item_resolution", Integer.class)).isZero();

        var validJson = objectMapper.readTree("""
                {"exerciseID":"state-valid-02","exerciseType":"fillin","difficulty":1,"gradeIndex":7,
                 "gradeName":"七年级","question":"计算 $2+2$","queAns":"4","solution":"","subQues":[]}
                """);
        var finalBatch = legacyImports.submit(TEACHER, task.id(), new QuestionDtos.LegacyImportBatchRequest(
                UUID.randomUUID(), true, List.of(legacyItem("state-valid-02", validJson))), "trace-t09b-state-02");
        assertThat(finalBatch.finalBatch()).isTrue();
        assertThat(imports.get(TEACHER, task.id()).status()).isEqualTo(QuestionModels.ImportStatus.PARTIAL_SUCCESS);

        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                ready.countDown(); start.await();
                return legacyImports.resolve(TEACHER, task.id(), reviewItem.id(), request, "trace-t09b-state-concurrent-01");
            });
            var second = executor.submit(() -> {
                ready.countDown(); start.await();
                return legacyImports.resolve(TEACHER, task.id(), reviewItem.id(), request, "trace-t09b-state-concurrent-02");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(firstResult.outcome()).isEqualTo(QuestionModels.ImportOutcome.IMPORTED);
            assertThat(firstResult.revision()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        var completed = imports.get(TEACHER, task.id());
        assertThat(completed.status()).isEqualTo(QuestionModels.ImportStatus.COMPLETED);
        assertThat(completed.reviewCount()).isZero();
        assertThat(completed.resolvedCount()).isEqualTo(1);
        assertThat(completed.successCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate WHERE owner_id=?",
                Integer.class, TEACHER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_item_resolution WHERE import_task_id=?",
                Integer.class, task.id())).isEqualTo(1);
    }

    private QuestionDtos.VersionInput single(String answer, List<QuestionDtos.MediaInput> mediaRows) {
        return new QuestionDtos.VersionInput(QuestionModels.Type.SINGLE_CHOICE, QuestionModels.Difficulty.EASY,
                QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS,
                "计算 $1+1$", List.of(new QuestionDtos.OptionInput("A", "2"), new QuestionDtos.OptionInput("B", "3")),
                List.of(answer), "完整解析", List.of(UUID.fromString(KP)), mediaRows);
    }

    private QuestionDtos.VersionInput version(QuestionModels.Type type, List<QuestionDtos.OptionInput> options, List<String> answers) {
        return new QuestionDtos.VersionInput(type, QuestionModels.Difficulty.MEDIUM, QuestionModels.Stage.JUNIOR,
                QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS, "题干 $x$", options, answers,
                "解析", List.of(UUID.fromString(KP)), List.of());
    }

    private QuestionDtos.VersionInput composite(String parentStem, String childStem, String childAnswer) {
        var child = new QuestionDtos.ChildInput(QuestionModels.Type.SINGLE_CHOICE, QuestionModels.Difficulty.EASY,
                QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS,
                childStem, List.of(new QuestionDtos.OptionInput("A", "1"), new QuestionDtos.OptionInput("B", "2")),
                List.of(childAnswer), "子题解析", List.of(UUID.fromString(KP)), List.of());
        return new QuestionDtos.VersionInput(QuestionModels.Type.COMPOSITE, QuestionModels.Difficulty.MEDIUM,
                QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS,
                parentStem, List.of(), List.of("综合作答"), "父题解析", List.of(UUID.fromString(KP)), List.of(),
                List.of(child));
    }

    private static String row(String... values) {
        return java.util.Arrays.stream(values).map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private QuestionDtos.LegacyImportItem legacyItem(String externalId, tools.jackson.databind.JsonNode json) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(json)));
        return new QuestionDtos.LegacyImportItem(externalId, "虚构版/七年级上/text/" + externalId + ".json",
                "虚构版", "七年级上", hash, json);
    }

    private static byte[] xlsx(String sheet, boolean image) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml")); zip.write(sheet.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            if (image) { zip.putNextEntry(new ZipEntry("xl/media/image1.png")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }

    private static String inlineCells(List<String> values, int row) {
        StringBuilder xml = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            char column = (char) ('A' + index); xml.append("<c r='").append(column).append(row)
                    .append("' t='inlineStr'><is><t>").append(values.get(index)).append("</t></is></c>");
        }
        return xml.toString();
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> send(HttpRequest request) throws Exception { return http.send(request, HttpResponse.BodyHandlers.ofString()); }
}
