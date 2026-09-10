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
import com.eduplatform.agentaccess.messaging.AgentIndexOutboxPublisher;
import com.eduplatform.agentaccess.persistence.MemoryProfileRepository;
import com.eduplatform.agentaccess.persistence.MemoryProfileRepository.OutboxRow;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 验证 Agent Access 发布端只有收到 broker ACK 才确认，并在有限次数后保留 FAILED 供人工重投。 */
class AgentIndexOutboxPublisherTest {

    @Test
    void publisherMarksEventOnlyAfterBrokerConfirm() {
        MemoryProfileRepository repository = mock(MemoryProfileRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        AgentIndexProperties properties = properties(3);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));

        OutboxRow event = event(0);
        new AgentIndexOutboxPublisher(repository, rabbit, properties).publish(event);

        verify(repository).markOutboxPublished(eq(event.eventId()), any(Instant.class));
    }

    @Test
    void publisherStopsAutomaticRetryAtConfiguredLimit() {
        MemoryProfileRepository repository = mock(MemoryProfileRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new IllegalStateException("offline"))).when(rabbit)
                .send(anyString(), anyString(), any(), any(CorrelationData.class));

        OutboxRow event = event(2);
        new AgentIndexOutboxPublisher(repository, rabbit, properties(3)).publish(event);

        verify(repository).markOutboxFailure(eq(event.eventId()), eq(3), eq(true), any(Instant.class),
                eq("AmqpConnectException"), any(Instant.class));
    }

    private static AgentIndexProperties properties(int attempts) {
        AgentIndexProperties properties = mock(AgentIndexProperties.class);
        when(properties.confirmTimeout()).thenReturn(Duration.ofSeconds(1));
        when(properties.maxPublishAttempts()).thenReturn(attempts);
        return properties;
    }

    private static OutboxRow event(int attempts) {
        UUID id = UUID.randomUUID();
        return new OutboxRow(id, "StudentMemoryIndexProjectionChanged", UUID.randomUUID(),
                "edu.agent.index.events.v1", "agent.index.student-memory.changed.v1", 7,
                "test-trace", "{}", attempts);
    }
}
