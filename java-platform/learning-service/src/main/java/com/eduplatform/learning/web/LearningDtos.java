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
import java.util.UUID;

/** OpenAPI 0.16.0 学习/测评请求与响应；学生结果用显式可见性与安全 null 语义隔离未公布评分和答案。 */
public final class LearningDtos {
    private LearningDtos() {}

    public enum EnrollmentStatus { ACTIVE, CANCELED }
    public enum AssessmentType { PRACTICE, HOMEWORK, EXAM }
    public enum AssessmentStatus { DRAFT, PUBLISHED, ARCHIVED }
    /** 整场关闭事实只能由 OPEN 单向推进到 CLOSED。 */
    public enum AssessmentCloseStatus { OPEN, CLOSED }
    /** 草稿结算的可靠推进状态；FAILED 表示有限自动重试耗尽且不能视为就绪。 */
    public enum AssessmentSettlementStatus { NOT_STARTED, PENDING, PROCESSING, COMPLETED, FAILED }
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

    public record CreateEnrollmentRequest(@NotNull UUID courseId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Enrollment(String id, String learnerId, String courseId, String courseVersionId,
            EnrollmentStatus status, Instant enrolledAt, Instant canceledAt, Instant updatedAt) {}
    public record EnrollmentPage(List<Enrollment> items, int page, int size, long total) {}

    public record ReportProgressRequest(@NotNull UUID clientEventId, @NotNull Instant occurredAt,
            @Min(0) @Max(28800) long positionSeconds, @Min(0) @Max(300) int watchedSeconds,
            boolean completed) {}
    public record LessonProgress(String courseId, String lessonId, long positionSeconds, long watchedSeconds,
            boolean completed, Instant lastEventAt, long revision, boolean replayed) {}
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
            @Min(60) @Max(28800) Integer timeLimitSeconds) {
        /** 仅供既有 Java 调用点平滑迁移；HTTP 契约始终要求显式成绩公布策略。 */
        public CreateAssessmentRequest(UUID clientRequestId, UUID courseId, UUID lessonId, String title,
                String description, AssessmentType type, AnswerPublicationPolicy answerPublicationPolicy,
                Instant opensAt, Instant closesAt, Integer timeLimitSeconds) {
            this(clientRequestId, courseId, lessonId, title, description, type, GradePublicationPolicy.MANUAL,
                    answerPublicationPolicy, opensAt, closesAt, timeLimitSeconds);
        }
    }
    public record UpdateAssessmentRequest(@Min(0) long expectedRevision,
            @NotBlank @Size(max = 160) String title, @NotNull @Size(max = 5000) String description,
            @NotNull AssessmentType type, @NotNull GradePublicationPolicy gradePublicationPolicy,
            @NotNull AnswerPublicationPolicy answerPublicationPolicy,
            Instant opensAt, Instant closesAt, @Min(60) @Max(28800) Integer timeLimitSeconds) {
        /** 仅供既有 Java 调用点平滑迁移；HTTP 契约始终要求显式成绩公布策略。 */
        public UpdateAssessmentRequest(long expectedRevision, String title, String description,
                AssessmentType type, AnswerPublicationPolicy answerPublicationPolicy,
                Instant opensAt, Instant closesAt, Integer timeLimitSeconds) {
            this(expectedRevision, title, description, type, GradePublicationPolicy.MANUAL,
                    answerPublicationPolicy, opensAt, closesAt, timeLimitSeconds);
        }
    }
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
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds,
            int questionCount, BigDecimal totalPoints, long revision, Instant publishedAt,
            Instant createdAt, Instant updatedAt) {}
    public record AssessmentPage(List<AssessmentSummary> items, int page, int size, long total) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttemptSummary(String id, AttemptStatus status, long revision, Instant startedAt,
            Instant deadlineAt, Instant submittedAt, Instant gradedAt) {}
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
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds, int questionCount,
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
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds, int questionCount,
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
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds,
            int questionCount, BigDecimal totalPoints, long revision, Instant publishedAt,
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
            Instant createdAt, Instant updatedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnswerResult(String assessmentQuestionId, List<String> submittedAnswer, BigDecimal score,
            Boolean correct, GradingStatus gradingStatus, List<String> correctAnswer, String analysis) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttemptResult(String attemptId, String assessmentId, String learnerId, AttemptStatus status,
            long revision, boolean gradeVisible, boolean answerVisible, BigDecimal score,
            BigDecimal totalPoints, Instant submittedAt, Instant gradeUpdatedAt,
            AttemptResultAssessment assessment, List<AnswerResult> answers) {}
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
}
