package com.eduplatform.agentaccess.messaging;

import com.eduplatform.agentaccess.config.AgentIndexProperties;
import com.eduplatform.agentaccess.persistence.MemoryProfileRepository;
import com.eduplatform.agentaccess.persistence.MemoryProfileRepository.OutboxRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 使用 mandatory、publisher confirm 和有限退避发布记忆 Outbox；失败事实留库供重投。 */
@Component
@ConditionalOnProperty(prefix = "platform.agent-index", name = "publish-enabled", havingValue = "true")
public class AgentIndexOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(AgentIndexOutboxPublisher.class);
    private final MemoryProfileRepository repository;
    private final RabbitTemplate rabbit;
    private final AgentIndexProperties properties;

    public AgentIndexOutboxPublisher(MemoryProfileRepository repository, RabbitTemplate rabbit,
            AgentIndexProperties properties) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.properties = properties;
        rabbit.setMandatory(true);
        rabbit.setReturnsCallback(returned -> LOG.warn(
                "RabbitMQ returned agent index event exchange={} routingKey={}",
                returned.getExchange(), returned.getRoutingKey()));
    }

    /** 每轮只处理有限批次；单条失败不会阻塞同批其他记忆事件。 */
    @Scheduled(fixedDelayString = "${platform.agent-index.outbox-poll-delay:2s}")
    public void publishPending() {
        for (OutboxRow event : repository.pendingOutbox(Instant.now(), 50)) publish(event);
    }

    /** 只有可路由消息得到 broker ACK 才标记 PUBLISHED，正文和异常消息都不写日志。 */
    public void publish(OutboxRow event) {
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
                    properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) throw new IllegalStateException("publish rejected");
            repository.markOutboxPublished(event.eventId(), Instant.now());
        } catch (Exception failure) {
            int attempt = event.attempts() + 1;
            long delay = Math.min(300, 1L << Math.min(attempt, 8));
            repository.markOutboxFailure(event.eventId(), attempt,
                    attempt >= properties.maxPublishAttempts(), Instant.now().plusSeconds(delay),
                    failure.getClass().getSimpleName(), Instant.now());
        }
    }
}
