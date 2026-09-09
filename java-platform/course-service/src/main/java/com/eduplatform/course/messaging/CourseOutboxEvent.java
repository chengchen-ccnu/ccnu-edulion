package com.eduplatform.course.messaging;

import java.time.Instant;

/**
 * course-service 本地 Outbox 记录；payloadJson 是冻结的媒体事件 v1 信封，不包含签名 URL 或存储信息。
 */
public record CourseOutboxEvent(
        String eventId,
        String eventType,
        int eventVersion,
        String aggregateId,
        String exchange,
        String routingKey,
        String traceId,
        String payloadJson,
        String status,
        int attempts,
        Instant nextAttemptAt,
        Instant publishedAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {}
