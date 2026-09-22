package com.eduplatform.learning.messaging;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.PublishedLessonContentRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 消费 Course 发布内容身份；严格校验最小信封后在一个 Learning 事务中替换当前投影。 */
@Component
@ConditionalOnProperty(prefix = "platform.learning.course-content-messaging", name = "enabled", havingValue = "true")
public class CourseLessonContentEventListener {
    private final ObjectMapper mapper;
    private final LearningAssessmentRepository repository;

    public CourseLessonContentEventListener(ObjectMapper mapper, LearningAssessmentRepository repository) {
        this.mapper = mapper;
        this.repository = repository;
    }

    /** 无法解析或违反 v1 契约时抛回容器，有限重试后进入 DLQ；不会 ACK 半套投影。 */
    @RabbitListener(queues = CourseLessonContentRabbitConfig.QUEUE,
            containerFactory = "courseLessonContentContainerFactory")
    public void consume(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String eventId = uuid(root, "eventId");
            if (!"PublishedLessonContentChanged".equals(text(root, "eventType"))
                    || root.path("eventVersion").asInt(-1) != 1) throw invalid();
            instant(root, "occurredAt");
            String traceId = text(root, "traceId");
            if (traceId.length() < 8 || traceId.length() > 64) throw invalid();
            JsonNode payload = root.path("payload");
            String courseId = uuid(payload, "courseId");
            String courseVersionId = uuid(payload, "courseVersionId");
            if (!courseId.equals(uuid(root, "aggregateId"))) throw invalid();
            int publicationRevision = payload.path("publicationRevision").asInt(0);
            JsonNode lessonNodes = payload.path("lessons");
            if (publicationRevision < 1 || !lessonNodes.isArray() || lessonNodes.size() > 20000) throw invalid();
            Set<String> lessonIds = new HashSet<>();
            Set<String> revisions = new HashSet<>();
            List<PublishedLessonContentRow> lessons = new ArrayList<>();
            Instant now = Instant.now();
            for (JsonNode node : lessonNodes) {
                String lessonId = uuid(node, "lessonId");
                String revisionId = uuid(node, "contentRevisionId");
                String type = text(node, "lessonType");
                if (!Set.of("VIDEO", "ARTICLE", "ASSESSMENT").contains(type)
                        || !lessonIds.add(lessonId) || !revisions.add(revisionId)) throw invalid();
                lessons.add(new PublishedLessonContentRow(courseId, lessonId, revisionId,
                        type, publicationRevision, now));
            }
            repository.applyPublishedContentEvent(eventId, courseId, courseVersionId,
                    publicationRevision, List.copyOf(lessons), now);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid published lesson content event", failure);
        }
    }

    private static String uuid(JsonNode node, String name) {
        String value = text(node, name);
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException failure) { throw invalid(); }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static Instant instant(JsonNode node, String name) {
        try { return Instant.parse(text(node, name)); }
        catch (RuntimeException failure) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid published lesson content event");
    }
}
