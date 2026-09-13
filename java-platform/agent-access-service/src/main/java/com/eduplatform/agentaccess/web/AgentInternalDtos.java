package com.eduplatform.agentaccess.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/** Python 与 Agent Access 之间的私有 DTO；从类型层面排除浏览器 userId、Token 和临时 URL。 */
public final class AgentInternalDtos {
    private AgentInternalDtos() {}

    public record CourseCandidate(@NotNull UUID entityId, @NotNull UUID courseId,
            @NotBlank @Size(max = 80) String courseVersionId, @NotNull UUID lessonId,
            @NotNull @Positive Long sourceRevision) {}
    public record VerifyCourseCandidatesRequest(@NotEmpty @Size(max = 20)
            List<@Valid CourseCandidate> candidates) {}
    public record VerifyCourseCandidatesResponse(List<UUID> allowedEntityIds) {}

    public enum StudentFactTool {
        STUDENT_WRONG_QUESTIONS, STUDENT_SUBMISSIONS, STUDENT_PUBLISHED_GRADES, STUDENT_PROGRESS
    }
    public record StudentFactsRequest(@NotNull StudentFactTool toolName,
            @Size(max = 100) List<UUID> resourceIds, @Positive @Max(20) int limit) {}
    public record SafeFact(@NotNull UUID evidenceId, @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String summary, @NotNull Long revision) {}
    public record StudentFactsResponse(List<SafeFact> facts) {}

    public record QuestionVersionKey(@NotNull UUID questionId, @NotNull UUID questionVersionId) {}
    public record QuestionVersionProjectionRequest(@NotNull UUID ownerUserId,
            @NotEmpty @Size(max = 100) List<@Valid QuestionVersionKey> versions) {}
    public record QuestionOption(String key, String content) {}
    public record QuestionMedia(UUID id, UUID mediaId, String purpose, int sequence) {}
    public record QuestionVersionChildProjection(UUID questionId, UUID questionVersionId, int sequence,
            String type, String difficulty, String stage, String grade, String subject, String stem,
            List<QuestionOption> options, List<QuestionMedia> media) {}
    public record QuestionVersionProjection(UUID questionId, UUID questionVersionId, String type,
            String difficulty, String stage, String grade, String subject, String stem,
            List<QuestionOption> options, List<QuestionMedia> media,
            List<QuestionVersionChildProjection> children) {}
    public record QuestionVersionProjectionResponse(List<QuestionVersionProjection> items) {}
    public record QuestionVersionMediaAccessRequest(@NotNull UUID ownerUserId, @NotNull UUID questionId,
            @NotNull UUID questionVersionId, @NotNull UUID mediaId, @NotBlank String variant,
            @NotBlank String disposition) {}
    public record MediaAccessGrant(UUID mediaId, String mimeType, long sizeBytes, String method, String url,
            Map<String, String> headers, Instant expiresAt) {}

    /** 帮助查询不接受 userId、版本或路由，可信 owner 与当前版本均由 Agent Access 决定。 */
    public record UserHelpSearchRequest(@NotBlank @Size(max = 500) String query,
            @Positive @Max(5) int limit) {}
    public record UserHelpEvidence(@NotBlank @Size(max = 128) String evidenceId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String summary,
            @NotBlank @Size(max = 32) String version,
            boolean directAnswer,
            @Size(max = 120) String appRoute) {}
    public record UserHelpSearchResponse(@NotBlank String version, List<UserHelpEvidence> evidence) {}

    public record RunResultReportRequest(@NotNull UUID reportId,
            @NotBlank @Size(max = 120) String model,
            @PositiveOrZero @Max(16) int modelCalls,
            @PositiveOrZero @Max(16) int visionCalls,
            @PositiveOrZero @Max(20) int toolAttempts,
            @PositiveOrZero @Max(10_000_000) long promptTokens,
            @PositiveOrZero @Max(10_000_000) long completionTokens,
            @NotNull @Size(max = 32) Map<@NotNull @Pattern(regexp = "^[A-Z0-9_]{2,64}$") String,
                    @NotNull @PositiveOrZero @Max(180_000) Long> phaseDurationsMs,
            @NotBlank @Size(max = 80) String terminationReason,
            @NotNull @Size(max = 8) List<@NotBlank @Size(max = 500) String> memoryCandidates) {}
    public record RunResultReportResponse(UUID runId, UUID reportId, boolean replayed) {}

    public record PracticeCandidateKey(@NotNull UUID questionId, @NotNull UUID questionVersionId) {}
    public record PracticeCriteria(@NotBlank @Size(max = 200) String topic,
            @NotNull AgentDtos.PracticeDifficulty difficulty,
            @Positive @Max(20) int requestedCount) {}
    public record ValidatePracticeCandidatesRequest(@NotNull UUID ownerUserId,
            @NotNull @Valid PracticeCriteria criteria,
            @NotNull @Size(max = 20) List<@Valid PracticeCandidateKey> candidates) {}
    public record ValidatedPracticeCandidate(UUID questionId, UUID questionVersionId,
            AgentDtos.PracticeQuestionType questionType, AgentDtos.PracticeDifficulty difficulty) {}
    public record ValidatePracticeCandidatesResponse(List<ValidatedPracticeCandidate> candidates) {}
    public record CreateAutonomousPracticeRequest(@NotNull UUID actionId, @NotNull UUID ownerUserId,
            @NotNull @Valid PracticeCriteria criteria,
            @NotNull @Size(min = 1, max = 20) List<@Valid PracticeCandidateKey> candidates) {}
    public record AutonomousPracticeResult(UUID autonomousPracticeId, UUID assessmentId,
            String startRoute, boolean replayed) {}
    public record FindAutonomousPracticeRequest(@NotNull UUID actionId, @NotNull UUID ownerUserId) {}
}
