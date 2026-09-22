package com.eduplatform.learning.web;

import com.eduplatform.learning.domain.QuestionModels;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

/** Agent Access 专用学习事实 DTO；没有官方答案、解析、其他用户身份或临时 URL 字段。 */
public final class AgentAccessDtos {
    private AgentAccessDtos() {}
    public enum ToolName {
        STUDENT_WRONG_QUESTIONS, STUDENT_SUBMISSIONS, STUDENT_PUBLISHED_GRADES, STUDENT_PROGRESS
    }
    public record QueryRequest(@NotNull UUID ownerUserId, @NotNull ToolName toolName,
            @Size(max = 100) List<UUID> resourceIds, @Min(1) @Max(20) int limit) {}
    public record SafeFact(UUID evidenceId, String title, String summary, long revision) {}
    public record QueryResponse(List<SafeFact> facts) {}

    /** 一页 Agent 消息去重后的不可变根题版本键；questionId 与 versionId 必须同时匹配。 */
    public record QuestionVersionKey(@NotNull UUID questionId, @NotNull UUID questionVersionId) {}
    public record QuestionVersionProjectionRequest(@NotNull UUID ownerUserId,
            @NotNull @Size(min = 1, max = 100) List<QuestionVersionKey> versions) {}
    public record QuestionVersionProjection(UUID questionId, UUID questionVersionId, QuestionModels.Type type,
            QuestionModels.Difficulty difficulty, QuestionModels.Stage stage, QuestionModels.Grade grade,
            QuestionModels.Subject subject, String stem, List<QuestionDtos.OptionInput> options,
            List<QuestionDtos.MediaResponse> media,
            List<QuestionVersionChildProjection> children) {}
    public record QuestionVersionChildProjection(UUID questionId, UUID questionVersionId, int sequence,
            QuestionModels.Type type, QuestionModels.Difficulty difficulty, QuestionModels.Stage stage,
            QuestionModels.Grade grade, QuestionModels.Subject subject, String stem, List<QuestionDtos.OptionInput> options,
            List<QuestionDtos.MediaResponse> media) {}
    public record QuestionVersionProjectionResponse(List<QuestionVersionProjection> items) {}

    /** Java 可信条件；普通 bean 保留严格 Jackson 下可选补齐字段缺失为 false 的兼容语义。 */
    public static final class PracticeCriteria {
        @com.fasterxml.jackson.annotation.JsonProperty @NotBlank @Size(max = 200) private String topic;
        @com.fasterxml.jackson.annotation.JsonProperty @NotNull private QuestionModels.Difficulty difficulty;
        @com.fasterxml.jackson.annotation.JsonProperty @Min(1) @Max(20) private int requestedCount;
        @com.fasterxml.jackson.annotation.JsonProperty private boolean allowDifficultyFallback;
        public PracticeCriteria() {}
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        public PracticeCriteria(String topic, QuestionModels.Difficulty difficulty, int requestedCount,
                boolean allowDifficultyFallback) {
            this.topic = topic; this.difficulty = difficulty; this.requestedCount = requestedCount;
            this.allowDifficultyFallback = allowDifficultyFallback;
        }
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        public PracticeCriteria(String topic, QuestionModels.Difficulty difficulty, int requestedCount) {
            this(topic, difficulty, requestedCount, false);
        }
        public String topic() { return topic; }
        public QuestionModels.Difficulty difficulty() { return difficulty; }
        public int requestedCount() { return requestedCount; }
        public boolean allowDifficultyFallback() { return allowDifficultyFallback; }
    }
    public record PracticeCandidateKey(@NotNull UUID questionId, @NotNull UUID questionVersionId) {}
    public record ValidatePracticeCandidatesRequest(@NotNull UUID ownerUserId,
            @NotNull @Valid PracticeCriteria criteria,
            @NotNull @Size(max = 20) List<@Valid PracticeCandidateKey> candidates) {}
    public record ValidatedPracticeCandidate(UUID questionId, UUID questionVersionId,
            QuestionModels.Type questionType, QuestionModels.Difficulty difficulty) {}
    public record ValidatePracticeCandidatesResponse(List<ValidatedPracticeCandidate> candidates) {}
    public record CreateAutonomousPracticeRequest(@NotNull UUID actionId, @NotNull UUID ownerUserId,
            @NotNull @Valid PracticeCriteria criteria,
            @NotNull @Size(min = 1, max = 20) List<@Valid PracticeCandidateKey> candidates) {}
    public record AutonomousPracticeResult(UUID autonomousPracticeId, UUID assessmentId,
            String startRoute, boolean replayed) {}
    public record FindAutonomousPracticeRequest(@NotNull UUID actionId, @NotNull UUID ownerUserId) {}

    /** 历史题图授权的题目身份只由 Agent Access 从本人已保存消息中派生。 */
    public record QuestionVersionMediaAccessRequest(@NotNull UUID ownerUserId, @NotNull UUID questionId,
            @NotNull UUID questionVersionId, @NotNull UUID mediaId, @NotNull String variant,
            @NotNull LearningDtos.MediaDisposition disposition) {}
}
