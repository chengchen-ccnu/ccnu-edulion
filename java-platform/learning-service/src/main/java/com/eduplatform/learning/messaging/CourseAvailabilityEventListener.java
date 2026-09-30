package com.eduplatform.learning.messaging;

import com.eduplatform.learning.course.CourseTaskClock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 校验 Course 系统暂停事件并按 eventId 追平；同步失败抛回容器，三次尝试后 DLQ，不 ACK 半次补时。 */
@Component
@ConditionalOnProperty(prefix="platform.learning.suspension-messaging", name="enabled", havingValue="true")
public class CourseAvailabilityEventListener {
    private final CourseTaskClock clock;
    private final ObjectMapper mapper;
    public CourseAvailabilityEventListener(CourseTaskClock clock, ObjectMapper mapper) { this.clock = clock; this.mapper = mapper; }

    /** payload 是提示，绝不按到达顺序覆盖时钟；乱序仍通过权威 API 一次补齐全部实际区间。 */
    @RabbitListener(queues=CourseAvailabilityRabbitConfig.QUEUE, containerFactory="courseAvailabilityContainerFactory")
    public void consume(String json) {
        JsonNode root = mapper.readTree(json);
        String eventId = uuid(root, "eventId");
        if (!"CourseAvailabilityChanged".equals(text(root, "eventType"))
                || !root.path("eventVersion").isIntegralNumber() || !root.path("eventVersion").canConvertToInt() || root.path("eventVersion").asInt() != 1) throw invalid();
        Instant.parse(text(root, "occurredAt"));
        String trace = text(root, "traceId");
        if (!trace.matches("[A-Za-z0-9_-]{8,64}")) throw invalid();
        JsonNode payload = root.path("payload");
        String courseId = uuid(payload, "courseId");
        uuid(payload, "suspensionId");
        if (!courseId.equals(uuid(root, "aggregateId")) || !payload.path("paused").isBoolean()
                || !payload.path("availabilityRevision").isIntegralNumber()
                || !payload.path("availabilityRevision").canConvertToLong()
                || payload.path("availabilityRevision").asLong() < 1) throw invalid();
        Instant started = Instant.parse(text(payload, "suspendedAt"));
        JsonNode resumed = payload.get("resumedAt");
        if (resumed == null || (payload.path("paused").asBoolean() && !resumed.isNull())
                || (!payload.path("paused").asBoolean() && (!resumed.isTextual() || Instant.parse(resumed.asText()).isBefore(started)))) throw invalid();
        clock.consume(eventId, courseId, payload.path("availabilityRevision").asLong());
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }
    private static String uuid(JsonNode node, String key) { return UUID.fromString(text(node, key)).toString(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid course availability event"); }
}
