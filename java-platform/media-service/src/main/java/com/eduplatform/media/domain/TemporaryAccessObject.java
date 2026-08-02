package com.eduplatform.media.domain;

import java.time.Instant;

/**
 * 一次 HLS 授权生成的临时播放清单对象。
 *
 * <p>数据库只保存精确 Object Key 与过期时间用于清理，不保存清单内的短期签名 URL。</p>
 */
public record TemporaryAccessObject(
        String grantId,
        String mediaId,
        String variantName,
        String objectKey,
        Instant expiresAt,
        Instant deletedAt,
        Instant createdAt) {}
