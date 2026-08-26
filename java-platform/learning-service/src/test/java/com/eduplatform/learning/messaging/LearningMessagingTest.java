package com.eduplatform.learning.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.importing.QuestionImportProcessor;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.persistence.QuestionRepository.Outbox;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 验证导入队列 DLQ 参数、重试耗尽拒绝语义和 Outbox 发布上限。 */
class LearningMessagingTest {
    @Test
    void importQueueUsesDedicatedDeadLetterRouteAndRecovererRejectsWithoutRequeue() {
        LearningRabbitConfig config = new LearningRabbitConfig();
        assertThat(config.learningImportQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", LearningRabbitTopology.DEAD_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", LearningRabbitTopology.IMPORT_DEAD_KEY);
        QuestionImportProcessor processor = mock(QuestionImportProcessor.class);
        LearningImportRecoverer recoverer = new LearningImportRecoverer(processor);
        Message message = new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> recoverer.recover(message, new IllegalStateException("poison")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(processor).exhausted(org.mockito.ArgumentMatchers.eq("{}"), org.mockito.ArgumentMatchers.any(Throwable.class));
    }

    @Test
    void outboxStopsAutomaticRetryAtConfiguredAttemptLimit() {
        QuestionRepository repository = mock(QuestionRepository.class); RabbitTemplate rabbit = mock(RabbitTemplate.class);
        LearningProperties properties = new LearningProperties(new LearningProperties.Media("http://media", Duration.ofSeconds(1)),
                new LearningProperties.Course("http://course", Duration.ofSeconds(1)),
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), true, Duration.ofSeconds(1), Duration.ofSeconds(1), 3));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable")).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any());
        LearningOutboxPublisher publisher = new LearningOutboxPublisher(repository, rabbit, properties);
        Instant now = Instant.now(); Outbox event = new Outbox("event", "QuestionImportRequested", 1, "task", "exchange",
                "key", "trace-1234", "{}", "PENDING", 2, now, null, now, null, now);
        publisher.publish(event);
        verify(repository).markOutboxFailure(anyString(), org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(true), any(Instant.class), anyString(), any(Instant.class));
    }
}
