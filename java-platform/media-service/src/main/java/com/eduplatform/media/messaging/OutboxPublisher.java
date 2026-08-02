package com.eduplatform.media.messaging;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.persistence.MediaRepository;
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

/**
 * 将已提交的媒体 Outbox 可靠投递到 RabbitMQ。
 *
 * <p>只有 mandatory 路由未返回且 publisher confirm ACK 后才标记 PUBLISHED。NACK、return、超时或连接失败
 * 均保留记录并有限指数退避；达上限后标记 FAILED 供人工重放，不回滚已提交的媒体事实。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final MediaRepository repository;
    private final RabbitTemplate rabbit;
    private final MediaProperties properties;

    public OutboxPublisher(MediaRepository repository, RabbitTemplate rabbit, MediaProperties properties) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.properties = properties;
        rabbit.setMandatory(true);
        rabbit.setReturnsCallback(returned -> LOG.warn(
                "RabbitMQ returned unroutable message from exchange {} with routing key {}",
                returned.getExchange(), returned.getRoutingKey()));
    }

    @Scheduled(fixedDelayString = "${platform.media.messaging.outbox-poll-delay:2s}")
    /** 按 nextAttemptAt 批量补投到期 Outbox，单条失败不阻断后续记录。 */
    public void publishPending() {
        for (MediaRepository.OutboxEvent event : repository.pendingOutbox(Instant.now(), 50)) publish(event);
    }

    /** 发布一条 Outbox 并同步等待 confirm/return 结果，然后持久化成功或下次重试时间。 */
    public void publish(MediaRepository.OutboxEvent event) {
        CorrelationData correlation = new CorrelationData(event.eventId());
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(event.eventId());
        messageProperties.setHeader("eventId", event.eventId());
        messageProperties.setHeader("eventType", event.eventType());
        messageProperties.setHeader("eventVersion", event.eventVersion());
        messageProperties.setHeader("traceId", event.traceId());
        Message message = new Message(event.payloadJson().getBytes(StandardCharsets.UTF_8), messageProperties);
        try {
            rabbit.send(MediaRabbitTopology.EVENTS_EXCHANGE, event.routingKey(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.messaging().confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) throw new IllegalStateException("publisher nack");
            if (correlation.getReturned() != null) throw new IllegalStateException("publisher return: unroutable");
            repository.markOutboxPublished(event.eventId(), Instant.now());
        } catch (Exception failure) {
            int attempt = event.attempts() + 1;
            long delaySeconds = Math.min(300, 1L << Math.min(attempt, 8));
            repository.markOutboxFailure(event.eventId(), attempt,
                    attempt >= properties.messaging().maxPublishAttempts(), Instant.now().plusSeconds(delaySeconds),
                    failure.getClass().getSimpleName(), Instant.now());
        }
    }
}
