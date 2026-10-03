package com.eduplatform.course.messaging;

import java.time.Instant;

/**
 * course-service 本地 Outbox 记录；payloadJson遵循所属媒体引用、内容、可用性或门户事件版本，
 * 只承载可靠信封，不包含签名URL、凭据或存储信息；同课程事务提交后有限确认发布。
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
