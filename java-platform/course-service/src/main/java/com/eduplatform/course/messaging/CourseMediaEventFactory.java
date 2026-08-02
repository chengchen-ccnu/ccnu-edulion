package com.eduplatform.course.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 生成 MediaReferenceBound/Released v1 信封，供课程事务与 Outbox 原子提交。
 */
@Component
public class CourseMediaEventFactory {
    public static final String BOUND = "MediaReferenceBound";
    public static final String RELEASED = "MediaReferenceReleased";
    private final ObjectMapper objectMapper;

    public CourseMediaEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一个课程媒体引用事件。
     *
     * @param eventType 只能是 MediaReferenceBound 或 MediaReferenceReleased
     * @param mediaId 作为 aggregateId 的稳定媒体 ID
     * @param referenceType COURSE_VERSION_COVER 或 LESSON_RESOURCE
     * @param referenceId 课程版本 ID 或课时资源引用行 ID
     * @param traceId 触发本次课程变更的调用链 ID
     * @return 待在同一课程数据库事务中插入的 PENDING Outbox 记录
     */
    public CourseOutboxEvent create(String eventType, String mediaId, String referenceType,
            String referenceId, String traceId) {
        if (!BOUND.equals(eventType) && !RELEASED.equals(eventType)) {
            throw new IllegalArgumentException("Unsupported course media reference event");
        }
        Instant now = Instant.now();
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = Map.of(
                "mediaId", mediaId,
                "ownerService", "course-service",
                "referenceType", referenceType,
                "referenceId", referenceId);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("aggregateId", mediaId);
        envelope.put("occurredAt", now);
        envelope.put("traceId", traceId);
        envelope.put("payload", payload);
        try {
            String routingKey = BOUND.equals(eventType)
                    ? "media.reference.bound.v1" : "media.reference.released.v1";
            return new CourseOutboxEvent(eventId, eventType, 1, mediaId, routingKey, traceId,
                    objectMapper.writeValueAsString(envelope), "PENDING", 0, now, null, null, now, now);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize course media event", exception);
        }
    }
}
