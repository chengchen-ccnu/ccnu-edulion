package com.eduplatform.learning.web;

import com.eduplatform.learning.domain.QuestionModels;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** OpenAPI 0.21.0 学习/测评请求与响应；学生结果用显式可见性与安全 null 语义隔离未公布评分和答案。 */
public final class LearningDtos {
    private LearningDtos() {}

    public enum EnrollmentStatus { ACTIVE, CANCELED }
    public enum AssessmentType { PRACTICE, HOMEWORK, EXAM }
    public enum AssessmentStatus { DRAFT, PUBLISHED, ARCHIVED }
    /** 整场关闭事实只能由 OPEN 单向推进到 CLOSED。 */
    public enum AssessmentCloseStatus { OPEN, CLOSED }
    /** 草稿结算的可靠推进状态；FAILED 表示有限自动重试耗尽且不能视为就绪。 */
    public enum AssessmentSettlementStatus { NOT_STARTED, PENDING, PROCESSING, COMPLETED, FAILED }
    /** HOMEWORK 的迟交事实只允许不接收或接收到 lateUntil；迟交本身不自动扣分。 */
    public enum HomeworkLatePolicy { DISALLOW, ALLOW_UNTIL }
    /** 学生任务的时间状态与提交状态分离，避免用一个枚举混淆开放窗口和本人进度。 */
    public enum AssessmentTimeState { NOT_OPEN, OPEN, LATE_WINDOW, CLOSED }
    public enum AssessmentSubmissionStatus { NOT_STARTED, IN_PROGRESS, SUBMITTED, MISSING, WITHDRAWN }
    public enum AssessmentGradingStatus { NOT_REQUIRED, PENDING, PARTIALLY_GRADED, GRADED }
    public enum GradebookAggregateStatus { READY, PENDING }
    public enum GradebookSortField { STUDENT_NUMBER, LAST_SUBMITTED_AT, SCORE, SUBMISSION_STATUS, GRADING_STATUS }
    /** 成绩在全部资格满足后自动公布，或等待教师显式公布。 */
    public enum GradePublicationPolicy { AUTOMATIC_AFTER_GRADING, MANUAL }
    /** 两类结果分别使用的不可撤回公布事实。 */
    public enum ResultPublicationStatus { UNPUBLISHED, PUBLISHED }
    public enum AnswerPublicationPolicy {
        AFTER_SUBMISSION, AFTER_GRADED, AFTER_ASSESSMENT_GRADED, MANUAL_AFTER_CLOSE, NEVER
    }
    public enum AttemptStatus { DRAFT, SUBMITTED, PARTIALLY_GRADED, GRADED }
    public enum GradingStatus { PENDING_MANUAL, AUTO_GRADED, MANUALLY_GRADED }
    public enum LearnerAssessmentState { NOT_OPEN, AVAILABLE, IN_PROGRESS, SUBMITTED, PARTIALLY_GRADED, GRADED, CLOSED }
    public enum WrongQuestionMasteryStatus { LEARNING, MASTERED }
    public enum MediaDisposition { INLINE, ATTACHMENT }
    /** 播放器明确声明本次上报是普通心跳还是必须形成 MySQL 恢复事实的检查点。 */
    public enum ProgressEventType { HEARTBEAT, PAUSE, LESSON_SWITCH, LEAVE, ENDED }
    /** 返回值说明当前进度来自缓冲层、MySQL，还是 Redis 不可用时的 MySQL 降级读。 */
    public enum ProgressPersistenceStatus { BUFFERED, PERSISTED, PERSISTED_ONLY }

