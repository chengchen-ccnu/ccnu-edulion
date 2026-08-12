package com.eduplatform.learning.messaging;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.persistence.QuestionRepository.Outbox;
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

/** mandatory + publisher confirm 可靠发布学习域导入与媒体引用 Outbox，失败有限退避留痕。 */
@Component
@ConditionalOnProperty(prefix = "platform.learning.import", name = "messaging-enabled", havingValue = "true")
public class LearningOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(LearningOutboxPublisher.class);
    private final QuestionRepository repository; private final RabbitTemplate rabbit; private final LearningProperties properties;

    public LearningOutboxPublisher(QuestionRepository repository, RabbitTemplate rabbit, LearningProperties properties) {
        this.repository = repository; this.rabbit = rabbit; this.properties = properties; rabbit.setMandatory(true);
        rabbit.setReturnsCallback(returned -> LOG.warn("RabbitMQ returned learning event from exchange {} with routing key {}",
                returned.getExchange(), returned.getRoutingKey()));
    }

    /** 定时读取有限批次到期 Outbox；单个失败不会阻塞本轮其他事件。 */
    @Scheduled(fixedDelayString = "${platform.learning.import.outbox-poll-delay:2s}")
    public void publishPending() { for (Outbox event : repository.pendingOutbox(Instant.now(), 50)) publish(event); }

    /** 只有消息可路由且 confirm ACK 才标记成功；MQ 故障不回滚已提交题库事务。 */
    /** 以持久 mandatory 消息发布并等待 confirm/return，结果更新尝试次数和状态。 */
    public void publish(Outbox event) {
        CorrelationData correlation = new CorrelationData(event.eventId()); MessageProperties headers = new MessageProperties();
        headers.setContentType(MessageProperties.CONTENT_TYPE_JSON); headers.setContentEncoding(StandardCharsets.UTF_8.name());
        headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT); headers.setMessageId(event.eventId());
        headers.setHeader("eventId", event.eventId()); headers.setHeader("eventType", event.eventType());
        headers.setHeader("eventVersion", event.eventVersion()); headers.setHeader("traceId", event.traceId());
        try {
            rabbit.send(event.exchange(), event.routingKey(), new Message(event.payloadJson().getBytes(StandardCharsets.UTF_8), headers), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(properties.importSettings().confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) throw new IllegalStateException("publisher nack");
            if (correlation.getReturned() != null) throw new IllegalStateException("publisher return");
            repository.markOutboxPublished(event.eventId(), Instant.now());
        } catch (Exception failure) {
            int attempt = event.attempts() + 1; long delay = Math.min(300, 1L << Math.min(attempt, 8));
            repository.markOutboxFailure(event.eventId(), attempt, attempt >= properties.importSettings().maxPublishAttempts(),
                    Instant.now().plusSeconds(delay), failure.getClass().getSimpleName(), Instant.now());
        }
    }
}
