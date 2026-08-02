package com.eduplatform.media.service;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaVariant;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.domain.UploadSession;
import com.eduplatform.media.domain.UploadStatus;
import com.eduplatform.media.messaging.MediaEventFactory;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.storage.ObjectStorage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 周期回收未完成上传和超过保留期且无有效引用的资产。
 *
 * <p>每次只操作数据库中已保存的精确 Object Key；云端删除或取消失败时保留状态，
 * 下一轮继续幂等重试，不列举或批量删除 Bucket。</p>
 */
@Component
@ConditionalOnProperty(prefix = "platform.media.maintenance", name = "enabled", havingValue = "true")
public class MediaReclaimer {
    private final MediaRepository repository;
    private final ObjectStorage storage;
    private final MediaEventFactory events;
    private final TransactionTemplate transactions;

    public MediaReclaimer(MediaRepository repository, ObjectStorage storage, MediaEventFactory events,
            TransactionTemplate transactions) {
        this.repository = repository;
        this.storage = storage;
        this.events = events;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${platform.media.maintenance.fixed-delay:1h}")
    /** 扫描一个有限批次并执行回收；单条失败不阻断其他对象，失败项留待下轮重试。 */
    public void reclaim() {
        Instant now = Instant.now();
        repository.expiredTemporaryAccessObjects(now, 100).forEach(value -> {
            try {
                storage.delete(value.objectKey());
                repository.markTemporaryAccessObjectDeleted(value.grantId(), now);
            } catch (RuntimeException ignored) {
                // 精确对象键保留在数据库，下一轮继续幂等清理。
            }
        });
        repository.expiredUploads(now, 100).forEach(session -> expire(session, now));
        repository.reclaimableAssets(now, 100).forEach(asset -> delete(asset, now));
    }

    private void expire(UploadSession session, Instant now) {
        try {
            if (session.mode() == UploadMode.MULTIPART && session.providerUploadId() != null) {
                storage.abortMultipart(session.objectKey(), session.providerUploadId());
            } else {
                storage.delete(session.objectKey());
            }
            repository.setUploadStatus(session.uploadId(), UploadStatus.EXPIRED, now);
            repository.insertAudit(UUID.randomUUID().toString(), null, session.uploadId(), null,
                    "UPLOAD_SESSION_EXPIRED", session.status().name(), UploadStatus.EXPIRED.name(),
                    "system-reclaim", null, now);
        } catch (RuntimeException ignored) {
            // Leave the session eligible for the next idempotent pass.
        }
    }

    private void delete(MediaAsset asset, Instant now) {
        try {
            repository.markAssetDeleting(asset.mediaId(), now);
            repository.listAssetComponents(asset.mediaId()).forEach(component -> storage.delete(component.objectKey()));
            for (MediaVariant variant : repository.listVariants(asset.mediaId())) storage.delete(variant.objectKey());
            storage.delete(asset.objectKey());
            transactions.executeWithoutResult(status -> {
                repository.markAssetDeleted(asset.mediaId(), now);
                repository.insertOutbox(events.create("MediaDeleted", asset.mediaId(), "media.deleted.v1",
                        "system-reclaim", Map.of("mediaId", asset.mediaId(), "purpose", asset.purpose().name(),
                                "reason", "UNBOUND_RETENTION_EXPIRED"), now));
                repository.insertAudit(UUID.randomUUID().toString(), asset.mediaId(), asset.uploadId(), null,
                        "MEDIA_RECLAIMED", asset.status().name(), "DELETED", "system-reclaim", null, now);
            });
        } catch (RuntimeException ignored) {
            // DELETING remains eligible and exact object deletes are idempotent.
        }
    }
}
