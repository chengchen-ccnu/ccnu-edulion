package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.config.AgentProperties;
import com.eduplatform.agentaccess.config.AgentRetentionProperties;
import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.AgentRepository.MediaOutboxRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 按稳定游标和有限批次清理原始消息，并在同事务释放真实存在的 AI 图片引用。 */
@Component
@ConditionalOnProperty(prefix = "platform.agent-retention", name = "enabled", havingValue = "true")
public class MessageRetentionService {
    private final AgentRepository repository;
    private final AgentProperties agent;
    private final AgentRetentionProperties retention;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public MessageRetentionService(AgentRepository repository, AgentProperties agent,
            AgentRetentionProperties retention, PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.repository = repository;
        this.agent = agent;
        this.retention = retention;
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
    }

    /** 每轮仅提交一个小事务；到表尾重置游标，下一周期再从最老仍保留正文开始。 */
    @Scheduled(fixedDelayString = "${platform.agent-retention.poll-delay:1h}")
    public void purgeDue() {
        transactions.executeWithoutResult(ignored -> {
            var cursor = repository.lockRetentionCursor();
            Instant now = Instant.now();
            Instant cutoff = now.minus(Math.max(1, agent.rawMessageRetentionDays()), ChronoUnit.DAYS);
            var batch = repository.retentionBatch(cursor, cutoff, Math.max(1, Math.min(500, retention.batchSize())));
            if (batch.isEmpty()) {
                repository.advanceRetentionCursor(new AgentRepository.RetentionCursor(null, null), now);
                return;
            }
            for (var message : batch) {
                if (!repository.purgeMessage(message.messageId(), now)) continue;
                for (var reference : repository.boundMessageMedia(message.messageId())) {
                    repository.releaseMessageMedia(reference.messageId(), reference.mediaId(), now);
                    repository.insertMediaOutbox(releaseEvent(reference.mediaId(), reference.messageId(), now), now);
                }
            }
            var last = batch.get(batch.size() - 1);
            repository.advanceRetentionCursor(new AgentRepository.RetentionCursor(last.createdAt(), last.messageId()), now);
        });
    }

    /** Media v1 释放事件只保存稳定 ID；eventId 由 Media ledger 负责幂等消费。 */
    private MediaOutboxRow releaseEvent(UUID mediaId, UUID messageId, Instant now) {
        UUID eventId = UUID.randomUUID();
        String traceId = "retention-" + eventId;
        Map<String, Object> payload = Map.of("mediaId", mediaId, "ownerService", "agent-access",
                "referenceType", "AI_MESSAGE_IMAGE", "referenceId", messageId.toString());
        try {
            String json = mapper.writeValueAsString(Map.of("eventId", eventId, "eventType", "MediaReferenceReleased",
                    "eventVersion", 1, "aggregateId", mediaId, "occurredAt", now, "traceId", traceId,
                    "payload", payload));
            return new MediaOutboxRow(eventId, "MediaReferenceReleased", mediaId, "edu.media.events.v1",
                    "media.reference.released.v1", traceId, json, 0);
        } catch (Exception failure) {
            throw new IllegalStateException("safe media event serialization failed", failure);
        }
    }
}
