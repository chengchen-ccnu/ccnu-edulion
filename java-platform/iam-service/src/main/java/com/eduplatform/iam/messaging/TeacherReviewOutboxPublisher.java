package com.eduplatform.iam.messaging;

import com.eduplatform.iam.persistence.TeacherReviewRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发布 IAM 事务中写入的媒体引用、最小审核决定和账号权限事实事件。
 * mandatory return、NACK、超时或连接错误均有限退避，耗尽后留 FAILED 供人工重放。
 */
@Component
@ConditionalOnProperty(prefix = "platform.iam.review", name = "messaging-enabled", havingValue = "true")
public class TeacherReviewOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(TeacherReviewOutboxPublisher.class);
    private final TeacherReviewRepository repository;
    private final RabbitTemplate rabbit;
    private final int maxAttempts;
    private final Duration confirmTimeout;

    public TeacherReviewOutboxPublisher(TeacherReviewRepository repository, RabbitTemplate rabbit,
            @Value("${platform.iam.review.max-publish-attempts:20}") int maxAttempts,
            @Value("${platform.iam.review.confirm-timeout:10s}") Duration confirmTimeout) {
        this.repository = repository; this.rabbit = rabbit;
        this.maxAttempts = maxAttempts; this.confirmTimeout = confirmTimeout;
        rabbit.setMandatory(true);
        rabbit.setReturnsCallback(returned -> LOG.warn("IAM event unroutable: {} {}",
                returned.getExchange(), returned.getRoutingKey()));
    }

    /** 每批最多 50 条到期事件，单条失败不影响其他记录；数据库写入顺序使绑定先于后续释放。 */
    @Scheduled(fixedDelayString = "${IAM_REVIEW_OUTBOX_POLL_DELAY:2s}")
    public void publishPending() {
        for (TeacherReviewRepository.Outbox event : repository.pending(Instant.now())) publish(event);
    }

    /** 同步等待发布确认后才将事件标为已发布；已提交的资格决定不会因异步失败回滚。 */
    public void publish(TeacherReviewRepository.Outbox event) {
        if (repository.hasEarlierUnpublished(event.eventId())) return;
        CorrelationData correlation = new CorrelationData(event.eventId());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.eventId());
        properties.setHeader("eventId", event.eventId());
        properties.setHeader("eventType", event.eventType());
        properties.setHeader("eventVersion", event.eventVersion());
        properties.setHeader("traceId", event.traceId());
        try {
            rabbit.send(event.exchange(), event.routingKey(),
                    new Message(event.payloadJson().getBytes(StandardCharsets.UTF_8), properties), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) throw new IllegalStateException("publisher not confirmed");
            repository.published(event.eventId(), Instant.now());
        } catch (Exception failure) {
            int attempts = event.attempts() + 1;
            long delay = Math.min(300, 1L << Math.min(attempts, 8));
            repository.failed(event.eventId(), attempts, attempts >= maxAttempts,
                    failure.getClass().getSimpleName(), Instant.now().plusSeconds(delay));
        }
    }
}
