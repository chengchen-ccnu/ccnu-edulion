package com.eduplatform.iam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
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
    /** Broker ACK 但 mandatory 消息被退回时仍视为失败，防止误报已送达。 */
    @Test
    void mandatoryReturnCannotBeRecordedAsPublished() {
        TeacherReviewRepository repository = mock(TeacherReviewRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TeacherReviewOutboxPublisher publisher = new TeacherReviewOutboxPublisher(repository, rabbit, 2,
                Duration.ofMillis(10));
        TeacherReviewRepository.Outbox event = new TeacherReviewRepository.Outbox(
                "10000000-0000-4000-8000-000000000904", "MediaReferenceBound", 1,
                "edu.media.events.v1", "media.reference.bound.v1", "trace-test-publish", "{}", 0);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new org.springframework.amqp.core.ReturnedMessage(call.getArgument(2),
                    312, "NO_ROUTE", event.exchange(), event.routingKey()));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(event.exchange()), eq(event.routingKey()), any(Message.class), any(CorrelationData.class));
        publisher.publish(event);
        verify(repository).failed(eq(event.eventId()), eq(1), eq(false), eq("IllegalStateException"), any(Instant.class));
        verify(repository, never()).published(any(String.class), any(Instant.class));
    }

    /** NACK 与确认超时均只安排有限重试，不能标记事件已发布。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void nackOrTimeoutKeepsEventPending(boolean nack) {
        TeacherReviewRepository repository = mock(TeacherReviewRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TeacherReviewOutboxPublisher publisher = new TeacherReviewOutboxPublisher(repository, rabbit, 2,
                Duration.ofMillis(10));
        TeacherReviewRepository.Outbox event = new TeacherReviewRepository.Outbox(
                "10000000-0000-4000-8000-000000000903", "MediaReferenceBound", 1,
                "edu.media.events.v1", "media.reference.bound.v1", "trace-test-publish", "{}", 0);
        doAnswer(call -> {
            if (nack) ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(eq(event.exchange()), eq(event.routingKey()), any(Message.class), any(CorrelationData.class));
        publisher.publish(event);
        verify(repository).failed(eq(event.eventId()), eq(1), eq(false),
                eq(nack ? "IllegalStateException" : "TimeoutException"), any(Instant.class));
        verify(repository, never()).published(any(String.class), any(Instant.class));
    }

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
