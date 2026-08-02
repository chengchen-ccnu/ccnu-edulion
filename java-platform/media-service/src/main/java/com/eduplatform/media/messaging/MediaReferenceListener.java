package com.eduplatform.media.messaging;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.persistence.MediaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 消费领域服务发布的媒体绑定/解除事件，维护 media-service 引用台账。
 *
 * <p>消费者会校验 ownerService 与媒体用途的固定映射，但不读取或保存课程/题目实体。
 * 整个台账变更与 consumed_event 去重记录在一个本地事务中完成。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class MediaReferenceListener {
    private static final String CONSUMER = "media-reference-ledger-v1";
    private static final Map<String, Set<MediaPurpose>> OWNER_PURPOSES = Map.of(
            "course-service", Set.of(MediaPurpose.COURSE_COVER, MediaPurpose.LESSON_VIDEO,
                    MediaPurpose.VIDEO_POSTER, MediaPurpose.SUBTITLE, MediaPurpose.COURSEWARE,
                    MediaPurpose.ARTICLE_ATTACHMENT),
            "learning-service", Set.of(MediaPurpose.QUESTION_IMAGE, MediaPurpose.QUESTION_AUDIO,
                    MediaPurpose.QUESTION_ATTACHMENT, MediaPurpose.QUESTION_IMPORT_SOURCE),
            "iam-service", Set.of(MediaPurpose.USER_AVATAR, MediaPurpose.TEACHER_QUALIFICATION),
            "agent-access", Set.of(MediaPurpose.AI_QUESTION_IMAGE),
            "operation-service", Set.of(MediaPurpose.BANNER_IMAGE, MediaPurpose.TOPIC_COVER, MediaPurpose.REPORT_EXPORT));

    private final MediaRepository repository;
    private final MediaEventParser parser;
    private final MediaProperties properties;
    private final TransactionTemplate transactions;

    public MediaReferenceListener(MediaRepository repository, MediaEventParser parser,
            MediaProperties properties, TransactionTemplate transactions) {
        this.repository = repository;
        this.parser = parser;
        this.properties = properties;
        this.transactions = transactions;
    }

    @RabbitListener(queues = MediaRabbitTopology.REFERENCES_QUEUE,
            containerFactory = "mediaRabbitListenerContainerFactory",
            autoStartup = "${platform.media.messaging.enabled:false}")
    /** 按 eventId 幂等应用 v1 绑定/解除事件；失败时由容器有限重试。 */
    public void onMessage(String body) {
        MediaDomainEvent event = parser.parse(body);
        if (!Set.of("MediaReferenceBound", "MediaReferenceReleased").contains(event.eventType())) {
            throw new IllegalArgumentException("Unexpected reference event type");
        }
        transactions.executeWithoutResult(status -> apply(event));
    }

    private void apply(MediaDomainEvent event) {
        if (repository.consumed(event.eventId())) return;
        String mediaId = parser.required(event.payload(), "mediaId");
        if (!mediaId.equals(event.aggregateId())) throw new IllegalArgumentException("aggregateId mismatch");
        MediaAsset asset = repository.findAsset(mediaId).orElseThrow(() -> new IllegalStateException("Media asset missing"));
        String ownerService = parser.required(event.payload(), "ownerService");
        if (!OWNER_PURPOSES.getOrDefault(ownerService, Set.of()).contains(asset.purpose())) {
            throw new IllegalArgumentException("Media purpose cannot be referenced by owner service");
        }
        MediaRepository.ReferenceChange change = new MediaRepository.ReferenceChange(UUID.randomUUID().toString(),
                event.eventId(), mediaId, ownerService, parser.required(event.payload(), "referenceType"),
                parser.required(event.payload(), "referenceId"));
        Instant now = Instant.now();
        if ("MediaReferenceBound".equals(event.eventType())) {
            if (asset.status() != MediaStatus.READY) throw new IllegalStateException("Only READY media can be bound");
            repository.bindReference(change, now);
        } else {
            repository.releaseReference(change, now.plus(properties.maintenance().unboundRetention()), now);
        }
        repository.markConsumed(event.eventId(), event.eventType(), CONSUMER, now);
        repository.insertAudit(UUID.randomUUID().toString(), mediaId, asset.uploadId(), null, event.eventType(),
                null, null, event.traceId(), ownerService, now);
    }
}
