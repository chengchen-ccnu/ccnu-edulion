package com.eduplatform.learning.web;

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
}
