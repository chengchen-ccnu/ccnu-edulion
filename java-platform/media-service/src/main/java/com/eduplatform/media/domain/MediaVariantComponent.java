package com.eduplatform.media.domain;

import java.time.Instant;

/** 一个 HLS 播放清单拥有的私有切片对象；仅 media-service 可见其精确 Object Key。 */
public record MediaVariantComponent(
        String componentId,
        String variantId,
        String mediaId,
        String componentName,
        int position,
        String objectKey,
        String mimeType,
        long size,
        String etag,
        Instant createdAt) {}
