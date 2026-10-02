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
    public static final String ENROLLMENT_EXCHANGE = "edu.learning.enrollment.events.v1";
    public static final String AGENT_INDEX_EXCHANGE = "edu.agent.index.events.v1";
    private final ObjectMapper mapper;

    public LearningEvents(ObjectMapper mapper) { this.mapper = mapper; }

    /** 任务开放/截止/立即结束的最小通知事实；触发时点固定收件人，不发布题干、答案或媒体信息。 */
    public Outbox taskNotificationChanged(String assessmentId,String courseId,long revision,String action,
            Instant triggeredAt,String traceId,Instant now) {
        return event("CourseTaskNotificationChanged",1,assessmentId,"edu.learning.task-notifications.events.v1",
                "learning.task.notification.changed.v1",traceId,
                Map.of("assessmentId",assessmentId,"courseId",courseId,"revision",revision,"action",action,"triggeredAt",triggeredAt),now);
    }

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

    /** 创建课程访问投影事件；ACTIVE/CANCELED 都由同一业务键按 changedAt 向前演进。 */
    public Outbox enrollmentChanged(String enrollmentId, String learnerId, String courseId, String status,
            String courseVersionId, String traceId, Instant now) {
        return event("EnrollmentAccessChanged", 1, enrollmentId, ENROLLMENT_EXCHANGE,
                "learning.enrollment.access-changed.v1", traceId,
                Map.of("enrollmentId", enrollmentId, "learnerId", learnerId, "courseId", courseId,
                        "status", status, "changedAt", now, "courseVersionId", courseVersionId), now);
    }

    /** 创建 ACTIVE SYSTEM 题索引通知；正文、答案、解析和媒体 URL 由事件明确排除。 */
    public Outbox questionIndexChanged(String questionId, String versionId, long sourceRevision,
            String grade, String traceId, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceDomain", "LEARNING");
        payload.put("entityType", "QUESTION");
        payload.put("entityId", questionId);
        payload.put("questionVersionId", versionId);
        payload.put("sourceRevision", sourceRevision);
        payload.put("operation", "UPSERT");
        payload.put("sourceState", "ACTIVE");
        payload.put("educationStage", "JUNIOR");
        payload.put("grade", grade);
        payload.put("visibility", "PUBLIC_SYSTEM");
        payload.put("authorization", Map.of("questionScope", "ACTIVE_SYSTEM_ONLY",
                "projectionApi", "LEARNING_QUESTION_SAFE_PROJECTION"));
        return event("QuestionIndexProjectionChanged", 1, questionId, AGENT_INDEX_EXCHANGE,
                "agent.index.question.changed.v1", traceId,
                payload, now);
    }

    /** 既有公共根题被收编为组合子题时创建 tombstone，防止 Worker 保留先前已索引的根题。 */
    public Outbox questionIndexDeleted(String questionId, String versionId, long sourceRevision,
            String traceId, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceDomain", "LEARNING");
        payload.put("entityType", "QUESTION");
        payload.put("entityId", questionId);
        payload.put("questionVersionId", versionId);
        payload.put("sourceRevision", sourceRevision);
        payload.put("operation", "DELETE");
        payload.put("sourceState", "DELETED");
        payload.put("educationStage", "JUNIOR");
        payload.put("visibility", "PUBLIC_SYSTEM");
        payload.put("authorization", Map.of("questionScope", "ACTIVE_SYSTEM_ONLY",
                "projectionApi", "LEARNING_QUESTION_SAFE_PROJECTION"));
        return event("QuestionIndexProjectionChanged", 1, questionId, AGENT_INDEX_EXCHANGE,
                "agent.index.question.changed.v1", traceId, payload, now);
    }

    /** 创建知识点索引通知；关系变化同样提升节点 revision 后按节点重新读取。 */
    public Outbox knowledgeIndexChanged(String knowledgeId, long sourceRevision, String traceId, Instant now) {
        return event("KnowledgePointIndexProjectionChanged", 1, knowledgeId, AGENT_INDEX_EXCHANGE,
                "agent.index.knowledge-point.changed.v1", traceId,
                Map.of("sourceDomain", "LEARNING", "entityType", "KNOWLEDGE_POINT", "entityId", knowledgeId,
                        "sourceRevision", sourceRevision, "operation", "UPSERT", "sourceState", "ACTIVE",
                        "educationStage", "JUNIOR", "visibility", "PUBLIC_MATH_KNOWLEDGE",
                        "authorization", Map.of("enabledOnly", true,
                                "projectionApi", "LEARNING_KNOWLEDGE_SAFE_PROJECTION",
                                "graphProjection", "BOUNDED_PREREQUISITE_RELATIONS")), now);
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
