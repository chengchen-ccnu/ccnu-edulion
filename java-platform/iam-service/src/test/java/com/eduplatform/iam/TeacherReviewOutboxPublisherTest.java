package com.eduplatform.iam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.iam.messaging.TeacherReviewOutboxPublisher;
import com.eduplatform.iam.persistence.TeacherReviewRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** RabbitMQ 发布错误必须留下可追查的 FAILED Outbox，不把数据库决定冒充已送达。 */
class TeacherReviewOutboxPublisherTest {
    /** 前序绑定未获确认时，释放事件留在 Outbox，下轮仍可安全重试。 */
    @Test
    void laterReferenceEventWaitsForEarlierConfirmation() {
        TeacherReviewRepository repository = mock(TeacherReviewRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TeacherReviewOutboxPublisher publisher = new TeacherReviewOutboxPublisher(repository, rabbit, 2,
                Duration.ofMillis(100));
        TeacherReviewRepository.Outbox event = new TeacherReviewRepository.Outbox(
                "10000000-0000-4000-8000-000000000902", "MediaReferenceReleased", 1,
                "edu.media.events.v1", "media.reference.released.v1", "trace-test-publish", "{}", 0);
        when(repository.hasEarlierUnpublished(event.eventId())).thenReturn(true);
        publisher.publish(event);
        verify(repository).hasEarlierUnpublished(event.eventId());
        verify(rabbit, never()).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void brokerFailureExhaustsFiniteAttempts() {
        TeacherReviewRepository repository = mock(TeacherReviewRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TeacherReviewOutboxPublisher publisher = new TeacherReviewOutboxPublisher(repository, rabbit, 2,
                Duration.ofMillis(100));
        TeacherReviewRepository.Outbox event = new TeacherReviewRepository.Outbox(
                "10000000-0000-4000-8000-000000000901", "TeacherQualificationDecided", 1,
                "edu.iam.events.v1", "iam.teacher-qualification.decided.v1", "trace-test-publish", "{}", 1);
        doThrow(new IllegalStateException("broker unavailable")).when(rabbit).send(
                eq(event.exchange()), eq(event.routingKey()), any(Message.class), any(CorrelationData.class));
        publisher.publish(event);
        verify(repository).failed(eq(event.eventId()), eq(2), eq(true),
                eq("IllegalStateException"), any(Instant.class));
    }
}
