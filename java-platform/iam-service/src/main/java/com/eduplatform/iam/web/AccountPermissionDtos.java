package com.eduplatform.iam.web;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** T16.3权限命令HTTP模型；输入以OpenAPI为准，输出区分本域决定与真实课程联动状态，不返回审核原因。 */
public final class AccountPermissionDtos {
    private AccountPermissionDtos() {}
    public enum Action { REVOKE_TEACHING, RESTORE_TEACHING, BAN, UNBAN }
    public record PreviewRequest(@NotNull Action action) {}
    public record Preview(UUID previewId, UUID userId, Action action, long expectedRevision,
            int affectedCourses, long affectedStudents, Instant expiresAt) {}
    public record CommandRequest(@NotNull UUID clientRequestId, @NotNull UUID previewId,
            @NotNull @Min(0) Long expectedRevision, @NotNull Action action,
            @NotBlank @Size(min=10,max=500) String reason, @NotNull @AssertTrue Boolean confirmed) {}
    public record Command(UUID commandId, UUID userId, Action action, long decidedRevision,
            long accessRevision, UUID suspensionId, String coordinationStatus, int attempts,
            int maxAttempts, int affectedCourses, String lastErrorCode, boolean replayed) {}
    public record RetryRequest(@NotNull UUID clientRequestId, @Min(3) @Max(5) int expectedAttempts) {}
}
