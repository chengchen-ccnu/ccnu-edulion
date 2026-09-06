package com.eduplatform.learning.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.course.CourseFactsClient.CourseFact;
import com.eduplatform.learning.identity.LearnerDirectoryClient;
import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AnswerRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentProjectionCounts;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AttemptRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.EnrollmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.NoteRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.ProgressRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.WrongRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.RedoRow;
import com.eduplatform.learning.progress.ProgressBufferStore;
import com.eduplatform.learning.progress.ProgressBufferStore.BufferedProgress;
import com.eduplatform.learning.progress.ProgressBufferUnavailableException;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.LearningDtos.AnswerInput;
import com.eduplatform.learning.web.LearningDtos.AnswerPublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.AssessmentCloseStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentSettlementStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentStatus;
import com.eduplatform.learning.web.LearningDtos.AttemptStatus;
import com.eduplatform.learning.web.LearningDtos.EnrollmentStatus;
import com.eduplatform.learning.web.LearningDtos.GradePublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.GradingStatus;
import com.eduplatform.learning.web.LearningDtos.ResultPublicationStatus;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * T11B 学习与测评领域服务。所有写操作在 learning Schema 内完成；课程发布、课时归属和教师所有权
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
    private final MediaClient media;
    private final ObjectMapper mapper;
    private final ProgressBufferStore progressBuffer;
    private final MeterRegistry meterRegistry;
    private final LearnerDirectoryClient directory;

    @Autowired
    public LearningAssessmentService(LearningAssessmentRepository repository, CourseFactsClient courses,
            LearningEvents events, QuestionRepository questions, QuestionService questionService,
            MediaClient media, ObjectMapper mapper, ProgressBufferStore progressBuffer, MeterRegistry meterRegistry,
            LearnerDirectoryClient directory) {
        this.repository = repository;
        this.courses = courses;
        this.events = events;
        this.questions = questions;
        this.questionService = questionService;
        this.media = media;
        this.mapper = mapper;
        this.progressBuffer = progressBuffer;
        this.meterRegistry = meterRegistry;
        this.directory = directory;
    }

    /** 兼容只在 Java 单元测试中手工构造领域服务的旧构造器；HTTP 运行时由 Spring 注入 IAM 批量查询客户端。 */
    public LearningAssessmentService(LearningAssessmentRepository repository, CourseFactsClient courses,
            LearningEvents events, QuestionRepository questions, QuestionService questionService,
            MediaClient media, ObjectMapper mapper, ProgressBufferStore progressBuffer, MeterRegistry meterRegistry) {
        this(repository, courses, events, questions, questionService, media, mapper, progressBuffer, meterRegistry,
                (learnerIds, traceId) -> new LearnerDirectoryClient.NicknameLookup(Map.of(), true));
    }

    /** 报名已发布课程；重复 ACTIVE 请求返回原记录，取消后重报会恢复原业务 ID 并发出新访问事件。 */
    @Transactional
    public EnrollmentCreation enroll(String learnerId, String courseId, String traceId) {
        CourseFact course = courses.requirePublished(courseId, traceId);
        Instant now = Instant.now();
        EnrollmentRow current = repository.enrollment(learnerId, courseId).orElse(null);
        if (current != null && current.status() == EnrollmentStatus.ACTIVE) {
            ensureLearnerCourseProjection(learnerId, courseId, now);
            return new EnrollmentCreation(enrollment(current), false);
        }
        if (current != null) {
            if (repository.reactivateEnrollment(current.id(), course.publishedVersion(), now) == 0) {
                return new EnrollmentCreation(enrollment(repository.enrollment(learnerId, courseId).orElseThrow()), false);
            }
            ensureLearnerCourseProjection(learnerId, courseId, now);
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
        ensureLearnerCourseProjection(learnerId, courseId, now);
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
        repository.withdrawOpenParticipantsForLearner(current.courseId(), learnerId, now);
        repository.insertOutbox(events.enrollmentChanged(current.id(), learnerId, current.courseId(), "CANCELED",
                current.courseVersionId(), traceId, now));
        return enrollment(repository.ownedEnrollment(enrollmentId, learnerId).orElseThrow());
    }

    /** 报名成为成绩册的稳定主体；编号只在课程内分配一次，参与者行只保存 Learning 域所需状态。 */
    private void ensureLearnerCourseProjection(String learnerId, String courseId, Instant now) {
        if (repository.courseStudentNumber(courseId, learnerId).isEmpty()) {
            int number = repository.nextCourseStudentNumber(courseId);
            repository.insertCourseStudentNumber(UUID.randomUUID().toString(), courseId, learnerId, number, now);
        }
        repository.ensureOpenParticipantsForLearner(courseId, learnerId, now);
    }

    /** 按当前学习者和可选状态分页返回报名历史，不读取他人记录。 */
    public LearningDtos.EnrollmentPage enrollments(String learnerId, EnrollmentStatus status, int page, int size) {
        return new LearningDtos.EnrollmentPage(repository.listEnrollments(learnerId, status, page, size)
                .stream().map(LearningAssessmentService::enrollment).toList(), page, size,
                repository.countEnrollments(learnerId, status));
    }

    /**
     * 按显式事件类型处理播放进度。旧客户端缺少 eventType 时继续同步写 MySQL；HEARTBEAT 只写 Redis，
     * PAUSE/LESSON_SWITCH/LEAVE/ENDED 直接形成 MySQL 可恢复事实。完成标志只有 ENDED 可以由新契约设置。
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
        LearningDtos.ProgressEventType eventType = input.eventType();
        if (eventType == LearningDtos.ProgressEventType.HEARTBEAT) {
            if (input.completed()) {
                throw invalid("LEARNING.PROGRESS_COMPLETION_EVENT_INVALID", "只有 ENDED 事件可以记录完成");
            }
            try {
                if (progressBuffer.backlogExceedsLimit()) {
                    meterRegistry.counter("learning.progress.heartbeat.rejected", "reason", "backlog").increment();
                    throw new QuestionException(HttpStatus.SERVICE_UNAVAILABLE,
                            "LEARNING.PROGRESS_BUFFER_BACKLOG", "进度缓冲积压过多，请稍后重试");
                }
            } catch (ProgressBufferUnavailableException unavailable) {
                meterRegistry.counter("learning.progress.heartbeat.rejected", "reason", "redis").increment();
                throw new QuestionException(HttpStatus.SERVICE_UNAVAILABLE,
                        "LEARNING.PROGRESS_BUFFER_UNAVAILABLE", "进度缓冲暂时不可用，请稍后重试");
            }
            ProgressRow persisted = repository.findProgress(learnerId, courseId, lessonId).orElse(null);
            try {
                var buffered = progressBuffer.buffer(learnerId, courseId, lessonId, persisted, input, now);
                return progress(buffered.value(), buffered.replayed(), LearningDtos.ProgressPersistenceStatus.BUFFERED);
            } catch (ProgressBufferUnavailableException unavailable) {
                throw new QuestionException(HttpStatus.SERVICE_UNAVAILABLE,
                        "LEARNING.PROGRESS_BUFFER_UNAVAILABLE", "进度缓冲暂时不可用，请稍后重试");
            }
        }
        if (eventType != null && input.completed() && eventType != LearningDtos.ProgressEventType.ENDED) {
            throw invalid("LEARNING.PROGRESS_COMPLETION_EVENT_INVALID", "只有 ENDED 事件可以记录完成");
        }
        try {
            return persistCheckpoint(learnerId, courseId, lessonId, input, eventType, now);
        } catch (DataAccessException databaseFailure) {
            // 强制检查点失败必须明确返回 503，不能把未落库结果包装成成功响应。
            meterRegistry.counter("learning.progress.checkpoint.failures").increment();
            throw new QuestionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LEARNING.PROGRESS_PERSISTENCE_UNAVAILABLE", "进度暂时无法可靠保存，请稍后重试");
        }
    }

    /** 强制检查点与旧客户端兼容写入同一 MySQL 聚合和幂等表，Worker 不会参与该路径。 */
    private LearningDtos.LessonProgress persistCheckpoint(String learnerId, String courseId, String lessonId,
            LearningDtos.ReportProgressRequest input, LearningDtos.ProgressEventType eventType, Instant now) {
        boolean inserted = repository.insertProgressEvent(input.clientEventId().toString(), learnerId, courseId,
                lessonId, input.occurredAt(), input.positionSeconds(), input.watchedSeconds(), input.completed(),
                eventType, now);
        repository.ensureProgress(UUID.randomUUID().toString(), learnerId, courseId, lessonId, input.occurredAt(), now);
        if (inserted) {
            ProgressRow locked = repository.lockProgress(learnerId, courseId, lessonId);
            repository.mergeProgress(locked, input.positionSeconds(), input.watchedSeconds(), input.completed(),
                    input.occurredAt(), now);
        }
        ProgressRow value = repository.lockProgress(learnerId, courseId, lessonId);
        meterRegistry.counter("learning.progress.checkpoint.persisted").increment();
        return progress(value, !inserted, LearningDtos.ProgressPersistenceStatus.PERSISTED);
    }

    /**
     * 将成功渲染的 ARTICLE 课时从 0 单向推进到 100%。命令使用独立 clientRequestId 幂等表，
     * 不生成视频 positionSeconds/watchedSeconds 事件；PUBLIC 或有效报名用户才可首次记录。
     */
    @Transactional
    public LearningDtos.ArticleLessonCompletion completeArticle(String learnerId, String courseId, String lessonId,
            LearningDtos.CompleteArticleLessonRequest input, String traceId) {
        String requestId = input.clientRequestId().toString();
        var replay = repository.articleCompletionRequest(requestId);
        if (replay.isPresent()) {
            var existingRequest = replay.orElseThrow();
            if (!existingRequest.learnerId().equals(learnerId) || !existingRequest.courseId().equals(courseId)
                    || !existingRequest.lessonId().equals(lessonId)) {
                throw conflict("LEARNING.IDEMPOTENCY_CONFLICT", "相同请求 ID 已用于其他图文课时");
            }
            var fact = repository.articleCompletion(learnerId, courseId, lessonId);
            return articleCompletion(fact, true);
        }
        CourseFact course = requireLearningAccess(learnerId, courseId, traceId);
        requireLesson(course, lessonId);
        if (!course.isArticle(lessonId)) {
            throw new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "LEARNING.LESSON_TYPE_INVALID",
                    "只有 ARTICLE 课时可以使用图文完成命令");
        }
        Instant now = Instant.now();
        var candidate = new LearningAssessmentRepository.ArticleCompletionRow(UUID.randomUUID().toString(),
                learnerId, courseId, lessonId, now);
        boolean newFact = repository.insertArticleCompletionFact(candidate);
        var fact = repository.articleCompletion(learnerId, courseId, lessonId);
        if (newFact) {
            repository.ensureProgress(UUID.randomUUID().toString(), learnerId, courseId, lessonId, now, now);
            repository.markArticleProgressCompleted(learnerId, courseId, lessonId, now);
        }
        boolean inserted = repository.insertArticleCompletionRequest(requestId, learnerId, courseId,
                lessonId, fact.id(), now);
        if (!inserted) {
            var current = repository.articleCompletionRequest(requestId).orElseThrow();
            if (!current.learnerId().equals(learnerId) || !current.courseId().equals(courseId)
                    || !current.lessonId().equals(lessonId)) {
                throw conflict("LEARNING.IDEMPOTENCY_CONFLICT", "相同请求 ID 已用于其他图文课时");
            }
        }
        return articleCompletion(fact, !inserted);
    }

    /** 校验课程访问权后返回本人课时进度和按当前发布目录计算的完成率。 */
    public LearningDtos.CourseProgress progress(String learnerId, String courseId, String traceId) {
        CourseFact course = requireLearningAccess(learnerId, courseId, traceId);
        List<ProgressRow> rows = repository.progress(learnerId, courseId);
        List<LearningDtos.LessonProgress> views = new ArrayList<>();
        Set<String> returnedLessons = new HashSet<>();
        for (ProgressRow row : rows) {
            var read = progressBuffer.read(learnerId, courseId, row.lessonId());
            if (!read.available()) meterRegistry.counter("learning.progress.read.degraded").increment();
            views.add(read.available() && read.value().isPresent()
                    ? progress(row, read.value().orElseThrow(), false, LearningDtos.ProgressPersistenceStatus.BUFFERED)
                    : progress(row, false, read.available() ? LearningDtos.ProgressPersistenceStatus.PERSISTED
                            : LearningDtos.ProgressPersistenceStatus.PERSISTED_ONLY));
            returnedLessons.add(row.lessonId());
        }
        // 心跳尚未落库时 lesson_progress 不存在，仍把 Redis 中的最新课时投影返回给学生。
        for (String lessonId : course.lessonIds()) {
            if (returnedLessons.contains(lessonId)) continue;
            var read = progressBuffer.read(learnerId, courseId, lessonId);
            if (read.available() && read.value().isPresent()) {
                views.add(progress(read.value().orElseThrow(), false, LearningDtos.ProgressPersistenceStatus.BUFFERED));
            }
        }
        int completed = (int) views.stream().filter(LearningDtos.LessonProgress::completed).count();
        int lessonCount = course.lessonIds().size();
        double percent = lessonCount == 0 ? 0 : BigDecimal.valueOf(completed * 100.0 / lessonCount)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
        return new LearningDtos.CourseProgress(courseId, completed, lessonCount, percent, views);
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
        validateAnswerPublicationPolicy(input.type(), input.answerPublicationPolicy());
        validateAssessmentTiming(input.type(), input.opensAt(), input.closesAt(), input.latePolicy(),
                input.lateUntil(), input.maxAttempts(), input.timeLimitSeconds());
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
                input.type(), AssessmentStatus.DRAFT, input.gradePublicationPolicy(),
                canonicalAnswerPolicy(input.answerPublicationPolicy()), AssessmentCloseStatus.OPEN,
                AssessmentSettlementStatus.NOT_STARTED, null, 0, 0, null, null,
                ResultPublicationStatus.UNPUBLISHED, ResultPublicationStatus.UNPUBLISHED, null, null,
                input.opensAt(), input.closesAt(), input.timeLimitSeconds(), input.latePolicy(), input.lateUntil(),
                input.maxAttempts(), 0, null, null, now, now));
        repository.insertIdempotency(teacherId, requestId, fingerprint, "ASSESSMENT", id, now);
        return new AssessmentCreation(teacherAssessment(id, teacherId), true);
    }

    /** 仅对本人未发布草稿使用 revision 修改元数据，返回完整教师投影。 */
    @Transactional
    public LearningDtos.TeacherAssessment updateAssessment(String teacherId, String assessmentId,
            LearningDtos.UpdateAssessmentRequest input) {
        validateAnswerPublicationPolicy(input.type(), input.answerPublicationPolicy());
        validateAssessmentTiming(input.type(), input.opensAt(), input.closesAt(), input.latePolicy(),
                input.lateUntil(), input.maxAttempts(), input.timeLimitSeconds());
        repository.ownedAssessment(assessmentId, teacherId).orElseThrow(this::notFound);
        if (repository.updateAssessment(assessmentId, teacherId, input.expectedRevision(), input.title(),
                input.description(), input.type(), input.gradePublicationPolicy(), input.answerPublicationPolicy(),
                input.opensAt(), input.closesAt(), input.latePolicy(), input.lateUntil(), input.maxAttempts(),
                input.timeLimitSeconds(), Instant.now()) == 0) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评已发布、归档或被其他请求更新");
        }
        return teacherAssessment(assessmentId, teacherId);
    }

    /** 发布后只允许 owner 调整截止节点；新最终截止至少距离服务端当前时间一小时并写入审计。 */
    @Transactional
    public LearningDtos.TeacherAssessment updateAssessmentDeadline(String teacherId, String assessmentId,
            LearningDtos.UpdateAssessmentDeadlineRequest input, String traceId) {
        AssessmentRow assessment = repository.lockOwnedAssessment(assessmentId, teacherId);
        if (assessment == null) throw notFound();
        if (assessment.status() != AssessmentStatus.PUBLISHED
                || assessment.closeStatus() == AssessmentCloseStatus.CLOSED
                || assessment.gradePublicationStatus() == ResultPublicationStatus.PUBLISHED
                || assessment.answerPublicationStatus() == ResultPublicationStatus.PUBLISHED) {
            throw conflict("LEARNING.DEADLINE_UPDATE_NOT_ALLOWED", "已关闭、归档或已公布结果的测评不能调整截止时间");
        }
        Instant now = Instant.now();
        if (assessment.opensAt() != null && !assessment.opensAt().isBefore(input.closesAt())) {
            throw invalid("LEARNING.ASSESSMENT_WINDOW_INVALID", "截止时间必须晚于开放时间");
        }
        validateAssessmentTiming(assessment.type(), assessment.opensAt(), input.closesAt(), assessment.latePolicy(),
                input.lateUntil(), assessment.maxAttempts(), assessment.timeLimitSeconds());
        Instant finalDeadline = assessment.latePolicy() == LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL
                ? input.lateUntil() : input.closesAt();
        if (finalDeadline == null || finalDeadline.isBefore(now.plus(Duration.ofHours(1)))) {
            throw invalid("LEARNING.DEADLINE_TOO_SOON", "新的最终截止至少要晚于当前服务时间一小时");
        }
        if (assessment.revision() != input.expectedRevision()
                || repository.updateAssessmentDeadline(assessmentId, teacherId, input.expectedRevision(),
                        input.closesAt(), input.lateUntil(), now) == 0) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评 revision 已变化，请刷新后重试");
        }
        repository.reclassifyLateSubmissions(assessmentId, input.closesAt(), now);
        repository.insertDeadlineAudit(UUID.randomUUID().toString(), assessmentId, teacherId,
                input.expectedRevision(), assessment.closesAt(), input.closesAt(), assessment.lateUntil(),
                input.lateUntil(), input.reason(), traceId, now);
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
            if (assessment.type() == LearningDtos.AssessmentType.HOMEWORK
                    || assessment.type() == LearningDtos.AssessmentType.EXAM) {
                ensureAssessmentParticipants(assessment.courseId(), assessmentId, Instant.now());
            }
        }
        return teacherAssessment(assessmentId, teacherId);
    }

    /**
     * 不可撤销地关闭本人已发布测评。关闭事务冻结影响人数并立即阻断新 attempt/答案写入，
     * 草稿只由后续固定批量处理器按服务端最后成功保存内容结算。
     */
    @Transactional
    public LearningDtos.CloseAssessmentResult closeAssessment(String teacherId, String assessmentId,
            LearningDtos.CloseAssessmentRequest input) {
        AssessmentRow assessment = repository.lockOwnedAssessment(assessmentId, teacherId);
        if (assessment == null) throw notFound();
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(Map.of("assessmentId", assessmentId,
                "expectedRevision", input.expectedRevision(), "command", "CLOSE"));
        var replay = repository.idempotency(teacherId, requestId).orElse(null);
        if (replay != null) {
            idempotentResource(replay, fingerprint, "ASSESSMENT_CLOSE");
            LearningDtos.TeacherAssessment current = teacherAssessment(assessmentId, teacherId);
            return new LearningDtos.CloseAssessmentResult(current,
                    current.affectedInProgressAttemptCount(), true);
        }
        if (assessment.status() != AssessmentStatus.PUBLISHED) {
            throw invalid("LEARNING.ASSESSMENT_CLOSE_NOT_ALLOWED", "只有已发布测评可以结束整场");
        }
        if (assessment.closeStatus() == AssessmentCloseStatus.CLOSED) {
            throw conflict("LEARNING.ASSESSMENT_ALREADY_CLOSED", "测评已经结束且不可撤回");
        }
        if (assessment.revision() != input.expectedRevision()) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评 revision 已变化，请刷新后重试");
        }
        int affected = repository.countDraftAttempts(assessmentId);
        Instant now = Instant.now();
        ensureAssessmentParticipants(assessment.courseId(), assessmentId, now);
        if (repository.closeAssessment(assessmentId, teacherId, input.expectedRevision(), affected, now) == 0) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评状态或 revision 已变化");
        }
        repository.insertIdempotency(teacherId, requestId, fingerprint, "ASSESSMENT_CLOSE", assessmentId, now);
        if (affected == 0) {
            repository.markUnsubmittedParticipantsMissing(assessmentId, now);
            evaluateAutomaticPublications(assessmentId, now);
        }
        return new LearningDtos.CloseAssessmentResult(teacherAssessment(assessmentId, teacherId), affected, false);
    }

    /** 手动公布成绩；只接受配置为 MANUAL 且已关闭、已结算、无待批内容的测评。 */
    @Transactional
    public LearningDtos.AssessmentPublicationCommandResult publishGrades(String teacherId, String assessmentId,
            LearningDtos.PublishAssessmentResultRequest input) {
        return publishResultFact(teacherId, assessmentId, input, true);
    }

    /** 手动公布参考答案/解析；必须已关闭并完成草稿结算，但不要求主观题批改完成。 */
    @Transactional
    public LearningDtos.AssessmentPublicationCommandResult publishAnswers(String teacherId, String assessmentId,
            LearningDtos.PublishAssessmentResultRequest input) {
        return publishResultFact(teacherId, assessmentId, input, false);
    }

    /** 校验 owner、状态、策略、资格、幂等与 revision 后，只把对应公布事实从未公布推进为已公布。 */
    private LearningDtos.AssessmentPublicationCommandResult publishResultFact(String teacherId,
            String assessmentId, LearningDtos.PublishAssessmentResultRequest input, boolean gradeCommand) {
        AssessmentRow assessment = repository.lockOwnedAssessment(assessmentId, teacherId);
        if (assessment == null) throw notFound();
        String type = gradeCommand ? "GRADE_PUBLICATION" : "ANSWER_PUBLICATION";
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(Map.of("assessmentId", assessmentId,
                "expectedRevision", input.expectedRevision(), "command", type));
        var replay = repository.idempotency(teacherId, requestId).orElse(null);
        if (replay != null) {
            idempotentResource(replay, fingerprint, type);
            return new LearningDtos.AssessmentPublicationCommandResult(
                    teacherAssessment(assessmentId, teacherId), true);
        }
        if (assessment.revision() != input.expectedRevision()) {
            throw conflict("LEARNING.ASSESSMENT_REVISION_CONFLICT", "测评 revision 已变化，请刷新后重试");
        }
        if (assessment.status() != AssessmentStatus.PUBLISHED) {
            throw invalid("LEARNING.RESULT_PUBLICATION_NOT_ALLOWED", "只有仍处于已发布状态的测评可以公布结果");
        }
        if (assessment.closeStatus() != AssessmentCloseStatus.CLOSED) {
            throw invalid("LEARNING.ASSESSMENT_NOT_CLOSED", "整场结束后才能公布结果");
        }
        AssessmentProjectionCounts counts = repository.assessmentProjectionCounts(assessmentId);
        if (counts.activeAttempts() != 0 || assessment.settlementStatus() != AssessmentSettlementStatus.COMPLETED) {
            throw invalid("LEARNING.ASSESSMENT_SETTLEMENT_PENDING", "仍有草稿待结算，暂不能公布");
        }
        Instant now = Instant.now();
        if (gradeCommand) {
            if (assessment.gradePolicy() != GradePublicationPolicy.MANUAL) {
                throw invalid("LEARNING.GRADE_PUBLICATION_POLICY_INVALID", "当前测评配置为自动公布成绩");
            }
            if (counts.pendingManualAnswers() != 0) {
                throw invalid("LEARNING.MANUAL_GRADING_PENDING", "仍有内容待人工批改，暂不能公布成绩");
            }
            if (assessment.gradePublicationStatus() == ResultPublicationStatus.PUBLISHED
                    || repository.publishGrades(assessmentId, input.expectedRevision(), now) == 0) {
                throw conflict("LEARNING.GRADE_ALREADY_PUBLISHED", "成绩已经公布或 revision 已变化");
            }
        } else {
            if (assessment.answerPolicy() != AnswerPublicationPolicy.MANUAL_AFTER_CLOSE) {
                throw invalid("LEARNING.ANSWER_PUBLICATION_POLICY_INVALID", "当前参考答案策略不允许手动公布");
            }
            if (assessment.answerPublicationStatus() == ResultPublicationStatus.PUBLISHED
                    || repository.publishAnswers(assessmentId, input.expectedRevision(), now) == 0) {
                throw conflict("LEARNING.ANSWER_ALREADY_PUBLISHED", "参考答案已经公布或 revision 已变化");
            }
        }
        repository.insertIdempotency(teacherId, requestId, fingerprint, type, assessmentId, now);
        return new LearningDtos.AssessmentPublicationCommandResult(teacherAssessment(assessmentId, teacherId), false);
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
                summary.title(), summary.type(), summary.status(), summary.closeStatus(), summary.settlementStatus(),
                summary.gradePublicationPolicy(), summary.answerPublicationPolicy(),
                summary.gradePublicationStatus(), summary.answerPublicationStatus(), summary.closedAt(),
                summary.affectedInProgressAttemptCount(), summary.activeAttemptCount(),
                summary.pendingSettlementCount(), summary.pendingManualGradingAttemptCount(),
                summary.pendingManualGradingAnswerCount(), summary.gradePublishedAt(), summary.answerPublishedAt(),
                summary.gradePublicationEligible(), summary.answerPublicationEligible(), summary.resultsReady(),
                summary.opensAt(), summary.closesAt(), summary.timeLimitSeconds(), summary.latePolicy(),
                summary.lateUntil(), summary.maxAttempts(), summary.questionCount(),
                summary.totalPoints(), summary.revision(), summary.publishedAt(),
                summary.createdAt(), summary.updatedAt(), row.description(), items.stream().map(item ->
                    new LearningDtos.TeacherQuestion(item.id(), item.questionVersionId(), item.sequence(),
                            item.points(), item.snapshot())).toList());
    }

    /** 返回刷新可恢复的参与状态；attempt ID/状态只投影当前学习者自己的唯一作答。 */
    public LearningDtos.LearnerAssessmentPage learnerAssessments(String learnerId, String courseId, int page, int size) {
        Instant now = Instant.now();
        List<LearningDtos.LearnerAssessmentSummary> values = repository
                .listLearnerAssessments(learnerId, courseId, page, size).stream()
                .map(row -> learnerSummary(row, repository.attemptForLearner(row.id(), learnerId).orElse(null), now))
                .toList();
        return new LearningDtos.LearnerAssessmentPage(values, page, size,
                repository.countLearnerAssessments(learnerId, courseId), now);
    }

    /** 学生模型从类型上移除了答案、解析和评分规则，发布策略尚未满足时无法序列化泄漏。 */
    public LearningDtos.LearnerAssessment learnerAssessment(String learnerId, String assessmentId) {
        AssessmentRow row = publishedForLearner(learnerId, assessmentId);
        Instant now = Instant.now();
        requireAssessmentOpen(row, now);
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(assessmentId);
        LearningDtos.AssessmentSummary summary = summary(row, items);
        List<LearningDtos.LearnerQuestion> safe = items.stream().map(this::learnerQuestion).toList();
        return new LearningDtos.LearnerAssessment(summary.id(), summary.courseId(), summary.lessonId(),
                summary.title(), summary.type(), summary.status(), summary.closeStatus(), summary.settlementStatus(),
                summary.gradePublicationPolicy(), summary.answerPublicationPolicy(),
                summary.gradePublicationStatus(), summary.answerPublicationStatus(), summary.closedAt(),
                summary.affectedInProgressAttemptCount(), summary.activeAttemptCount(),
                summary.pendingSettlementCount(), summary.pendingManualGradingAttemptCount(),
                summary.pendingManualGradingAnswerCount(), summary.gradePublishedAt(), summary.answerPublishedAt(),
                summary.gradePublicationEligible(), summary.answerPublicationEligible(), summary.resultsReady(),
                summary.opensAt(), summary.closesAt(), summary.timeLimitSeconds(), summary.latePolicy(),
                summary.lateUntil(), summary.maxAttempts(), summary.questionCount(),
                summary.totalPoints(), summary.revision(), summary.publishedAt(),
                summary.createdAt(), summary.updatedAt(), row.description(), safe, now,
                attemptSummary(repository.attemptForLearner(assessmentId, learnerId).orElse(null),
                        row.gradePublicationStatus() == ResultPublicationStatus.PUBLISHED));
    }

    /** 为 T11C.2 返回课程级任务清单；未开放课时任务只有在显式当前课时匹配时才可见。 */
    public LearningDtos.LearnerCourseTaskPage learnerCourseTasks(String learnerId, String courseId,
            String currentLessonId, int page, int size, String traceId) {
        CourseFact course = requireEnrolledCourse(learnerId, courseId, traceId);
        Instant now = Instant.now();
        List<LearningDtos.LearnerCourseTaskSummary> all = repository.publishedCourseAssessments(courseId).stream()
                .map(assessment -> taskSummary(learnerId, assessment, currentLessonId, now))
                .filter(value -> value != null).toList();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new LearningDtos.LearnerCourseTaskPage(all.subList(from, to), page, size, all.size(), now);
    }

    /** 返回单场任务安全投影；未开放时不返回题目快照，错课时/错课程/非应交人统一隐藏。 */
    public LearningDtos.LearnerCourseTask learnerCourseTask(String learnerId, String courseId,
            String assessmentId, String currentLessonId, String traceId) {
        requireEnrolledCourse(learnerId, courseId, traceId);
        AssessmentRow assessment = learnerTaskAssessment(learnerId, courseId, assessmentId);
        Instant now = Instant.now();
        LearningDtos.LearnerCourseTaskSummary summary = taskSummary(learnerId, assessment, currentLessonId, now);
        if (summary == null) throw notFound();
        List<LearningDtos.LearnerQuestion> safe = summary.questionContentAvailable()
                ? repository.assessmentQuestions(assessmentId).stream().map(this::learnerQuestion).toList()
                : List.of();
        return new LearningDtos.LearnerCourseTask(assessment.id(), assessment.courseId(), assessment.lessonId(),
                assessment.title(), assessment.type(), summary.timeState(), summary.submissionStatus(),
                summary.gradingStatus(), summary.lateSubmission(), summary.attemptsUsed(), summary.maxAttempts(),
                assessment.opensAt(), assessment.closesAt(), assessment.lateUntil(), summary.lastSubmittedAt(),
                assessment.description(), safe, now, summary.finalAttempt());
    }

    /** T11C.2 的稳定开始入口只绑定 courseId/assessmentId，实际写入仍复用既有 attempt 状态机。 */
    @Transactional
    public LearningDtos.AttemptDraft startCourseTaskAttempt(String learnerId, String courseId, String assessmentId,
            LearningDtos.StartAttemptRequest input, String traceId) {
        requireEnrolledCourse(learnerId, courseId, traceId);
        learnerTaskAssessment(learnerId, courseId, assessmentId);
        return startAttempt(learnerId, assessmentId, input);
    }

    /** 只解析 participant.finalAttemptId，避免客户端通过旧 attemptId 切换历史提交。 */
    public LearningDtos.AttemptResult learnerCourseTaskResult(String learnerId, String courseId, String assessmentId,
            String traceId) {
        requireEnrolledCourse(learnerId, courseId, traceId);
        learnerTaskAssessment(learnerId, courseId, assessmentId);
        AttemptRow finalAttempt = repository.finalAttemptForLearner(assessmentId, learnerId).orElseThrow(this::notFound);
        return result(finalAttempt.id(), learnerId, false);
    }

    private CourseFact requireEnrolledCourse(String learnerId, String courseId, String traceId) {
        CourseFact course = courses.requirePublished(courseId, traceId);
        if (repository.enrollment(learnerId, courseId).filter(value -> value.status() == EnrollmentStatus.ACTIVE).isEmpty()) {
            throw new QuestionException(HttpStatus.FORBIDDEN, "LEARNING.ENROLLMENT_REQUIRED", "请先报名课程");
        }
        return course;
    }

    private AssessmentRow learnerTaskAssessment(String learnerId, String courseId, String assessmentId) {
        AssessmentRow assessment = repository.assessment(assessmentId).orElseThrow(this::notFound);
        if (assessment.status() != AssessmentStatus.PUBLISHED || !assessment.courseId().equals(courseId)
                || !repository.isAssignedToCourse(assessmentId, courseId)
                || (assessment.type() != LearningDtos.AssessmentType.HOMEWORK
                        && assessment.type() != LearningDtos.AssessmentType.EXAM)
                || repository.participant(assessmentId, learnerId).isEmpty()) throw notFound();
        return assessment;
    }

    private LearningDtos.LearnerCourseTaskSummary taskSummary(String learnerId, AssessmentRow assessment,
            String currentLessonId, Instant now) {
        LearningDtos.AssessmentTimeState timeState = timeState(assessment, now);
        if (timeState == LearningDtos.AssessmentTimeState.NOT_OPEN
                && (assessment.lessonId() == null || !assessment.lessonId().equals(currentLessonId))) return null;
        var participant = repository.participant(assessment.id(), learnerId).orElse(null);
        if (participant == null) return null;
        AttemptRow latest = repository.attemptForLearner(assessment.id(), learnerId).orElse(null);
        AttemptRow finalAttempt = repository.finalAttemptForLearner(assessment.id(), learnerId).orElse(null);
        AttemptRow projected = finalAttempt != null ? finalAttempt : latest;
        boolean content = timeState != LearningDtos.AssessmentTimeState.NOT_OPEN;
        boolean finalProjection = finalAttempt != null;
        return new LearningDtos.LearnerCourseTaskSummary(assessment.id(), assessment.courseId(), assessment.lessonId(),
                assessment.title(), assessment.type(), timeState, participant.submissionStatus(),
                participant.gradingStatus(), participant.lateSubmission(), participant.attemptsUsed(),
                assessment.maxAttempts(), assessment.opensAt(), assessment.closesAt(), assessment.lateUntil(),
                participant.lastSubmittedAt(), attemptSummary(projected,
                        assessment.gradePublicationStatus() == ResultPublicationStatus.PUBLISHED, finalProjection), content);
    }

    private static LearningDtos.AssessmentTimeState timeState(AssessmentRow assessment, Instant now) {
        if (assessment.closeStatus() == AssessmentCloseStatus.CLOSED) return LearningDtos.AssessmentTimeState.CLOSED;
        if (assessment.opensAt() != null && now.isBefore(assessment.opensAt())) return LearningDtos.AssessmentTimeState.NOT_OPEN;
        if (assessment.type() == LearningDtos.AssessmentType.HOMEWORK
                && assessment.latePolicy() == LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL
                && assessment.closesAt() != null && !now.isBefore(assessment.closesAt())
                && assessment.lateUntil() != null && now.isBefore(assessment.lateUntil())) {
            return LearningDtos.AssessmentTimeState.LATE_WINDOW;
        }
        Instant finalDeadline = assessment.type() == LearningDtos.AssessmentType.HOMEWORK
                && assessment.latePolicy() == LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL
                        ? assessment.lateUntil() : assessment.closesAt();
        return finalDeadline != null && !now.isBefore(finalDeadline)
                ? LearningDtos.AssessmentTimeState.CLOSED : LearningDtos.AssessmentTimeState.OPEN;
    }

    /** 查询教师成绩册矩阵；未统一完成的测评只返回安全状态和提交事实，不返回任何分数。 */
    @Transactional(readOnly = true)
    public LearningDtos.Gradebook teacherGradebook(String teacherId, String courseId, String assessmentId,
        LearningDtos.AssessmentType type, LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword, int page, int size,
            LearningDtos.GradebookSortField sort, boolean descending, String traceId) {
        courses.requireOwnedPublished(courseId, teacherId, traceId);
        return buildGradebook(teacherId, courseId, assessmentId, type, submissionStatus, gradingStatus, late, keyword,
                page, size, sort, descending, false, traceId);
    }

    /** 按学生批次流式生成成绩册宽表 CSV；输出端由 HTTP 层持有，服务端不构造完整 byte[]。 */
    @Transactional
    public void streamGradebook(String teacherId, String courseId, String assessmentId,
            LearningDtos.AssessmentType type, LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword,
            UUID clientRequestId, String traceId, OutputStream output) {
        courses.requireOwnedPublished(courseId, teacherId, traceId);
        GradebookContext context = loadGradebookContext(teacherId, courseId, assessmentId, type, submissionStatus,
                gradingStatus, late, keyword, LearningDtos.GradebookSortField.STUDENT_NUMBER, false, true, traceId);
        if (!context.ready()) {
            throw invalid("LEARNING.GRADEBOOK_EXPORT_NOT_READY", "存在待结算或待批改测评，不能导出不完整成绩");
        }
        String filterJson = gradebookFilterJson(assessmentId, type, submissionStatus, gradingStatus, late, keyword);
        repository.insertGradebookExportAudit(UUID.randomUUID().toString(), clientRequestId.toString(), teacherId,
                courseId, filterJson, (int) context.total(), context.assessments().size(), traceId, Instant.now());
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writeCsvHeader(writer, context.assessments());
            int page = 1;
            long written = 0;
            while (written < context.total()) {
                List<LearningAssessmentRepository.CourseStudentNumberRow> numbers = repository.gradebookStudentNumbers(
                        courseId, context.assessmentIds(), context.submissionStatus(), context.gradingStatus(),
                        context.late(), context.keyword(), context.keywordLearnerIds(), page, 250,
                        LearningDtos.GradebookSortField.STUDENT_NUMBER, false);
                if (numbers.isEmpty()) break;
                writeCsvRows(writer, context, numbers, traceId);
                written += numbers.size();
                page++;
                writer.flush();
            }
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("gradebook CSV stream failed", failure);
        }
    }

    private LearningDtos.Gradebook buildGradebook(String teacherId, String courseId, String assessmentId,
            LearningDtos.AssessmentType type, LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword, int page, int size,
            LearningDtos.GradebookSortField sort, boolean descending, boolean export, String traceId) {
        GradebookContext context = loadGradebookContext(teacherId, courseId, assessmentId, type, submissionStatus,
                gradingStatus, late, keyword, sort, descending, export, traceId);
        List<LearningAssessmentRepository.CourseStudentNumberRow> numbers = repository.gradebookStudentNumbers(courseId,
                context.assessmentIds(), submissionStatus, gradingStatus, late, keyword,
                context.keywordLearnerIds(), page, size, sort, descending);
        List<LearningDtos.GradebookRow> rows = buildGradebookRows(context, numbers, traceId);
        boolean nicknameDependencyFailed = rows.stream().anyMatch(LearningDtos.GradebookRow::nicknameDependencyFailed);
        return new LearningDtos.Gradebook(courseId,
                context.ready() ? LearningDtos.GradebookAggregateStatus.READY
                        : LearningDtos.GradebookAggregateStatus.PENDING,
                context.assessmentProjections(), rows, context.homeworkAverage(), context.examAverage(),
                nicknameDependencyFailed, page, size, context.total());
    }

    /** 只读取测评元数据和数据库聚合，成绩册分页/导出行由调用方按批次读取。 */
    private GradebookContext loadGradebookContext(String teacherId, String courseId, String assessmentId,
            LearningDtos.AssessmentType type, LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword,
            LearningDtos.GradebookSortField sort, boolean descending, boolean export, String traceId) {
        if (type == LearningDtos.AssessmentType.PRACTICE) {
            throw invalid("LEARNING.GRADEBOOK_TYPE_INVALID", "PRACTICE 不属于正式成绩册");
        }
        List<AssessmentRow> assessments = repository.teacherGradebookAssessments(courseId, teacherId).stream()
                .filter(value -> assessmentId == null || value.id().equals(assessmentId))
                .filter(value -> type == null || value.type() == type).toList();
        if (assessmentId != null && assessments.stream().noneMatch(value -> value.id().equals(assessmentId))) {
            throw notFound();
        }
        long numberCount = repository.countCourseStudentNumbers(courseId);
        if (export && numberCount > 5000) {
            throw invalid("LEARNING.GRADEBOOK_EXPORT_TOO_LARGE", "单课程导出最多支持 5000 名学生");
        }
        if (assessments.size() > 100) {
            throw invalid(export ? "LEARNING.GRADEBOOK_EXPORT_TOO_LARGE" : "LEARNING.GRADEBOOK_TOO_LARGE",
                    "单次最多支持 100 场正式测评");
        }
        if (sort == LearningDtos.GradebookSortField.SCORE
                && assessments.stream().anyMatch(value -> !assessmentReady(value))) {
            throw invalid("LEARNING.GRADEBOOK_SCORE_SORT_NOT_READY", "成绩尚未统一可见，不能按隐藏成绩排序");
        }
        KeywordProjection keywordProjection = resolveKeywordProjection(courseId, keyword, traceId);
        List<String> assessmentIds = assessments.stream().map(AssessmentRow::id).toList();
        Map<String, LearningAssessmentRepository.GradebookAggregateRow> aggregates =
                repository.gradebookAggregates(assessmentIds);
        List<LearningDtos.GradebookAssessment> projections = assessments.stream()
                .map(value -> gradebookAssessment(value, aggregates.get(value.id()))).toList();
        boolean ready = projections.stream().allMatch(LearningDtos.GradebookAssessment::resultsReady);
        if (assessments.isEmpty()) ready = true;
        Map<LearningDtos.AssessmentType, LearningAssessmentRepository.GradebookTypeAverageRow> typeAverages =
                ready ? repository.gradebookTypeAverages(courseId, assessmentIds) : Map.of();
        long total = repository.countGradebookStudentNumbers(courseId, assessmentIds, submissionStatus, gradingStatus,
                late, keyword, keywordProjection.learnerIds());
        BigDecimal homeworkAverage = averageType(typeAverages.get(LearningDtos.AssessmentType.HOMEWORK));
        BigDecimal examAverage = averageType(typeAverages.get(LearningDtos.AssessmentType.EXAM));
        return new GradebookContext(assessments, assessmentIds, aggregates, projections, ready, total,
                submissionStatus, gradingStatus, late, keyword, keywordProjection.learnerIds(),
                keywordProjection.nicknames(), homeworkAverage, examAverage);
    }

    /**
     * 解析成绩册关键词。无关键词时不读取页外身份；有关键词时只读取最多 5,000 个课程编号和一次 IAM
     * 昵称投影，得到候选 learnerId 后再让数据库完成状态筛选、排序、分页和总数统计，避免加载测评矩阵。
     */
    private KeywordProjection resolveKeywordProjection(String courseId, String keyword, String traceId) {
        if (keyword == null || keyword.isBlank()) return new KeywordProjection(null, null);
        List<LearningAssessmentRepository.CourseStudentNumberRow> numbers = repository.courseStudentNumbers(courseId,
                1, 5_000);
        List<String> learnerIds = numbers.stream()
                .map(LearningAssessmentRepository.CourseStudentNumberRow::learnerId).toList();
        LearnerDirectoryClient.NicknameLookup nicknames = directory.nicknames(learnerIds, traceId);
        List<String> candidates = numbers.stream()
                .filter(number -> formatStudentNumber(number.studentNumber()).contains(keyword)
                        || contains(nicknames.values().get(number.learnerId()), keyword))
                .map(LearningAssessmentRepository.CourseStudentNumberRow::learnerId).toList();
        return new KeywordProjection(candidates, nicknames);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }


    private static boolean assessmentReady(AssessmentRow assessment) {
        return assessment.closeStatus() == AssessmentCloseStatus.CLOSED
                && assessment.settlementStatus() == AssessmentSettlementStatus.COMPLETED;
    }

    /** 把数据库聚合转换为成绩册公开统计；未就绪测评永远不返回分数统计。 */
    private LearningDtos.GradebookAssessment gradebookAssessment(AssessmentRow assessment,
            LearningAssessmentRepository.GradebookAggregateRow aggregate) {
        int expected = aggregate == null ? 0 : aggregate.expectedCount();
        int missing = aggregate == null ? 0 : aggregate.missingCount();
        int pending = aggregate == null ? 0 : aggregate.pendingCount();
        boolean ready = assessmentReady(assessment) && pending == 0;
        AssessmentStatsValues values = ready
                ? assessmentStats(aggregate)
                : new AssessmentStatsValues(null, null, expected, null, null, missing, pending, null, null, Map.of());
        return new LearningDtos.GradebookAssessment(assessment.id(), assessment.title(), assessment.type(),
                assessment.closeStatus(), assessment.settlementStatus(),
                ready ? LearningDtos.GradebookAggregateStatus.READY : LearningDtos.GradebookAggregateStatus.PENDING,
                expected, missing, pending, ready,
                new LearningDtos.AssessmentStats(values.gradedAverage(), values.allAverage(), values.expectedCount(),
                        values.onTimeRate(), values.finalRate(), values.missingCount(), values.pendingCount(),
                        values.highest(), values.lowest(), values.distribution()));
    }

    private AssessmentStatsValues assessmentStats(LearningAssessmentRepository.GradebookAggregateRow aggregate) {
        BigDecimal scoreSum = aggregate.scoreSum() == null ? BigDecimal.ZERO : aggregate.scoreSum();
        BigDecimal gradedAverage = aggregate.scoredCount() == 0 ? null
                : scoreSum.divide(BigDecimal.valueOf(aggregate.scoredCount()), 2, RoundingMode.HALF_UP);
        int allCount = aggregate.scoredCount() + aggregate.missingCount();
        BigDecimal allAverage = allCount == 0 ? null
                : scoreSum.divide(BigDecimal.valueOf(allCount), 2, RoundingMode.HALF_UP);
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("0-59", aggregate.band0To59() + aggregate.missingCount());
        distribution.put("60-69", aggregate.band60To69());
        distribution.put("70-79", aggregate.band70To79());
        distribution.put("80-89", aggregate.band80To89());
        distribution.put("90-100", aggregate.band90To100());
        return new AssessmentStatsValues(gradedAverage, allAverage, aggregate.expectedCount(),
                ratio(aggregate.onTimeCount(), aggregate.expectedCount()),
                ratio(aggregate.submittedCount(), aggregate.expectedCount()), aggregate.missingCount(), 0,
                aggregate.highestScore(), aggregate.lowestScore(), distribution);
    }

    /** 当前页才读取昵称、参与者和最终 attempt；页外学生只参与数据库总数/统计聚合。 */
    private List<LearningDtos.GradebookRow> buildGradebookRows(GradebookContext context,
            List<LearningAssessmentRepository.CourseStudentNumberRow> numbers, String traceId) {
        List<String> learnerIds = numbers.stream()
                .map(LearningAssessmentRepository.CourseStudentNumberRow::learnerId).toList();
        LearnerDirectoryClient.NicknameLookup nicknames = context.keywordNicknames() == null
                ? directory.nicknames(learnerIds, traceId) : context.keywordNicknames();
        Map<String, Map<String, LearningAssessmentRepository.GradebookParticipantRow>> cellsByLearner = new HashMap<>();
        for (LearningAssessmentRepository.GradebookParticipantRow row : repository.gradebookParticipantRows(
                context.assessmentIds(), learnerIds)) {
            cellsByLearner.computeIfAbsent(row.participant().learnerId(), ignored -> new LinkedHashMap<>())
                    .put(row.participant().assessmentId(), row);
        }
        List<LearningDtos.GradebookRow> result = new ArrayList<>(numbers.size());
        for (var number : numbers) {
            Map<String, LearningDtos.GradebookCell> cells = new LinkedHashMap<>();
            Map<String, LearningAssessmentRepository.GradebookParticipantRow> learnerCells =
                    cellsByLearner.getOrDefault(number.learnerId(), Map.of());
            for (AssessmentRow assessment : context.assessments()) {
                LearningAssessmentRepository.GradebookParticipantRow cell = learnerCells.get(assessment.id());
                boolean ready = context.assessmentProjections().stream()
                        .filter(value -> value.assessmentId().equals(assessment.id()))
                        .findFirst().map(LearningDtos.GradebookAssessment::resultsReady).orElse(false);
                cells.put(assessment.id(), gradebookCell(cell, ready));
            }
            BigDecimal homeworkAverage = context.ready()
                    ? typeAverage(cells, context.assessments(), LearningDtos.AssessmentType.HOMEWORK) : null;
            BigDecimal examAverage = context.ready()
                    ? typeAverage(cells, context.assessments(), LearningDtos.AssessmentType.EXAM) : null;
            String nickname = nicknames.values().get(number.learnerId());
            result.add(new LearningDtos.GradebookRow(number.learnerId(), formatStudentNumber(number.studentNumber()),
                    nickname, nickname != null, nicknames.dependencyFailed(), cells, homeworkAverage, examAverage));
        }
        return result;
    }

    private static LearningDtos.GradebookCell gradebookCell(
            LearningAssessmentRepository.GradebookParticipantRow row, boolean ready) {
        LearningAssessmentRepository.ParticipantRow participant = row == null ? null : row.participant();
        if (participant == null || !"ACTIVE".equals(participant.enrollmentStatus())) {
            return new LearningDtos.GradebookCell(null, participant == null ? null : participant.submissionStatus(),
                    participant == null ? null : participant.gradingStatus(), false, null, null);
        }
        BigDecimal score = ready && participant.gradingStatus() == LearningDtos.AssessmentGradingStatus.GRADED
                && row.finalScore() != null ? percent(row.finalScore(), row.finalTotalPoints())
                : (ready && participant.submissionStatus() == LearningDtos.AssessmentSubmissionStatus.MISSING
                        ? BigDecimal.ZERO : null);
        return new LearningDtos.GradebookCell(score, participant.submissionStatus(), participant.gradingStatus(),
                participant.lateSubmission(), row.finalAttemptId(), participant.learnerId());
    }

    private static BigDecimal typeAverage(Map<String, LearningDtos.GradebookCell> cells,
            List<AssessmentRow> assessments, LearningDtos.AssessmentType type) {
        List<BigDecimal> values = assessments.stream().filter(value -> value.type() == type)
                .map(value -> cells.get(value.id())).filter(value -> value != null && value.scorePercent() != null)
                .map(LearningDtos.GradebookCell::scorePercent).toList();
        return average(values);
    }

    private static BigDecimal averageType(LearningAssessmentRepository.GradebookTypeAverageRow value) {
        if (value == null || value.scoreCount() == 0 || value.scoreSum() == null) return null;
        return value.scoreSum().divide(BigDecimal.valueOf(value.scoreCount()), 2, RoundingMode.HALF_UP);
    }

    private String gradebookFilterJson(String assessmentId, LearningDtos.AssessmentType type,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (assessmentId != null) filters.put("assessmentId", assessmentId);
        if (type != null) filters.put("type", type.name());
        if (submissionStatus != null) filters.put("submissionStatus", submissionStatus.name());
        if (gradingStatus != null) filters.put("gradingStatus", gradingStatus.name());
        if (late != null) filters.put("late", late);
        if (keyword != null) filters.put("keyword", keyword);
        try {
            return mapper.writeValueAsString(filters);
        } catch (Exception failure) {
            throw new IllegalStateException("gradebook filter serialization failed", failure);
        }
    }

    /** 流式输出 CSV 表头和当前学生批次，批次结束后立即释放行级 DTO。 */
    private void writeCsvRows(BufferedWriter writer, GradebookContext context,
            List<LearningAssessmentRepository.CourseStudentNumberRow> numbers, String traceId) throws IOException {
        for (LearningDtos.GradebookRow row : buildGradebookRows(context, numbers, traceId)) {
            writer.write(csvCell(row.courseStudentNumber()));
            writer.write(',');
            writer.write(csvCell(row.nickname()));
            for (AssessmentRow assessment : context.assessments()) {
                LearningDtos.GradebookCell cell = row.assessments().get(assessment.id());
                boolean blankCell = cell == null || cell.learnerId() == null;
                writer.write(',');
                writer.write(csvCell(blankCell || cell.scorePercent() == null ? null : decimal(cell.scorePercent())));
                writer.write(',');
                writer.write(csvCell(blankCell || cell.submissionStatus() == null ? null : cell.submissionStatus().name()));
                writer.write(',');
                writer.write(csvCell(blankCell ? null : Boolean.toString(cell.lateSubmission())));
            }
            writer.write(',');
            writer.write(csvCell(decimal(row.homeworkAverage())));
            writer.write(',');
            writer.write(csvCell(decimal(row.examAverage())));
            writer.newLine();
        }
    }

    private static void writeCsvHeader(BufferedWriter writer, List<AssessmentRow> assessments) throws IOException {
        writer.write("\uFEFF");
        writer.write(csvCell("课程学生编号"));
        writer.write(',');
        writer.write(csvCell("昵称"));
        for (AssessmentRow assessment : assessments) {
            String label = assessment.id() + " " + assessment.title();
            writer.write(',');
            writer.write(csvCell(label + " 成绩"));
            writer.write(',');
            writer.write(csvCell(label + " 提交状态"));
            writer.write(',');
            writer.write(csvCell(label + " 是否迟交"));
        }
        writer.write(',');
        writer.write(csvCell("作业平均分"));
        writer.write(',');
        writer.write(csvCell("考试平均分"));
        writer.newLine();
    }

    private record AssessmentStatsValues(BigDecimal gradedAverage, BigDecimal allAverage, int expectedCount,
            BigDecimal onTimeRate, BigDecimal finalRate, int missingCount, int pendingCount, BigDecimal highest,
            BigDecimal lowest, Map<String, Integer> distribution) {}

    private record GradebookContext(List<AssessmentRow> assessments, List<String> assessmentIds,
            Map<String, LearningAssessmentRepository.GradebookAggregateRow> aggregates,
            List<LearningDtos.GradebookAssessment> assessmentProjections, boolean ready, long total,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword,
            List<String> keywordLearnerIds, LearnerDirectoryClient.NicknameLookup keywordNicknames,
            BigDecimal homeworkAverage, BigDecimal examAverage) {}

    private record KeywordProjection(List<String> learnerIds, LearnerDirectoryClient.NicknameLookup nicknames) {}

    private static BigDecimal percent(BigDecimal score, BigDecimal total) {
        if (score == null || total == null || total.signum() == 0) return null;
        return score.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator * 100L)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private static String distributionBand(BigDecimal value) {
        int score = value.setScale(0, RoundingMode.DOWN).intValue();
        return score < 60 ? "0-59" : score < 70 ? "60-69" : score < 80 ? "70-79"
                : score < 90 ? "80-89" : "90-100";
    }

    private static String formatStudentNumber(int number) { return String.format(Locale.ROOT, "%04d", number); }
    private static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static String csvCell(String value) {
        if (value == null) return "";
        String safe = value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    /** 为本人开始一次已分配的测评作答；HOMEWORK 每次正式提交后才允许创建下一次不可变 attempt。 */
    @Transactional
    public LearningDtos.AttemptDraft startAttempt(String learnerId, String assessmentId,
            LearningDtos.StartAttemptRequest input) {
        publishedForLearner(learnerId, assessmentId);
        AssessmentRow assessment = repository.lockAssessment(assessmentId);
        if (assessment == null) throw notFound();
        Instant now = Instant.now();
        requireAssessmentOpen(assessment, now);
        var participant = repository.lockParticipant(assessmentId, learnerId);
        if (participant == null || !"ACTIVE".equals(participant.enrollmentStatus())) throw notFound();
        AttemptRow existing = repository.attemptForLearner(assessmentId, learnerId).orElse(null);
        if (existing != null) {
            if (existing.status() == AttemptStatus.DRAFT) {
                requireBeforeDeadline(existing, now);
                repository.updateParticipantOnDraft(assessmentId, learnerId, participant.attemptsUsed(),
                        participant.finalAttemptId(), now);
                return draft(existing, now);
            }
            if (assessment.type() != LearningDtos.AssessmentType.HOMEWORK
                    || existing.attemptSequence() >= assessment.maxAttempts()) {
                throw conflict("LEARNING.ATTEMPT_ALREADY_SUBMITTED", "试卷已提交，不能重新开始");
            }
        }
        int sequence = repository.latestAttemptSequence(assessmentId, learnerId) + 1;
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(assessmentId);
        Instant deadline = effectiveDeadline(assessment, now);
        AttemptRow created = new AttemptRow(UUID.randomUUID().toString(), assessmentId, learnerId,
                AttemptStatus.DRAFT, 0, null, null, null, null, null, total(items), now, deadline,
                null, null, null, sequence, false, now, now);
        try {
            repository.insertAttempt(created);
        } catch (DuplicateKeyException concurrent) {
            created = repository.attemptForLearner(assessmentId, learnerId).orElseThrow();
        }
        repository.updateParticipantOnDraft(assessmentId, learnerId, participant.attemptsUsed(),
                participant.finalAttemptId(), now);
        return draft(created, now);
    }

    /** 按 owner 恢复可写草稿；超时或已提交时不会伪造可继续编辑状态。 */
    public LearningDtos.AttemptDraft getDraft(String learnerId, String attemptId) {
        AttemptRow attempt = repository.ownedAttempt(attemptId, learnerId).orElseThrow(this::notFound);
        if (attempt.status() != AttemptStatus.DRAFT) {
            throw conflict("LEARNING.ATTEMPT_ALREADY_SUBMITTED", "试卷已提交，请查看结果");
        }
        AssessmentRow assessment = repository.assessment(attempt.assessmentId()).orElseThrow(this::notFound);
        requireAssessmentAcceptsAnswers(assessment);
        Instant now = Instant.now();
        requireBeforeDeadline(attempt, now);
        return draft(attempt, now);
    }

    /** 使用 clientRequestId 和 expectedRevision 覆盖保存本人草稿，冲突时不覆盖新数据。 */
    @Transactional
    public LearningDtos.AttemptDraft saveDraft(String learnerId, String attemptId,
            LearningDtos.SaveDraftRequest input) {
        AttemptRow visible = repository.ownedAttempt(attemptId, learnerId).orElseThrow(this::notFound);
        AssessmentRow assessment = repository.lockAssessment(visible.assessmentId());
        if (assessment == null) throw notFound();
        AttemptRow attempt = repository.lockAttempt(attemptId);
        if (attempt == null || !attempt.learnerId().equals(learnerId)) throw notFound();
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(input.answers());
        if (requestId.equals(attempt.draftRequestId())) {
            if (!fingerprint.equals(attempt.draftFingerprint())) throw conflict("LEARNING.IDEMPOTENCY_CONFLICT", "相同请求 ID 的草稿内容不同");
            return draft(attempt, Instant.now());
        }
        if (attempt.status() != AttemptStatus.DRAFT || attempt.revision() != input.expectedRevision()) {
            throw conflict("LEARNING.ATTEMPT_REVISION_CONFLICT", "作答状态或 revision 已变化");
        }
        requireAssessmentAcceptsAnswers(assessment);
        requireBeforeDeadline(attempt, Instant.now());
        validateAnswers(attempt.assessmentId(), input.answers(), false);
        Instant now = Instant.now();
        if (repository.updateDraftMetadata(attemptId, input.expectedRevision(), requestId, fingerprint, now) == 0) {
            throw conflict("LEARNING.ATTEMPT_REVISION_CONFLICT", "草稿已被其他请求更新");
        }
        repository.replaceDraftAnswers(attemptId, input.answers(), now);
        var participant = repository.participant(assessment.id(), learnerId).orElseThrow(this::notFound);
        repository.updateParticipantOnDraft(assessment.id(), learnerId, participant.attemptsUsed(),
                participant.finalAttemptId(), now);
        return draft(repository.ownedAttempt(attemptId, learnerId).orElseThrow(), Instant.now());
    }

    /** 提交只允许成功一次；事务内先写 SUBMITTED，再完成自动判分、错题累计和最终状态汇总。 */
    @Transactional
    public LearningDtos.AttemptResult submit(String learnerId, String attemptId,
            LearningDtos.SubmitAttemptRequest input) {
        AttemptRow visible = repository.ownedAttempt(attemptId, learnerId).orElseThrow(this::notFound);
        AssessmentRow assessment = repository.lockAssessment(visible.assessmentId());
        if (assessment == null) throw notFound();
        AttemptRow attempt = repository.lockAttempt(attemptId);
        if (attempt == null || !attempt.learnerId().equals(learnerId)) throw notFound();
        String requestId = input.clientRequestId().toString();
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(attempt.assessmentId());
        boolean timedOut = attempt.deadlineAt() != null && !Instant.now().isBefore(attempt.deadlineAt());
        boolean wholeAssessmentClosed = assessment.closeStatus() == AssessmentCloseStatus.CLOSED;
        List<AnswerInput> requested = timedOut || wholeAssessmentClosed
                ? repository.answers(attemptId).stream().map(answer -> new AnswerInput(
                        UUID.fromString(answer.assessmentQuestionId()), answer.answer())).toList()
                : input.answers();
        List<AnswerInput> complete = completeAnswers(items, requested);
        String fingerprint = fingerprint(complete);
        if (attempt.status() != AttemptStatus.DRAFT) {
            if (requestId.equals(attempt.submitRequestId()) && fingerprint.equals(attempt.submissionFingerprint())) {
                return result(attemptId, learnerId, false);
            }
            throw conflict("LEARNING.ATTEMPT_ALREADY_SUBMITTED", "试卷已提交，不能再次修改");
        }
        if (assessment.closeStatus() == AssessmentCloseStatus.CLOSED) {
            throw conflict("LEARNING.ASSESSMENT_CLOSED", "整场测评已结束，请等待服务端结算最后草稿");
        }
        if (attempt.revision() != input.expectedRevision()) throw conflict("LEARNING.ATTEMPT_REVISION_CONFLICT", "作答 revision 已变化");
        validateAnswers(attempt.assessmentId(), complete, true);
        finalizeLockedDraft(attempt, requestId, fingerprint, complete, items, Instant.now());
        evaluateAutomaticPublications(assessment.id(), Instant.now());
        return result(attemptId, learnerId, false);
    }

    /**
     * 一次有界结算最多处理 batchSize 份草稿；每份只读取服务端最后保存答案并使用确定性请求 ID。
     * 整批事务失败会整体回滚，外层处理器记录有限重试；不会出现半份试卷结算。
     */
    @Transactional
    public int processSettlementBatch(String assessmentId, int batchSize) {
        AssessmentRow assessment = repository.lockAssessment(assessmentId);
        if (assessment == null || assessment.closeStatus() != AssessmentCloseStatus.CLOSED
                || (assessment.settlementStatus() != AssessmentSettlementStatus.PENDING
                        && assessment.settlementStatus() != AssessmentSettlementStatus.PROCESSING)) {
            return 0;
        }
        Instant now = Instant.now();
        repository.markSettlementProcessing(assessmentId, now);
        List<AssessmentQuestionRow> items = repository.assessmentQuestions(assessmentId);
        List<AttemptRow> drafts = repository.draftAttemptsForSettlement(assessmentId, batchSize);
        for (AttemptRow attempt : drafts) {
            List<AnswerInput> saved = repository.answers(attempt.id()).stream().map(answer -> new AnswerInput(
                    UUID.fromString(answer.assessmentQuestionId()), answer.answer())).toList();
            if (saved.stream().allMatch(answer -> answer.answer().isEmpty())) {
                repository.deleteEmptyDraftAttempt(attempt.id());
                repository.markParticipantMissing(assessmentId, attempt.learnerId(), now);
                continue;
            }
            List<AnswerInput> complete = completeAnswers(items, saved);
            String requestId = UUID.nameUUIDFromBytes(("assessment-settlement:" + attempt.id())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            finalizeLockedDraft(attempt, requestId, fingerprint(complete), complete, items, now);
        }
        repository.markUnsubmittedParticipantsMissing(assessmentId, now);
        int remaining = repository.countDraftAttempts(assessmentId);
        repository.markSettlementBatchSucceeded(assessmentId, remaining, Instant.now());
        repository.insertSettlementAudit(UUID.randomUUID().toString(), assessmentId,
                "settlement:" + now.getEpochSecond(), drafts.size(), remaining == 0 ? "COMPLETED" : "PENDING",
                null, now, Instant.now());
        if (remaining == 0) evaluateAutomaticPublications(assessmentId, Instant.now());
        return drafts.size();
    }

    /** 每次调度只返回固定上限的到期测评 ID，不在服务内无限循环。 */
    public List<String> dueSettlementAssessments(int limit) {
        return repository.dueSettlementAssessmentIds(Instant.now(), limit);
    }

    /** 返回达到最终截止但尚未关闭的测评，供有界后台任务原子推进 OPEN→CLOSED。 */
    public List<String> dueAssessmentCloseIds(int limit) {
        return repository.dueAssessmentCloseIds(Instant.now(), limit);
    }

    /** 自动关闭单场测评并冻结当前草稿数量；竞争实例只有一个能更新 OPEN 行。 */
    @Transactional
    public void autoCloseAssessment(String assessmentId) {
        AssessmentRow assessment = repository.lockAssessment(assessmentId);
        if (assessment == null || assessment.status() != AssessmentStatus.PUBLISHED
                || assessment.closeStatus() == AssessmentCloseStatus.CLOSED) return;
        Instant now = Instant.now();
        ensureAssessmentParticipants(assessment.courseId(), assessmentId, now);
        int affected = repository.countDraftAttempts(assessmentId);
        if (repository.closeAssessmentAutomatically(assessmentId, affected, now) != 0 && affected == 0) {
            repository.markUnsubmittedParticipantsMissing(assessmentId, now);
            evaluateAutomaticPublications(assessmentId, now);
        }
    }

    /** 关闭前按当时 ACTIVE 报名冻结应交名单；重复插入由 assessment+learner 唯一约束吸收。 */
    private void ensureAssessmentParticipants(String courseId, String assessmentId, Instant now) {
        repository.ensureParticipantsForAssessment(courseId, assessmentId, now);
    }

    /** 独立事务记录一次安全失败摘要和有限退避；第五次失败后保留 FAILED 供人工诊断。 */
    @Transactional
    public void recordSettlementFailure(String assessmentId, RuntimeException failure) {
        AssessmentRow assessment = repository.lockAssessment(assessmentId);
        if (assessment == null || assessment.settlementStatus() == AssessmentSettlementStatus.COMPLETED
                || assessment.settlementStatus() == AssessmentSettlementStatus.FAILED) return;
        int attempts = assessment.settlementAttempts() + 1;
        long delaySeconds = switch (Math.min(attempts, 5)) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 30;
            case 4 -> 120;
            default -> 300;
        };
        String safeError = failure.getClass().getSimpleName();
        repository.markSettlementBatchFailed(assessmentId, attempts,
                Instant.now().plusSeconds(delaySeconds), safeError, Instant.now());
    }

    /**
     * 使用调用方已锁定的草稿和服务端答案完成一次不可变提交与判分；输入必须是补齐后的整卷答案，
     * 输出通过 attempt/answer 状态写回数据库，任一步失败由外层事务整体回滚。
     */
    private void finalizeLockedDraft(AttemptRow attempt, String requestId, String fingerprint,
            List<AnswerInput> complete, List<AssessmentQuestionRow> items, Instant now) {
        AssessmentRow assessment = repository.assessment(attempt.assessmentId()).orElseThrow(this::notFound);
        boolean late = assessment.type() == LearningDtos.AssessmentType.HOMEWORK
                && assessment.latePolicy() == LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL
                && assessment.closesAt() != null && !now.isBefore(assessment.closesAt());
        repository.updateAttemptLateSubmission(attempt.id(), late, now);
        repository.replaceDraftAnswers(attempt.id(), complete, now);
        if (repository.submitAttempt(attempt.id(), attempt.revision(), requestId, fingerprint,
                AttemptStatus.SUBMITTED, null, now) == 0) {
            throw conflict("LEARNING.ATTEMPT_ALREADY_SUBMITTED", "试卷已由其他请求提交");
        }
        Map<String, AssessmentQuestionRow> byId = index(items);
        boolean pendingManual = false;
        BigDecimal score = BigDecimal.ZERO;
        for (AnswerRow answer : repository.answers(attempt.id())) {
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
        repository.updateAttemptGrade(attempt.id(), finalStatus, score,
                finalStatus == AttemptStatus.GRADED ? now : null, now);
        repository.updateParticipantOnSubmit(assessment.id(), attempt.learnerId(), attempt.attemptSequence(),
                attempt.id(), now, late,
                finalStatus == AttemptStatus.GRADED ? "GRADED" : "PARTIALLY_GRADED", now);
    }

    /** 在同一 assessment 行锁下推进不可撤回的自动公布事实，绝不把 Redis/RabbitMQ 当可见性来源。 */
    private void evaluateAutomaticPublications(String assessmentId, Instant now) {
        AssessmentRow assessment = repository.lockAssessment(assessmentId);
        if (assessment == null) throw notFound();
        if (assessment.type() == LearningDtos.AssessmentType.PRACTICE
                && assessment.answerPolicy() == AnswerPublicationPolicy.AFTER_SUBMISSION
                && assessment.answerPublicationStatus() == ResultPublicationStatus.UNPUBLISHED) {
            repository.publishAnswers(assessmentId, assessment.revision(), now);
            assessment = repository.lockAssessment(assessmentId);
        }
        AssessmentProjectionCounts counts = repository.assessmentProjectionCounts(assessmentId);
        boolean readyForWholeAssessmentPublication = assessment.closeStatus() == AssessmentCloseStatus.CLOSED
                && assessment.settlementStatus() == AssessmentSettlementStatus.COMPLETED
                && counts.activeAttempts() == 0 && counts.pendingManualAnswers() == 0;
        if (!readyForWholeAssessmentPublication) return;
        if (assessment.gradePolicy() == GradePublicationPolicy.AUTOMATIC_AFTER_GRADING
                && assessment.gradePublicationStatus() == ResultPublicationStatus.UNPUBLISHED) {
            repository.publishGrades(assessmentId, assessment.revision(), now);
            assessment = repository.lockAssessment(assessmentId);
        }
        if (assessment.answerPolicy() == AnswerPublicationPolicy.AFTER_ASSESSMENT_GRADED
                && assessment.answerPublicationStatus() == ResultPublicationStatus.UNPUBLISHED) {
            repository.publishAnswers(assessmentId, assessment.revision(), now);
        }
    }

    /** 返回本人已提交成绩，正确答案/解析是否存在由公布策略决定。 */
    @Transactional(readOnly = true)
    public LearningDtos.AttemptResult learnerResult(String learnerId, String attemptId) {
        return result(attemptId, learnerId, false);
    }

    /** 按本人与可选课程分页返回安全题目快照、掌握状态和有限重做事实。 */
    public LearningDtos.WrongQuestionPage wrongQuestions(String learnerId, String courseId, int page, int size) {
        return new LearningDtos.WrongQuestionPage(repository.wrongQuestions(learnerId, courseId, page, size).stream()
                .map(this::wrongQuestion).toList(),
                page, size, repository.countWrongQuestions(learnerId, courseId));
    }

    /** 返回本人单条错题；他人记录和不存在记录使用相同 404。 */
    public LearningDtos.WrongQuestion wrongQuestion(String learnerId, String wrongQuestionId) {
        return wrongQuestion(repository.wrongQuestion(wrongQuestionId, learnerId).orElseThrow(this::notFound));
    }

    /** 以 revision 更新本人掌握状态，相同目标为幂等成功，不影响原 attempt。 */
    @Transactional
    public LearningDtos.WrongQuestion updateWrongQuestionMastery(String learnerId, String wrongQuestionId,
            LearningDtos.UpdateWrongQuestionMasteryRequest input) {
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(Map.of("wrongQuestionId", wrongQuestionId,
                "masteryStatus", input.masteryStatus().name()));
        var replay = repository.idempotency(learnerId, requestId).orElse(null);
        if (replay != null) {
            return wrongQuestion(learnerId,
                    idempotentResource(replay, fingerprint, "WRONG_QUESTION_MASTERY"));
        }
        WrongRow row = repository.lockWrongQuestion(wrongQuestionId, learnerId);
        if (row == null) throw notFound();
        if (row.masteryStatus() != input.masteryStatus() && (row.revision() != input.expectedRevision()
                || repository.updateWrongMastery(wrongQuestionId, learnerId, input.expectedRevision(),
                        input.masteryStatus(), Instant.now()) == 0)) {
            throw conflict("LEARNING.WRONG_QUESTION_REVISION_CONFLICT", "错题状态已变化，请刷新后重试");
        }
        repository.insertIdempotency(learnerId, requestId, fingerprint, "WRONG_QUESTION_MASTERY",
                wrongQuestionId, Instant.now());
        return wrongQuestion(repository.wrongQuestion(wrongQuestionId, learnerId).orElseThrow());
    }

    /**
     * 创建一次独立错题重做记录。最多三次，每次都不可变；不重开原测评 attempt。
     * 主观题无法可靠自动判分时 correct/score 为 null，但仍消耗一次有限配额。
     */
    @Transactional
    public RedoCreation redoWrongQuestion(String learnerId, String wrongQuestionId,
            LearningDtos.RedoWrongQuestionRequest input) {
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(input.answer());
        RedoRow replay = repository.redoByRequest(learnerId, requestId).orElse(null);
        if (replay != null) {
            if (!replay.wrongQuestionId().equals(wrongQuestionId) || !replay.requestFingerprint().equals(fingerprint)) {
                throw conflict("LEARNING.IDEMPOTENCY_CONFLICT", "相同请求 ID 已用于不同重做内容");
            }
            return new RedoCreation(redo(replay, true), false);
        }
        WrongRow wrong = repository.lockWrongQuestion(wrongQuestionId, learnerId);
        if (wrong == null) throw notFound();
        if (wrong.revision() != input.expectedRevision()) {
            throw conflict("LEARNING.WRONG_QUESTION_REVISION_CONFLICT", "错题状态已变化，请刷新后重试");
        }
        if (wrong.redoAttemptsUsed() >= 3) {
            throw conflict("LEARNING.WRONG_QUESTION_REDO_EXHAUSTED", "该错题的有限重做次数已用完");
        }
        AssessmentQuestionRow item = assessmentQuestionByVersion(wrong.assessmentId(), wrong.questionVersionId());
        AutoGrade grade = autoGrade(item.snapshot(), input.answer(), item.points());
        Instant now = Instant.now();
        RedoRow created = new RedoRow(UUID.randomUUID().toString(), wrong.id(), learnerId, requestId, fingerprint,
                wrong.redoAttemptsUsed() + 1, List.copyOf(input.answer()), grade == null ? null : grade.score(),
                grade == null ? null : grade.correct(), now);
        try {
            repository.insertWrongQuestionRedo(created, input.expectedRevision(), now);
        } catch (org.springframework.dao.OptimisticLockingFailureException | DuplicateKeyException conflict) {
            throw conflict("LEARNING.WRONG_QUESTION_REVISION_CONFLICT", "错题重做状态已变化");
        }
        return new RedoCreation(redo(created, false), true);
    }

    /**
     * 校验当前学习者的发布测评访问或历史 attempt owner 事实，再请求媒体短期授权。
     * 媒体用途必须从冻结快照导出，浏览器不能声明 owner/可见性或访问类型。
     */
    public LearningDtos.MediaAccessGrant assessmentMediaAccess(String learnerId, String assessmentId,
            String mediaId, LearningDtos.CreateAssessmentMediaAccessGrantRequest input, String traceId) {
        if (!"ORIGINAL".equals(input.variant())) {
            throw invalid("LEARNING.MEDIA_VARIANT_INVALID", "测评题目媒体只允许 ORIGINAL");
        }
        AssessmentRow assessment = repository.assessment(assessmentId).orElseThrow(this::notFound);
        boolean currentAccess = assessment.status() == AssessmentStatus.PUBLISHED
                && repository.isAssignedToCourse(assessmentId, assessment.courseId())
                && repository.enrollment(learnerId, assessment.courseId())
                        .filter(value -> value.status() == EnrollmentStatus.ACTIVE).isPresent();
        boolean historicalOwner = repository.attemptForLearner(assessmentId, learnerId).isPresent();
        if (!currentAccess && !historicalOwner) throw notFound();
        if (!repository.assessmentContainsMedia(assessmentId, mediaId)) throw notFound();
        String purpose = assessmentMediaPurpose(assessmentId, mediaId);
        LearningDtos.MediaDisposition expected = "QUESTION_ATTACHMENT".equals(purpose)
                ? LearningDtos.MediaDisposition.ATTACHMENT : LearningDtos.MediaDisposition.INLINE;
        if (input.disposition() != expected) {
            throw invalid("LEARNING.MEDIA_DISPOSITION_INVALID", "媒体处置方式与冻结用途不匹配");
        }
        return media.issueAssessmentAccess(mediaId, learnerId, purpose, input.disposition(), traceId);
    }

    /** 人工批改按 attempt revision 做乐观并发控制，并为每道题记录 grader、时间、分数和答案 revision。 */
    @Transactional
    public LearningDtos.AttemptResult manualGrade(String teacherId, String attemptId,
            LearningDtos.ManualGradeRequest input) {
        String requestId = input.clientRequestId().toString();
        String fingerprint = fingerprint(input.grades());
        var replay = repository.idempotency(teacherId, requestId).orElse(null);
        if (replay != null) return teacherResult(teacherId, idempotentResource(replay, fingerprint, "MANUAL_GRADE"));
        AttemptRow visible = repository.attempt(attemptId).orElse(null);
        if (visible == null) throw notFound();
        AssessmentRow assessment = repository.lockOwnedAssessment(visible.assessmentId(), teacherId);
        if (assessment == null) throw notFound();
        if (requiresClosedGrading(assessment)
                && (assessment.closeStatus() != AssessmentCloseStatus.CLOSED
                        || assessment.settlementStatus() != AssessmentSettlementStatus.COMPLETED)) {
            throw conflict("LEARNING.GRADING_NOT_READY", "测评关闭并完成结算后才能人工批改");
        }
        if (requiresClosedGrading(assessment)
                && !repository.isFinalAttempt(assessment.id(), visible.learnerId(), attemptId)) {
            throw notFound();
        }
        AttemptRow attempt = repository.lockAttempt(attemptId);
        if (attempt == null || attempt.status() == AttemptStatus.DRAFT
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
            if (item == null || answer.gradingStatus() == GradingStatus.AUTO_GRADED) {
                throw conflict("LEARNING.ANSWER_NOT_MANUALLY_GRADABLE", "该答案不需要或不允许人工批改");
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
        repository.updateParticipantGrading(assessment.id(), attempt.learnerId(),
                pending ? LearningDtos.AssessmentGradingStatus.PARTIALLY_GRADED
                        : LearningDtos.AssessmentGradingStatus.GRADED, now);
        repository.insertIdempotency(teacherId, requestId, fingerprint, "MANUAL_GRADE", attemptId, now);
        evaluateAutomaticPublications(assessment.id(), now);
        return teacherResult(teacherId, attemptId);
    }

    /** 校验测评 owner 后分页返回已作答学生的评分结果。 */
    @Transactional(readOnly = true)
    public LearningDtos.AttemptResultPage teacherResults(String teacherId, String assessmentId, int page, int size) {
        AssessmentRow assessment = repository.ownedAssessment(assessmentId, teacherId).orElseThrow(this::notFound);
        if (requiresClosedGrading(assessment)
                && (assessment.closeStatus() != AssessmentCloseStatus.CLOSED
                        || assessment.settlementStatus() != AssessmentSettlementStatus.COMPLETED
                        || repository.assessmentProjectionCounts(assessmentId).pendingManualAnswers() != 0)) {
            throw conflict("LEARNING.RESULTS_NOT_READY", "测评关闭、结算并完成批改后才能查看结果列表");
        }
        List<LearningDtos.AttemptResult> values = repository.assessmentAttempts(assessmentId, page, size).stream()
                .map(item -> result(item.id(), item.learnerId(), true)).toList();
        return new LearningDtos.AttemptResultPage(values, page, size,
                repository.countAssessmentAttempts(assessmentId));
    }

    /** 返回教师本人测评中的单次完整结果，包含评分所需答案与解析。 */
    public LearningDtos.AttemptResult teacherResult(String teacherId, String attemptId) {
        AttemptRow attempt = repository.attempt(attemptId).orElseThrow(this::notFound);
        AssessmentRow assessment = repository.ownedAssessment(attempt.assessmentId(), teacherId).orElseThrow(this::notFound);
        if (requiresClosedGrading(assessment)
                && (assessment.closeStatus() != AssessmentCloseStatus.CLOSED
                        || assessment.settlementStatus() != AssessmentSettlementStatus.COMPLETED)) {
            throw conflict("LEARNING.RESULTS_NOT_READY", "测评关闭并完成结算后才能查看结果");
        }
        if (requiresClosedGrading(assessment)
                && !repository.isFinalAttempt(assessment.id(), attempt.learnerId(), attemptId)) throw notFound();
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

    /**
     * 根据两个独立公布事实裁剪学生结果。未授权侧始终输出契约规定的 null，
     * 教师视图保留批改所需完整事实；两者都只读取冻结试卷，不追随题库新版本。
     */
    private LearningDtos.AttemptResult result(String attemptId, String learnerId, boolean teacherView) {
        AttemptRow attempt = repository.ownedAttempt(attemptId, learnerId).orElseThrow(this::notFound);
        AssessmentRow assessment = repository.assessment(attempt.assessmentId()).orElseThrow(this::notFound);
        if (requiresClosedGrading(assessment) && !repository.isFinalAttempt(assessment.id(), learnerId, attemptId)) {
            throw notFound();
        }
        if (attempt.status() == AttemptStatus.DRAFT) throw conflict("LEARNING.ATTEMPT_NOT_SUBMITTED", "试卷尚未提交");
        boolean gradeVisible = teacherView
                || assessment.gradePublicationStatus() == ResultPublicationStatus.PUBLISHED;
        boolean answerVisible = teacherView
                || assessment.answerPublicationStatus() == ResultPublicationStatus.PUBLISHED;
        Map<String, AssessmentQuestionRow> items = index(repository.assessmentQuestions(attempt.assessmentId()));
        List<LearningDtos.AnswerResult> answers = repository.answers(attemptId).stream().map(answer -> {
            AssessmentQuestionRow item = items.get(answer.assessmentQuestionId());
            return new LearningDtos.AnswerResult(answer.assessmentQuestionId(), answer.answer(),
                    gradeVisible ? answer.score() : null, gradeVisible ? answer.correct() : null,
                    gradeVisible ? answer.gradingStatus() : null,
                    answerVisible ? item.snapshot().answers() : null,
                    answerVisible ? item.snapshot().analysis() : null);
        }).toList();
        List<LearningDtos.LearnerQuestion> safeQuestions = repository.assessmentQuestions(attempt.assessmentId())
                .stream().map(this::learnerQuestion).toList();
        LearningDtos.AttemptResultAssessment frozen = new LearningDtos.AttemptResultAssessment(
                assessment.id(), assessment.courseId(), assessment.lessonId(), assessment.title(),
                assessment.description(), assessment.type(), assessment.gradePolicy(), assessment.answerPolicy(),
                assessment.opensAt(), assessment.closesAt(), assessment.timeLimitSeconds(), safeQuestions);
        AttemptStatus visibleStatus = gradeVisible ? attempt.status() : AttemptStatus.SUBMITTED;
        return new LearningDtos.AttemptResult(attempt.id(), attempt.assessmentId(), attempt.learnerId(),
                visibleStatus, attempt.revision(), gradeVisible, answerVisible,
                gradeVisible ? attempt.score() : null, attempt.totalPoints(), attempt.submittedAt(),
                gradeVisible ? attempt.gradeUpdatedAt() : null, frozen, answers, attempt.attemptSequence(),
                repository.isFinalAttempt(attempt.assessmentId(), learnerId, attempt.id()), attempt.lateSubmission());
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

    private LearningDtos.AttemptDraft draft(AttemptRow attempt, Instant serverTime) {
        List<AnswerInput> answers = repository.answers(attempt.id()).stream()
                .map(value -> new AnswerInput(UUID.fromString(value.assessmentQuestionId()), value.answer())).toList();
        return new LearningDtos.AttemptDraft(attempt.id(), attempt.assessmentId(), attempt.status(), attempt.revision(),
                answers, attempt.startedAt(), attempt.deadlineAt(), serverTime, attempt.createdAt(), attempt.updatedAt(),
                attempt.attemptSequence(), participantAttemptsUsed(attempt),
                repository.assessment(attempt.assessmentId()).map(AssessmentRow::maxAttempts).orElse(1),
                attempt.lateSubmission());
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

    /** 聚合关闭影响、当前草稿、待批人数/题数和公布资格，结果就绪不会早于草稿清零与批改完成。 */
    private LearningDtos.AssessmentSummary summary(AssessmentRow row, List<AssessmentQuestionRow> items) {
        AssessmentProjectionCounts counts = repository.assessmentProjectionCounts(row.id());
        int pendingSettlement = row.closeStatus() == AssessmentCloseStatus.CLOSED ? counts.activeAttempts() : 0;
        boolean settled = row.closeStatus() == AssessmentCloseStatus.CLOSED
                && row.settlementStatus() == AssessmentSettlementStatus.COMPLETED && pendingSettlement == 0;
        boolean gradeEligible = settled && counts.pendingManualAnswers() == 0;
        boolean answerEligible = settled && row.answerPolicy() != AnswerPublicationPolicy.NEVER;
        return new LearningDtos.AssessmentSummary(row.id(), row.courseId(), row.lessonId(), row.title(), row.type(),
                row.status(), row.closeStatus(), row.settlementStatus(), row.gradePolicy(), row.answerPolicy(),
                row.gradePublicationStatus(), row.answerPublicationStatus(), row.closedAt(),
                row.closeAffectedAttemptCount(), counts.activeAttempts(), pendingSettlement,
                counts.pendingManualAttempts(), counts.pendingManualAnswers(), row.gradePublishedAt(),
                row.answerPublishedAt(), gradeEligible, answerEligible,
                gradeEligible, row.opensAt(), row.closesAt(), row.timeLimitSeconds(), row.latePolicy(),
                row.lateUntil(), row.maxAttempts(),
                items.size(), total(items), row.revision(), row.publishedAt(),
                row.createdAt(), row.updatedAt());
    }

    private LearningDtos.LearnerAssessmentSummary learnerSummary(AssessmentRow row, AttemptRow attempt, Instant now) {
        LearningDtos.AssessmentSummary value = summary(row);
        LearningDtos.LearnerAssessmentState state;
        if (attempt != null) {
            AttemptStatus projectedStatus = row.gradePublicationStatus() == ResultPublicationStatus.PUBLISHED
                    ? attempt.status()
                    : (attempt.status() == AttemptStatus.DRAFT ? AttemptStatus.DRAFT : AttemptStatus.SUBMITTED);
            state = switch (projectedStatus) {
                case DRAFT -> deadlinePassed(attempt, now)
                        ? LearningDtos.LearnerAssessmentState.CLOSED
                        : LearningDtos.LearnerAssessmentState.IN_PROGRESS;
                case SUBMITTED -> LearningDtos.LearnerAssessmentState.SUBMITTED;
                case PARTIALLY_GRADED -> LearningDtos.LearnerAssessmentState.PARTIALLY_GRADED;
                case GRADED -> LearningDtos.LearnerAssessmentState.GRADED;
            };
        } else if (row.opensAt() != null && now.isBefore(row.opensAt())) {
            state = LearningDtos.LearnerAssessmentState.NOT_OPEN;
        } else if (row.closesAt() != null && !now.isBefore(row.closesAt())) {
            state = LearningDtos.LearnerAssessmentState.CLOSED;
        } else {
            state = LearningDtos.LearnerAssessmentState.AVAILABLE;
        }
        boolean resume = attempt != null && attempt.status() == AttemptStatus.DRAFT
                && row.closeStatus() == AssessmentCloseStatus.OPEN && !deadlinePassed(attempt, now);
        boolean resultAvailable = attempt != null && attempt.status() != AttemptStatus.DRAFT;
        return new LearningDtos.LearnerAssessmentSummary(value.id(), value.courseId(), value.lessonId(),
                value.title(), value.type(), value.status(), value.closeStatus(), value.settlementStatus(),
                value.gradePublicationPolicy(), value.answerPublicationPolicy(), value.gradePublicationStatus(),
                value.answerPublicationStatus(), value.closedAt(), value.affectedInProgressAttemptCount(),
                value.activeAttemptCount(), value.pendingSettlementCount(),
                value.pendingManualGradingAttemptCount(), value.pendingManualGradingAnswerCount(),
                value.gradePublishedAt(), value.answerPublishedAt(), value.gradePublicationEligible(),
                value.answerPublicationEligible(), value.resultsReady(), value.opensAt(),
                value.closesAt(), value.timeLimitSeconds(), value.latePolicy(), value.lateUntil(),
                value.maxAttempts(), value.questionCount(), value.totalPoints(),
                value.revision(), value.publishedAt(), value.createdAt(), value.updatedAt(), state,
                attemptSummary(attempt, row.gradePublicationStatus() == ResultPublicationStatus.PUBLISHED),
                resume, resultAvailable);
    }

    private static LearningDtos.AttemptSummary attemptSummary(AttemptRow attempt, boolean gradeVisible) {
        return attemptSummary(attempt, gradeVisible, true);
    }

    private static LearningDtos.AttemptSummary attemptSummary(AttemptRow attempt, boolean gradeVisible,
            boolean finalAttempt) {
        return attempt == null ? null : new LearningDtos.AttemptSummary(attempt.id(),
                gradeVisible || attempt.status() == AttemptStatus.DRAFT ? attempt.status() : AttemptStatus.SUBMITTED,
                attempt.revision(), attempt.startedAt(), attempt.deadlineAt(), attempt.submittedAt(),
                gradeVisible ? attempt.gradedAt() : null, attempt.attemptSequence(), finalAttempt,
                attempt.lateSubmission());
    }

    private int participantAttemptsUsed(AttemptRow attempt) {
        return repository.participant(attempt.assessmentId(), attempt.learnerId())
                .map(LearningAssessmentRepository.ParticipantRow::attemptsUsed)
                .orElse(attempt.attemptSequence() - (attempt.status() == AttemptStatus.DRAFT ? 1 : 0));
    }

    private static Instant effectiveDeadline(AssessmentRow assessment, Instant startedAt) {
        if (assessment.type() == LearningDtos.AssessmentType.HOMEWORK) {
            return assessment.latePolicy() == LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL
                    ? assessment.lateUntil() : assessment.closesAt();
        }
        Instant byLimit = assessment.timeLimitSeconds() == null ? null
                : startedAt.plusSeconds(assessment.timeLimitSeconds());
        if (byLimit == null) return assessment.closesAt();
        if (assessment.closesAt() == null) return byLimit;
        return byLimit.isBefore(assessment.closesAt()) ? byLimit : assessment.closesAt();
    }

    private static boolean deadlinePassed(AttemptRow attempt, Instant now) {
        return attempt.deadlineAt() != null && !now.isBefore(attempt.deadlineAt());
    }

    private static void requireBeforeDeadline(AttemptRow attempt, Instant now) {
        if (deadlinePassed(attempt, now)) {
            throw conflict("LEARNING.ATTEMPT_DEADLINE_PASSED", "作答时间已截止，请提交服务端保存的最后草稿");
        }
    }

    private static void requireAssessmentOpen(AssessmentRow assessment, Instant now) {
        requireAssessmentAcceptsAnswers(assessment);
        if (assessment.opensAt() != null && now.isBefore(assessment.opensAt())) {
            throw conflict("LEARNING.ASSESSMENT_NOT_OPEN", "测评尚未开放");
        }
        Instant finalDeadline = assessment.type() == LearningDtos.AssessmentType.HOMEWORK
                && assessment.latePolicy() == LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL
                        ? assessment.lateUntil() : assessment.closesAt();
        if (finalDeadline != null && !now.isBefore(finalDeadline)) {
            throw conflict("LEARNING.ASSESSMENT_CLOSED", "测评已截止");
        }
    }

    private static void requireAssessmentAcceptsAnswers(AssessmentRow assessment) {
        if (assessment.closeStatus() == AssessmentCloseStatus.CLOSED) {
            throw conflict("LEARNING.ASSESSMENT_CLOSED", "整场测评已结束，不再接受新作答或答案写入");
        }
    }

    private static void validateAssessmentTiming(LearningDtos.AssessmentType type, Instant opensAt,
            Instant closesAt, LearningDtos.HomeworkLatePolicy latePolicy, Instant lateUntil,
            int maxAttempts, Integer timeLimitSeconds) {
        if (type == LearningDtos.AssessmentType.PRACTICE
                && (opensAt != null || closesAt != null || timeLimitSeconds != null
                        || latePolicy != LearningDtos.HomeworkLatePolicy.DISALLOW || lateUntil != null
                        || maxAttempts != 1)) {
            throw invalid("LEARNING.ASSESSMENT_TIMING_INVALID", "普通练习不能配置作业/考试时间和重试策略");
        }
        if (type == LearningDtos.AssessmentType.HOMEWORK) {
            if (closesAt == null || timeLimitSeconds != null || maxAttempts < 1 || maxAttempts > 5
                    || latePolicy == null) {
                throw invalid("LEARNING.ASSESSMENT_TIMING_INVALID", "作业必须配置截止时间且不能配置考试限时");
            }
            if (latePolicy == LearningDtos.HomeworkLatePolicy.DISALLOW && lateUntil != null) {
                throw invalid("LEARNING.LATE_WINDOW_INVALID", "DISALLOW 不能配置迟交截止时间");
            }
            if (latePolicy == LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL
                    && (lateUntil == null || lateUntil.isBefore(closesAt.plus(Duration.ofHours(1))))) {
                throw invalid("LEARNING.LATE_WINDOW_INVALID", "迟交截止必须至少晚于作业截止一小时");
            }
        } else if (latePolicy != LearningDtos.HomeworkLatePolicy.DISALLOW || lateUntil != null || maxAttempts != 1) {
            throw invalid("LEARNING.ASSESSMENT_TIMING_INVALID", "只有作业可以配置迟交和多次提交");
        }
        if (opensAt != null && closesAt != null && !opensAt.isBefore(closesAt)) {
            throw invalid("LEARNING.ASSESSMENT_WINDOW_INVALID", "开放时间必须早于截止时间");
        }
    }

    private static void validateAnswerPublicationPolicy(LearningDtos.AssessmentType type,
            AnswerPublicationPolicy policy) {
        if (policy == AnswerPublicationPolicy.AFTER_SUBMISSION && type != LearningDtos.AssessmentType.PRACTICE) {
            throw invalid("LEARNING.ANSWER_POLICY_INVALID", "AFTER_SUBMISSION 只能用于普通练习");
        }
    }

    /** T11C 正式作业/考试才启用关闭前结果门禁；旧的无截止时间 Java 夹具保持 T11B 兼容。 */
    private static boolean requiresClosedGrading(AssessmentRow assessment) {
        return (assessment.type() == LearningDtos.AssessmentType.HOMEWORK
                || assessment.type() == LearningDtos.AssessmentType.EXAM) && assessment.closesAt() != null;
    }

    private static AnswerPublicationPolicy canonicalAnswerPolicy(AnswerPublicationPolicy value) {
        return value == AnswerPublicationPolicy.AFTER_GRADED
                ? AnswerPublicationPolicy.AFTER_ASSESSMENT_GRADED : value;
    }

    private LearningDtos.WrongQuestion wrongQuestion(WrongRow row) {
        AssessmentQuestionRow item = assessmentQuestionByVersion(row.assessmentId(), row.questionVersionId());
        List<LearningDtos.WrongQuestionRedoResult> history = repository.wrongQuestionRedos(row.id()).stream()
                .map(value -> redo(value, false)).toList();
        return new LearningDtos.WrongQuestion(row.id(), row.courseId(), row.assessmentId(), row.questionVersionId(),
                row.wrongCount(), row.masteryStatus(), row.revision(), row.redoAttemptsUsed(), 3,
                row.redoAttemptsUsed() < 3, learnerQuestion(item), history, row.firstWrongAt(), row.lastWrongAt(),
                row.updatedAt());
    }

    private LearningDtos.WrongQuestionRedoResult redo(RedoRow value, boolean replayed) {
        return new LearningDtos.WrongQuestionRedoResult(value.id(), value.wrongQuestionId(), value.sequence(),
                value.answer(), value.score(), value.correct(), value.submittedAt(), replayed);
    }

    private AssessmentQuestionRow assessmentQuestionByVersion(String assessmentId, String versionId) {
        return repository.assessmentQuestions(assessmentId).stream()
                .filter(item -> item.questionVersionId().equals(versionId)).findFirst().orElseThrow(this::notFound);
    }

    private String assessmentMediaPurpose(String assessmentId, String mediaId) {
        for (AssessmentQuestionRow question : repository.assessmentQuestions(assessmentId)) {
            for (QuestionDtos.MediaResponse mediaValue : question.snapshot().media()) {
                if (mediaValue.mediaId().equals(mediaId)) return mediaValue.purpose().name();
            }
            for (QuestionDtos.ChildResponse child : question.snapshot().children()) {
                for (QuestionDtos.MediaResponse mediaValue : child.media()) {
                    if (mediaValue.mediaId().equals(mediaId)) return mediaValue.purpose().name();
                }
            }
        }
        throw notFound();
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

    private static LearningDtos.LessonProgress progress(ProgressRow row, boolean replayed,
            LearningDtos.ProgressPersistenceStatus status) {
        return new LearningDtos.LessonProgress(row.courseId(), row.lessonId(), row.positionSeconds(),
                row.watchedSeconds(), row.completed(), row.lastEventAt(), row.revision(), replayed, status);
    }

    private static LearningDtos.LessonProgress progress(BufferedProgress value, boolean replayed,
            LearningDtos.ProgressPersistenceStatus status) {
        return new LearningDtos.LessonProgress(value.courseId(), value.lessonId(), value.positionSeconds(),
                value.watchedSeconds(), value.completed(), value.lastEventAt(), value.version(), replayed, status);
    }

    /** 合并 MySQL 事实与 Redis 最新快照，位置/时长/完成和事件时间都保持单调。 */
    private static LearningDtos.LessonProgress progress(ProgressRow persisted, BufferedProgress buffered,
            boolean replayed, LearningDtos.ProgressPersistenceStatus status) {
        long position = Math.max(persisted.positionSeconds(), buffered.positionSeconds());
        long watched = Math.max(persisted.watchedSeconds(), buffered.watchedSeconds());
        boolean completed = persisted.completed() || buffered.completed();
        Instant eventAt = persisted.lastEventAt().isAfter(buffered.lastEventAt())
                ? persisted.lastEventAt() : buffered.lastEventAt();
        long revision = Math.max(persisted.revision(), buffered.version());
        return new LearningDtos.LessonProgress(persisted.courseId(), persisted.lessonId(), position, watched,
                completed, eventAt, revision, replayed, status);
    }

    private static LearningDtos.ArticleLessonCompletion articleCompletion(
            LearningAssessmentRepository.ArticleCompletionRow row, boolean replayed) {
        return new LearningDtos.ArticleLessonCompletion(row.courseId(), row.lessonId(), 100,
                row.completedAt(), row.revision(), replayed);
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
    public record RedoCreation(LearningDtos.WrongQuestionRedoResult result, boolean created) {}
}
