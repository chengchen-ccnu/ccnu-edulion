package com.eduplatform.media.processing;

import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.domain.MediaVariant;
import com.eduplatform.media.domain.MediaVariantComponent;
import com.eduplatform.media.domain.ProcessingStatus;
import com.eduplatform.media.domain.UploadStatus;
import com.eduplatform.media.messaging.MediaDomainEvent;
import com.eduplatform.media.messaging.MediaEventFactory;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.service.MediaAssetService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 协调 MediaUploaded 的持久化处理状态机和 Worker。
 *
 * <p>任务以 mediaId/jobType/presetVersion 唯一，消费以 eventId 去重。成功时在一个本地事务中
 * 保存派生资产、READY/SUCCEEDED、consumed_event 和 MediaReady Outbox；过程异常抛回给 Rabbit 有限重试。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class MediaProcessingCoordinator {
    private static final String CONSUMER = "media-processing-v1";
    private final MediaRepository repository;
    private final MediaProcessingWorker worker;
    private final MediaEventFactory events;
    private final TransactionTemplate transactions;

    public MediaProcessingCoordinator(MediaRepository repository, MediaProcessingWorker worker,
            MediaEventFactory events, TransactionTemplate transactions) {
        this.repository = repository;
        this.worker = worker;
        this.events = events;
        this.transactions = transactions;
    }

    /**
     * 处理一条 MediaUploaded v1。重复 eventId 或已成功 job 直接幂等返回。
     * @throws RuntimeException Worker 失败或任务不可领取时抛出，使容器执行有限重试
     */
    public void process(MediaDomainEvent event) {
        if (!"MediaUploaded".equals(event.eventType())) throw new IllegalArgumentException("Unexpected event type");
        if (repository.consumed(event.eventId())) return;
        MediaAsset asset = repository.findAsset(event.aggregateId())
                .orElseThrow(() -> new IllegalStateException("Media asset does not exist"));
        var job = repository.findProcessingJob(asset.mediaId(), MediaAssetService.PROCESS_JOB,
                MediaAssetService.PRESET_VERSION).orElseThrow(() -> new IllegalStateException("Processing job missing"));
        if (job.status() == ProcessingStatus.SUCCEEDED) {
            transactions.executeWithoutResult(status -> markConsumedUnlessPresent(event));
            return;
        }
        Instant started = Instant.now();
        if (!repository.beginProcessing(asset.mediaId(), MediaAssetService.PROCESS_JOB,
                MediaAssetService.PRESET_VERSION, workerKey(), started)) {
            if (repository.findProcessingJob(asset.mediaId(), MediaAssetService.PROCESS_JOB,
                    MediaAssetService.PRESET_VERSION).orElseThrow().status() == ProcessingStatus.SUCCEEDED) {
                transactions.executeWithoutResult(status -> markConsumedUnlessPresent(event));
                return;
            }
            throw new IllegalStateException("Processing job cannot be claimed");
        }
        repository.updateAssetState(asset.mediaId(), MediaStatus.PROCESSING, ProcessingStatus.RUNNING, null, null, started);
        ProcessingResult result;
        try {
            result = worker.process(asset);
        } catch (RuntimeException failure) {
            repository.failProcessing(asset.mediaId(), MediaAssetService.PROCESS_JOB,
                    MediaAssetService.PRESET_VERSION, "MEDIA.PROCESSING_FAILED", safeReason(failure), Instant.now());
            repository.updateAssetState(asset.mediaId(), MediaStatus.PROCESSING, ProcessingStatus.PENDING,
                    null, null, Instant.now());
            throw failure;
        }
        Instant completed = Instant.now();
        transactions.executeWithoutResult(status -> {
            if (repository.consumed(event.eventId())) return;
            for (ProcessingResult.VariantResult variant : result.variants()) {
                repository.insertVariant(new MediaVariant(UUID.randomUUID().toString(), asset.mediaId(),
                        variant.name(), variant.objectKey(), variant.mimeType(), variant.size(), variant.etag(), completed));
                MediaVariant saved = repository.findVariant(asset.mediaId(), variant.name()).orElseThrow();
                List<MediaVariantComponent> components = new java.util.ArrayList<>();
                for (int index = 0; index < variant.components().size(); index++) {
                    ProcessingResult.ComponentResult component = variant.components().get(index);
                    components.add(new MediaVariantComponent(UUID.randomUUID().toString(), saved.variantId(),
                            asset.mediaId(), component.name(), index + 1, component.objectKey(), component.mimeType(),
                            component.size(), component.etag(), completed));
                }
                repository.replaceVariantComponents(saved.variantId(), asset.mediaId(), components);
            }
            repository.completeProcessing(asset.mediaId(), MediaAssetService.PROCESS_JOB,
                    MediaAssetService.PRESET_VERSION, completed);
            repository.updateAssetState(asset.mediaId(), MediaStatus.READY, ProcessingStatus.SUCCEEDED,
                    null, null, completed);
            repository.updateUploadStateByMedia(asset.mediaId(), UploadStatus.READY, completed);
            repository.markConsumed(event.eventId(), event.eventType(), CONSUMER, completed);
            List<Map<String, Object>> variants = result.variants().stream().map(value -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("variant", value.name());
                item.put("mimeType", value.mimeType());
                item.put("size", value.size());
                if (!value.components().isEmpty()) item.put("componentCount", value.components().size());
                return item;
            }).toList();
            repository.insertOutbox(events.create("MediaReady", asset.mediaId(), "media.ready.v1", event.traceId(),
                    Map.of("mediaId", asset.mediaId(), "purpose", asset.purpose().name(),
                            "status", "READY", "variants", variants), completed));
            repository.insertAudit(UUID.randomUUID().toString(), asset.mediaId(), asset.uploadId(), null,
                    "MEDIA_PROCESSING_SUCCEEDED", MediaStatus.PROCESSING.name(), MediaStatus.READY.name(),
                    event.traceId(), MediaAssetService.PRESET_VERSION, completed);
        });
    }

    /**
     * 在 MQ 重试耗尽时将任务/资产标记 FAILED，写入 MediaFailed Outbox 和审计。
     * <p>该方法不 ACK 或重新入队原消息，最终 DLQ 动作由 Recoverer/Broker 完成。</p>
     */
    public void exhausted(MediaDomainEvent event, Throwable failure) {
        if (!"MediaUploaded".equals(event.eventType())) return;
        repository.findAsset(event.aggregateId()).ifPresent(asset -> {
            Instant now = Instant.now();
            transactions.executeWithoutResult(status -> {
                repository.failProcessing(asset.mediaId(), MediaAssetService.PROCESS_JOB,
                        MediaAssetService.PRESET_VERSION, "MEDIA.PROCESSING_FAILED", safeReason(failure), now);
                repository.updateAssetState(asset.mediaId(), MediaStatus.FAILED, ProcessingStatus.FAILED,
                        "MEDIA.PROCESSING_FAILED", "媒体处理失败，可由运维修复后重放原事件", now);
                repository.updateUploadStateByMedia(asset.mediaId(), UploadStatus.FAILED, now);
                repository.insertOutbox(events.create("MediaFailed", asset.mediaId(), "media.failed.v1", event.traceId(),
                        Map.of("mediaId", asset.mediaId(), "purpose", asset.purpose().name(),
                                "errorCode", "MEDIA.PROCESSING_FAILED",
                                "reason", "媒体处理失败，可由运维修复后重放", "retryable", true), now));
                repository.insertAudit(UUID.randomUUID().toString(), asset.mediaId(), asset.uploadId(), null,
                        "MEDIA_PROCESSING_EXHAUSTED", MediaStatus.PROCESSING.name(), MediaStatus.FAILED.name(),
                        event.traceId(), safeReason(failure), now);
            });
        });
    }

    private void markConsumedUnlessPresent(MediaDomainEvent event) {
        if (!repository.consumed(event.eventId())) repository.markConsumed(event.eventId(), event.eventType(), CONSUMER, Instant.now());
    }

    private static String workerKey() {
        return "worker-" + UUID.randomUUID();
    }

    private static String safeReason(Throwable failure) {
        String value = failure.getClass().getSimpleName();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
