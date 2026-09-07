package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.identity.LearnerDirectoryClient;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.service.LearningAssessmentService;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.QuestionDtos;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * 使用独立 H2 MySQL 模式验证 T11 事务、幂等、乱序、自动/人工评分与答案防泄漏。
 * 测试数据均为固定合成 UUID，不访问真实课程、RabbitMQ、媒体或用户数据。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:learning_t11_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class LearningAssessmentIntegrationTest {
    private static final String LEARNER = "00000000-0000-4000-8000-000000011001";
    private static final String TEACHER_AS_LEARNER = "00000000-0000-4000-8000-000000011002";
    private static final String OTHER = "00000000-0000-4000-8000-000000011003";
    private static final String COURSE = "00000000-0000-4000-8000-000000012001";
    private static final String LESSON = "00000000-0000-4000-8000-000000012101";
    private static final String ASSESSMENT = "00000000-0000-4000-8000-000000013001";

    @Autowired LearningAssessmentService service;
    @Autowired LearningAssessmentRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CourseFactsClient courses;
    @MockitoBean MediaClient media;
    @MockitoBean LearnerDirectoryClient directory;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM article_completion_request");
        jdbc.update("DELETE FROM article_lesson_completion");
        jdbc.update("DELETE FROM manual_grading_record");
        jdbc.update("DELETE FROM wrong_question_redo");
        jdbc.update("DELETE FROM wrong_question_occurrence");
        jdbc.update("DELETE FROM wrong_question");
        jdbc.update("DELETE FROM gradebook_export_audit");
        jdbc.update("DELETE FROM assessment_settlement_audit");
        jdbc.update("DELETE FROM assessment_deadline_audit");
        jdbc.update("DELETE FROM assessment_participant");
        jdbc.update("DELETE FROM assessment_answer");
        jdbc.update("DELETE FROM assessment_attempt");
        jdbc.update("DELETE FROM assessment_assignment");
        jdbc.update("DELETE FROM assessment_question");
        jdbc.update("DELETE FROM assessment");
        jdbc.update("DELETE FROM learning_note");
        jdbc.update("DELETE FROM lesson_progress_event");
        jdbc.update("DELETE FROM lesson_progress");
        jdbc.update("DELETE FROM learning_idempotency_request");
        jdbc.update("DELETE FROM course_student_number");
        jdbc.update("DELETE FROM course_student_number_counter");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM learning_outbox_event");
        jdbc.update("DELETE FROM question_version_knowledge_point");
        jdbc.update("DELETE FROM question_version_media");
        jdbc.update("DELETE FROM question_version_child");
        jdbc.update("DELETE FROM question_version");
        jdbc.update("DELETE FROM question_aggregate");
        var fact = new CourseFactsClient.CourseFact(COURSE, "v8", "ENROLLMENT_REQUIRED", TEACHER_AS_LEARNER,
                Set.of(LESSON), Map.of(LESSON, "ARTICLE"));
        when(courses.requirePublished(anyString(), anyString())).thenReturn(fact);
        when(courses.requireOwnedPublished(anyString(), anyString(), anyString())).thenReturn(fact);
        when(directory.nicknames(anyList(), anyString())).thenReturn(new LearnerDirectoryClient.NicknameLookup(
                Map.of(LEARNER, "student_test_01", OTHER, "student_test_02"), false));
    }

    @Test
    /** PUBLIC ARTICLE 可 0→100；同一请求重放不增事实，新请求也不能使通用进度 revision 再增长。 */
    void articleCompletionIsMonotonicIdempotentAndDoesNotForgeVideoFields() {
        var publicArticle = new CourseFactsClient.CourseFact(COURSE, "v8", "PUBLIC", TEACHER_AS_LEARNER,
                Set.of(LESSON), Map.of(LESSON, "ARTICLE"));
        when(courses.requirePublished(eq(COURSE), anyString())).thenReturn(publicArticle);
        UUID requestId = UUID.randomUUID();

        var first = service.completeArticle(LEARNER, COURSE, LESSON,
                new LearningDtos.CompleteArticleLessonRequest(requestId), "trace-article-first");
        var replay = service.completeArticle(LEARNER, COURSE, LESSON,
                new LearningDtos.CompleteArticleLessonRequest(requestId), "trace-article-replay");
        var secondRequest = service.completeArticle(LEARNER, COURSE, LESSON,
                new LearningDtos.CompleteArticleLessonRequest(UUID.randomUUID()), "trace-article-second-id");

        assertThat(first.completionPercent()).isEqualTo(100);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.completedAt()).isEqualTo(first.completedAt());
        assertThat(secondRequest.revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_lesson_completion", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT position_seconds FROM lesson_progress", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT watched_seconds FROM lesson_progress", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM lesson_progress", Long.class)).isEqualTo(1);
    }

    @Test
    /** 报名制试看不能记录正式进度，且 VIDEO 不能伪装为 ARTICLE 完成命令。 */
    void articleCompletionRequiresPublicOrActiveEnrollmentAndExactArticleType() {
        assertThatThrownBy(() -> service.completeArticle(LEARNER, COURSE, LESSON,
                new LearningDtos.CompleteArticleLessonRequest(UUID.randomUUID()), "trace-no-enrollment"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.ENROLLMENT_REQUIRED"));

        var publicVideo = new CourseFactsClient.CourseFact(COURSE, "v8", "PUBLIC", TEACHER_AS_LEARNER,
                Set.of(LESSON), Map.of(LESSON, "VIDEO"));
        when(courses.requirePublished(eq(COURSE), anyString())).thenReturn(publicVideo);
        assertThatThrownBy(() -> service.completeArticle(LEARNER, COURSE, LESSON,
                new LearningDtos.CompleteArticleLessonRequest(UUID.randomUUID()), "trace-video"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.LESSON_TYPE_INVALID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_lesson_completion", Integer.class)).isZero();
    }

    @Test
    void teacherCapabilityAccountCanEnrollAndProgressRetriesOrOutOfOrderEventsNeverRegress() throws Exception {
        var first = service.enroll(TEACHER_AS_LEARNER, COURSE, "trace-enroll");
        var replay = service.enroll(TEACHER_AS_LEARNER, COURSE, "trace-enroll-replay");
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.enrollment().id()).isEqualTo(first.enrollment().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event", Integer.class)).isEqualTo(1);

        Instant now = Instant.now();
        UUID event = UUID.randomUUID();
        var current = service.reportProgress(TEACHER_AS_LEARNER, COURSE, LESSON,
                new LearningDtos.ReportProgressRequest(event, now, 100, 20, false), "trace-progress");
        var duplicate = service.reportProgress(TEACHER_AS_LEARNER, COURSE, LESSON,
                new LearningDtos.ReportProgressRequest(event, now, 999, 300, true), "trace-progress-replay");
        var older = service.reportProgress(TEACHER_AS_LEARNER, COURSE, LESSON,
                new LearningDtos.ReportProgressRequest(UUID.randomUUID(), now.minusSeconds(10), 80, 30, false),
                "trace-progress-old");
        assertThat(current.positionSeconds()).isEqualTo(100);
        assertThat(duplicate.replayed()).isTrue();
        assertThat(duplicate.positionSeconds()).isEqualTo(100);
        assertThat(older.positionSeconds()).isEqualTo(100);
        assertThat(older.watchedSeconds()).isEqualTo(20);

        var executor = Executors.newFixedThreadPool(2);
        try {
            Instant concurrentAt = now.plusSeconds(1);
            var firstConcurrent = executor.submit(() -> service.reportProgress(TEACHER_AS_LEARNER, COURSE, LESSON,
                    new LearningDtos.ReportProgressRequest(UUID.randomUUID(), concurrentAt, 200, 10, false),
                    "trace-progress-concurrent-1"));
            var secondConcurrent = executor.submit(() -> service.reportProgress(TEACHER_AS_LEARNER, COURSE, LESSON,
                    new LearningDtos.ReportProgressRequest(UUID.randomUUID(), concurrentAt, 300, 10, false),
                    "trace-progress-concurrent-2"));
            firstConcurrent.get(5, TimeUnit.SECONDS);
            secondConcurrent.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        var merged = service.progress(TEACHER_AS_LEARNER, COURSE, "trace-progress-read").lessons().get(0);
        assertThat(merged.positionSeconds()).isEqualTo(300);
        assertThat(merged.watchedSeconds()).isEqualTo(40);

        var note = service.createNote(TEACHER_AS_LEARNER, COURSE, LESSON,
                new LearningDtos.CreateNoteRequest("测试笔记", 90L), "trace-note");
        assertThatThrownBy(() -> service.updateNote(OTHER, note.id(),
                new LearningDtos.UpdateNoteRequest(0, "越权修改", 1L)))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(404));
    }

    @Test
    /** 显式强制检查点直接落 MySQL；只有 ENDED 可以携带 completed=true。 */
    void explicitCheckpointPersistsAndOnlyEndedCanComplete() {
        service.enroll(LEARNER, COURSE, "trace-t12g-checkpoint-enroll");
        Instant now = Instant.now();
        var paused = service.reportProgress(LEARNER, COURSE, LESSON,
                new LearningDtos.ReportProgressRequest(UUID.randomUUID(), now, 120, 10, false,
                        LearningDtos.ProgressEventType.PAUSE), "trace-t12g-pause");
        assertThat(paused.persistenceStatus()).isEqualTo(LearningDtos.ProgressPersistenceStatus.PERSISTED);
        assertThat(jdbc.queryForObject("SELECT event_type FROM lesson_progress_event", String.class))
                .isEqualTo("PAUSE");
        assertThatThrownBy(() -> service.reportProgress(LEARNER, COURSE, LESSON,
                new LearningDtos.ReportProgressRequest(UUID.randomUUID(), now.plusSeconds(1), 130, 1, true,
                        LearningDtos.ProgressEventType.LEAVE), "trace-t12g-invalid-completion"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.PROGRESS_COMPLETION_EVENT_INVALID"));
        var ended = service.reportProgress(LEARNER, COURSE, LESSON,
                new LearningDtos.ReportProgressRequest(UUID.randomUUID(), now.plusSeconds(2), 130, 1, true,
                        LearningDtos.ProgressEventType.ENDED), "trace-t12g-ended");
        assertThat(ended.completed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress_event WHERE event_type='ENDED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    /** Redis 未接通时显式 HEARTBEAT 必须返回 503，不能把缓冲失败包装为成功。 */
    void explicitHeartbeatWithoutRedisNeverReturnsFalseSuccess() {
        service.enroll(LEARNER, COURSE, "trace-t12g-heartbeat-enroll");
        assertThatThrownBy(() -> service.reportProgress(LEARNER, COURSE, LESSON,
                new LearningDtos.ReportProgressRequest(UUID.randomUUID(), Instant.now(), 10, 1, false,
                        LearningDtos.ProgressEventType.HEARTBEAT), "trace-t12g-heartbeat"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("LEARNING.PROGRESS_BUFFER_UNAVAILABLE");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress", Integer.class)).isZero();
    }

    /**
     * 验证测评快照会显式保存知识点中的可空字段，防止从 MySQL JSON 读回时因字段缺失而失败。
     */
    @Test
    void assessmentQuestionSnapshotRoundTripsNullableKnowledgePointFields() {
        Instant now = Instant.now();
        repository.insertAssessment(new AssessmentRow(ASSESSMENT, TEACHER_AS_LEARNER, COURSE, LESSON,
                "可空知识点快照", "验证持久化往返", LearningDtos.AssessmentType.PRACTICE,
                LearningDtos.AssessmentStatus.DRAFT, LearningDtos.AnswerPublicationPolicy.AFTER_SUBMISSION,
                null, null, null, 1, now, null, now, now));
        var knowledgePoint = new QuestionDtos.KnowledgePointResponse(
                "00000000-0000-4000-8000-000000018001", "JUNIOR_MATH_TEST", "测试知识点", null,
                QuestionModels.Stage.JUNIOR, null, QuestionModels.Subject.MATHEMATICS,
                1, true, 0, List.of());
        var legacySource = new QuestionDtos.LegacySourceResponse(
                "KG_MATH_TEST", "test-source-1", "MEDIUM", "人教版", null, "七年级", null, false);
        var snapshot = new QuestionDtos.VersionResponse(
                "00000000-0000-4000-8000-000000018101", 1, QuestionModels.Type.SINGLE_CHOICE,
                QuestionModels.Difficulty.MEDIUM, QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN,
                QuestionModels.Subject.MATHEMATICS, "测试题干", List.of(new QuestionDtos.OptionInput("A", "选项 A")),
                List.of("A"), "测试解析", List.of(knowledgePoint), List.of(), legacySource, List.of(), now);
        repository.insertAssessmentQuestion(new AssessmentQuestionRow(
                "00000000-0000-4000-8000-000000018201", ASSESSMENT,
                "00000000-0000-4000-8000-000000018301", snapshot.id(), 1, BigDecimal.ONE, snapshot, now));

        var restored = repository.assessmentQuestions(ASSESSMENT).get(0).snapshot().knowledgePoints().get(0);
        assertThat(restored.parentId()).isNull();
        assertThat(restored.grade()).isNull();
        var restoredLegacySource = repository.assessmentQuestions(ASSESSMENT).get(0).snapshot().legacySource();
        assertThat(restoredLegacySource.jsonEdition()).isNull();
        assertThat(restoredLegacySource.jsonGrade()).isNull();
    }

    @Test
    void submissionAutoGradesFourObjectiveTypesThenManualGradeFinalizesWithoutLeakingUnpublishedFacts() throws Exception {
        service.enroll(LEARNER, COURSE, "trace-enroll");
        seedPublishedAssessment();
        String safePaper = mapper.writeValueAsString(service.learnerAssessment(LEARNER, ASSESSMENT));
        assertThat(safePaper).doesNotContain("answers", "analysis", "scoringRule");

        var draft = service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        List<LearningDtos.AnswerInput> answers = List.of(
                answer("00000000-0000-4000-8000-000000014001", "B"),
                answer("00000000-0000-4000-8000-000000014002", "C", "A"),
                answer("00000000-0000-4000-8000-000000014003", "true"),
                answer("00000000-0000-4000-8000-000000014004", "  Ａ   B  "),
                answer("00000000-0000-4000-8000-000000014005", "解题过程"));
        var saved = service.saveDraft(LEARNER, draft.id(),
                new LearningDtos.SaveDraftRequest(UUID.randomUUID(), 0, answers));
        assertThat(service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID())).answers()).hasSize(5);

        UUID submitRequest = UUID.randomUUID();
        var partial = service.submit(LEARNER, draft.id(),
                new LearningDtos.SubmitAttemptRequest(submitRequest, saved.revision(), answers));
        assertThat(partial.status()).isEqualTo(LearningDtos.AttemptStatus.SUBMITTED);
        assertThat(partial.gradeVisible()).isFalse();
        assertThat(partial.score()).isNull();
        assertThat(partial.answers()).allSatisfy(value -> {
            assertThat(value.score()).isNull();
            assertThat(value.correct()).isNull();
            assertThat(value.gradingStatus()).isNull();
            assertThat(value.correctAnswer()).isNull();
            assertThat(value.analysis()).isNull();
        });
        assertThat(service.submit(LEARNER, draft.id(),
                new LearningDtos.SubmitAttemptRequest(submitRequest, saved.revision(), answers)).revision())
                .isEqualTo(partial.revision());
        assertThatThrownBy(() -> service.submit(LEARNER, draft.id(),
                new LearningDtos.SubmitAttemptRequest(UUID.randomUUID(), saved.revision(), answers)))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(409));

        var graded = service.manualGrade(TEACHER_AS_LEARNER, draft.id(),
                new LearningDtos.ManualGradeRequest(UUID.randomUUID(), partial.revision(), List.of(
                        new LearningDtos.ManualGradeItem(
                                UUID.fromString("00000000-0000-4000-8000-000000014005"),
                                new BigDecimal("1.00"), "步骤基本正确"))));
        assertThat(graded.status()).isEqualTo(LearningDtos.AttemptStatus.GRADED);
        assertThat(graded.score()).isEqualByComparingTo("7.00");
        assertThat(graded.answers()).allSatisfy(value -> assertThat(value.correctAnswer()).isNotNull());
        assertThat(service.wrongQuestions(LEARNER, COURSE, 1, 20).total()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_grading_record", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> service.manualGrade(TEACHER_AS_LEARNER, draft.id(),
                new LearningDtos.ManualGradeRequest(UUID.randomUUID(), partial.revision(), List.of(
                        new LearningDtos.ManualGradeItem(
                                UUID.fromString("00000000-0000-4000-8000-000000014005"),
                                BigDecimal.TEN, "陈旧批改")))))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(409));
        assertThat(service.learnerResult(LEARNER, draft.id()).answerVisible()).isFalse();
        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));
        assertSafeProjection(service.learnerResult(LEARNER, draft.id()), false, true);
    }

    @Test
    void assessmentAcceptsActiveSystemAndOwnedTeacherQuestionsRejectsOtherPrivateAndKeepsSnapshotStable() {
        String systemQuestion = "00000000-0000-4000-8000-000000015001";
        String systemVersion = "00000000-0000-4000-8000-000000015101";
        String ownQuestion = "00000000-0000-4000-8000-000000015002";
        String ownVersion = "00000000-0000-4000-8000-000000015102";
        String otherQuestion = "00000000-0000-4000-8000-000000015003";
        String otherVersion = "00000000-0000-4000-8000-000000015103";
        seedQuestion(systemQuestion, systemVersion, "00000000-0000-4000-8000-000000019999", "SYSTEM", "系统题旧题干");
        seedQuestion(ownQuestion, ownVersion, TEACHER_AS_LEARNER, "TEACHER", "本人题旧题干");
        seedQuestion(otherQuestion, otherVersion, OTHER, "TEACHER", "其他教师私有题");

        var created = service.createAssessment(TEACHER_AS_LEARNER,
                new LearningDtos.CreateAssessmentRequest(UUID.randomUUID(), UUID.fromString(COURSE),
                        UUID.fromString(LESSON), "快照测评", "验证题目范围", LearningDtos.AssessmentType.PRACTICE,
                        LearningDtos.AnswerPublicationPolicy.AFTER_SUBMISSION, null, null, null), "trace-create");
        var selected = service.replaceQuestions(TEACHER_AS_LEARNER, created.assessment().id(),
                new LearningDtos.ReplaceQuestionsRequest(0, List.of(
                        new LearningDtos.AssessmentQuestionInput(UUID.fromString(systemVersion), 1, BigDecimal.ONE),
                        new LearningDtos.AssessmentQuestionInput(UUID.fromString(ownVersion), 2, BigDecimal.ONE))));
        assertThat(selected.questions()).extracting(item -> item.snapshot().stem())
                .containsExactly("系统题旧题干", "本人题旧题干");
        assertThatThrownBy(() -> service.replaceQuestions(TEACHER_AS_LEARNER, created.assessment().id(),
                new LearningDtos.ReplaceQuestionsRequest(selected.revision(), List.of(
                        new LearningDtos.AssessmentQuestionInput(UUID.fromString(otherVersion), 1, BigDecimal.ONE)))))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(404));

        String newVersion = "00000000-0000-4000-8000-000000015104";
        seedVersion(systemQuestion, newVersion, "系统题新题干", 2);
        jdbc.update("UPDATE question_aggregate SET current_version_id=?,revision=revision+1 WHERE id=?", newVersion, systemQuestion);
        var frozen = service.teacherAssessment(created.assessment().id(), TEACHER_AS_LEARNER);
        assertThat(frozen.questions().get(0).snapshot().id()).isEqualTo(systemVersion);
        assertThat(frozen.questions().get(0).snapshot().stem()).isEqualTo("系统题旧题干");

        jdbc.update("UPDATE question_aggregate SET status='ARCHIVED',revision=revision+1 WHERE id=?", ownQuestion);
        assertThatThrownBy(() -> service.publication(TEACHER_AS_LEARNER, created.assessment().id(),
                new LearningDtos.UpdatePublicationRequest(frozen.revision(), LearningDtos.AssessmentStatus.PUBLISHED),
                "trace-publish-archived"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(422);
                    assertThat(error.code()).isEqualTo("LEARNING.QUESTION_NOT_USABLE");
                });
        service.deleteAssessment(TEACHER_AS_LEARNER, created.assessment().id());
        assertThatThrownBy(() -> service.teacherAssessment(created.assessment().id(), TEACHER_AS_LEARNER))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(404));
    }

    @Test
    void refreshRecoveryUsesServerDeadlineAndArchivedResultRemainsReadableWithoutAnswers() throws Exception {
        service.enroll(LEARNER, COURSE, "trace-enroll-exam");
        Instant now = Instant.now();
        repository.insertAssessment(new AssessmentRow(ASSESSMENT, TEACHER_AS_LEARNER, COURSE, LESSON,
                "限时考试", "服务端裁决时间", LearningDtos.AssessmentType.EXAM,
                LearningDtos.AssessmentStatus.PUBLISHED, LearningDtos.AnswerPublicationPolicy.NEVER,
                now.plusSeconds(60), now.plusSeconds(600), 120, 1, now, null, now, now));
        repository.ensureCourseAssignment(UUID.randomUUID().toString(), ASSESSMENT, COURSE, now);
        addQuestion("00000000-0000-4000-8000-000000016001", 1, QuestionModels.Type.SINGLE_CHOICE,
                List.of("A"), "不得公开的考试解析");
        repository.ensureParticipantsForAssessment(COURSE, ASSESSMENT, now);

        var notOpen = service.learnerAssessments(LEARNER, COURSE, 1, 20).items().get(0);
        assertThat(notOpen.learnerState()).isEqualTo(LearningDtos.LearnerAssessmentState.NOT_OPEN);
        assertThat(notOpen.currentAttempt()).isNull();
        assertThatThrownBy(() -> service.learnerAssessment(LEARNER, ASSESSMENT))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("LEARNING.ASSESSMENT_NOT_OPEN");
                });

        jdbc.update("UPDATE assessment SET opens_at=? WHERE id=?",
                java.sql.Timestamp.from(now.minusSeconds(1)), ASSESSMENT);
        var started = service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        assertThat(started.deadlineAt()).isAfter(started.startedAt()).isBeforeOrEqualTo(started.startedAt().plusSeconds(120));
        var inProgress = service.learnerAssessments(LEARNER, COURSE, 1, 20).items().get(0);
        assertThat(inProgress.learnerState()).isEqualTo(LearningDtos.LearnerAssessmentState.IN_PROGRESS);
        assertThat(inProgress.currentAttempt().id()).isEqualTo(started.id());
        assertThat(inProgress.resumeAllowed()).isTrue();
        assertThat(service.getDraft(LEARNER, started.id()).id()).isEqualTo(started.id());

        var saved = service.saveDraft(LEARNER, started.id(), new LearningDtos.SaveDraftRequest(
                UUID.randomUUID(), 0, List.of(answer("00000000-0000-4000-8000-000000016001", "B"))));
        jdbc.update("UPDATE assessment_attempt SET deadline_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), started.id());
        assertThatThrownBy(() -> service.getDraft(LEARNER, started.id()))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("LEARNING.ATTEMPT_DEADLINE_PASSED");
                });
        var submitted = service.submit(LEARNER, started.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), saved.revision(), List.of(answer("00000000-0000-4000-8000-000000016001", "A"))));
        assertThat(submitted.gradeVisible()).isFalse();
        assertThat(submitted.score()).isNull();
        assertThat(submitted.answers().get(0).submittedAnswer()).containsExactly("B");
        assertThat(submitted.answers().get(0).correctAnswer()).isNull();
        assertThat(mapper.writeValueAsString(submitted)).doesNotContain("不得公开的考试解析");

        service.publication(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.UpdatePublicationRequest(1, LearningDtos.AssessmentStatus.ARCHIVED),
                "trace-archive-exam");
        jdbc.update("UPDATE enrollment SET status='CANCELED',canceled_at=? WHERE learner_id=? AND course_id=?",
                java.sql.Timestamp.from(Instant.now()), LEARNER, COURSE);
        var archived = service.learnerResult(LEARNER, started.id());
        assertThat(archived.assessment().title()).isEqualTo("限时考试");
        assertThat(archived.assessment().questions()).hasSize(1);
        var archivedList = service.learnerAssessments(LEARNER, COURSE, 1, 20).items().get(0);
        assertThat(archivedList.learnerState()).isEqualTo(LearningDtos.LearnerAssessmentState.SUBMITTED);
        assertThat(archivedList.resultAvailable()).isTrue();
        assertThat(archivedList.resumeAllowed()).isFalse();
    }

    @Test
    void wrongQuestionMasteryAndThreeImmutableRedosNeverReopenSubmittedAttempt() {
        service.enroll(LEARNER, COURSE, "trace-enroll-redo");
        seedPublishedAssessment();
        var draft = service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        List<LearningDtos.AnswerInput> answers = List.of(
                answer("00000000-0000-4000-8000-000000014001", "B"),
                answer("00000000-0000-4000-8000-000000014002", "A", "C"),
                answer("00000000-0000-4000-8000-000000014003", "TRUE"),
                answer("00000000-0000-4000-8000-000000014004", "a b"),
                answer("00000000-0000-4000-8000-000000014005", "过程"));
        var submitted = service.submit(LEARNER, draft.id(),
                new LearningDtos.SubmitAttemptRequest(UUID.randomUUID(), 0, answers));
        var wrong = service.wrongQuestions(LEARNER, COURSE, 1, 20).items().get(0);
        String wrongId = wrong.id();
        assertThat(wrong.question().id()).isEqualTo("00000000-0000-4000-8000-000000014001");
        assertThat(service.wrongQuestion(LEARNER, wrongId).question().stem()).contains("测试题干");
        assertThatThrownBy(() -> service.wrongQuestion(OTHER, wrongId))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(404));

        UUID masteryRequest = UUID.randomUUID();
        long learningRevision = wrong.revision();
        wrong = service.updateWrongQuestionMastery(LEARNER, wrongId,
                new LearningDtos.UpdateWrongQuestionMasteryRequest(masteryRequest, learningRevision,
                        LearningDtos.WrongQuestionMasteryStatus.MASTERED));
        assertThat(wrong.masteryStatus()).isEqualTo(LearningDtos.WrongQuestionMasteryStatus.MASTERED);
        assertThat(service.updateWrongQuestionMastery(LEARNER, wrongId,
                new LearningDtos.UpdateWrongQuestionMasteryRequest(masteryRequest, learningRevision,
                        LearningDtos.WrongQuestionMasteryStatus.MASTERED)).revision()).isEqualTo(wrong.revision());
        assertThatThrownBy(() -> service.updateWrongQuestionMastery(LEARNER, wrongId,
                new LearningDtos.UpdateWrongQuestionMasteryRequest(UUID.randomUUID(), 0,
                        LearningDtos.WrongQuestionMasteryStatus.LEARNING)))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(409));

        UUID firstRequest = UUID.randomUUID();
        var firstRedo = service.redoWrongQuestion(LEARNER, wrongId,
                new LearningDtos.RedoWrongQuestionRequest(firstRequest, wrong.revision(), List.of("A")));
        assertThat(firstRedo.created()).isTrue();
        assertThat(firstRedo.result().correct()).isTrue();
        assertThat(service.redoWrongQuestion(LEARNER, wrongId,
                new LearningDtos.RedoWrongQuestionRequest(firstRequest, wrong.revision(), List.of("A"))).created()).isFalse();
        for (int sequence = 2; sequence <= 3; sequence++) {
            wrong = service.wrongQuestion(LEARNER, wrongId);
            service.redoWrongQuestion(LEARNER, wrongId, new LearningDtos.RedoWrongQuestionRequest(
                    UUID.randomUUID(), wrong.revision(), List.of("A")));
        }
        wrong = service.wrongQuestion(LEARNER, wrongId);
        assertThat(wrong.redoAttemptsUsed()).isEqualTo(3);
        assertThat(wrong.redoHistory()).extracting(LearningDtos.WrongQuestionRedoResult::sequence)
                .containsExactly(1, 2, 3);
        long exhaustedRevision = wrong.revision();
        assertThatThrownBy(() -> service.redoWrongQuestion(LEARNER, wrongId,
                new LearningDtos.RedoWrongQuestionRequest(UUID.randomUUID(), exhaustedRevision, List.of("A"))))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("LEARNING.WRONG_QUESTION_REDO_EXHAUSTED");
                });
        assertThat(service.learnerResult(LEARNER, draft.id()).status()).isEqualTo(submitted.status());
        assertThat(jdbc.queryForObject("SELECT status FROM assessment_attempt WHERE id=?", String.class, draft.id()))
                .isEqualTo("PARTIALLY_GRADED");
    }

    @Test
    void assessmentMediaGrantUsesFrozenAccessFactAndExactDisposition() {
        service.enroll(LEARNER, COURSE, "trace-enroll-media");
        seedMediaAssessment();
        String mediaId = "00000000-0000-4000-8000-000000017901";
        var expected = new LearningDtos.MediaAccessGrant("GET", "https://fixture.invalid/private-image", java.util.Map.of(),
                Instant.now().plusSeconds(30));
        when(media.issueAssessmentAccess(eq(mediaId), eq(LEARNER), eq("QUESTION_IMAGE"),
                eq(LearningDtos.MediaDisposition.INLINE), anyString())).thenReturn(expected);
        var grant = service.assessmentMediaAccess(LEARNER, ASSESSMENT, mediaId,
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.INLINE),
                "trace-assessment-media");
        assertThat(grant.url()).isEqualTo(expected.url());
        verify(media).issueAssessmentAccess(eq(mediaId), eq(LEARNER), eq("QUESTION_IMAGE"),
                eq(LearningDtos.MediaDisposition.INLINE), eq("trace-assessment-media"));
        assertThatThrownBy(() -> service.assessmentMediaAccess(LEARNER, ASSESSMENT, mediaId,
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.ATTACHMENT),
                "trace-wrong-disposition"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(422));
        assertThatThrownBy(() -> service.assessmentMediaAccess(OTHER, ASSESSMENT, mediaId,
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.INLINE),
                "trace-other-media"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(404));

        service.startAttempt(LEARNER, ASSESSMENT, new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        service.publication(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.UpdatePublicationRequest(1, LearningDtos.AssessmentStatus.ARCHIVED), "trace-archive-media");
        jdbc.update("UPDATE enrollment SET status='CANCELED' WHERE learner_id=? AND course_id=?", LEARNER, COURSE);
        assertThat(service.assessmentMediaAccess(LEARNER, ASSESSMENT, mediaId,
                new LearningDtos.CreateAssessmentMediaAccessGrantRequest("ORIGINAL", LearningDtos.MediaDisposition.INLINE),
                "trace-historical-media").url()).isEqualTo(expected.url());
    }

    @Test
    void irreversibleCloseIsOwnerScopedIdempotentAndSettlesOnlyLastSavedDraftInBoundedBatch() {
        service.enroll(LEARNER, COURSE, "trace-close-enroll");
        service.enroll(OTHER, COURSE, "trace-close-other-enroll");
        seedPublishedAssessment(LearningDtos.AssessmentType.HOMEWORK,
                LearningDtos.GradePublicationPolicy.MANUAL,
                LearningDtos.AnswerPublicationPolicy.MANUAL_AFTER_CLOSE);
        var draft = service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        var saved = service.saveDraft(LEARNER, draft.id(), new LearningDtos.SaveDraftRequest(
                UUID.randomUUID(), draft.revision(),
                List.of(answer("00000000-0000-4000-8000-000000014001", "B"))));
        assertThat(service.teacherAssessment(ASSESSMENT, TEACHER_AS_LEARNER).activeAttemptCount()).isEqualTo(1);

        assertThatThrownBy(() -> service.closeAssessment(OTHER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1)))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 0)))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.status().value()).isEqualTo(409));

        UUID closeRequest = UUID.randomUUID();
        var closed = service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(closeRequest, 1));
        assertThat(closed.affectedInProgressAttemptCount()).isEqualTo(1);
        assertThat(closed.assessment().settlementStatus())
                .isEqualTo(LearningDtos.AssessmentSettlementStatus.PENDING);
        assertThat(closed.assessment().pendingSettlementCount()).isEqualTo(1);
        assertThat(closed.assessment().resultsReady()).isFalse();
        assertThat(service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(closeRequest, 1)).replayed()).isTrue();
        assertThatThrownBy(() -> service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 2)))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.ASSESSMENT_ALREADY_CLOSED"));
        assertThatThrownBy(() -> service.startAttempt(OTHER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID())))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.ASSESSMENT_CLOSED"));
        assertThatThrownBy(() -> service.getDraft(LEARNER, draft.id()))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.ASSESSMENT_CLOSED"));
        assertThatThrownBy(() -> service.saveDraft(LEARNER, draft.id(), new LearningDtos.SaveDraftRequest(
                UUID.randomUUID(), saved.revision(),
                List.of(answer("00000000-0000-4000-8000-000000014001", "A")))))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.ASSESSMENT_CLOSED"));

        assertThat(service.processSettlementBatch(ASSESSMENT, 1)).isEqualTo(1);
        var settled = service.teacherAssessment(ASSESSMENT, TEACHER_AS_LEARNER);
        assertThat(settled.settlementStatus()).isEqualTo(LearningDtos.AssessmentSettlementStatus.COMPLETED);
        assertThat(settled.pendingSettlementCount()).isZero();
        assertThat(service.processSettlementBatch(ASSESSMENT, 1)).isZero();
        var result = service.learnerResult(LEARNER, draft.id());
        assertThat(result.status()).isEqualTo(LearningDtos.AttemptStatus.SUBMITTED);
        assertThat(result.answers().get(0).submittedAnswer()).containsExactly("B");
        assertThat(result.gradeVisible()).isFalse();
        assertThat(result.answerVisible()).isFalse();
    }

    @Test
    void manualAnswerAndGradePublicationProduceNeitherAnswerOnlyAndFullSafeProjections() throws Exception {
        service.enroll(LEARNER, COURSE, "trace-manual-release-enroll");
        seedPublishedAssessment(LearningDtos.AssessmentType.HOMEWORK,
                LearningDtos.GradePublicationPolicy.MANUAL,
                LearningDtos.AnswerPublicationPolicy.MANUAL_AFTER_CLOSE);
        var draft = service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        List<LearningDtos.AnswerInput> answers = completeFixtureAnswers();
        var hidden = service.submit(LEARNER, draft.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), draft.revision(), answers));
        assertSafeProjection(hidden, false, false);
        String hiddenJson = mapper.writeValueAsString(hidden);
        assertThat(hiddenJson).contains("\"gradeVisible\":false", "\"answerVisible\":false",
                "\"score\":null", "\"correct\":null", "\"gradingStatus\":null");
        assertThat(hiddenJson).doesNotContain("标准过程", "解答解析");

        assertThatThrownBy(() -> service.publishAnswers(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.PublishAssessmentResultRequest(UUID.randomUUID(), 1)))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.ASSESSMENT_NOT_CLOSED"));
        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));
        assertThatThrownBy(() -> service.publishGrades(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.PublishAssessmentResultRequest(UUID.randomUUID(), 2)))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.MANUAL_GRADING_PENDING"));

        UUID answerPublicationRequest = UUID.randomUUID();
        var answersPublished = service.publishAnswers(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.PublishAssessmentResultRequest(answerPublicationRequest, 2));
        assertThat(answersPublished.replayed()).isFalse();
        assertSafeProjection(service.learnerResult(LEARNER, draft.id()), false, true);
        assertThat(service.publishAnswers(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.PublishAssessmentResultRequest(answerPublicationRequest, 2)).replayed()).isTrue();
        assertThatThrownBy(() -> service.publishAnswers(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.PublishAssessmentResultRequest(UUID.randomUUID(), 3)))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.ANSWER_ALREADY_PUBLISHED"));

        var graded = service.manualGrade(TEACHER_AS_LEARNER, draft.id(),
                new LearningDtos.ManualGradeRequest(UUID.randomUUID(), hidden.revision(), List.of(
                        new LearningDtos.ManualGradeItem(
                                UUID.fromString("00000000-0000-4000-8000-000000014005"),
                                new BigDecimal("1.00"), "首次批改"))));
        UUID gradePublicationRequest = UUID.randomUUID();
        var gradesPublished = service.publishGrades(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.PublishAssessmentResultRequest(gradePublicationRequest, 3));
        assertThat(gradesPublished.replayed()).isFalse();
        var full = service.learnerResult(LEARNER, draft.id());
        assertSafeProjection(full, true, true);
        assertThat(full.score()).isEqualByComparingTo("9.00");
        assertThat(service.publishGrades(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.PublishAssessmentResultRequest(gradePublicationRequest, 3)).replayed()).isTrue();

        Instant originalPublishedAt = gradesPublished.assessment().gradePublishedAt();
        var regraded = service.manualGrade(TEACHER_AS_LEARNER, draft.id(),
                new LearningDtos.ManualGradeRequest(UUID.randomUUID(), graded.revision(), List.of(
                        new LearningDtos.ManualGradeItem(
                                UUID.fromString("00000000-0000-4000-8000-000000014005"),
                                BigDecimal.ZERO, "复核改判"))));
        var latest = service.learnerResult(LEARNER, draft.id());
        assertThat(latest.score()).isEqualByComparingTo("8.00");
        assertThat(latest.gradeUpdatedAt()).isEqualTo(regraded.gradeUpdatedAt()).isNotNull();
        assertThat(service.teacherAssessment(ASSESSMENT, TEACHER_AS_LEARNER).gradePublishedAt())
                .isEqualTo(originalPublishedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM manual_grading_record", Integer.class)).isEqualTo(2);

        long assessmentRevision = service.teacherAssessment(ASSESSMENT, TEACHER_AS_LEARNER).revision();
        service.publication(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.UpdatePublicationRequest(assessmentRevision, LearningDtos.AssessmentStatus.ARCHIVED),
                "trace-archive-published-result");
        jdbc.update("UPDATE enrollment SET status='CANCELED',canceled_at=? WHERE learner_id=? AND course_id=?",
                java.sql.Timestamp.from(Instant.now()), LEARNER, COURSE);
        assertThat(service.learnerResult(LEARNER, draft.id()).score()).isEqualByComparingTo("8.00");
    }

    @Test
    void automaticGradePublicationCanExposeGradesOnlyAndIllegalAfterSubmissionPolicyIsRejected() throws Exception {
        assertThatThrownBy(() -> service.createAssessment(TEACHER_AS_LEARNER,
                new LearningDtos.CreateAssessmentRequest(UUID.randomUUID(), UUID.fromString(COURSE),
                        UUID.fromString(LESSON), "非法策略", "作业不能提交后泄题",
                        LearningDtos.AssessmentType.HOMEWORK,
                        LearningDtos.GradePublicationPolicy.MANUAL,
                        LearningDtos.AnswerPublicationPolicy.AFTER_SUBMISSION,
                        null, null, null), "trace-invalid-policy"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(422);
                    assertThat(error.code()).isEqualTo("LEARNING.ANSWER_POLICY_INVALID");
                });

        service.enroll(LEARNER, COURSE, "trace-auto-grade-enroll");
        seedPublishedAssessment(LearningDtos.AssessmentType.EXAM,
                LearningDtos.GradePublicationPolicy.AUTOMATIC_AFTER_GRADING,
                LearningDtos.AnswerPublicationPolicy.NEVER);
        var draft = service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        var hidden = service.submit(LEARNER, draft.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), draft.revision(), completeFixtureAnswers()));
        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));
        var graded = service.manualGrade(TEACHER_AS_LEARNER, draft.id(),
                new LearningDtos.ManualGradeRequest(UUID.randomUUID(), hidden.revision(), List.of(
                        new LearningDtos.ManualGradeItem(
                                UUID.fromString("00000000-0000-4000-8000-000000014005"),
                                new BigDecimal("1.00"), "自动公布前完成批改"))));
        var gradeOnly = service.learnerResult(LEARNER, draft.id());
        assertSafeProjection(gradeOnly, true, false);
        assertThat(gradeOnly.score()).isEqualByComparingTo(graded.score());
        String json = mapper.writeValueAsString(gradeOnly);
        assertThat(json).contains("\"gradeVisible\":true", "\"answerVisible\":false");
        assertThat(json).doesNotContain("标准过程", "解答解析");
        assertThat(service.teacherAssessment(ASSESSMENT, TEACHER_AS_LEARNER).resultsReady()).isTrue();
    }

    @Test
    void practiceAfterSubmissionPublishesAnswersWithoutPublishingGrades() {
        service.enroll(LEARNER, COURSE, "trace-practice-release-enroll");
        seedPublishedAssessment(LearningDtos.AssessmentType.PRACTICE,
                LearningDtos.GradePublicationPolicy.MANUAL,
                LearningDtos.AnswerPublicationPolicy.AFTER_SUBMISSION);
        var draft = service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        var result = service.submit(LEARNER, draft.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), draft.revision(), completeFixtureAnswers()));
        assertSafeProjection(result, false, true);
        assertThat(service.teacherAssessment(ASSESSMENT, TEACHER_AS_LEARNER).answerPublicationStatus())
                .isEqualTo(LearningDtos.ResultPublicationStatus.PUBLISHED);
    }

    @Test
    void settlementFailureStopsAfterFiveAttemptsAndNeverReportsResultsReady() {
        service.enroll(LEARNER, COURSE, "trace-finite-settlement-enroll");
        seedPublishedAssessment(LearningDtos.AssessmentType.HOMEWORK,
                LearningDtos.GradePublicationPolicy.MANUAL,
                LearningDtos.AnswerPublicationPolicy.NEVER);
        service.startAttempt(LEARNER, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()));
        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));

        for (int attempt = 1; attempt <= 5; attempt++) {
            service.recordSettlementFailure(ASSESSMENT, new IllegalStateException("synthetic-safe-failure"));
        }
        var failed = repository.assessment(ASSESSMENT).orElseThrow();
        assertThat(failed.settlementStatus()).isEqualTo(LearningDtos.AssessmentSettlementStatus.FAILED);
        assertThat(failed.settlementAttempts()).isEqualTo(5);
        assertThat(failed.settlementLastError()).isEqualTo("IllegalStateException");
        assertThat(failed.settlementNextAttemptAt()).isNull();
        assertThat(service.teacherAssessment(ASSESSMENT, TEACHER_AS_LEARNER).resultsReady()).isFalse();
        assertThat(service.dueSettlementAssessments(10)).doesNotContain(ASSESSMENT);
    }

    @Test
    /** 两个调度线程同时关闭同一场到期作业时，只允许一个事务推进 OPEN→CLOSED。 */
    void concurrentAutomaticCloseHasOneWinnerAndOneSettlementFact() throws Exception {
        service.enroll(LEARNER, COURSE, "trace-t11c1-concurrent-close-enroll");
        seedHomeworkAssessment(1, QuestionModels.Type.SINGLE_CHOICE);
        Instant elapsed = Instant.now().minusSeconds(1);
        jdbc.update("UPDATE assessment SET opens_at=?,closes_at=? WHERE id=?",
                java.sql.Timestamp.from(elapsed.minusSeconds(60)), java.sql.Timestamp.from(elapsed), ASSESSMENT);

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                service.autoCloseAssessment(ASSESSMENT);
                return null;
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                service.autoCloseAssessment(ASSESSMENT);
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        var closed = repository.assessment(ASSESSMENT).orElseThrow();
        assertThat(closed.closeStatus()).isEqualTo(LearningDtos.AssessmentCloseStatus.CLOSED);
        assertThat(closed.closeAffectedAttemptCount()).isZero();
        assertThat(closed.settlementStatus()).isEqualTo(LearningDtos.AssessmentSettlementStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=? AND close_status='CLOSED'",
                Integer.class, ASSESSMENT)).isEqualTo(1);
    }

    @Test
    /** 作业草稿不计次数；每次正式提交独立递增，最后一次成为学生结果的唯一有效版本。 */
    void homeworkRetriesKeepOnlyTheFinalAttemptVisible() {
        service.enroll(LEARNER, COURSE, "trace-t11c1-retry-enroll");
        seedHomeworkAssessment(2, QuestionModels.Type.SINGLE_CHOICE);

        var first = service.startCourseTaskAttempt(LEARNER, COURSE, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()), "trace-t11c1-start-first");
        var saved = service.saveDraft(LEARNER, first.id(), new LearningDtos.SaveDraftRequest(
                UUID.randomUUID(), first.revision(), List.of()));
        assertThat(saved.attemptSequence()).isEqualTo(1);
        assertThat(saved.attemptsUsed()).isZero();
        var firstResult = service.submit(LEARNER, first.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), saved.revision(), List.of(answer("00000000-0000-4000-8000-000000014001", "A"))));
        assertThat(firstResult.attemptSequence()).isEqualTo(1);

        var second = service.startCourseTaskAttempt(LEARNER, COURSE, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()), "trace-t11c1-start-second");
        assertThat(second.attemptSequence()).isEqualTo(2);
        assertThat(second.attemptsUsed()).isEqualTo(1);
        var secondResult = service.submit(LEARNER, second.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), second.revision(), List.of(answer("00000000-0000-4000-8000-000000014001", "B"))));
        assertThat(secondResult.finalAttempt()).isTrue();
        assertThat(secondResult.attemptSequence()).isEqualTo(2);
        assertThatThrownBy(() -> service.learnerResult(LEARNER, first.id()))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
        assertThat(service.learnerCourseTaskResult(LEARNER, COURSE, ASSESSMENT, "trace-t11c1-final")
                .attemptId()).isEqualTo(second.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt WHERE assessment_id=?",
                Integer.class, ASSESSMENT)).isEqualTo(2);
    }

    @Test
    /** 未完成主观题批改时，成绩册仅返回事实状态；统计和同步 CSV 均保持不可见。 */
    void pendingHomeworkHidesGradesAndRejectsGradebookExport() {
        service.enroll(LEARNER, COURSE, "trace-t11c1-pending-enroll");
        seedHomeworkAssessment(1, QuestionModels.Type.SHORT_ANSWER);
        var draft = service.startCourseTaskAttempt(LEARNER, COURSE, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()), "trace-t11c1-pending-start");
        service.submit(LEARNER, draft.id(), new LearningDtos.SubmitAttemptRequest(UUID.randomUUID(), draft.revision(),
                List.of(answer("00000000-0000-4000-8000-000000014001", "待批答案"))));
        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));

        // 单份最终有效答卷已满足关闭/结算门禁，即使仍有待批答案也能开始人工批改。
        var single = service.teacherResult(TEACHER_AS_LEARNER, draft.id());
        assertThat(single.attemptId()).isEqualTo(draft.id());
        assertThat(single.finalAttempt()).isTrue();
        assertThat(single.status()).isEqualTo(LearningDtos.AttemptStatus.PARTIALLY_GRADED);
        assertThatThrownBy(() -> service.teacherResults(TEACHER_AS_LEARNER, ASSESSMENT, 1, 20))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("LEARNING.RESULTS_NOT_READY");
                });

        var gradebook = service.teacherGradebook(TEACHER_AS_LEARNER, COURSE, null, null, null, null, null, null,
                1, 20, LearningDtos.GradebookSortField.STUDENT_NUMBER, false, "trace-t11c1-pending-gradebook");
        assertThat(gradebook.aggregateStatus()).isEqualTo(LearningDtos.GradebookAggregateStatus.PENDING);
        assertThat(gradebook.assessments().get(0).resultsReady()).isFalse();
        assertThat(gradebook.assessments().get(0).stats().gradedStudentAverage()).isNull();
        assertThat(gradebook.rows().get(0).assessments().get(ASSESSMENT).scorePercent()).isNull();
        assertThatThrownBy(() -> service.streamGradebook(TEACHER_AS_LEARNER, COURSE, null, null, null, null, null,
                null, UUID.randomUUID(), "trace-t11c1-pending-export", new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.GRADEBOOK_EXPORT_NOT_READY"));
    }

    @Test
    /** 单份教师读取只接受最终 attempt；关闭/结算前拒绝，非 owner、旧 attempt 和陈旧 revision 均保持安全语义。 */
    void teacherSingleAttemptReadUsesFinalAttemptAndPreservesGradingConcurrency() {
        service.enroll(LEARNER, COURSE, "trace-t11c1-single-attempt-enroll");
        seedHomeworkAssessment(2, QuestionModels.Type.SHORT_ANSWER);
        var draft = service.startCourseTaskAttempt(LEARNER, COURSE, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()), "trace-t11c1-single-attempt-start");
        var saved = service.saveDraft(LEARNER, draft.id(), new LearningDtos.SaveDraftRequest(
                UUID.randomUUID(), draft.revision(), List.of(answer("00000000-0000-4000-8000-000000014001", "待批草稿"))));
        assertThatThrownBy(() -> service.teacherResult(TEACHER_AS_LEARNER, draft.id()))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("LEARNING.RESULTS_NOT_READY");
                });
        var first = service.submit(LEARNER, draft.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), saved.revision(), saved.answers()));
        var secondDraft = service.startCourseTaskAttempt(LEARNER, COURSE, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()), "trace-t11c1-single-attempt-start-2");
        var second = service.submit(LEARNER, secondDraft.id(), new LearningDtos.SubmitAttemptRequest(
                UUID.randomUUID(), secondDraft.revision(), List.of(answer(
                        "00000000-0000-4000-8000-000000014001", "最终待批答案"))));

        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));
        var finalResult = service.teacherResult(TEACHER_AS_LEARNER, second.attemptId());
        assertThat(finalResult.finalAttempt()).isTrue();
        assertThat(finalResult.attemptSequence()).isEqualTo(2);
        assertThatThrownBy(() -> service.teacherResult(TEACHER_AS_LEARNER, first.attemptId()))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.teacherResult(OTHER, second.attemptId()))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));

        var graded = service.manualGrade(TEACHER_AS_LEARNER, second.attemptId(), new LearningDtos.ManualGradeRequest(
                UUID.randomUUID(), finalResult.revision(), List.of(new LearningDtos.ManualGradeItem(
                        UUID.fromString("00000000-0000-4000-8000-000000014001"), new BigDecimal("1.00"), "步骤基本正确"))));
        assertThat(graded.status()).isEqualTo(LearningDtos.AttemptStatus.GRADED);
        assertThat(service.teacherResult(TEACHER_AS_LEARNER, second.attemptId()).status())
                .isEqualTo(LearningDtos.AttemptStatus.GRADED);
        assertThatThrownBy(() -> service.manualGrade(TEACHER_AS_LEARNER, second.attemptId(),
                new LearningDtos.ManualGradeRequest(UUID.randomUUID(), finalResult.revision(), List.of(
                        new LearningDtos.ManualGradeItem(UUID.fromString("00000000-0000-4000-8000-000000014001"),
                                new BigDecimal("2.00"), "陈旧 revision")))))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("LEARNING.GRADING_REVISION_CONFLICT");
                });
    }

    @Test
    /** 关闭后的成绩册使用课程内编号和批量昵称，缺交按零计入全体平均且 CSV 带 BOM。 */
    void completedGradebookUsesStableNumbersAndExportsSafeWideCsv() {
        service.enroll(LEARNER, COURSE, "trace-t11c1-gradebook-enroll-1");
        service.enroll(OTHER, COURSE, "trace-t11c1-gradebook-enroll-2");
        seedHomeworkAssessment(1, QuestionModels.Type.SINGLE_CHOICE);
        var draft = service.startCourseTaskAttempt(LEARNER, COURSE, ASSESSMENT,
                new LearningDtos.StartAttemptRequest(UUID.randomUUID()), "trace-t11c1-gradebook-start");
        service.submit(LEARNER, draft.id(), new LearningDtos.SubmitAttemptRequest(UUID.randomUUID(), draft.revision(),
                List.of(answer("00000000-0000-4000-8000-000000014001", "A"))));
        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));

        var gradebook = service.teacherGradebook(TEACHER_AS_LEARNER, COURSE, null, null, null, null, null, null,
                1, 20, LearningDtos.GradebookSortField.STUDENT_NUMBER, false, "trace-t11c1-gradebook");
        assertThat(gradebook.aggregateStatus()).isEqualTo(LearningDtos.GradebookAggregateStatus.READY);
        assertThat(gradebook.rows()).extracting(LearningDtos.GradebookRow::courseStudentNumber)
                .containsExactly("0001", "0002");
        assertThat(gradebook.rows().get(0).nickname()).isEqualTo("student_test_01");
        assertThat(gradebook.rows().get(0).assessments().get(ASSESSMENT).scorePercent())
                .isEqualByComparingTo("100.00");
        assertThat(gradebook.rows().get(1).assessments().get(ASSESSMENT).scorePercent())
                .isEqualByComparingTo("0");
        assertThat(gradebook.assessments().get(0).stats().allStudentAverage()).isEqualByComparingTo("50.00");

        // 导出前把昵称替换为公式样式值，验证 CSV 单元格会加单引号而不是把输入交给表格执行。
        when(directory.nicknames(anyList(), anyString())).thenReturn(new LearnerDirectoryClient.NicknameLookup(
                Map.of(LEARNER, "=SUM(A1)", OTHER, "@cmd"), false));
        String csv = new String(captureCsv(TEACHER_AS_LEARNER, COURSE, null, null, null, null, null,
                null, UUID.randomUUID(), "trace-t11c1-gradebook-export"), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("课程学生编号", "是否迟交", "作业平均分");
        assertThat(csv).contains("\"'=SUM(A1)\"", "\"'@cmd\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gradebook_export_audit", Integer.class)).isEqualTo(1);
    }

    @Test
    /** 昵称关键词先由 IAM 投影收敛候选 ID，再由数据库完成成绩册分页和总数统计。 */
    void gradebookNicknameKeywordKeepsSearchSemanticsWithoutLoadingCells() {
        service.enroll(LEARNER, COURSE, "trace-t11c1-nickname-enroll-1");
        service.enroll(OTHER, COURSE, "trace-t11c1-nickname-enroll-2");
        seedHomeworkAssessment(1, QuestionModels.Type.SINGLE_CHOICE);
        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));

        var gradebook = service.teacherGradebook(TEACHER_AS_LEARNER, COURSE, null, null, null, null, null,
                "student_test_02", 1, 20, LearningDtos.GradebookSortField.STUDENT_NUMBER, false,
                "trace-t11c1-nickname-search");
        assertThat(gradebook.total()).isEqualTo(1);
        assertThat(gradebook.rows()).extracting(LearningDtos.GradebookRow::courseStudentNumber)
                .containsExactly("0002");
    }

    @Test
    /** 成绩册查询只读当前已有投影；重复批量确保不会让 ACTIVE 参与者的 revision 变化。 */
    void gradebookReadDoesNotMutateParticipantProjection() {
        service.enroll(LEARNER, COURSE, "trace-t11c1-read-only-enroll");
        seedHomeworkAssessment(1, QuestionModels.Type.SINGLE_CHOICE);
        long before = jdbc.queryForObject("SELECT revision FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                Long.class, ASSESSMENT, LEARNER);
        service.teacherGradebook(TEACHER_AS_LEARNER, COURSE, null, null, null, null, null, null,
                1, 20, LearningDtos.GradebookSortField.STUDENT_NUMBER, false, "trace-t11c1-read-only");
        repository.ensureParticipantsForAssessment(COURSE, ASSESSMENT, Instant.now());
        long after = jdbc.queryForObject("SELECT revision FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                Long.class, ASSESSMENT, LEARNER);
        assertThat(after).isEqualTo(before);
    }

    @Test
    /** 退课/重报只影响未关闭名单；关闭后名单、状态和 revision 均被冻结。 */
    void closedRosterRemainsFrozenAcrossWithdrawalAndReenrollment() {
        service.enroll(LEARNER, COURSE, "trace-t11c1-freeze-enroll-1");
        var otherEnrollment = service.enroll(OTHER, COURSE, "trace-t11c1-freeze-enroll-2").enrollment();
        seedHomeworkAssessment(1, QuestionModels.Type.SINGLE_CHOICE);

        service.cancelEnrollment(OTHER, otherEnrollment.id(), "trace-t11c1-freeze-withdraw-open");
        assertThat(jdbc.queryForObject("SELECT enrollment_status FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                String.class, ASSESSMENT, OTHER)).isEqualTo("WITHDRAWN");
        long withdrawnRevision = jdbc.queryForObject("SELECT revision FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                Long.class, ASSESSMENT, OTHER);
        service.enroll(OTHER, COURSE, "trace-t11c1-freeze-reenroll-open");
        assertThat(jdbc.queryForObject("SELECT enrollment_status FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                String.class, ASSESSMENT, OTHER)).isEqualTo("ACTIVE");
        long activeRevision = jdbc.queryForObject("SELECT revision FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                Long.class, ASSESSMENT, OTHER);
        assertThat(activeRevision).isEqualTo(withdrawnRevision + 1);

        service.closeAssessment(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.CloseAssessmentRequest(UUID.randomUUID(), 1));
        long closedRevision = jdbc.queryForObject("SELECT revision FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                Long.class, ASSESSMENT, OTHER);
        service.cancelEnrollment(OTHER,
                service.enrollments(OTHER, LearningDtos.EnrollmentStatus.ACTIVE, 1, 20).items().get(0).id(),
                "trace-t11c1-freeze-withdraw-closed");
        service.enroll(OTHER, COURSE, "trace-t11c1-freeze-reenroll-closed");
        assertThat(jdbc.queryForObject("SELECT enrollment_status FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                String.class, ASSESSMENT, OTHER)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT revision FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                Long.class, ASSESSMENT, OTHER)).isEqualTo(closedRevision);
    }

    @Test
    /** 两个并发批量确保共享唯一键；最终只有一条 ACTIVE 名单且 revision 不因重试膨胀。 */
    void concurrentParticipantEnsureIsIdempotent() throws Exception {
        service.enroll(LEARNER, COURSE, "trace-t11c1-concurrent-roster-enroll-1");
        service.enroll(OTHER, COURSE, "trace-t11c1-concurrent-roster-enroll-2");
        seedHomeworkAssessment(1, QuestionModels.Type.SINGLE_CHOICE);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return repository.ensureParticipantsForAssessment(COURSE, ASSESSMENT, Instant.now());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return repository.ensureParticipantsForAssessment(COURSE, ASSESSMENT, Instant.now());
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE assessment_id=?",
                Integer.class, ASSESSMENT)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT MAX(revision) FROM assessment_participant WHERE assessment_id=?",
                Long.class, ASSESSMENT)).isZero();
    }

    @Test
    /** 作业迟交窗口与一小时边界由服务端校验，不能用不完整时间配置绕过规则。 */
    void homeworkLateWindowAndDeadlineAdjustmentRespectServerBoundary() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> service.createAssessment(TEACHER_AS_LEARNER,
                new LearningDtos.CreateAssessmentRequest(UUID.randomUUID(), UUID.fromString(COURSE),
                        UUID.fromString(LESSON), "迟交边界", "", LearningDtos.AssessmentType.HOMEWORK,
                        LearningDtos.GradePublicationPolicy.MANUAL, LearningDtos.AnswerPublicationPolicy.NEVER,
                        now, now.plusSeconds(7200), LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL,
                        now.plusSeconds(3600), 1, null), "trace-t11c1-late-invalid"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.LATE_WINDOW_INVALID"));

        service.enroll(LEARNER, COURSE, "trace-t11c1-deadline-enroll");
        seedHomeworkAssessment(1, QuestionModels.Type.SINGLE_CHOICE);
        assertThatThrownBy(() -> service.updateAssessmentDeadline(TEACHER_AS_LEARNER, ASSESSMENT,
                new LearningDtos.UpdateAssessmentDeadlineRequest(0, now.plusSeconds(30 * 60L), null, "缩短测试"),
                "trace-t11c1-deadline-invalid"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.code()).isEqualTo("LEARNING.DEADLINE_TOO_SOON"));
    }

    private byte[] captureCsv(String teacherId, String courseId, String assessmentId,
            LearningDtos.AssessmentType type, LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword, UUID requestId,
            String traceId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.streamGradebook(teacherId, courseId, assessmentId, type, submissionStatus, gradingStatus, late,
                keyword, requestId, traceId, output);
        return output.toByteArray();
    }

    private void seedPublishedAssessment() {
        seedPublishedAssessment(LearningDtos.AssessmentType.HOMEWORK,
                LearningDtos.GradePublicationPolicy.MANUAL,
                LearningDtos.AnswerPublicationPolicy.AFTER_ASSESSMENT_GRADED);
    }

    private void seedPublishedAssessment(LearningDtos.AssessmentType type,
            LearningDtos.GradePublicationPolicy gradePolicy,
            LearningDtos.AnswerPublicationPolicy answerPolicy) {
        Instant now = Instant.now();
        repository.insertAssessment(new AssessmentRow(ASSESSMENT, TEACHER_AS_LEARNER, COURSE, LESSON,
                "综合测评", "包含客观题与解答题", type, LearningDtos.AssessmentStatus.PUBLISHED,
                gradePolicy, answerPolicy, LearningDtos.AssessmentCloseStatus.OPEN,
                LearningDtos.AssessmentSettlementStatus.NOT_STARTED, null, 0, 0, null, null,
                LearningDtos.ResultPublicationStatus.UNPUBLISHED,
                LearningDtos.ResultPublicationStatus.UNPUBLISHED, null, null,
                null, null, null, 1, now, null, now, now));
        repository.ensureCourseAssignment(UUID.randomUUID().toString(), ASSESSMENT, COURSE, now);
        addQuestion("00000000-0000-4000-8000-000000014001", 1, QuestionModels.Type.SINGLE_CHOICE,
                List.of("A"), "单选解析");
        addQuestion("00000000-0000-4000-8000-000000014002", 2, QuestionModels.Type.MULTIPLE_CHOICE,
                List.of("A", "C"), "多选解析");
        addQuestion("00000000-0000-4000-8000-000000014003", 3, QuestionModels.Type.TRUE_FALSE,
                List.of("TRUE"), "判断解析");
        addQuestion("00000000-0000-4000-8000-000000014004", 4, QuestionModels.Type.FILL_BLANK,
                List.of("a b"), "填空解析");
        addQuestion("00000000-0000-4000-8000-000000014005", 5, QuestionModels.Type.SHORT_ANSWER,
                List.of("标准过程"), "解答解析");
        if (type == LearningDtos.AssessmentType.PRACTICE) {
            repository.insertParticipant(UUID.randomUUID().toString(), ASSESSMENT, LEARNER, now);
        } else {
            repository.ensureParticipantsForAssessment(COURSE, ASSESSMENT, now);
        }
    }

    private void seedHomeworkAssessment(int maxAttempts, QuestionModels.Type questionType) {
        Instant now = Instant.now();
        repository.insertAssessment(new AssessmentRow(ASSESSMENT, TEACHER_AS_LEARNER, COURSE, LESSON,
                "T11C.1 作业", "作业规则验收", LearningDtos.AssessmentType.HOMEWORK,
                LearningDtos.AssessmentStatus.PUBLISHED, LearningDtos.GradePublicationPolicy.MANUAL,
                LearningDtos.AnswerPublicationPolicy.NEVER, LearningDtos.AssessmentCloseStatus.OPEN,
                LearningDtos.AssessmentSettlementStatus.NOT_STARTED, null, 0, 0, null, null,
                LearningDtos.ResultPublicationStatus.UNPUBLISHED, LearningDtos.ResultPublicationStatus.UNPUBLISHED,
                null, null, now.minusSeconds(1), now.plusSeconds(2 * 60 * 60L), null,
                LearningDtos.HomeworkLatePolicy.DISALLOW, null, maxAttempts, 1, now, null, now, now));
        repository.ensureCourseAssignment(UUID.randomUUID().toString(), ASSESSMENT, COURSE, now);
        List<String> answers = questionType == QuestionModels.Type.SHORT_ANSWER ? List.of("标准答案") : List.of("A");
        addQuestion("00000000-0000-4000-8000-000000014001", 1, questionType, answers, "作业解析");
        repository.ensureParticipantsForAssessment(COURSE, ASSESSMENT, now);
    }

    private static List<LearningDtos.AnswerInput> completeFixtureAnswers() {
        return List.of(
                answer("00000000-0000-4000-8000-000000014001", "A"),
                answer("00000000-0000-4000-8000-000000014002", "A", "C"),
                answer("00000000-0000-4000-8000-000000014003", "TRUE"),
                answer("00000000-0000-4000-8000-000000014004", "a b"),
                answer("00000000-0000-4000-8000-000000014005", "本人解题过程"));
    }

    /** 验证四态结果中的不可见侧严格使用 null，且可见侧同时包含相应业务事实。 */
    private static void assertSafeProjection(LearningDtos.AttemptResult result,
            boolean gradeVisible, boolean answerVisible) {
        assertThat(result.gradeVisible()).isEqualTo(gradeVisible);
        assertThat(result.answerVisible()).isEqualTo(answerVisible);
        if (gradeVisible) {
            assertThat(result.score()).isNotNull();
            assertThat(result.gradeUpdatedAt()).isNotNull();
            assertThat(result.answers()).allSatisfy(answer -> {
                assertThat(answer.score()).isNotNull();
                assertThat(answer.correct()).isNotNull();
                assertThat(answer.gradingStatus()).isNotNull();
            });
        } else {
            assertThat(result.score()).isNull();
            assertThat(result.gradeUpdatedAt()).isNull();
            assertThat(result.answers()).allSatisfy(answer -> {
                assertThat(answer.score()).isNull();
                assertThat(answer.correct()).isNull();
                assertThat(answer.gradingStatus()).isNull();
            });
        }
        if (answerVisible) {
            assertThat(result.answers()).allSatisfy(answer -> {
                assertThat(answer.correctAnswer()).isNotNull();
                assertThat(answer.analysis()).isNotNull();
            });
        } else {
            assertThat(result.answers()).allSatisfy(answer -> {
                assertThat(answer.correctAnswer()).isNull();
                assertThat(answer.analysis()).isNull();
            });
        }
    }

    private void seedMediaAssessment() {
        Instant now = Instant.now();
        repository.insertAssessment(new AssessmentRow(ASSESSMENT, TEACHER_AS_LEARNER, COURSE, LESSON,
                "图片测评", "私有题目图片", LearningDtos.AssessmentType.PRACTICE,
                LearningDtos.AssessmentStatus.PUBLISHED, LearningDtos.AnswerPublicationPolicy.AFTER_SUBMISSION,
                null, null, null, 1, now, null, now, now));
        repository.ensureCourseAssignment(UUID.randomUUID().toString(), ASSESSMENT, COURSE, now);
        QuestionDtos.VersionResponse snapshot = new QuestionDtos.VersionResponse(
                UUID.randomUUID().toString(), 1, QuestionModels.Type.SINGLE_CHOICE,
                QuestionModels.Difficulty.MEDIUM, QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN,
                QuestionModels.Subject.MATHEMATICS, "带私有图片的题目", List.of(
                        new QuestionDtos.OptionInput("A", "选项 A")), List.of("A"), "解析", List.of(),
                List.of(new QuestionDtos.MediaResponse(UUID.randomUUID().toString(),
                        "00000000-0000-4000-8000-000000017901", QuestionModels.MediaPurpose.QUESTION_IMAGE, 1)),
                null, List.of(), now);
        repository.insertAssessmentQuestion(new AssessmentQuestionRow(
                "00000000-0000-4000-8000-000000017001", ASSESSMENT, UUID.randomUUID().toString(),
                snapshot.id(), 1, BigDecimal.ONE, snapshot, now));
        repository.insertParticipant(UUID.randomUUID().toString(), ASSESSMENT, LEARNER, now);
    }

    private void addQuestion(String id, int sequence, QuestionModels.Type type, List<String> answers,
            String analysis) {
        Instant now = Instant.now();
        QuestionDtos.VersionResponse snapshot = new QuestionDtos.VersionResponse(
                UUID.randomUUID().toString(), 1, type, QuestionModels.Difficulty.MEDIUM,
                QuestionModels.Stage.JUNIOR, QuestionModels.Grade.SEVEN, QuestionModels.Subject.MATHEMATICS,
                "测试题干 " + sequence, List.of(new QuestionDtos.OptionInput("A", "选项 A"),
                        new QuestionDtos.OptionInput("B", "选项 B")), answers, analysis, List.of(), List.of(),
                null, List.of(), now);
        repository.insertAssessmentQuestion(new AssessmentQuestionRow(id, ASSESSMENT, UUID.randomUUID().toString(),
                snapshot.id(), sequence, new BigDecimal("2.00"), snapshot, now));
    }

    private void seedQuestion(String questionId, String versionId, String ownerId, String scope, String stem) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO question_aggregate(id,owner_id,scope,status,current_version_id,revision,created_at,updated_at,parent_question_id) VALUES(?,?,?,'ACTIVE',?,0,?,?,NULL)",
                questionId, ownerId, scope, versionId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        seedVersion(questionId, versionId, stem, 1);
    }

    private void seedVersion(String questionId, String versionId, String stem, int versionNumber) {
        jdbc.update("INSERT INTO question_version(id,question_id,version_number,question_type,difficulty,stage,grade,subject,stem_md,options_json,answers_json,analysis_md,created_by,created_at) VALUES(?,?,?,'SINGLE_CHOICE','MEDIUM','JUNIOR','SEVEN','MATHEMATICS',?,'[{\"key\":\"A\",\"content\":\"选项 A\"}]','[\"A\"]','测试解析',?,?)",
                versionId, questionId, versionNumber, stem, TEACHER_AS_LEARNER,
                java.sql.Timestamp.from(Instant.now()));
    }

    private static LearningDtos.AnswerInput answer(String questionId, String... values) {
        return new LearningDtos.AnswerInput(UUID.fromString(questionId), List.of(values));
    }
}