    public record CreateEnrollmentRequest(@NotNull UUID courseId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Enrollment(String id, String learnerId, String courseId, String courseVersionId,
            EnrollmentStatus status, Instant enrolledAt, Instant canceledAt, Instant updatedAt) {}
    public record EnrollmentPage(List<Enrollment> items, int page, int size, long total) {}

    public record ReportProgressRequest(@NotNull UUID clientEventId, @NotNull Instant occurredAt,
            @Min(0) @Max(28800) long positionSeconds, @Min(0) @Max(300) int watchedSeconds,
            boolean completed, ProgressEventType eventType) {
        /** 兼容 T11/T12A 旧调用方：缺少 eventType 时仍同步写 MySQL，不按请求间隔猜事件类型。 */
        public ReportProgressRequest(UUID clientEventId, Instant occurredAt, long positionSeconds,
                int watchedSeconds, boolean completed) {
            this(clientEventId, occurredAt, positionSeconds, watchedSeconds, completed, null);
        }
    }
    public record LessonProgress(String courseId, String lessonId, long positionSeconds, long watchedSeconds,
            boolean completed, Instant lastEventAt, long revision, boolean replayed,
            ProgressPersistenceStatus persistenceStatus) {
        /** 兼容既有 Java 测试/调用点；新 HTTP 响应明确返回持久化结果。 */
        public LessonProgress(String courseId, String lessonId, long positionSeconds, long watchedSeconds,
                boolean completed, Instant lastEventAt, long revision, boolean replayed) {
            this(courseId, lessonId, positionSeconds, watchedSeconds, completed, lastEventAt, revision,
                    replayed, ProgressPersistenceStatus.PERSISTED);
        }
    }
    public record CompleteArticleLessonRequest(@NotNull UUID clientRequestId) {}
    /** ARTICLE 的独立 0→100 单调完成事实，不复用视频位置或观看时长字段。 */
    public record ArticleLessonCompletion(String courseId, String lessonId, int completionPercent,
            Instant completedAt, long revision, boolean replayed) {}
    public record CourseProgress(String courseId, int completedLessonCount, int lessonCount,
            double completionPercent, List<LessonProgress> lessons) {}

    public record CreateNoteRequest(@NotBlank @Size(max = 10000) String content,
            @Min(0) @Max(28800) Long positionSeconds) {}
    public record UpdateNoteRequest(@Min(0) long expectedRevision,
            @NotBlank @Size(max = 10000) String content, @Min(0) @Max(28800) Long positionSeconds) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LearningNote(String id, String courseId, String lessonId, String content,
            Long positionSeconds, long revision, Instant createdAt, Instant updatedAt) {}

    public record CreateAssessmentRequest(@NotNull UUID clientRequestId, @NotNull UUID courseId,
            UUID lessonId, @NotBlank @Size(max = 160) String title,
            @NotNull @Size(max = 5000) String description, @NotNull AssessmentType type,
            @NotNull GradePublicationPolicy gradePublicationPolicy,
            @NotNull AnswerPublicationPolicy answerPublicationPolicy, Instant opensAt, Instant closesAt,
            HomeworkLatePolicy latePolicy, Instant lateUntil,
            @Min(1) @Max(5) Integer maxAttempts, @Min(60) @Max(28800) Integer timeLimitSeconds) {
        /** 旧客户端不带作业字段时沿用 DISALLOW/1；显式非法值仍由 Bean Validation 和服务层拒绝。 */
        public CreateAssessmentRequest {
            latePolicy = latePolicy == null ? HomeworkLatePolicy.DISALLOW : latePolicy;
            maxAttempts = maxAttempts == null ? 1 : maxAttempts;
        }
        /** 仅供既有 Java 调用点平滑迁移；HTTP 契约始终要求显式成绩公布策略。 */
        public CreateAssessmentRequest(UUID clientRequestId, UUID courseId, UUID lessonId, String title,
                String description, AssessmentType type, AnswerPublicationPolicy answerPublicationPolicy,
                Instant opensAt, Instant closesAt, Integer timeLimitSeconds) {
            this(clientRequestId, courseId, lessonId, title, description, type, GradePublicationPolicy.MANUAL,
                    answerPublicationPolicy, opensAt, closesAt, HomeworkLatePolicy.DISALLOW, null, 1,
                    timeLimitSeconds);
        }
        /** 兼容 T11B 测试夹具显式传入成绩公布策略但尚未传入作业重试策略。 */
        public CreateAssessmentRequest(UUID clientRequestId, UUID courseId, UUID lessonId, String title,
                String description, AssessmentType type, GradePublicationPolicy gradePublicationPolicy,
                AnswerPublicationPolicy answerPublicationPolicy, Instant opensAt, Instant closesAt,
                Integer timeLimitSeconds) {
            this(clientRequestId, courseId, lessonId, title, description, type, gradePublicationPolicy,
                    answerPublicationPolicy, opensAt, closesAt, HomeworkLatePolicy.DISALLOW, null, 1,
                    timeLimitSeconds);
        }
    }
    public record UpdateAssessmentRequest(@Min(0) long expectedRevision,
            @NotBlank @Size(max = 160) String title, @NotNull @Size(max = 5000) String description,
            @NotNull AssessmentType type, @NotNull GradePublicationPolicy gradePublicationPolicy,
            @NotNull AnswerPublicationPolicy answerPublicationPolicy,
            Instant opensAt, Instant closesAt, HomeworkLatePolicy latePolicy, Instant lateUntil,
            @Min(1) @Max(5) Integer maxAttempts, @Min(60) @Max(28800) Integer timeLimitSeconds) {
        /** 旧客户端不带作业字段时沿用 DISALLOW/1；显式非法值仍由 Bean Validation 和服务层拒绝。 */
        public UpdateAssessmentRequest {
            latePolicy = latePolicy == null ? HomeworkLatePolicy.DISALLOW : latePolicy;
            maxAttempts = maxAttempts == null ? 1 : maxAttempts;
        }
        /** 仅供既有 Java 调用点平滑迁移；HTTP 契约始终要求显式成绩公布策略。 */
        public UpdateAssessmentRequest(long expectedRevision, String title, String description,
                AssessmentType type, AnswerPublicationPolicy answerPublicationPolicy,
                Instant opensAt, Instant closesAt, Integer timeLimitSeconds) {
            this(expectedRevision, title, description, type, GradePublicationPolicy.MANUAL,
                    answerPublicationPolicy, opensAt, closesAt, HomeworkLatePolicy.DISALLOW, null, 1,
                    timeLimitSeconds);
        }
    }
    /** 已发布 HOMEWORK/EXAM 的截止节点调整；reason 会进入不可变审计记录。 */
    public record UpdateAssessmentDeadlineRequest(@Min(0) long expectedRevision, @NotNull Instant closesAt,
            Instant lateUntil, @NotBlank @Size(max = 500) String reason) {}
    public record AssessmentQuestionInput(@NotNull UUID questionVersionId, @Min(1) @Max(500) int sequence,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000.00") BigDecimal points) {}
    public record ReplaceQuestionsRequest(@Min(0) long expectedRevision,
            @NotNull @Size(min = 1, max = 500) List<@Valid AssessmentQuestionInput> questions) {}
    public record UpdatePublicationRequest(@Min(0) long expectedRevision, @NotNull AssessmentStatus status) {}
    /** 高风险关闭命令同时携带幂等标识和教师最后看到的测评 revision。 */
    public record CloseAssessmentRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision) {}
    /** 成绩或答案公布命令同时携带幂等标识和并发保护 revision。 */
    public record PublishAssessmentResultRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AssessmentSummary(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentStatus status, AssessmentCloseStatus closeStatus,
            AssessmentSettlementStatus settlementStatus, GradePublicationPolicy gradePublicationPolicy,
            AnswerPublicationPolicy answerPublicationPolicy, ResultPublicationStatus gradePublicationStatus,
            ResultPublicationStatus answerPublicationStatus, Instant closedAt,
            int affectedInProgressAttemptCount, int activeAttemptCount, int pendingSettlementCount,
            int pendingManualGradingAttemptCount, int pendingManualGradingAnswerCount,
            Instant gradePublishedAt, Instant answerPublishedAt, boolean gradePublicationEligible,
            boolean answerPublicationEligible, boolean resultsReady,
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds, HomeworkLatePolicy latePolicy,
            Instant lateUntil, int maxAttempts,
            int questionCount, BigDecimal totalPoints, long revision, Instant publishedAt,
            Instant createdAt, Instant updatedAt) {}
    public record AssessmentPage(List<AssessmentSummary> items, int page, int size, long total) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttemptSummary(String id, AttemptStatus status, long revision, Instant startedAt,
            Instant deadlineAt, Instant submittedAt, Instant gradedAt, int attemptSequence,
            boolean finalAttempt, boolean lateSubmission) {
        /** 兼容 T11B 调用点；旧单次 attempt 视为序号 1、当前最终版本且不迟交。 */
        public AttemptSummary(String id, AttemptStatus status, long revision, Instant startedAt,
                Instant deadlineAt, Instant submittedAt, Instant gradedAt) {
            this(id, status, revision, startedAt, deadlineAt, submittedAt, gradedAt, 1, true, false);
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LearnerAssessmentSummary(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentStatus status, AssessmentCloseStatus closeStatus,
            AssessmentSettlementStatus settlementStatus, GradePublicationPolicy gradePublicationPolicy,
            AnswerPublicationPolicy answerPublicationPolicy, ResultPublicationStatus gradePublicationStatus,
            ResultPublicationStatus answerPublicationStatus, Instant closedAt,
            int affectedInProgressAttemptCount, int activeAttemptCount, int pendingSettlementCount,
            int pendingManualGradingAttemptCount, int pendingManualGradingAnswerCount,
            Instant gradePublishedAt, Instant answerPublishedAt, boolean gradePublicationEligible,
            boolean answerPublicationEligible, boolean resultsReady,
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds, HomeworkLatePolicy latePolicy,
            Instant lateUntil, int maxAttempts, int questionCount,
            BigDecimal totalPoints, long revision, Instant publishedAt, Instant createdAt, Instant updatedAt,
            LearnerAssessmentState learnerState, AttemptSummary currentAttempt,
            boolean resumeAllowed, boolean resultAvailable) {}
    public record LearnerAssessmentPage(List<LearnerAssessmentSummary> items, int page, int size,
            long total, Instant serverTime) {}
    public record LearnerQuestion(String id, String questionVersionId, int sequence, BigDecimal points,
            QuestionModels.Type type, String stem, List<QuestionDtos.OptionInput> options,
            List<QuestionDtos.MediaResponse> media, List<QuestionDtos.SystemQuestionChild> children) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LearnerAssessment(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentStatus status, AssessmentCloseStatus closeStatus,
            AssessmentSettlementStatus settlementStatus, GradePublicationPolicy gradePublicationPolicy,
            AnswerPublicationPolicy answerPublicationPolicy, ResultPublicationStatus gradePublicationStatus,
            ResultPublicationStatus answerPublicationStatus, Instant closedAt,
            int affectedInProgressAttemptCount, int activeAttemptCount, int pendingSettlementCount,
            int pendingManualGradingAttemptCount, int pendingManualGradingAnswerCount,
            Instant gradePublishedAt, Instant answerPublishedAt, boolean gradePublicationEligible,
            boolean answerPublicationEligible, boolean resultsReady,
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds, HomeworkLatePolicy latePolicy,
            Instant lateUntil, int maxAttempts, int questionCount,
            BigDecimal totalPoints, long revision, Instant publishedAt,
            Instant createdAt, Instant updatedAt, String description, List<LearnerQuestion> questions,
            Instant serverTime, AttemptSummary currentAttempt) {}
    public record TeacherQuestion(String id, String questionVersionId, int sequence, BigDecimal points,
            QuestionDtos.VersionResponse snapshot) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TeacherAssessment(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentStatus status, AssessmentCloseStatus closeStatus,
            AssessmentSettlementStatus settlementStatus, GradePublicationPolicy gradePublicationPolicy,
            AnswerPublicationPolicy answerPublicationPolicy, ResultPublicationStatus gradePublicationStatus,
            ResultPublicationStatus answerPublicationStatus, Instant closedAt,
            int affectedInProgressAttemptCount, int activeAttemptCount, int pendingSettlementCount,
            int pendingManualGradingAttemptCount, int pendingManualGradingAnswerCount,
            Instant gradePublishedAt, Instant answerPublishedAt, boolean gradePublicationEligible,
            boolean answerPublicationEligible, boolean resultsReady,
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds, HomeworkLatePolicy latePolicy,
            Instant lateUntil, int maxAttempts, int questionCount, BigDecimal totalPoints, long revision, Instant publishedAt,
            Instant createdAt, Instant updatedAt, String description, List<TeacherQuestion> questions) {}
    /** 返回不可变关闭后的教师投影、冻结影响人数及是否命中相同幂等请求。 */
    public record CloseAssessmentResult(TeacherAssessment assessment, int affectedInProgressAttemptCount,
            boolean replayed) {}
    /** 返回公布后的教师投影及是否命中相同幂等请求。 */
    public record AssessmentPublicationCommandResult(TeacherAssessment assessment, boolean replayed) {}

    public record StartAttemptRequest(@NotNull UUID clientRequestId) {}
    public record AnswerInput(@NotNull UUID assessmentQuestionId,
            @NotNull @Size(max = 50) List<@Size(max = 20000) String> answer) {}
    public record SaveDraftRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull @Size(max = 500) List<@Valid AnswerInput> answers) {}
    public record SubmitAttemptRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull @Size(max = 500) List<@Valid AnswerInput> answers) {}
    public record AttemptDraft(String id, String assessmentId, AttemptStatus status, long revision,
            List<AnswerInput> answers, Instant startedAt, Instant deadlineAt, Instant serverTime,
            Instant createdAt, Instant updatedAt, int attemptSequence, int attemptsUsed, int maxAttempts,
            boolean lateSubmission) {
        /** 兼容 T11B 调用点；旧接口只有一次提交且没有迟交投影。 */
        public AttemptDraft(String id, String assessmentId, AttemptStatus status, long revision,
                List<AnswerInput> answers, Instant startedAt, Instant deadlineAt, Instant serverTime,
                Instant createdAt, Instant updatedAt) {
            this(id, assessmentId, status, revision, answers, startedAt, deadlineAt, serverTime,
                    createdAt, updatedAt, 1, 0, 1, false);
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnswerResult(String assessmentQuestionId, List<String> submittedAnswer, BigDecimal score,
            Boolean correct, GradingStatus gradingStatus, List<String> correctAnswer, String analysis) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttemptResult(String attemptId, String assessmentId, String learnerId, AttemptStatus status,
            long revision, boolean gradeVisible, boolean answerVisible, BigDecimal score,
            BigDecimal totalPoints, Instant submittedAt, Instant gradeUpdatedAt,
            AttemptResultAssessment assessment, List<AnswerResult> answers, int attemptSequence,
            boolean finalAttempt, boolean lateSubmission) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttemptResultAssessment(String id, String courseId, String lessonId, String title,
            String description, AssessmentType type, GradePublicationPolicy gradePublicationPolicy,
            AnswerPublicationPolicy answerPublicationPolicy,
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds, List<LearnerQuestion> questions) {}
    public record ManualGradeItem(@NotNull UUID assessmentQuestionId,
            @NotNull @DecimalMin("0.00") @DecimalMax("1000.00") BigDecimal score,
            @NotNull @Size(max = 2000) String comment) {}
    public record ManualGradeRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull @Size(min = 1, max = 500) List<@Valid ManualGradeItem> grades) {}
    public record AttemptResultPage(List<AttemptResult> items, int page, int size, long total) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WrongQuestion(String id, String courseId, String assessmentId, String questionVersionId,
            int wrongCount, WrongQuestionMasteryStatus masteryStatus, long revision, int redoAttemptsUsed,
            int redoAttemptsLimit, boolean redoAvailable, LearnerQuestion question,
            List<WrongQuestionRedoResult> redoHistory, Instant firstWrongAt, Instant lastWrongAt,
            Instant updatedAt) {}
    public record WrongQuestionPage(List<WrongQuestion> items, int page, int size, long total) {}
    public record UpdateWrongQuestionMasteryRequest(@NotNull UUID clientRequestId,
            @Min(0) long expectedRevision, @NotNull WrongQuestionMasteryStatus masteryStatus) {}
    public record RedoWrongQuestionRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull @Size(max = 50) List<@Size(max = 20000) String> answer) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WrongQuestionRedoResult(String id, String wrongQuestionId, int sequence,
            List<String> submittedAnswer, BigDecimal score, Boolean correct, Instant submittedAt,
            boolean replayed) {}
    public record CreateAssessmentMediaAccessGrantRequest(@NotBlank String variant,
            @NotNull MediaDisposition disposition) {}
    public record MediaAccessGrant(String method, String url, java.util.Map<String, String> headers,
            Instant expiresAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StudentLearningSummary(String learnerId, EnrollmentStatus enrollmentStatus,
            int completedLessonCount, long watchedSeconds, int submittedAttemptCount,
            int gradedAttemptCount, BigDecimal averageScore) {}
    public record StudentLearningPage(List<StudentLearningSummary> items, int page, int size, long total) {}

    /** T11C.2 使用的学生课程任务摘要；未开放任务不会携带题目或答案快照。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LearnerCourseTaskSummary(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentTimeState timeState, AssessmentSubmissionStatus submissionStatus,
            AssessmentGradingStatus gradingStatus, boolean lateSubmission, int attemptsUsed, int maxAttempts,
            Instant opensAt, Instant closesAt, Instant lateUntil, Instant lastSubmittedAt,
            AttemptSummary finalAttempt, boolean questionContentAvailable) {}
    public record LearnerCourseTaskPage(List<LearnerCourseTaskSummary> items, int page, int size, long total,
            Instant serverTime) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LearnerCourseTask(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentTimeState timeState, AssessmentSubmissionStatus submissionStatus,
            AssessmentGradingStatus gradingStatus, boolean lateSubmission, int attemptsUsed, int maxAttempts,
            Instant opensAt, Instant closesAt, Instant lateUntil, Instant lastSubmittedAt,
            String description, List<LearnerQuestion> questions, Instant serverTime,
            AttemptSummary finalAttempt) {}

    /** 成绩册单元格只保存最终 attempt 的成绩投影，不复制答卷、答案或评语。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GradebookCell(BigDecimal scorePercent, AssessmentSubmissionStatus submissionStatus,
            AssessmentGradingStatus gradingStatus, boolean lateSubmission, String attemptId,
            String learnerId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GradebookAssessment(String assessmentId, String title, AssessmentType type,
            AssessmentCloseStatus closeStatus, AssessmentSettlementStatus settlementStatus,
            GradebookAggregateStatus aggregateStatus, int participantCount, int missingCount,
            int pendingCount, boolean resultsReady, AssessmentStats stats) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AssessmentStats(BigDecimal gradedStudentAverage, BigDecimal allStudentAverage,
            int expectedCount, BigDecimal onTimeSubmissionRate, BigDecimal finalSubmissionRate,
            int missingCount, int pendingCount, BigDecimal highestScore, BigDecimal lowestScore,
            Map<String, Integer> scoreDistribution) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GradebookRow(String learnerId, String courseStudentNumber, String nickname,
            boolean nicknameAvailable, boolean nicknameDependencyFailed,
            Map<String, GradebookCell> assessments, BigDecimal homeworkAverage, BigDecimal examAverage) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Gradebook(String courseId, GradebookAggregateStatus aggregateStatus,
            List<GradebookAssessment> assessments, List<GradebookRow> rows, BigDecimal homeworkAverage,
            BigDecimal examAverage, boolean nicknameDependencyFailed, int page, int size, long total) {}
    /** 导出请求只有稳定幂等标识；筛选条件来自 URL，避免请求体与 URL 产生第二套契约。 */
    public record GradebookExportRequest(@NotNull UUID clientRequestId) {}
}
