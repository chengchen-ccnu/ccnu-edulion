package com.eduplatform.agentaccess.messaging;

import com.eduplatform.agentaccess.config.AgentIndexProperties;
import com.eduplatform.agentaccess.config.AgentRetentionProperties;
import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.AgentRepository.MediaOutboxRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 可靠发布 Agent 消息媒体引用 Outbox；失败有限重试并保留，不记录事件正文。 */
@Component
@ConditionalOnProperty(prefix = "platform.agent-retention", name = "media-publish-enabled", havingValue = "true")
public class AgentMediaOutboxPublisher {
    private final AgentRepository repository;
    private final RabbitTemplate rabbit;
    private final AgentRetentionProperties retention;
    private final AgentIndexProperties index;

    public AgentMediaOutboxPublisher(AgentRepository repository, RabbitTemplate rabbit,
            AgentRetentionProperties retention, AgentIndexProperties index) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.retention = retention;
        this.index = index;
        rabbit.setMandatory(true);
    }

    /** 每轮最多发布 50 条，单条失败不阻塞后续事件。 */
    @Scheduled(fixedDelayString = "${platform.agent-retention.media-outbox-poll-delay:2s}")
    public void publishPending() {
        for (MediaOutboxRow event : repository.pendingMediaOutbox(Instant.now(), 50)) publish(event);
    }

    /** 发布一条已持久事件；输入只含稳定 ID 信封，输出为 PUBLISHED 或有界退避/FAILED。 */
    public void publish(MediaOutboxRow event) {
        CorrelationData correlation = new CorrelationData(event.eventId().toString());
        MessageProperties headers = new MessageProperties();
        headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        headers.setContentEncoding(StandardCharsets.UTF_8.name());
        headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        headers.setMessageId(event.eventId().toString());
        try {
            rabbit.send(event.exchange(), event.routingKey(),
                    new Message(event.payloadJson().getBytes(StandardCharsets.UTF_8), headers), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    index.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) throw new IllegalStateException("publish rejected");
            repository.markMediaOutboxPublished(event.eventId(), Instant.now());
        } catch (Exception failure) {
            int attempt = event.attempts() + 1;
            long delay = Math.min(300, 1L << Math.min(attempt, 8));
            repository.markMediaOutboxFailure(event.eventId(), attempt,
                    attempt >= retention.maxPublishAttempts(), Instant.now().plusSeconds(delay),
                    failure.getClass().getSimpleName(), Instant.now());
        }
    }
}
