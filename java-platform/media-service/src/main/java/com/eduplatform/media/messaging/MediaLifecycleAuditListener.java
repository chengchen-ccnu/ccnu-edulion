package com.eduplatform.media.messaging;

import com.eduplatform.media.persistence.MediaRepository;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 幂等消费 media-service 自身生命周期事件，验证路由并形成持久化消费证据。 */
@Component
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class MediaLifecycleAuditListener {
    private static final Set<String> TYPES = Set.of("MediaReady", "MediaRejected", "MediaFailed", "MediaDeleted");
    private final MediaEventParser parser;
    private final MediaRepository repository;

    public MediaLifecycleAuditListener(MediaEventParser parser, MediaRepository repository) {
        this.parser = parser;
        this.repository = repository;
    }

    @RabbitListener(queues = MediaRabbitTopology.LIFECYCLE_QUEUE,
            containerFactory = "mediaRabbitListenerContainerFactory",
            autoStartup = "${platform.media.messaging.enabled:false}")
    /** 校验事件类型并按 eventId 幂等写入 consumed_event；成功返回后 ACK。 */
    public void onMessage(String body) {
        MediaDomainEvent event = parser.parse(body);
        if (!TYPES.contains(event.eventType())) throw new IllegalArgumentException("Unexpected lifecycle event type");
        if (!repository.consumed(event.eventId())) {
            repository.markConsumed(event.eventId(), event.eventType(), "media-lifecycle-audit-v1", Instant.now());
        }
    }
}
