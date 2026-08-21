package com.eduplatform.media.web;

import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.MediaAssetScope;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.domain.ProcessingStatus;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.domain.UploadStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** T07 OpenAPI 的 HTTP 投影；不包含 Object Key、Bucket、云端 uploadId 或长期凭据。 */
public final class MediaDtos {
    private MediaDtos() {}

    public record CreateUploadRequest(
            @NotNull UUID clientRequestId,
            @NotNull MediaPurpose purpose,
            @NotBlank @Size(max = 255) String fileName,
            @Positive long size,
            @NotBlank @Size(max = 127) String mimeType,
            @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) {}

    /** 外部历史图片只在本次调用内使用 HTTPS URL；服务端不得持久化或记录该字段。 */
    public record ExternalImageTransferRequest(@NotNull UUID clientRequestId,
            @NotBlank @Size(max = 2000) @Pattern(regexp = "^https://.*$") String sourceUrl,
            @NotNull MediaAssetScope scope) {}

    public record AuthorizePartRequest(
            @Positive @Max(10000) int partNumber,
            @Positive long contentLength,
            @Pattern(regexp = "^[A-Za-z0-9+/]{22}==$") String contentMd5) {}

    public record CompleteUploadRequest(
            @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256,
            @NotNull @Size(max = 10000) List<@Valid CompletedPart> parts) {}

    public record CompletedPart(@Positive @Max(10000) int partNumber, @NotBlank @Size(max = 128) String etag) {}

    public record CreateAccessGrantRequest(
            @NotBlank @Size(max = 80) String variant,
            @NotNull Disposition disposition) {}

    public enum Disposition { INLINE, ATTACHMENT }

    public record UploadGrant(String method, String url, Map<String, String> headers, Instant expiresAt) {}

    public record UploadedPart(int partNumber, String etag, long size) {}

    public record MultipartUpload(long partSize, int maxParts, List<UploadedPart> uploadedParts) {}

    public record UploadSession(
            String uploadId,
            String clientRequestId,
            MediaPurpose purpose,
            UploadMode mode,
            UploadStatus status,
            String fileName,
            long size,
            String mimeType,
            String sha256,
            UploadGrant uploadGrant,
            MultipartUpload multipart,
            String mediaId,
            Instant expiresAt,
            Instant createdAt) {}

    /** 派生对象摘要；HLS 的 componentCount 是已登记私有切片数，单对象变体为 0。 */
    public record Variant(String variant, String mimeType, long size, int componentCount) {}

    public record BindingValidationRequest(@NotNull MediaPurpose purpose) {}

    /** 成功响应本身证明 owner、用途和 READY 状态均已由 media-service 校验。 */
    public record BindingValidation(String mediaId, MediaPurpose purpose, MediaStatus status, boolean bindable) {}

    public record Asset(
            String mediaId,
            MediaAssetScope scope,
            MediaPurpose purpose,
            MediaStatus status,
            ProcessingStatus processingStatus,
            String originalFileName,
            String mimeType,
            long size,
            String sha256,
            List<Variant> variants,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {}

    public record AssetPage(List<Asset> items, int page, int size, long total) {}

    public record AccessGrant(String method, String url, Map<String, String> headers, Instant expiresAt) {}
}
