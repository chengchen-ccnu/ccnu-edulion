package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.service.LearningAssessmentService;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.QuestionDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM manual_grading_record");
        jdbc.update("DELETE FROM wrong_question_redo");
        jdbc.update("DELETE FROM wrong_question_occurrence");
        jdbc.update("DELETE FROM wrong_question");
        jdbc.update("DELETE FROM assessment_answer");
        jdbc.update("DELETE FROM assessment_attempt");
        jdbc.update("DELETE FROM assessment_assignment");
        jdbc.update("DELETE FROM assessment_question");
        jdbc.update("DELETE FROM assessment");
        jdbc.update("DELETE FROM learning_note");
        jdbc.update("DELETE FROM lesson_progress_event");
        jdbc.update("DELETE FROM lesson_progress");
        jdbc.update("DELETE FROM learning_idempotency_request");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM learning_outbox_event");
        jdbc.update("DELETE FROM question_version_knowledge_point");
        jdbc.update("DELETE FROM question_version_media");
        jdbc.update("DELETE FROM question_version_child");
        jdbc.update("DELETE FROM question_version");
        jdbc.update("DELETE FROM question_aggregate");
        var fact = new CourseFactsClient.CourseFact(COURSE, "v8", "ENROLLMENT_REQUIRED", TEACHER_AS_LEARNER,
                Set.of(LESSON));
        when(courses.requirePublished(anyString(), anyString())).thenReturn(fact);
        when(courses.requireOwnedPublished(anyString(), anyString(), anyString())).thenReturn(fact);
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
    void submissionAutoGradesFourObjectiveTypesThenManualGradeFinalizesAndDisclosesAnswers() throws Exception {
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
        assertThat(partial.status()).isEqualTo(LearningDtos.AttemptStatus.PARTIALLY_GRADED);
        assertThat(partial.score()).isEqualByComparingTo("6.00");
        assertThat(partial.answers()).allSatisfy(value -> {
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
        assertThat(submitted.score()).isEqualByComparingTo(BigDecimal.ZERO);
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
        assertThat(archivedList.learnerState()).isEqualTo(LearningDtos.LearnerAssessmentState.GRADED);
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

    private void seedPublishedAssessment() {
        Instant now = Instant.now();
        repository.insertAssessment(new AssessmentRow(ASSESSMENT, TEACHER_AS_LEARNER, COURSE, LESSON,
                "综合测评", "包含客观题与解答题", LearningDtos.AssessmentType.HOMEWORK,
                LearningDtos.AssessmentStatus.PUBLISHED, LearningDtos.AnswerPublicationPolicy.AFTER_GRADED,
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
