package com.eduplatform.agentaccess;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.agentaccess.config.AgentIndexProperties;
import com.eduplatform.agentaccess.config.AgentRetentionProperties;
import com.eduplatform.agentaccess.messaging.AgentMediaOutboxPublisher;
import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.AgentRepository.MediaOutboxRow;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 验证媒体引用 Outbox 只在 broker ACK 后确认，失败到上限保留 FAILED。 */
class AgentMediaOutboxPublisherTest {

    @Test
    void publisherMarksReferenceOnlyAfterBrokerConfirm() {
        AgentRepository repository = mock(AgentRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));

        MediaOutboxRow event = event(0);
        new AgentMediaOutboxPublisher(repository, rabbit, retention(3), index()).publish(event);

        verify(repository).markMediaOutboxPublished(eq(event.eventId()), any(Instant.class));
    }

    @Test
    void publisherRetainsFailureAfterConfiguredAttempts() {
        AgentRepository repository = mock(AgentRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new IllegalStateException("offline"))).when(rabbit)
                .send(anyString(), anyString(), any(), any(CorrelationData.class));

        MediaOutboxRow event = event(2);
        new AgentMediaOutboxPublisher(repository, rabbit, retention(3), index()).publish(event);

        verify(repository).markMediaOutboxFailure(eq(event.eventId()), eq(3), eq(true), any(Instant.class),
                eq("AmqpConnectException"), any(Instant.class));
    }

    private static AgentRetentionProperties retention(int attempts) {
        AgentRetentionProperties properties = mock(AgentRetentionProperties.class);
        when(properties.maxPublishAttempts()).thenReturn(attempts);
        return properties;
    }

    private static AgentIndexProperties index() {
        AgentIndexProperties properties = mock(AgentIndexProperties.class);
        when(properties.confirmTimeout()).thenReturn(Duration.ofSeconds(1));
        return properties;
    }

    private static MediaOutboxRow event(int attempts) {
        UUID id = UUID.randomUUID();
        return new MediaOutboxRow(id, "MediaReferenceReleased", UUID.randomUUID(), "edu.media.events.v1",
                "media.reference.released.v1", "test-trace", "{}", attempts);
    }
}
