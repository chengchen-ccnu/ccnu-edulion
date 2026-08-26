package com.eduplatform.learning.service;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.course.CourseFactsClient.CourseFact;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AnswerRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AttemptRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.EnrollmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.NoteRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.ProgressRow;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.LearningDtos.AnswerInput;
import com.eduplatform.learning.web.LearningDtos.AnswerPublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.AssessmentStatus;
import com.eduplatform.learning.web.LearningDtos.AttemptStatus;
import com.eduplatform.learning.web.LearningDtos.EnrollmentStatus;
import com.eduplatform.learning.web.LearningDtos.GradingStatus;
import com.eduplatform.learning.web.QuestionDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * T11 学习与测评领域服务。所有写操作在 learning Schema 内完成；课程发布、课时归属和教师所有权
 * 只通过 course-service 版本化 API 校验，报名访问事实则通过同事务 Outbox 异步投影到 course-service。
 */
@Service
public class LearningAssessmentService {
    private static final Duration MAX_OFFLINE_AGE = Duration.ofDays(30);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final LearningAssessmentRepository repository;
    private final CourseFactsClient courses;
    private final LearningEvents events;
    private final QuestionRepository questions;
    private final QuestionService questionService;
    private final ObjectMapper mapper;

    public LearningAssessmentService(LearningAssessmentRepository repository, CourseFactsClient courses,
            LearningEvents events, QuestionRepository questions, QuestionService questionService,
            ObjectMapper mapper) {
        this.repository = repository;
        this.courses = courses;
        this.events = events;
        this.questions = questions;
        this.questionService = questionService;
        this.mapper = mapper;
    }

    /** 报名已发布课程；重复 ACTIVE 请求返回原记录，取消后重报会恢复原业务 ID 并发出新访问事件。 */
    @Transactional
    public EnrollmentCreation enroll(String learnerId, String courseId, String traceId) {
        CourseFact course = courses.requirePublished(courseId, traceId);
        Instant now = Instant.now();
        EnrollmentRow current = repository.enrollment(learnerId, courseId).orElse(null);
        if (current != null && current.status() == EnrollmentStatus.ACTIVE) return new EnrollmentCreation(enrollment(current), false);
        if (current != null) {
            if (repository.reactivateEnrollment(current.id(), course.publishedVersion(), now) == 0) {
                return new EnrollmentCreation(enrollment(repository.enrollment(learnerId, courseId).orElseThrow()), false);
            }
            repository.insertOutbox(events.enrollmentChanged(current.id(), learnerId, courseId, "ACTIVE",
                    course.publishedVersion(), traceId, now));
            return new EnrollmentCreation(enrollment(repository.enrollment(learnerId, courseId).orElseThrow()), false);
        }
        String id = UUID.randomUUID().toString();
        EnrollmentRow created = new EnrollmentRow(id, learnerId, courseId, course.publishedVersion(),
                EnrollmentStatus.ACTIVE, now, null, now, now);
        try {
            repository.insertEnrollment(created);
        } catch (DuplicateKeyException concurrent) {
            return new EnrollmentCreation(enrollment(repository.enrollment(learnerId, courseId).orElseThrow()), false);
        }
        repository.insertOutbox(events.enrollmentChanged(id, learnerId, courseId, "ACTIVE",
                course.publishedVersion(), traceId, now));
        return new EnrollmentCreation(enrollment(created), true);
    }

    /** 取消报名只撤销后续访问资格，不删除进度、作答、成绩或错题历史。 */
    @Transactional
    public LearningDtos.Enrollment cancelEnrollment(String learnerId, String enrollmentId, String traceId) {
        EnrollmentRow current = repository.ownedEnrollment(enrollmentId, learnerId).orElseThrow(this::notFound);
        if (current.status() == EnrollmentStatus.CANCELED) return enrollment(current);
        Instant now = Instant.now();
        if (repository.cancelEnrollment(enrollmentId, learnerId, now) == 0) throw conflict("LEARNING.ENROLLMENT_CONFLICT", "报名状态已变化，请刷新后重试");
        repository.insertOutbox(events.enrollmentChanged(current.id(), learnerId, current.courseId(), "CANCELED",
                current.courseVersionId(), traceId, now));
        return enrollment(repository.ownedEnrollment(enrollmentId, learnerId).orElseThrow());
    }

    /** 按当前学习者和可选状态分页返回报名历史，不读取他人记录。 */
    public LearningDtos.EnrollmentPage enrollments(String learnerId, EnrollmentStatus status, int page, int size) {
        return new LearningDtos.EnrollmentPage(repository.listEnrollments(learnerId, status, page, size)
                .stream().map(LearningAssessmentService::enrollment).toList(), page, size,
                repository.countEnrollments(learnerId, status));
    }

