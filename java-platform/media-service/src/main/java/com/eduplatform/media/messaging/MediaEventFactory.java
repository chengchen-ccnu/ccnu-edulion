package com.eduplatform.media.messaging;

import com.eduplatform.media.persistence.MediaRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 创建符合统一 v1 信封的媒体 Outbox 记录，并传播调用链 Trace ID。 */
@Component
public class MediaEventFactory {
    private final ObjectMapper objectMapper;

    public MediaEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成新的 eventId 并将信封序列化为 PENDING Outbox 记录。
     * @param aggregateId 稳定 mediaId，不是 OSS Object Key
     * @param payload 事件版本内已冻结的业务负载
     * @return 应与业务变更在同一本地事务中插入的 Outbox 记录
     */
    public MediaRepository.OutboxEvent create(String eventType, String aggregateId, String routingKey,
            String traceId, Map<String, Object> payload, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("aggregateId", aggregateId);
        envelope.put("occurredAt", now);
        envelope.put("traceId", traceId);
        envelope.put("payload", payload);
        try {
            return new MediaRepository.OutboxEvent(eventId, eventType, 1, aggregateId, routingKey, traceId,
                    objectMapper.writeValueAsString(envelope), "PENDING", 0, now, null, null, now, now);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize media event", exception);
        }
    }
}
