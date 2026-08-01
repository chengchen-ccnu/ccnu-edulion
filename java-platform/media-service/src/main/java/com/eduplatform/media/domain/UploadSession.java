package com.eduplatform.media.domain;

import java.time.Instant;

/**
 * 上传会话持久化快照。Object Key 和云端 uploadId 仅在 media-service 内部使用。
 */
public record UploadSession(
        String uploadId,
        String ownerId,
        String clientRequestId,
        String requestFingerprint,
        MediaPurpose purpose,
        UploadMode mode,
        UploadStatus status,
        String originalFilename,
        String extension,
        String declaredMimeType,
        long declaredSize,
        String declaredSha256,
        String objectKey,
        String providerUploadId,
        Long partSize,
        String completionFingerprint,
        String mediaId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {}