    /**
     * 合并播放器进度。clientEventId 唯一约束吸收重试，旧事件只允许提高播放位置/完成状态，
     * watchedSeconds 仅对未乱序的新事件累计，MySQL 始终是最终事实来源。
     */
    @Transactional
    public LearningDtos.LessonProgress reportProgress(String learnerId, String courseId, String lessonId,
            LearningDtos.ReportProgressRequest input, String traceId) {
        CourseFact course = requireLearningAccess(learnerId, courseId, traceId);
        requireLesson(course, lessonId);
        Instant now = Instant.now();
        if (input.occurredAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                || input.occurredAt().isBefore(now.minus(MAX_OFFLINE_AGE))) {
            throw invalid("LEARNING.PROGRESS_TIME_INVALID", "进度事件时间超出允许范围");
        }
        boolean inserted = repository.insertProgressEvent(input.clientEventId().toString(), learnerId, courseId,
                lessonId, input.occurredAt(), input.positionSeconds(), input.watchedSeconds(), input.completed(), now);
        repository.ensureProgress(UUID.randomUUID().toString(), learnerId, courseId, lessonId, input.occurredAt(), now);
        if (inserted) {
            ProgressRow locked = repository.lockProgress(learnerId, courseId, lessonId);
            repository.mergeProgress(locked, input.positionSeconds(), input.watchedSeconds(), input.completed(),
                    input.occurredAt(), now);
        }
        ProgressRow value = repository.lockProgress(learnerId, courseId, lessonId);
        return progress(value, !inserted);
    }

    /** 校验课程访问权后返回本人课时进度和按当前发布目录计算的完成率。 */
    public LearningDtos.CourseProgress progress(String learnerId, String courseId, String traceId) {
        CourseFact course = requireLearningAccess(learnerId, courseId, traceId);
        List<ProgressRow> rows = repository.progress(learnerId, courseId);
        int completed = (int) rows.stream().filter(ProgressRow::completed).count();
        int lessonCount = course.lessonIds().size();
        double percent = lessonCount == 0 ? 0 : BigDecimal.valueOf(completed * 100.0 / lessonCount)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
        return new LearningDtos.CourseProgress(courseId, completed, lessonCount, percent,
                rows.stream().map(row -> progress(row, false)).toList());
    }

    /** 返回学习者本人在指定当前课时的笔记；无访问权时拒绝。 */
    public List<LearningDtos.LearningNote> notes(String learnerId, String courseId, String lessonId, String traceId) {
        CourseFact course = requireLearningAccess(learnerId, courseId, traceId);
        requireLesson(course, lessonId);
        return repository.notes(learnerId, courseId, lessonId).stream().map(LearningAssessmentService::note).toList();
    }

    /** 在确认课时归属和学习权限后创建本人笔记，返回新记录。 */
    @Transactional
    public LearningDtos.LearningNote createNote(String learnerId, String courseId, String lessonId,
            LearningDtos.CreateNoteRequest input, String traceId) {
        CourseFact course = requireLearningAccess(learnerId, courseId, traceId);
        requireLesson(course, lessonId);
        Instant now = Instant.now();
        NoteRow row = new NoteRow(UUID.randomUUID().toString(), learnerId, courseId, lessonId,
                input.content(), input.positionSeconds(), 0, now, now);
        repository.insertNote(row);
        return note(row);
    }

    /** 使用 expectedRevision 修改本人笔记；他人资源 404，并发修改 409。 */
    @Transactional
    public LearningDtos.LearningNote updateNote(String learnerId, String noteId,
            LearningDtos.UpdateNoteRequest input) {
        repository.note(noteId, learnerId).orElseThrow(this::notFound);
        if (repository.updateNote(noteId, learnerId, input.expectedRevision(), input.content(),
                input.positionSeconds(), Instant.now()) == 0) {
            throw conflict("LEARNING.NOTE_REVISION_CONFLICT", "笔记已被更新，请刷新后重试");
        }
        return note(repository.note(noteId, learnerId).orElseThrow());
    }

    /** 按 noteId 删除本人笔记；记录不存在或不属于当前用户时统一 404。 */
    @Transactional
    public void deleteNote(String learnerId, String noteId) {
        if (repository.deleteNote(noteId, learnerId) == 0) throw notFound();
    }

    /** 创建草稿测评；course-service 不可用时返回 503，绝不在本地猜测所有权。 */
    @Transactional
    public AssessmentCreation createAssessment(String teacherId,
            LearningDtos.CreateAssessmentRequest input, String traceId) {
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(input);
        var replay = repository.idempotency(teacherId, requestId).orElse(null);
        if (replay != null) return new AssessmentCreation(
                teacherAssessment(idempotentResource(replay, fingerprint, "ASSESSMENT"), teacherId), false);
        CourseFact course = courses.requireOwnedPublished(input.courseId().toString(), teacherId, traceId);
        if (input.lessonId() != null) requireLesson(course, input.lessonId().toString());
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        repository.insertAssessment(new AssessmentRow(id, teacherId, input.courseId().toString(),
                input.lessonId() == null ? null : input.lessonId().toString(), input.title(), input.description(),
                input.type(), AssessmentStatus.DRAFT, input.answerPublicationPolicy(), 0, null, null, now, now));
        repository.insertIdempotency(teacherId, requestId, fingerprint, "ASSESSMENT", id, now);
        return new AssessmentCreation(teacherAssessment(id, teacherId), true);
    }

