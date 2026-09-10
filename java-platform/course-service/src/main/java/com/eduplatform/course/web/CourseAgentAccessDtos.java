package com.eduplatform.course.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

/** Agent Access 专用课程候选 DTO；owner 仅允许来自已认证的 Java 服务调用。 */
public final class CourseAgentAccessDtos {
    private CourseAgentAccessDtos() {}
    public record Candidate(@NotNull UUID entityId, @NotNull UUID courseId,
            @NotBlank @Size(max = 80) String courseVersionId, @NotNull UUID lessonId,
            @NotNull @Positive Long sourceRevision) {}
    public record VerifyRequest(@NotNull UUID ownerUserId,
            @NotEmpty @Size(max = 20) List<@Valid Candidate> candidates) {}
    public record VerifyResponse(List<UUID> allowedEntityIds) {}
}
