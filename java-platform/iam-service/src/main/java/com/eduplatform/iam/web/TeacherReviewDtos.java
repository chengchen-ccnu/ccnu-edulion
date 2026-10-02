package com.eduplatform.iam.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 教师审核专用 REST 投影；列表绝不承载资质文件或联系方式。 */
public final class TeacherReviewDtos {
    private TeacherReviewDtos() {}
    public enum Status { SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED }
    public enum Order { NEWEST, OLDEST }
    public enum Decision { APPROVED, REJECTED }
    public enum Action { PREVIEW, DOWNLOAD }
    public record Summary(UUID id, UUID userId, String account, String displayName, Status status,
            long revision, Instant submittedAt) {}
    public record Page(List<Summary> items, int page, int size, long total) {}
    public record Round(UUID id, int roundNumber, UUID qualificationMediaId, String statement,
            Instant submittedAt, Status status, boolean materialAvailable, UUID firstViewedBy,
            Instant firstViewedAt, UUID decidedBy, Instant decidedAt, String rejectionReason,
            Instant retainUntil) {}
    public record Detail(UUID id, UUID userId, String account, String displayName, Status status,
            long revision, List<Round> rounds) {}
    /** 批准要求 materialConfirmed=true 且没有 rejectionReason；驳回要求 10～1000 字可理解理由。 */
    public record DecisionRequest(@NotNull UUID clientRequestId, @NotNull @Min(0) Long expectedRevision,
            @NotNull Decision decision, @NotNull Boolean materialConfirmed, String rejectionReason) {}
    /** 只选 IAM 已保存的轮次 ID，不接受外部 mediaId。 */
    public record AccessRequest(@NotNull UUID roundId, @NotNull Action action) {}
}