    /** 仅对本人未发布草稿使用 revision 修改元数据，返回完整教师投影。 */
    @Transactional
    public LearningDtos.TeacherAssessment updateAssessment(String teacherId, String assessmentId,
            LearningDtos.UpdateAssessmentRequest input) {
        repository.ownedAssessment(assessmentId, teacherId).orElseThrow(this::notFound);
        if (repository.updateAssessment(assessmentId, teacherId, input.expectedRevision(), input.title(),
                input.description(), input.type(), input.answerPublicationPolicy(), Instant.now()) == 0) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评已发布、归档或被其他请求更新");
        }
        return teacherAssessment(assessmentId, teacherId);
    }

    /** 只删除本人未发布草稿；发布历史必须归档，以保留学生作答和成绩。 */
    @Transactional
    public void deleteAssessment(String teacherId, String assessmentId) {
        AssessmentRow current = repository.lockOwnedAssessment(assessmentId, teacherId);
        if (current == null) throw notFound();
        if (current.status() != AssessmentStatus.DRAFT) {
            throw conflict("LEARNING.ASSESSMENT_PUBLISHED", "已发布测评不能删除，请改为归档");
        }
        if (repository.deleteDraftAssessment(assessmentId, teacherId) == 0) throw notFound();
    }

    /** 发布前冻结完整题目版本快照；只接受 ACTIVE 的 SYSTEM 题或当前教师自己的 TEACHER 题。 */
    @Transactional
    public LearningDtos.TeacherAssessment replaceQuestions(String teacherId, String assessmentId,
            LearningDtos.ReplaceQuestionsRequest input) {
        AssessmentRow assessment = repository.lockOwnedAssessment(assessmentId, teacherId);
        if (assessment == null) throw notFound();
        if (assessment.status() != AssessmentStatus.DRAFT || assessment.revision() != input.expectedRevision()) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "仅可修改未发布且 revision 匹配的测评");
        }
        validateSequences(input.questions());
        List<AssessmentQuestionRow> snapshots = new ArrayList<>();
        Instant now = Instant.now();
        for (LearningDtos.AssessmentQuestionInput item : input.questions()) {
            var version = questions.findVersion(item.questionVersionId().toString()).orElseThrow(this::notFound);
            var aggregate = questions.findVisibleRootAggregate(version.questionId(), teacherId).orElseThrow(this::notFound);
            if (aggregate.status() != QuestionModels.Status.ACTIVE) {
                throw invalid("LEARNING.QUESTION_NOT_USABLE", "测评只能选择已启用的公共题或本人题目");
            }
            QuestionDtos.VersionResponse snapshot = questionService.version(teacherId, version.questionId(), version.id());
            snapshots.add(new AssessmentQuestionRow(UUID.randomUUID().toString(), assessmentId,
                    version.questionId(), version.id(), item.sequence(), item.points(), snapshot, now));
        }
        if (repository.bumpDraftAssessmentRevision(assessmentId, teacherId, input.expectedRevision(), now) == 0) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评已被其他请求更新");
        }
        repository.deleteAssessmentQuestions(assessmentId);
        snapshots.forEach(repository::insertAssessmentQuestion);
        return teacherAssessment(assessmentId, teacherId);
    }

    /** 校验当前课程 owner、revision 和题目可用性后发布，或将已发布测评归档。 */
    @Transactional
    public LearningDtos.TeacherAssessment publication(String teacherId, String assessmentId,
            LearningDtos.UpdatePublicationRequest input, String traceId) {
        AssessmentRow assessment = repository.lockOwnedAssessment(assessmentId, teacherId);
        if (assessment == null) throw notFound();
        courses.requireOwnedPublished(assessment.courseId(), teacherId, traceId);
        if (assessment.status() == input.status()) return teacherAssessment(assessmentId, teacherId);
        AssessmentStatus expected;
        if (input.status() == AssessmentStatus.PUBLISHED) {
            expected = AssessmentStatus.DRAFT;
            List<AssessmentQuestionRow> selected = repository.assessmentQuestions(assessmentId);
            if (selected.isEmpty()) {
                throw invalid("LEARNING.ASSESSMENT_EMPTY", "测评至少包含一道题后才能发布");
            }
            // 题目可能在组卷后、发布前被归档；发布交易必须再次校验精确版本及当前可用范围。
            for (AssessmentQuestionRow item : selected) {
                var version = questions.findVersion(item.questionVersionId()).orElseThrow(this::notFound);
                var aggregate = questions.findVisibleRootAggregate(version.questionId(), teacherId)
                        .orElseThrow(this::notFound);
                if (aggregate.status() != QuestionModels.Status.ACTIVE) {
                    throw invalid("LEARNING.QUESTION_NOT_USABLE", "测评包含已归档或不可用的题目");
                }
            }
        } else if (input.status() == AssessmentStatus.ARCHIVED) {
            expected = AssessmentStatus.PUBLISHED;
        } else {
            throw invalid("LEARNING.ASSESSMENT_STATUS_INVALID", "状态只能发布或归档");
        }
        if (assessment.revision() != input.expectedRevision()
                || repository.advanceAssessmentStatus(assessmentId, teacherId, input.expectedRevision(), expected,
                        input.status(), Instant.now()) == 0) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评状态或 revision 已变化");
        }
        if (input.status() == AssessmentStatus.PUBLISHED) {
            repository.ensureCourseAssignment(UUID.randomUUID().toString(), assessmentId, assessment.courseId(), Instant.now());
        }
        return teacherAssessment(assessmentId, teacherId);
    }

    /** 按 owner、可选课程/状态返回教师测评分页；指定课程时先验证课程所有权。 */
    public LearningDtos.AssessmentPage teacherAssessments(String teacherId, String courseId,
            AssessmentStatus status, int page, int size, String traceId) {
        if (courseId != null) courses.requireOwnedPublished(courseId, teacherId, traceId);
        return new LearningDtos.AssessmentPage(repository.listTeacherAssessments(teacherId, courseId, status, page, size)
                .stream().map(this::summary).toList(), page, size,
                repository.countTeacherAssessments(teacherId, courseId, status));
    }

    /** 返回教师本人测评和含答案的冻结题目快照，他人资源按 404 隐藏。 */
    public LearningDtos.TeacherAssessment teacherAssessment(String id, String teacherId) {
        AssessmentRow row = repository.ownedAssessment(id, teacherId).orElseThrow(this::notFound);
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(id);
        LearningDtos.AssessmentSummary summary = summary(row, items);
        return new LearningDtos.TeacherAssessment(summary.id(), summary.courseId(), summary.lessonId(),
                summary.title(), summary.type(), summary.status(), summary.answerPublicationPolicy(),
                summary.questionCount(), summary.totalPoints(), summary.revision(), summary.publishedAt(),
                summary.createdAt(), summary.updatedAt(), row.description(), items.stream().map(item ->
                    new LearningDtos.TeacherQuestion(item.id(), item.questionVersionId(), item.sequence(),
                            item.points(), item.snapshot())).toList());
    }

    /** 返回已分配到学习者 ACTIVE 报名课程的已发布测评，不携带题目答案。 */
    public LearningDtos.AssessmentPage learnerAssessments(String learnerId, String courseId, int page, int size) {
        return new LearningDtos.AssessmentPage(repository.listLearnerAssessments(learnerId, courseId, page, size)
                .stream().map(this::summary).toList(), page, size,
                repository.countLearnerAssessments(learnerId, courseId));
    }

    /** 学生模型从类型上移除了答案、解析和评分规则，发布策略尚未满足时无法序列化泄漏。 */
    public LearningDtos.LearnerAssessment learnerAssessment(String learnerId, String assessmentId) {
        AssessmentRow row = publishedForLearner(learnerId, assessmentId);
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(assessmentId);
        LearningDtos.AssessmentSummary summary = summary(row, items);
        List<LearningDtos.LearnerQuestion> safe = items.stream().map(this::learnerQuestion).toList();
        return new LearningDtos.LearnerAssessment(summary.id(), summary.courseId(), summary.lessonId(),
                summary.title(), summary.type(), summary.status(), summary.answerPublicationPolicy(),
                summary.questionCount(), summary.totalPoints(), summary.revision(), summary.publishedAt(),
                summary.createdAt(), summary.updatedAt(), row.description(), safe);
    }

    /** 为本人开始一次已分配的测评作答；同 learner/assessment 重试返回原草稿。 */
    @Transactional
    public LearningDtos.AttemptDraft startAttempt(String learnerId, String assessmentId,
            LearningDtos.StartAttemptRequest input) {
        publishedForLearner(learnerId, assessmentId);
        AttemptRow existing = repository.attemptForLearner(assessmentId, learnerId).orElse(null);
        if (existing != null) return draft(existing);
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(assessmentId);
        Instant now = Instant.now();
        AttemptRow created = new AttemptRow(UUID.randomUUID().toString(), assessmentId, learnerId,
                AttemptStatus.DRAFT, 0, null, null, null, null, null, total(items), null, null, now, now);
        try {
            repository.insertAttempt(created);
        } catch (DuplicateKeyException concurrent) {
            created = repository.attemptForLearner(assessmentId, learnerId).orElseThrow();
        }
        return draft(created);
    }

    /** 使用 clientRequestId 和 expectedRevision 覆盖保存本人草稿，冲突时不覆盖新数据。 */
    @Transactional
    public LearningDtos.AttemptDraft saveDraft(String learnerId, String attemptId,
            LearningDtos.SaveDraftRequest input) {
        AttemptRow attempt = repository.lockAttempt(attemptId);
        if (attempt == null || !attempt.learnerId().equals(learnerId)) throw notFound();
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(input.answers());
        if (requestId.equals(attempt.draftRequestId())) {
            if (!fingerprint.equals(attempt.draftFingerprint())) throw conflict("LEARNING.IDEMPOTENCY_CONFLICT", "相同请求 ID 的草稿内容不同");
            return draft(attempt);
        }
        if (attempt.status() != AttemptStatus.DRAFT || attempt.revision() != input.expectedRevision()) {
            throw conflict("LEARNING.ATTEMPT_REVISION_CONFLICT", "作答状态或 revision 已变化");
        }
        validateAnswers(attempt.assessmentId(), input.answers(), false);
        Instant now = Instant.now();
        if (repository.updateDraftMetadata(attemptId, input.expectedRevision(), requestId, fingerprint, now) == 0) {
            throw conflict("LEARNING.ATTEMPT_REVISION_CONFLICT", "草稿已被其他请求更新");
        }
        repository.replaceDraftAnswers(attemptId, input.answers(), now);
        return draft(repository.ownedAttempt(attemptId, learnerId).orElseThrow());
    }

    /** 提交只允许成功一次；事务内先写 SUBMITTED，再完成自动判分、错题累计和最终状态汇总。 */
    @Transactional
    public LearningDtos.AttemptResult submit(String learnerId, String attemptId,
            LearningDtos.SubmitAttemptRequest input) {
        AttemptRow attempt = repository.lockAttempt(attemptId);
        if (attempt == null || !attempt.learnerId().equals(learnerId)) throw notFound();
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(input.answers());
        if (attempt.status() != AttemptStatus.DRAFT) {
            if (requestId.equals(attempt.submitRequestId()) && fingerprint.equals(attempt.submissionFingerprint())) {
                return result(attemptId, learnerId, false);
            }
            throw conflict("LEARNING.ATTEMPT_ALREADY_SUBMITTED", "试卷已提交，不能再次修改");
        }
        if (attempt.revision() != input.expectedRevision()) throw conflict("LEARNING.ATTEMPT_REVISION_CONFLICT", "作答 revision 已变化");
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(attempt.assessmentId());
        List<AnswerInput> complete = completeAnswers(items, input.answers());
        validateAnswers(attempt.assessmentId(), complete, true);
        Instant now = Instant.now();
        repository.replaceDraftAnswers(attemptId, complete, now);
        if (repository.submitAttempt(attemptId, input.expectedRevision(), requestId, fingerprint,
                AttemptStatus.SUBMITTED, null, now) == 0) {
            throw conflict("LEARNING.ATTEMPT_ALREADY_SUBMITTED", "试卷已由其他请求提交");
        }
        Map<String, AssessmentQuestionRow> byId = index(items);
        boolean pendingManual = false;
        BigDecimal score = BigDecimal.ZERO;
        for (AnswerRow answer : repository.answers(attemptId)) {
            AssessmentQuestionRow item = byId.get(answer.assessmentQuestionId());
            AutoGrade grade = autoGrade(item.snapshot(), answer.answer(), item.points());
            if (grade == null) {
                pendingManual = true;
                continue;
            }
            repository.gradeAnswer(answer.id(), grade.score(), grade.correct(), GradingStatus.AUTO_GRADED,
                    "SYSTEM", now);
            score = score.add(grade.score());
            if (!grade.correct()) recordWrong(attempt, item, now);
        }
        AttemptStatus finalStatus = pendingManual ? AttemptStatus.PARTIALLY_GRADED : AttemptStatus.GRADED;
        repository.updateAttemptGrade(attemptId, finalStatus, score,
                finalStatus == AttemptStatus.GRADED ? now : null, now);
        return result(attemptId, learnerId, false);
    }

    /** 返回本人已提交成绩，正确答案/解析是否存在由公布策略决定。 */
    public LearningDtos.AttemptResult learnerResult(String learnerId, String attemptId) {
        return result(attemptId, learnerId, false);
    }

    /** 按本人与可选课程分页返回幂等累计的错题版本统计。 */
    public LearningDtos.WrongQuestionPage wrongQuestions(String learnerId, String courseId, int page, int size) {
        return new LearningDtos.WrongQuestionPage(repository.wrongQuestions(learnerId, courseId, page, size).stream()
                .map(row -> new LearningDtos.WrongQuestion(row.id(), row.courseId(), row.assessmentId(),
                        row.questionVersionId(), row.wrongCount(), row.firstWrongAt(), row.lastWrongAt())).toList(),
                page, size, repository.countWrongQuestions(learnerId, courseId));
    }

    /** 人工批改按 attempt revision 做乐观并发控制，并为每道题记录 grader、时间、分数和答案 revision。 */
    @Transactional
    public LearningDtos.AttemptResult manualGrade(String teacherId, String attemptId,
            LearningDtos.ManualGradeRequest input) {
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(input.grades());
        var replay = repository.idempotency(teacherId, requestId).orElse(null);
        if (replay != null) return teacherResult(teacherId, idempotentResource(replay, fingerprint, "MANUAL_GRADE"));
        AttemptRow attempt = repository.lockAttempt(attemptId);
        if (attempt == null) throw notFound();
        AssessmentRow assessment = repository.ownedAssessment(attempt.assessmentId(), teacherId).orElseThrow(this::notFound);
        if ((attempt.status() != AttemptStatus.PARTIALLY_GRADED && attempt.status() != AttemptStatus.SUBMITTED)
                || attempt.revision() != input.expectedRevision()) {
            throw conflict("LEARNING.GRADING_REVISION_CONFLICT", "作答状态或 revision 已变化，请刷新后批改");
        }
        Map<String, AssessmentQuestionRow> items = index(repository.assessmentQuestions(assessment.id()));
        Set<String> unique = new HashSet<>();
        Instant now = Instant.now();
        for (LearningDtos.ManualGradeItem grade : input.grades()) {
            String questionId = grade.assessmentQuestionId().toString();
            if (!unique.add(questionId)) throw invalid("LEARNING.GRADING_DUPLICATE", "同一道题不能在一次请求中重复批改");
            AssessmentQuestionRow item = items.get(questionId);
            AnswerRow answer = repository.answer(attemptId, questionId).orElseThrow(this::notFound);
            if (item == null || answer.gradingStatus() != GradingStatus.PENDING_MANUAL) {
                throw conflict("LEARNING.ANSWER_ALREADY_GRADED", "答案已经评分或不需要人工批改");
            }
            if (grade.score().compareTo(item.points()) > 0) throw invalid("LEARNING.GRADE_OUT_OF_RANGE", "人工得分不能超过题目分值");
            repository.insertManualGrade(UUID.randomUUID().toString(), requestId, attemptId, answer.id(),
                    teacherId, answer.revision(), grade.score(), grade.comment(), now);
            repository.gradeAnswer(answer.id(), grade.score(),
                    grade.score().compareTo(item.points()) == 0, GradingStatus.MANUALLY_GRADED, teacherId, now);
            if (grade.score().compareTo(item.points()) < 0) recordWrong(attempt, item, now);
        }
        List<AnswerRow> after = repository.answers(attemptId);
        boolean pending = after.stream().anyMatch(item -> item.gradingStatus() == GradingStatus.PENDING_MANUAL);
        BigDecimal score = after.stream().filter(item -> item.score() != null).map(AnswerRow::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        repository.updateAttemptGrade(attemptId, pending ? AttemptStatus.PARTIALLY_GRADED : AttemptStatus.GRADED,
                score, pending ? null : now, now);
        repository.insertIdempotency(teacherId, requestId, fingerprint, "MANUAL_GRADE", attemptId, now);
        return teacherResult(teacherId, attemptId);
    }

    /** 校验测评 owner 后分页返回已作答学生的评分结果。 */
    public LearningDtos.AttemptResultPage teacherResults(String teacherId, String assessmentId, int page, int size) {
        repository.ownedAssessment(assessmentId, teacherId).orElseThrow(this::notFound);
        List<LearningDtos.AttemptResult> values = repository.assessmentAttempts(assessmentId, page, size).stream()
                .map(item -> result(item.id(), item.learnerId(), true)).toList();
        return new LearningDtos.AttemptResultPage(values, page, size,
                repository.countAssessmentAttempts(assessmentId));
    }

    /** 返回教师本人测评中的单次完整结果，包含评分所需答案与解析。 */
    public LearningDtos.AttemptResult teacherResult(String teacherId, String attemptId) {
        AttemptRow attempt = repository.lockAttempt(attemptId);
        if (attempt == null) throw notFound();
        repository.ownedAssessment(attempt.assessmentId(), teacherId).orElseThrow(this::notFound);
        return result(attemptId, attempt.learnerId(), true);
    }

    /** 验证课程 owner 后返回该课程学习者的报名、进度和成绩汇总投影。 */
    public LearningDtos.StudentLearningPage studentLearning(String teacherId, String courseId,
            int page, int size, String traceId) {
        courses.requireOwnedPublished(courseId, teacherId, traceId);
        return new LearningDtos.StudentLearningPage(repository.studentProjections(courseId, page, size).stream()
                .map(row -> new LearningDtos.StudentLearningSummary(row.learnerId(), row.enrollmentStatus(),
                        row.completedLessons(), row.watchedSeconds(), row.submittedAttempts(), row.gradedAttempts(),
                        row.averageScore())).toList(), page, size, repository.countStudentProjections(courseId));
    }

    private LearningDtos.AttemptResult result(String attemptId, String learnerId, boolean teacherView) {
        AttemptRow attempt = repository.ownedAttempt(attemptId, learnerId).orElseThrow(this::notFound);
        AssessmentRow assessment = repository.assessment(attempt.assessmentId()).orElseThrow(this::notFound);
        if (attempt.status() == AttemptStatus.DRAFT) throw conflict("LEARNING.ATTEMPT_NOT_SUBMITTED", "试卷尚未提交");
        boolean disclose = teacherView || assessment.policy() == AnswerPublicationPolicy.AFTER_SUBMISSION
                || (assessment.policy() == AnswerPublicationPolicy.AFTER_GRADED && attempt.status() == AttemptStatus.GRADED);
        Map<String, AssessmentQuestionRow> items = index(repository.assessmentQuestions(attempt.assessmentId()));
        List<LearningDtos.AnswerResult> answers = repository.answers(attemptId).stream().map(answer -> {
            AssessmentQuestionRow item = items.get(answer.assessmentQuestionId());
            return new LearningDtos.AnswerResult(answer.assessmentQuestionId(), answer.answer(), answer.score(),
                    answer.correct(), answer.gradingStatus(), disclose ? item.snapshot().answers() : null,
                    disclose ? item.snapshot().analysis() : null);
        }).toList();
        return new LearningDtos.AttemptResult(attempt.id(), attempt.assessmentId(), attempt.learnerId(),
                attempt.status(), attempt.revision(), attempt.score(), attempt.totalPoints(), attempt.submittedAt(),
                attempt.gradedAt(), answers);
    }

    private AssessmentRow publishedForLearner(String learnerId, String assessmentId) {
        AssessmentRow row = repository.assessment(assessmentId).orElseThrow(this::notFound);
        if (row.status() != AssessmentStatus.PUBLISHED
                || !repository.isAssignedToCourse(assessmentId, row.courseId())
                || repository.enrollment(learnerId, row.courseId()).filter(value -> value.status() == EnrollmentStatus.ACTIVE).isEmpty()) {
            throw notFound();
        }
        return row;
    }

    private CourseFact requireLearningAccess(String learnerId, String courseId, String traceId) {
        CourseFact course = courses.requirePublished(courseId, traceId);
        if (!"PUBLIC".equals(course.accessType())
                && repository.enrollment(learnerId, courseId).filter(value -> value.status() == EnrollmentStatus.ACTIVE).isEmpty()) {
            throw new QuestionException(HttpStatus.FORBIDDEN, "LEARNING.ENROLLMENT_REQUIRED", "请先报名课程");
        }
        return course;
    }

    private static void requireLesson(CourseFact course, String lessonId) {
        if (!course.lessonIds().contains(lessonId)) throw new QuestionException(HttpStatus.NOT_FOUND,
                "LEARNING.NOT_FOUND", "学习资源不存在或不可见");
    }

    private void validateAnswers(String assessmentId, List<AnswerInput> answers, boolean complete) {
        Set<String> expected = repository.assessmentQuestions(assessmentId).stream()
                .map(AssessmentQuestionRow::id).collect(java.util.stream.Collectors.toSet());
        Set<String> actual = new HashSet<>();
        for (AnswerInput answer : answers) {
            String id = answer.assessmentQuestionId().toString();
            if (!expected.contains(id)) throw invalid("LEARNING.ANSWER_QUESTION_INVALID", "答案包含不属于该试卷的题目");
            if (!actual.add(id)) throw invalid("LEARNING.ANSWER_DUPLICATE", "同一道题不能重复作答");
        }
        if (complete && !actual.equals(expected)) throw invalid("LEARNING.ANSWER_INCOMPLETE", "提交时必须包含试卷全部题目，可用空数组表示未作答");
    }

    private static List<AnswerInput> completeAnswers(List<AssessmentQuestionRow> questions, List<AnswerInput> input) {
        Map<String, AnswerInput> supplied = new LinkedHashMap<>();
        for (AnswerInput answer : input) supplied.put(answer.assessmentQuestionId().toString(), answer);
        return questions.stream().map(item -> supplied.getOrDefault(item.id(),
                new AnswerInput(UUID.fromString(item.id()), List.of()))).toList();
    }

    private static AutoGrade autoGrade(QuestionDtos.VersionResponse snapshot, List<String> submitted,
            BigDecimal points) {
        return switch (snapshot.type()) {
            case SINGLE_CHOICE, TRUE_FALSE -> exact(normalizeOrdered(snapshot.answers()), normalizeOrdered(submitted), points);
            case MULTIPLE_CHOICE -> exact(normalizeSet(snapshot.answers()), normalizeSet(submitted), points);
            case FILL_BLANK -> exact(normalizeFill(snapshot.answers()), normalizeFill(submitted), points);
            case SHORT_ANSWER, COMPOSITE -> null;
        };
    }

    private static AutoGrade exact(Object expected, Object actual, BigDecimal points) {
        boolean correct = expected.equals(actual);
        return new AutoGrade(correct ? points : BigDecimal.ZERO, correct);
    }

    private static List<String> normalizeOrdered(List<String> values) {
        return values.stream().map(value -> value.trim().toUpperCase(Locale.ROOT)).toList();
    }

    private static Set<String> normalizeSet(List<String> values) {
        return values.stream().map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 填空规范化固定为 Unicode NFKC、去首尾空白、连续空白折叠及 Locale.ROOT 小写。 */
    private static List<String> normalizeFill(List<String> values) {
        return values.stream().map(value -> Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)).toList();
    }

    private void recordWrong(AttemptRow attempt, AssessmentQuestionRow item, Instant now) {
        AssessmentRow assessment = repository.assessment(attempt.assessmentId()).orElseThrow();
        if (repository.markWrongOccurrence(attempt.id(), item.id(), now)) {
            repository.accumulateWrongQuestion(UUID.randomUUID().toString(), attempt.learnerId(),
                    assessment.courseId(), assessment.id(), item.questionVersionId(), attempt.id(), now);
        }
    }

    private LearningDtos.AttemptDraft draft(AttemptRow attempt) {
        List<AnswerInput> answers = repository.answers(attempt.id()).stream()
                .map(value -> new AnswerInput(UUID.fromString(value.assessmentQuestionId()), value.answer())).toList();
        return new LearningDtos.AttemptDraft(attempt.id(), attempt.assessmentId(), attempt.status(), attempt.revision(),
                answers, attempt.createdAt(), attempt.updatedAt());
    }

    private LearningDtos.LearnerQuestion learnerQuestion(AssessmentQuestionRow item) {
        QuestionDtos.VersionResponse value = item.snapshot();
        List<QuestionDtos.SystemQuestionChild> children = value.children().stream().map(child ->
                new QuestionDtos.SystemQuestionChild(child.questionId(), child.versionId(), child.sequence(),
                        child.type(), child.difficulty(), child.stage(), child.grade(), child.subject(), child.stem(),
                        child.options(), child.knowledgePoints(), child.media(), child.createdAt())).toList();
        return new LearningDtos.LearnerQuestion(item.id(), item.questionVersionId(), item.sequence(), item.points(),
                value.type(), value.stem(), value.options(), value.media(), children);
    }

    private LearningDtos.AssessmentSummary summary(AssessmentRow row) {
        return summary(row, repository.assessmentQuestions(row.id()));
    }

    private static LearningDtos.AssessmentSummary summary(AssessmentRow row, List<AssessmentQuestionRow> items) {
        return new LearningDtos.AssessmentSummary(row.id(), row.courseId(), row.lessonId(), row.title(), row.type(),
                row.status(), row.policy(), items.size(), total(items), row.revision(), row.publishedAt(),
                row.createdAt(), row.updatedAt());
    }

    private static BigDecimal total(List<AssessmentQuestionRow> items) {
        return items.stream().map(AssessmentQuestionRow::points).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, AssessmentQuestionRow> index(List<AssessmentQuestionRow> items) {
        Map<String, AssessmentQuestionRow> values = new HashMap<>();
        items.forEach(item -> values.put(item.id(), item));
        return values;
    }

    private static void validateSequences(List<LearningDtos.AssessmentQuestionInput> items) {
        Set<String> versions = new HashSet<>();
        List<Integer> sequences = new ArrayList<>();
        for (var item : items) {
            if (!versions.add(item.questionVersionId().toString())) throw invalid("LEARNING.QUESTION_DUPLICATE", "同一题目版本不能重复加入试卷");
            sequences.add(item.sequence());
        }
        sequences.sort(Comparator.naturalOrder());
        for (int index = 0; index < sequences.size(); index++) {
            if (sequences.get(index) != index + 1) throw invalid("LEARNING.QUESTION_SEQUENCE_INVALID", "题目顺序必须从 1 连续递增");
        }
    }

    private static LearningDtos.Enrollment enrollment(EnrollmentRow row) {
        return new LearningDtos.Enrollment(row.id(), row.learnerId(), row.courseId(), row.courseVersionId(),
                row.status(), row.enrolledAt(), row.canceledAt(), row.updatedAt());
    }

    private static LearningDtos.LessonProgress progress(ProgressRow row, boolean replayed) {
        return new LearningDtos.LessonProgress(row.courseId(), row.lessonId(), row.positionSeconds(),
                row.watchedSeconds(), row.completed(), row.lastEventAt(), row.revision(), replayed);
    }

    private static LearningDtos.LearningNote note(NoteRow row) {
        return new LearningDtos.LearningNote(row.id(), row.courseId(), row.lessonId(), row.content(),
                row.positionSeconds(), row.revision(), row.createdAt(), row.updatedAt());
    }

    private String idempotentResource(LearningAssessmentRepository.IdempotencyRow replay, String fingerprint,
            String type) {
        if (!replay.resourceType().equals(type) || !replay.fingerprint().equals(fingerprint)) {
            throw conflict("LEARNING.IDEMPOTENCY_CONFLICT", "相同请求 ID 已用于不同内容");
        }
        return replay.resourceId();
    }

    private String fingerprint(Object value) {
        try {
            byte[] bytes = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算请求幂等指纹", exception);
        }
    }

    private QuestionException notFound() {
        return new QuestionException(HttpStatus.NOT_FOUND, "LEARNING.NOT_FOUND", "学习资源不存在或不可见");
    }

    private static QuestionException conflict(String code, String message) {
        return new QuestionException(HttpStatus.CONFLICT, code, message);
    }

    private static QuestionException invalid(String code, String message) {
        return new QuestionException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private record AutoGrade(BigDecimal score, boolean correct) {}
    public record EnrollmentCreation(LearningDtos.Enrollment enrollment, boolean created) {}
    public record AssessmentCreation(LearningDtos.TeacherAssessment assessment, boolean created) {}
}
