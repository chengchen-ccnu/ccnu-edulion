package com.eduplatform.learning.messaging;

import com.eduplatform.learning.persistence.QuestionRepository.Outbox;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 创建冻结的学习域导入事件和 media-service 引用事件信封。 */
@Component
public class LearningEvents {
    public static final String LEARNING_EXCHANGE = "edu.learning.events.v1";
    public static final String LEARNING_EXCHANGE_V2 = "edu.learning.events.v2";
    public static final String MEDIA_EXCHANGE = "edu.media.events.v1";
    private final ObjectMapper mapper;

    public LearningEvents(ObjectMapper mapper) { this.mapper = mapper; }

    /**
     * 创建导入请求事件；payload 只有任务、媒体稳定 ID 与格式。
     * @return 待与 import task 同事务写入的 PENDING Outbox
     */
    public Outbox importRequested(String taskId, String mediaId, String format, String traceId, Instant now) {
        return event("QuestionImportRequested", 1, taskId, LEARNING_EXCHANGE, "question.import.requested.v1", traceId,
                Map.of("importTaskId", taskId, "sourceMediaId", mediaId, "sourceFormat", format), now);
    }

    /** 创建不含题目内容的 T09A 批次结果 v2，供迁移审计按 eventId 幂等消费。 */
    public Outbox legacyBatchProcessed(String taskId, String batchId, int itemCount, int importedCount,
            int reviewCount, int skippedCount, int failedCount, boolean dryRun, String traceId, Instant now) {
        return event("LegacyQuestionImportBatchProcessed", 2, taskId, LEARNING_EXCHANGE_V2,
                "question.legacy-import.batch.processed.v2", traceId,
                Map.of("importTaskId", taskId, "batchId", batchId, "itemCount", itemCount,
                        "importedCount", importedCount, "reviewCount", reviewCount,
                        "skippedCount", skippedCount, "failedCount", failedCount, "dryRun", dryRun), now);
    }

    /** 创建媒体引用绑定/解除 v1；aggregateId 必须是 mediaId。 */
    public Outbox mediaReference(String type, String mediaId, String referenceType, String referenceId,
            String traceId, Instant now) {
        String routing = "MediaReferenceBound".equals(type) ? "media.reference.bound.v1" : "media.reference.released.v1";
        return event(type, 1, mediaId, MEDIA_EXCHANGE, routing, traceId,
                Map.of("mediaId", mediaId, "ownerService", "learning-service", "referenceType", referenceType,
                        "referenceId", referenceId), now);
    }

    private Outbox event(String type, int version, String aggregateId, String exchange, String routing, String traceId,
            Map<String, Object> payload, Instant now) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId); envelope.put("eventType", type); envelope.put("eventVersion", version);
        envelope.put("aggregateId", aggregateId); envelope.put("occurredAt", now); envelope.put("traceId", traceId);
        envelope.put("payload", payload);
        try {
            return new Outbox(eventId, type, version, aggregateId, exchange, routing, traceId,
                    mapper.writeValueAsString(envelope), "PENDING", 0, now, null, now, null, now);
        } catch (Exception exception) {
            throw new IllegalStateException("Question event serialization failed", exception);
        }
    }
}
