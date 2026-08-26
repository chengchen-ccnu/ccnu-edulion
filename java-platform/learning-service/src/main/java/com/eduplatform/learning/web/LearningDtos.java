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

/** OpenAPI 0.14.0 学习/测评请求与响应；学生试卷模型从类型上排除答案、解析和评分规则。 */
public final class LearningDtos {
    private LearningDtos() {}

    public enum EnrollmentStatus { ACTIVE, CANCELED }
    public enum AssessmentType { PRACTICE, HOMEWORK, EXAM }
    public enum AssessmentStatus { DRAFT, PUBLISHED, ARCHIVED }
    public enum AnswerPublicationPolicy { AFTER_SUBMISSION, AFTER_GRADED, NEVER }
    public enum AttemptStatus { DRAFT, SUBMITTED, PARTIALLY_GRADED, GRADED }
    public enum GradingStatus { PENDING_MANUAL, AUTO_GRADED, MANUALLY_GRADED }

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
            @NotNull AnswerPublicationPolicy answerPublicationPolicy) {}
    public record UpdateAssessmentRequest(@Min(0) long expectedRevision,
            @NotBlank @Size(max = 160) String title, @NotNull @Size(max = 5000) String description,
            @NotNull AssessmentType type, @NotNull AnswerPublicationPolicy answerPublicationPolicy) {}
    public record AssessmentQuestionInput(@NotNull UUID questionVersionId, @Min(1) @Max(500) int sequence,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000.00") BigDecimal points) {}
    public record ReplaceQuestionsRequest(@Min(0) long expectedRevision,
            @NotNull @Size(min = 1, max = 500) List<@Valid AssessmentQuestionInput> questions) {}
    public record UpdatePublicationRequest(@Min(0) long expectedRevision, @NotNull AssessmentStatus status) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AssessmentSummary(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentStatus status, AnswerPublicationPolicy answerPublicationPolicy,
            int questionCount, BigDecimal totalPoints, long revision, Instant publishedAt,
            Instant createdAt, Instant updatedAt) {}
    public record AssessmentPage(List<AssessmentSummary> items, int page, int size, long total) {}
    public record LearnerQuestion(String id, String questionVersionId, int sequence, BigDecimal points,
            QuestionModels.Type type, String stem, List<QuestionDtos.OptionInput> options,
            List<QuestionDtos.MediaResponse> media, List<QuestionDtos.SystemQuestionChild> children) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LearnerAssessment(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentStatus status, AnswerPublicationPolicy answerPublicationPolicy,
            int questionCount, BigDecimal totalPoints, long revision, Instant publishedAt,
            Instant createdAt, Instant updatedAt, String description, List<LearnerQuestion> questions) {}
    public record TeacherQuestion(String id, String questionVersionId, int sequence, BigDecimal points,
            QuestionDtos.VersionResponse snapshot) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TeacherAssessment(String id, String courseId, String lessonId, String title,
            AssessmentType type, AssessmentStatus status, AnswerPublicationPolicy answerPublicationPolicy,
            int questionCount, BigDecimal totalPoints, long revision, Instant publishedAt,
            Instant createdAt, Instant updatedAt, String description, List<TeacherQuestion> questions) {}

    public record StartAttemptRequest(@NotNull UUID clientRequestId) {}
    public record AnswerInput(@NotNull UUID assessmentQuestionId,
            @NotNull @Size(max = 50) List<@Size(max = 20000) String> answer) {}
    public record SaveDraftRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull @Size(max = 500) List<@Valid AnswerInput> answers) {}
    public record SubmitAttemptRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull @Size(max = 500) List<@Valid AnswerInput> answers) {}
    public record AttemptDraft(String id, String assessmentId, AttemptStatus status, long revision,
            List<AnswerInput> answers, Instant createdAt, Instant updatedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnswerResult(String assessmentQuestionId, List<String> submittedAnswer, BigDecimal score,
            Boolean correct, GradingStatus gradingStatus, List<String> correctAnswer, String analysis) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttemptResult(String attemptId, String assessmentId, String learnerId, AttemptStatus status,
            long revision, BigDecimal score, BigDecimal totalPoints, Instant submittedAt, Instant gradedAt,
            List<AnswerResult> answers) {}
    public record ManualGradeItem(@NotNull UUID assessmentQuestionId,
            @NotNull @DecimalMin("0.00") @DecimalMax("1000.00") BigDecimal score,
            @NotNull @Size(max = 2000) String comment) {}
    public record ManualGradeRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
            @NotNull @Size(min = 1, max = 500) List<@Valid ManualGradeItem> grades) {}
    public record AttemptResultPage(List<AttemptResult> items, int page, int size, long total) {}

    public record WrongQuestion(String id, String courseId, String assessmentId, String questionVersionId,
            int wrongCount, Instant firstWrongAt, Instant lastWrongAt) {}
    public record WrongQuestionPage(List<WrongQuestion> items, int page, int size, long total) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StudentLearningSummary(String learnerId, EnrollmentStatus enrollmentStatus,
            int completedLessonCount, long watchedSeconds, int submittedAttemptCount,
            int gradedAttemptCount, BigDecimal averageScore) {}
    public record StudentLearningPage(List<StudentLearningSummary> items, int page, int size, long total) {}
}
