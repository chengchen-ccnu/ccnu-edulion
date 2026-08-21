package com.eduplatform.media.domain;

import java.time.Instant;

/** 已确认 OSS 对象的稳定媒体资产；领域服务只引用 {@code mediaId}。 */
public record MediaAsset(
        String mediaId,
        String uploadId,
        String ownerId,
        MediaAssetScope scope,
        MediaPurpose purpose,
        MediaStatus status,
        ProcessingStatus processingStatus,
        String objectKey,
        String originalFilename,
        String mimeType,
        long size,
        String sha256,
        String etag,
        String failureCode,
        String failureMessage,
        Instant unboundExpiresAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {}
