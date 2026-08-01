package com.eduplatform.media.messaging;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 严格解析媒体 v1 事件信封，拒绝缺失字段、非 UUID 标识或未支持版本。 */
@Component
public class MediaEventParser {
    private final ObjectMapper objectMapper;

    public MediaEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @return 结构化事件；JSON/信封不合法时抛出 {@link IllegalArgumentException}。 */
    public MediaDomainEvent parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String eventId = required(node, "eventId");
            String aggregateId = required(node, "aggregateId");
            UUID.fromString(eventId);
            UUID.fromString(aggregateId);
            int version = node.path("eventVersion").asInt(-1);
            if (version != 1) throw new IllegalArgumentException("Unsupported eventVersion");
            JsonNode payload = node.get("payload");
            if (payload == null || !payload.isObject()) throw new IllegalArgumentException("Missing payload");
            return new MediaDomainEvent(eventId, required(node, "eventType"), version, aggregateId,
                    required(node, "occurredAt"), required(node, "traceId"), payload, json);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid media event", exception);
        }
    }

    /** 读取不可为空的文本字段，供信封与 payload 消费者共用。 */
    public String required(JsonNode node, String field) {
        String value = node.path(field).asString();
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }
}
