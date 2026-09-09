package com.eduplatform.course.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 为发布/下架课程 ARTICLE 生成不含正文的 Agent 索引通知。 */
@Component
public class CourseAgentIndexEventFactory {
    private final ObjectMapper mapper;

    public CourseAgentIndexEventFactory(ObjectMapper mapper) { this.mapper = mapper; }

    /**
     * @param operation UPSERT 表示当前发布，DELETE 表示旧版本归档或课程下架
     * @param sourceRevision 同一 lesson 稳定递增；首个发布为 1，删除墓碑为 2
     */
    public CourseOutboxEvent create(String courseId, String courseVersionId, String lessonId,
            long sourceRevision, String operation, String traceId, Instant now) {
        String eventId = UUID.randomUUID().toString();
        String state = "UPSERT".equals(operation) ? "PUBLISHED" : "ARCHIVED";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceDomain", "COURSE"); payload.put("entityType", "COURSE_TEXT");
        payload.put("entityId", lessonId); payload.put("courseId", courseId);
        payload.put("courseVersionId", courseVersionId); payload.put("lessonId", lessonId);
        payload.put("sourceRevision", sourceRevision); payload.put("operation", operation);
        payload.put("sourceState", state); payload.put("educationStage", "JUNIOR");
        payload.put("visibility", "COURSE_AUTHORIZED");
        payload.put("authorization", Map.of("requiresCurrentCourseAccess", true,
                "projectionApi", "COURSE_AUTHORIZED_TEXT_PROJECTION"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId); envelope.put("eventType", "CourseTextIndexProjectionChanged");
        envelope.put("eventVersion", 1); envelope.put("aggregateId", courseId);
        envelope.put("occurredAt", now); envelope.put("traceId", traceId); envelope.put("payload", payload);
        try {
            return new CourseOutboxEvent(eventId, "CourseTextIndexProjectionChanged", 1, courseId,
                    CourseRabbitConfig.AGENT_INDEX_EVENTS_EXCHANGE, "agent.index.course-text.changed.v1", traceId,
                    mapper.writeValueAsString(envelope), "PENDING", 0, now, null, null, now, now);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to serialize course Agent index event", failure);
        }
    }
}
