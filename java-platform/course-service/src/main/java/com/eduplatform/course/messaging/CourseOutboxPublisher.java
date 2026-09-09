package com.eduplatform.course.messaging;

import com.eduplatform.course.config.CourseMediaProperties;
import com.eduplatform.course.persistence.CourseMapper;
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
 * 有限重试发布课程媒体引用 Outbox。
 *
 * <p>只有 mandatory 未返回且 publisher confirm ACK 才标记 PUBLISHED；失败只更新补投状态，
 * 不回滚已提交的课程版本或媒体 ID。达到上限后留为 FAILED，交由运维审计和人工重放。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.course.media.messaging", name = "enabled", havingValue = "true")
public class CourseOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(CourseOutboxPublisher.class);
    private final CourseMapper mapper;
    private final RabbitTemplate rabbit;
    private final CourseMediaProperties properties;

    public CourseOutboxPublisher(CourseMapper mapper, RabbitTemplate rabbit, CourseMediaProperties properties) {
        this.mapper = mapper;
        this.rabbit = rabbit;
        this.properties = properties;
        rabbit.setMandatory(true);
        rabbit.setReturnsCallback(returned -> LOG.warn(
                "RabbitMQ returned course media event from exchange {} with routing key {}",
                returned.getExchange(), returned.getRoutingKey()));
    }

    /** 补投最多 50 条到期记录；单条失败不会阻断后续事件。 */
    @Scheduled(fixedDelayString = "${platform.course.media.messaging.outbox-poll-delay:2s}")
    public void publishPending() {
        for (CourseOutboxEvent event : mapper.pendingOutbox(Instant.now(), 50)) publish(event);
    }

    /**
     * 同步等待一条持久消息的 confirm/return 结果，并记录成功或有限退避。
     * @param event 课程事务已落库、等待发布的媒体引用事件
     */
    public void publish(CourseOutboxEvent event) {
        CorrelationData correlation = new CorrelationData(event.eventId());
        MessageProperties headers = new MessageProperties();
        headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        headers.setContentEncoding(StandardCharsets.UTF_8.name());
        headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        headers.setMessageId(event.eventId());
        headers.setHeader("eventId", event.eventId());
        headers.setHeader("eventType", event.eventType());
        headers.setHeader("eventVersion", event.eventVersion());
        headers.setHeader("traceId", event.traceId());
        try {
            rabbit.send(event.exchange(), event.routingKey(),
                    new Message(event.payloadJson().getBytes(StandardCharsets.UTF_8), headers), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.messaging().confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) throw new IllegalStateException("publisher nack");
            if (correlation.getReturned() != null) throw new IllegalStateException("publisher return");
            mapper.markOutboxPublished(event.eventId(), Instant.now());
        } catch (Exception failure) {
            int attempt = event.attempts() + 1;
            long delaySeconds = Math.min(300, 1L << Math.min(attempt, 8));
            mapper.markOutboxFailure(event.eventId(), attempt,
                    attempt >= properties.messaging().maxPublishAttempts(), Instant.now().plusSeconds(delaySeconds),
                    failure.getClass().getSimpleName(), Instant.now());
        }
    }
}
