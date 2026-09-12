package com.eduplatform.learning.web;

import com.eduplatform.learning.domain.QuestionModels;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    /** 历史题图授权的题目身份只由 Agent Access 从本人已保存消息中派生。 */
    public record QuestionVersionMediaAccessRequest(@NotNull UUID ownerUserId, @NotNull UUID questionId,
            @NotNull UUID questionVersionId, @NotNull UUID mediaId, @NotNull String variant,
            @NotNull LearningDtos.MediaDisposition disposition) {}
}
