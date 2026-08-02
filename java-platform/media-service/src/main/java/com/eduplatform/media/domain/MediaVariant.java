package com.eduplatform.media.domain;

import java.time.Instant;

/** 由 Worker 从原始私有对象生成的派生资产元数据。 */
public record MediaVariant(
        String variantId,
        String mediaId,
        String variantName,
        String objectKey,
        String mimeType,
        long size,
        String etag,
        Instant createdAt) {}
