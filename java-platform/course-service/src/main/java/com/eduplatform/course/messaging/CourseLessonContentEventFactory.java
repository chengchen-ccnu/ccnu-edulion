package com.eduplatform.course.messaging;

import com.eduplatform.course.domain.LessonEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 为一次课程发布生成完整、无媒体内容的当前课时修订映射事件。 */
@Component
public class CourseLessonContentEventFactory {
    private final ObjectMapper mapper;

    public CourseLessonContentEventFactory(ObjectMapper mapper) { this.mapper = mapper; }

    /**
     * @param lessons 当前即将公开的全部课时；每项必须已有 Course 生成的 contentRevisionId
     * @return 与发布事务一并写入 Outbox 的 v1 事件；不包含 mediaId、正文或学习事实
     */
    public CourseOutboxEvent create(String courseId, String courseVersionId, int publicationRevision,
            List<LessonEntity> lessons, String traceId, Instant now) {
        String eventId = UUID.randomUUID().toString();
        List<Map<String, Object>> lessonPayloads = lessons.stream().map(lesson -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("lessonId", lesson.getId());
            value.put("contentRevisionId", lesson.getContentRevisionId());
            value.put("lessonType", lesson.getLessonType());
            return value;
        }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("courseId", courseId);
        payload.put("courseVersionId", courseVersionId);
        payload.put("publicationRevision", publicationRevision);
        payload.put("lessons", lessonPayloads);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "PublishedLessonContentChanged");
        envelope.put("eventVersion", 1);
        envelope.put("aggregateId", courseId);
        envelope.put("occurredAt", now);
        envelope.put("traceId", traceId);
        envelope.put("payload", payload);
        try {
            return new CourseOutboxEvent(eventId, "PublishedLessonContentChanged", 1, courseId,
                    CourseRabbitConfig.LESSON_CONTENT_EVENTS_EXCHANGE, "course.lesson-content.published.v1", traceId,
                    mapper.writeValueAsString(envelope), "PENDING", 0, now, null, null, now, now);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to serialize published lesson content event", failure);
        }
    }
}
