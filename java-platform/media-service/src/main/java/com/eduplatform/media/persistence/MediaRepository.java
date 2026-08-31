package com.eduplatform.media.persistence;

import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaAssetScope;
import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.domain.MediaVariant;
import com.eduplatform.media.domain.MediaVariantComponent;
import com.eduplatform.media.domain.TemporaryAccessObject;
import com.eduplatform.media.domain.ProcessingStatus;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.domain.UploadSession;
import com.eduplatform.media.domain.UploadStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * media-service 唯一 JDBC 持久化边界，只访问媒体 Schema。
 *
 * <p>本类不跨 Schema 查询或 Join；需要原子性的多步更改由上层 TransactionTemplate 包裹。
 * 唯一索引同时保护 clientRequestId、处理任务、引用关系、Outbox eventId 和消费 eventId 的幂等。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class MediaRepository {
    private final JdbcTemplate jdbc;

    public MediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 owner/clientRequestId 查找幂等上传会话。 */
    public Optional<UploadSession> findUploadByOwnerAndClient(String ownerId, String clientRequestId) {
        return one("SELECT * FROM upload_session WHERE owner_id = ? AND client_request_id = ?",
                UPLOAD_MAPPER, ownerId, clientRequestId);
    }

    /** 查找当前 owner 的上传会话，跨用户访问与不存在统一为空。 */
    public Optional<UploadSession> findUploadOwned(String uploadId, String ownerId) {
        return one("SELECT * FROM upload_session WHERE upload_id = ? AND owner_id = ?",
                UPLOAD_MAPPER, uploadId, ownerId);
    }

    /** 在事务内使用 FOR UPDATE 锁定 owner 会话，保护完成上传并发。 */
    public Optional<UploadSession> lockUploadOwned(String uploadId, String ownerId) {
        return one("SELECT * FROM upload_session WHERE upload_id = ? AND owner_id = ? FOR UPDATE",
                UPLOAD_MAPPER, uploadId, ownerId);
    }

    /** 插入新的上传会话；owner/clientRequestId 冲突由业务层按指纹解析。 */
    public void insertUpload(UploadSession value) {
        jdbc.update("""
                INSERT INTO upload_session(upload_id, owner_id, client_request_id, request_fingerprint,
                    purpose, upload_mode, status, original_filename, extension, declared_mime_type,
                    declared_size, declared_sha256, object_key, provider_upload_id, part_size,
                    completion_fingerprint, media_id, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.uploadId(), value.ownerId(), value.clientRequestId(), value.requestFingerprint(),
                value.purpose().name(), value.mode().name(), value.status().name(), value.originalFilename(),
                value.extension(), value.declaredMimeType(), value.declaredSize(), value.declaredSha256(),
                value.objectKey(), value.providerUploadId(), value.partSize(), value.completionFingerprint(),
                value.mediaId(), timestamp(value.expiresAt()), timestamp(value.createdAt()), timestamp(value.updatedAt()));
    }

    /** 保存 OSS Multipart ID 和分片大小，同时进入 UPLOADING。 */
    public void setMultipartProvider(String uploadId, String providerUploadId, long partSize, Instant now) {
        jdbc.update("UPDATE upload_session SET provider_upload_id = ?, part_size = ?, status = ?, updated_at = ? WHERE upload_id = ?",
                providerUploadId, partSize, UploadStatus.UPLOADING.name(), timestamp(now), uploadId);
    }

    /** 更新上传会话状态与最后修改时间。 */
    public void setUploadStatus(String uploadId, UploadStatus status, Instant now) {
        jdbc.update("UPDATE upload_session SET status = ?, updated_at = ? WHERE upload_id = ?",
                status.name(), timestamp(now), uploadId);
    }

    /** 汇总 owner 尚未过期且非终态会话声明的字节数，用于配额。 */
    public long activeDeclaredBytes(String ownerId, Instant now) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(declared_size), 0) FROM upload_session
                WHERE owner_id = ? AND expires_at > ? AND status IN ('CREATED', 'UPLOADING')
                """, Long.class, ownerId, timestamp(now));
        return value == null ? 0 : value;
    }

    /** 统计 owner 尚未过期且非终态会话数，用于防止会话滞留。 */
    public long activeSessionCount(String ownerId, Instant now) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM upload_session
                WHERE owner_id = ? AND expires_at > ? AND status IN ('CREATED', 'UPLOADING')
                """, Long.class, ownerId, timestamp(now));
        return value == null ? 0 : value;
    }

    /** 查询一个已授权分片声明。 */
    public Optional<UploadPartRow> findPart(String uploadId, int partNumber) {
        return one("SELECT * FROM upload_part WHERE upload_id = ? AND part_number = ?", PART_MAPPER,
                uploadId, partNumber);
    }

    /** 插入分片声明；唯一键保护同分片号幂等。 */
    public void insertPart(UploadPartRow part) {
        jdbc.update("""
                INSERT INTO upload_part(upload_id, part_number, content_length, content_md5, etag, authorized_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, part.uploadId(), part.partNumber(), part.contentLength(), part.contentMd5(), part.etag(),
                timestamp(part.authorizedAt()), timestamp(part.completedAt()));
    }

    /** 保存客户端确认的分片 ETag 和完成时间。 */
    public void completePart(String uploadId, int partNumber, String etag, Instant now) {
        jdbc.update("UPDATE upload_part SET etag = ?, completed_at = ? WHERE upload_id = ? AND part_number = ?",
                etag, timestamp(now), uploadId, partNumber);
    }

    /** 按分片号返回一个 Multipart 的已记录分片。 */
    public List<UploadPartRow> listParts(String uploadId) {
        return jdbc.query("SELECT * FROM upload_part WHERE upload_id = ? ORDER BY part_number", PART_MAPPER, uploadId);
    }

    /** 插入已经 OSS HEAD 复核的 PROCESSING 资产。 */
    public void insertAsset(MediaAsset asset) {
        jdbc.update("""
                INSERT INTO media_asset(media_id, upload_id, owner_id, scope, purpose, status, processing_status,
                    processing_contract_version, object_key, original_filename, mime_type, size_bytes, sha256, etag, failure_code,
                    failure_message, unbound_expires_at, deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, asset.mediaId(), asset.uploadId(), asset.ownerId(), asset.scope().name(), asset.purpose().name(), asset.status().name(),
                asset.processingStatus().name(), asset.processingContractVersion(), asset.objectKey(), asset.originalFilename(), asset.mimeType(),
                asset.size(), asset.sha256(), asset.etag(), asset.failureCode(), asset.failureMessage(),
                timestamp(asset.unboundExpiresAt()), timestamp(asset.deletedAt()), timestamp(asset.createdAt()),
                timestamp(asset.updatedAt()));
    }

    /** 将会话与稳定 mediaId 关联并保存完成指纹，用于完成请求重放。 */
    public void finalizeUpload(String uploadId, String mediaId, String completionFingerprint,
            UploadStatus status, Instant now) {
        jdbc.update("""
                UPDATE upload_session SET media_id = ?, completion_fingerprint = ?, status = ?, updated_at = ?
                WHERE upload_id = ?
                """, mediaId, completionFingerprint, status.name(), timestamp(now), uploadId);
    }

    /** 按稳定 mediaId 查询内部资产。 */
    public Optional<MediaAsset> findAsset(String mediaId) {
        return one("SELECT * FROM media_asset WHERE media_id = ?", ASSET_MAPPER, mediaId);
    }

    /** 查询当前 owner 的资产，用于防止资产 ID 枚举。 */
    public Optional<MediaAsset> findAssetOwned(String mediaId, String ownerId) {
        return one("SELECT * FROM media_asset WHERE media_id = ? AND owner_id = ?", ASSET_MAPPER, mediaId, ownerId);
    }

    /** 按 owner/可选用途/可选状态返回稳定排序的一页资产。 */
    public List<MediaAsset> listAssetsOwned(String ownerId, MediaPurpose purpose, MediaStatus status,
            long offset, int limit) {
        Query query = assetFilter("SELECT * FROM media_asset WHERE owner_id = ?", ownerId, purpose, status);
        query.sql.append(" ORDER BY created_at DESC, media_id DESC LIMIT ? OFFSET ?");
        query.args.add(limit);
        query.args.add(offset);
        return jdbc.query(query.sql.toString(), ASSET_MAPPER, query.args.toArray());
    }

    /** 返回与资产分页相同过滤条件的总数。 */
    public long countAssetsOwned(String ownerId, MediaPurpose purpose, MediaStatus status) {
        Query query = assetFilter("SELECT COUNT(*) FROM media_asset WHERE owner_id = ?", ownerId, purpose, status);
        Long value = jdbc.queryForObject(query.sql.toString(), Long.class, query.args.toArray());
        return value == null ? 0 : value;
    }

    private Query assetFilter(String base, String ownerId, MediaPurpose purpose, MediaStatus status) {
        Query query = new Query(new StringBuilder(base), new ArrayList<>(List.of(ownerId)));
        if (purpose != null) {
            query.sql.append(" AND purpose = ?");
            query.args.add(purpose.name());
        }
        if (status != null) {
            query.sql.append(" AND status = ?");
            query.args.add(status.name());
        }
        return query;
    }

    /** 列出一个资产的所有派生元数据。 */
    public List<MediaVariant> listVariants(String mediaId) {
        return jdbc.query("SELECT * FROM media_variant WHERE media_id = ? ORDER BY variant_name", VARIANT_MAPPER, mediaId);
    }

    /** 按稳定派生名查找资产，用于访问授权。 */
    public Optional<MediaVariant> findVariant(String mediaId, String variantName) {
        return one("SELECT * FROM media_variant WHERE media_id = ? AND variant_name = ?",
                VARIANT_MAPPER, mediaId, variantName);
    }

    /** 幂等插入 Worker 派生元数据，同一 mediaId/variant 不重复。 */
    public void insertVariant(MediaVariant variant) {
        try {
            jdbc.update("""
                    INSERT INTO media_variant(variant_id, media_id, variant_name, object_key, mime_type,
                        size_bytes, etag, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, variant.variantId(), variant.mediaId(), variant.variantName(), variant.objectKey(),
                    variant.mimeType(), variant.size(), variant.etag(), timestamp(variant.createdAt()));
        } catch (DuplicateKeyException duplicate) {
            jdbc.update("""
                    UPDATE media_variant SET object_key = ?, mime_type = ?, size_bytes = ?, etag = ?, created_at = ?
                    WHERE media_id = ? AND variant_name = ?
                    """, variant.objectKey(), variant.mimeType(), variant.size(), variant.etag(),
                    timestamp(variant.createdAt()), variant.mediaId(), variant.variantName());
        }
    }

    /** 用稳定 variantId 替换一次 HLS 处理得到的组件清单；调用方事务保证列表不会部分生效。 */
    public void replaceVariantComponents(String variantId, String mediaId, List<MediaVariantComponent> components) {
        jdbc.update("DELETE FROM media_variant_component WHERE variant_id = ?", variantId);
        for (MediaVariantComponent component : components) {
            jdbc.update("""
                    INSERT INTO media_variant_component(component_id, variant_id, media_id, component_name,
                        position, object_key, mime_type, size_bytes, etag, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, component.componentId(), variantId, mediaId, component.componentName(),
                    component.position(), component.objectKey(), component.mimeType(), component.size(),
                    component.etag(), timestamp(component.createdAt()));
        }
    }

    /** 按播放顺序返回一个 HLS 变体的私有切片元数据。 */
    public List<MediaVariantComponent> listVariantComponents(String variantId) {
        return jdbc.query("SELECT * FROM media_variant_component WHERE variant_id = ? ORDER BY position",
                COMPONENT_MAPPER, variantId);
    }

    /** 返回资产全部切片，用于资产回收时逐个精确删除。 */
    public List<MediaVariantComponent> listAssetComponents(String mediaId) {
        return jdbc.query("SELECT * FROM media_variant_component WHERE media_id = ? ORDER BY variant_id, position",
                COMPONENT_MAPPER, mediaId);
    }

    /** 创建 PENDING 处理任务；mediaId/jobType/presetVersion 组合唯一。 */
    public void insertProcessingJob(ProcessingJob job) {
        jdbc.update("""
                INSERT INTO processing_job(job_id, media_id, job_type, preset_version, status, attempts,
                    worker_key, failure_code, failure_message, started_at, finished_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, job.jobId(), job.mediaId(), job.jobType(), job.presetVersion(), job.status().name(),
                job.attempts(), job.workerKey(), job.failureCode(), job.failureMessage(), timestamp(job.startedAt()),
                timestamp(job.finishedAt()), timestamp(job.createdAt()), timestamp(job.updatedAt()));
    }

    /** 查询一个版本化处理任务。 */
    public Optional<ProcessingJob> findProcessingJob(String mediaId, String jobType, String presetVersion) {
        return one("SELECT * FROM processing_job WHERE media_id = ? AND job_type = ? AND preset_version = ?",
                JOB_MAPPER, mediaId, jobType, presetVersion);
    }

    /** 原子领取 PENDING/FAILED 任务并转为 RUNNING，返回是否领取成功。 */
    public boolean beginProcessing(String mediaId, String jobType, String presetVersion, String workerKey, Instant now) {
        return jdbc.update("""
                UPDATE processing_job SET status = 'RUNNING', attempts = attempts + 1, worker_key = ?,
                    started_at = ?, failure_code = NULL, failure_message = NULL, updated_at = ?
                WHERE media_id = ? AND job_type = ? AND preset_version = ? AND status NOT IN ('SUCCEEDED', 'CANCELED')
                """, workerKey, timestamp(now), timestamp(now), mediaId, jobType, presetVersion) == 1;
    }

    /** 将指定处理任务标记 SUCCEEDED。 */
    public void completeProcessing(String mediaId, String jobType, String presetVersion, Instant now) {
        jdbc.update("""
                UPDATE processing_job SET status = 'SUCCEEDED', finished_at = ?, updated_at = ?
                WHERE media_id = ? AND job_type = ? AND preset_version = ?
                """, timestamp(now), timestamp(now), mediaId, jobType, presetVersion);
    }

    /** 记录一次处理失败及安全错误摘要，供重试或最终失败处理。 */
    public void failProcessing(String mediaId, String jobType, String presetVersion, String code, String message, Instant now) {
        jdbc.update("""
                UPDATE processing_job SET status = 'FAILED', failure_code = ?, failure_message = ?, finished_at = ?, updated_at = ?
                WHERE media_id = ? AND job_type = ? AND preset_version = ?
                """, code, message, timestamp(now), timestamp(now), mediaId, jobType, presetVersion);
    }

    /** 更新资产/处理状态与可展示错误，不改变资产引用。 */
    public void updateAssetState(String mediaId, MediaStatus status, ProcessingStatus processing,
            String failureCode, String failureMessage, Instant now) {
        jdbc.update("""
                UPDATE media_asset SET status = ?, processing_status = ?, failure_code = ?, failure_message = ?, updated_at = ?
                WHERE media_id = ?
                """, status.name(), processing.name(), failureCode, failureMessage, timestamp(now), mediaId);
    }

    /** 按 mediaId 同步上传会话的对外状态。 */
    public void updateUploadStateByMedia(String mediaId, UploadStatus status, Instant now) {
        jdbc.update("UPDATE upload_session SET status = ?, updated_at = ? WHERE media_id = ?",
                status.name(), timestamp(now), mediaId);
    }

    /** 插入与业务事实同事务提交的 PENDING Outbox。 */
    public void insertOutbox(OutboxEvent event) {
        jdbc.update("""
                INSERT INTO outbox_event(event_id, event_type, event_version, aggregate_id, routing_key,
                    trace_id, payload_json, status, attempts, next_attempt_at, published_at, last_error,
                    created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.eventType(), event.eventVersion(), event.aggregateId(),
                event.routingKey(), event.traceId(), event.payloadJson(), event.status(), event.attempts(),
                timestamp(event.nextAttemptAt()), timestamp(event.publishedAt()), event.lastError(),
                timestamp(event.createdAt()), timestamp(event.updatedAt()));
    }

    /** 查询到期且尚未耗尽重试的 Outbox 批次。 */
    public List<OutboxEvent> pendingOutbox(Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM outbox_event WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY created_at LIMIT ?
                """, OUTBOX_MAPPER, timestamp(now), limit);
    }

    /** 仅在 publisher confirm ACK 且没有 return 后标记 PUBLISHED。 */
    public void markOutboxPublished(String eventId, Instant now) {
        jdbc.update("""
                UPDATE outbox_event SET status = 'PUBLISHED', attempts = attempts + 1, published_at = ?,
                    last_error = NULL, updated_at = ? WHERE event_id = ?
                """, timestamp(now), timestamp(now), eventId);
    }

    /** 记录发布失败、下次时间和是否耗尽；不删除 Outbox 行。 */
    public void markOutboxFailure(String eventId, int nextAttempt, boolean exhausted,
            Instant nextAttemptAt, String error, Instant now) {
        jdbc.update("""
                UPDATE outbox_event SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ?, updated_at = ?
                WHERE event_id = ?
                """, exhausted ? "FAILED" : "PENDING", nextAttempt, timestamp(nextAttemptAt), abbreviate(error),
                timestamp(now), eventId);
    }

    /** 判断 eventId 是否已由本服务持久化消费。 */
    public boolean consumed(String eventId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Integer.class, eventId);
        return count != null && count > 0;
    }

    /** 在消费业务事务内写入 eventId 去重记录。 */
    public void markConsumed(String eventId, String eventType, String consumer, Instant now) {
        jdbc.update("INSERT INTO consumed_event(event_id, event_type, consumer_name, processed_at) VALUES (?, ?, ?, ?)",
                eventId, eventType, consumer, timestamp(now));
    }

    /** 幂等建立媒体引用，并清空资产未绑定回收时间。 */
    public void bindReference(ReferenceChange change, Instant now) {
        Optional<ReferenceRow> current = findReference(change.mediaId(), change.ownerService(),
                change.referenceType(), change.referenceId());
        if (current.isPresent()) {
            jdbc.update("""
                    UPDATE media_reference SET status = 'BOUND', bound_event_id = ?, released_event_id = NULL,
                        bound_at = ?, released_at = NULL, updated_at = ? WHERE reference_record_id = ?
                    """, change.eventId(), timestamp(now), timestamp(now), current.get().recordId());
        } else {
            jdbc.update("""
                    INSERT INTO media_reference(reference_record_id, media_id, owner_service, reference_type,
                        reference_id, status, bound_event_id, released_event_id, bound_at, released_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'BOUND', ?, NULL, ?, NULL, ?)
                    """, change.recordId(), change.mediaId(), change.ownerService(), change.referenceType(),
                    change.referenceId(), change.eventId(), timestamp(now), timestamp(now));
        }
        jdbc.update("UPDATE media_asset SET unbound_expires_at = NULL, updated_at = ? WHERE media_id = ?",
                timestamp(now), change.mediaId());
    }

    /** 幂等解除媒体引用；最后一个 BOUND 引用移除后启动延迟回收时钟。 */
    public void releaseReference(ReferenceChange change, Instant unboundExpiresAt, Instant now) {
        Optional<ReferenceRow> current = findReference(change.mediaId(), change.ownerService(),
                change.referenceType(), change.referenceId());
        current.ifPresent(row -> jdbc.update("""
                UPDATE media_reference SET status = 'RELEASED', released_event_id = ?, released_at = ?, updated_at = ?
                WHERE reference_record_id = ?
                """, change.eventId(), timestamp(now), timestamp(now), row.recordId()));
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM media_reference WHERE media_id = ? AND status = 'BOUND'",
                Long.class, change.mediaId());
        if (active != null && active == 0) {
            jdbc.update("UPDATE media_asset SET unbound_expires_at = ?, updated_at = ? WHERE media_id = ?",
                    timestamp(unboundExpiresAt), timestamp(now), change.mediaId());
        }
    }

    private Optional<ReferenceRow> findReference(String mediaId, String ownerService, String referenceType, String referenceId) {
        return one("""
                SELECT reference_record_id, status FROM media_reference
                WHERE media_id = ? AND owner_service = ? AND reference_type = ? AND reference_id = ?
                """, (rs, row) -> new ReferenceRow(rs.getString("reference_record_id"), rs.getString("status")),
                mediaId, ownerService, referenceType, referenceId);
    }

    /** 列出到期且仍未完成的上传会话批次。 */
    public List<UploadSession> expiredUploads(Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM upload_session WHERE expires_at <= ? AND status IN ('CREATED', 'UPLOADING')
                ORDER BY expires_at LIMIT ?
                """, UPLOAD_MAPPER, timestamp(now), limit);
    }

    /** 列出已过未绑定保留期且不存在 BOUND 引用的资产。 */
    public List<MediaAsset> reclaimableAssets(Instant now, int limit) {
        return jdbc.query("""
                SELECT a.* FROM media_asset a
                WHERE a.unbound_expires_at <= ? AND a.status IN ('READY', 'REJECTED', 'FAILED', 'EXPIRED', 'DELETING')
                  AND NOT EXISTS (SELECT 1 FROM media_reference r WHERE r.media_id = a.media_id AND r.status = 'BOUND')
                ORDER BY a.unbound_expires_at LIMIT ?
                """, ASSET_MAPPER, timestamp(now), limit);
    }

    /** 删除 OSS 前先标记 DELETING，使崩溃后可重试。 */
    public void markAssetDeleting(String mediaId, Instant now) {
        jdbc.update("UPDATE media_asset SET status = 'DELETING', updated_at = ? WHERE media_id = ?", timestamp(now), mediaId);
    }

    /** 精确对象删除成功后将资产标记 DELETED/CANCELED。 */
    public void markAssetDeleted(String mediaId, Instant now) {
        jdbc.update("UPDATE media_asset SET status = 'DELETED', processing_status = 'CANCELED', deleted_at = ?, updated_at = ? WHERE media_id = ?",
                timestamp(now), timestamp(now), mediaId);
    }

    /** 写入上传、状态、引用或回收的安全审计，不保存凭据和签名 URL。 */
    public void insertAudit(String auditId, String mediaId, String uploadId, String actorId, String action,
            String fromStatus, String toStatus, String traceId, String details, Instant now) {
        jdbc.update("""
                INSERT INTO media_audit_record(audit_id, media_id, upload_id, actor_id, action, from_status,
                    to_status, trace_id, details, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, auditId, mediaId, uploadId, actorId, action, fromStatus, toStatus, traceId,
                abbreviate(details), timestamp(now));
    }

    /** 记录短期访问授权的主体、派生名和过期时间，不保存 URL。 */
    public void insertAccessAudit(String grantId, String mediaId, String actorId, String variant,
            String disposition, Instant expiresAt, String traceId, Instant now) {
        jdbc.update("""
                INSERT INTO access_grant_audit(grant_id, media_id, actor_id, variant_name, disposition,
                    expires_at, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, grantId, mediaId, actorId, variant, disposition, timestamp(expiresAt), traceId, timestamp(now));
    }

    /** 与授权审计同事务保存临时 HLS 清单的精确键，供过期回收。 */
    public void insertTemporaryAccessObject(TemporaryAccessObject value) {
        jdbc.update("""
                INSERT INTO temporary_access_object(grant_id, media_id, variant_name, object_key,
                    expires_at, deleted_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, value.grantId(), value.mediaId(), value.variantName(), value.objectKey(),
                timestamp(value.expiresAt()), timestamp(value.deletedAt()), timestamp(value.createdAt()));
    }

    /** 有限批量返回已过期且未清理的临时授权清单。 */
    public List<TemporaryAccessObject> expiredTemporaryAccessObjects(Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM temporary_access_object
                WHERE deleted_at IS NULL AND expires_at <= ?
                ORDER BY expires_at, grant_id LIMIT ?
                """, TEMPORARY_ACCESS_MAPPER, timestamp(now), limit);
    }

    /** 精确对象删除成功后幂等记录清理时间。 */
    public void markTemporaryAccessObjectDeleted(String grantId, Instant now) {
        jdbc.update("UPDATE temporary_access_object SET deleted_at = ? WHERE grant_id = ? AND deleted_at IS NULL",
                timestamp(now), grantId);
    }

    /**
     * 原子消费一次服务授权 jti；唯一键冲突表示同一令牌已使用，调用方必须拒绝重放。
     *
     * @return true 表示首次消费并已落库，false 表示 tokenId 已存在
     */
    public boolean consumeServiceAuthorization(String tokenId, String issuer, String audience,
            String visitorId, Instant expiresAt, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO consumed_service_authorization(
                        token_id, issuer, audience, visitor_id, expires_at, consumed_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, tokenId, issuer, audience, visitorId, timestamp(expiresAt), timestamp(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 删除已经失效的随机 jti 记录；不影响任何仍在有效期内的重放保护。 */
    public int deleteExpiredServiceAuthorizations(Instant now) {
        return jdbc.update("DELETE FROM consumed_service_authorization WHERE expires_at < ?", timestamp(now));
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.stream().findFirst();
    }

    private static final RowMapper<UploadSession> UPLOAD_MAPPER = (rs, row) -> new UploadSession(
            rs.getString("upload_id"), rs.getString("owner_id"), rs.getString("client_request_id"),
            rs.getString("request_fingerprint"), MediaPurpose.valueOf(rs.getString("purpose")),
            UploadMode.valueOf(rs.getString("upload_mode")), UploadStatus.valueOf(rs.getString("status")),
            rs.getString("original_filename"), rs.getString("extension"), rs.getString("declared_mime_type"),
            rs.getLong("declared_size"), rs.getString("declared_sha256"), rs.getString("object_key"),
            rs.getString("provider_upload_id"), nullableLong(rs, "part_size"), rs.getString("completion_fingerprint"),
            rs.getString("media_id"), instant(rs, "expires_at"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<UploadPartRow> PART_MAPPER = (rs, row) -> new UploadPartRow(
            rs.getString("upload_id"), rs.getInt("part_number"), rs.getLong("content_length"),
            rs.getString("content_md5"), rs.getString("etag"), instant(rs, "authorized_at"),
            nullableInstant(rs, "completed_at"));

    private static final RowMapper<MediaAsset> ASSET_MAPPER = (rs, row) -> new MediaAsset(
            rs.getString("media_id"), rs.getString("upload_id"), rs.getString("owner_id"),
            MediaAssetScope.valueOf(rs.getString("scope")),
            MediaPurpose.valueOf(rs.getString("purpose")), MediaStatus.valueOf(rs.getString("status")),
            ProcessingStatus.valueOf(rs.getString("processing_status")), rs.getString("processing_contract_version"), rs.getString("object_key"),
            rs.getString("original_filename"), rs.getString("mime_type"), rs.getLong("size_bytes"),
            rs.getString("sha256"), rs.getString("etag"), rs.getString("failure_code"),
            rs.getString("failure_message"), nullableInstant(rs, "unbound_expires_at"),
            nullableInstant(rs, "deleted_at"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<MediaVariant> VARIANT_MAPPER = (rs, row) -> new MediaVariant(
            rs.getString("variant_id"), rs.getString("media_id"), rs.getString("variant_name"),
            rs.getString("object_key"), rs.getString("mime_type"), rs.getLong("size_bytes"),
            rs.getString("etag"), instant(rs, "created_at"));

    private static final RowMapper<MediaVariantComponent> COMPONENT_MAPPER = (rs, row) ->
            new MediaVariantComponent(rs.getString("component_id"), rs.getString("variant_id"),
                    rs.getString("media_id"), rs.getString("component_name"), rs.getInt("position"),
                    rs.getString("object_key"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                    rs.getString("etag"), instant(rs, "created_at"));

    private static final RowMapper<TemporaryAccessObject> TEMPORARY_ACCESS_MAPPER = (rs, row) ->
            new TemporaryAccessObject(rs.getString("grant_id"), rs.getString("media_id"),
                    rs.getString("variant_name"), rs.getString("object_key"), instant(rs, "expires_at"),
                    nullableInstant(rs, "deleted_at"), instant(rs, "created_at"));

    private static final RowMapper<ProcessingJob> JOB_MAPPER = (rs, row) -> new ProcessingJob(
            rs.getString("job_id"), rs.getString("media_id"), rs.getString("job_type"),
            rs.getString("preset_version"), ProcessingStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"), rs.getString("worker_key"), rs.getString("failure_code"),
            rs.getString("failure_message"), nullableInstant(rs, "started_at"),
            nullableInstant(rs, "finished_at"), instant(rs, "created_at"), instant(rs, "updated_at"));

    private static final RowMapper<OutboxEvent> OUTBOX_MAPPER = (rs, row) -> new OutboxEvent(
            rs.getString("event_id"), rs.getString("event_type"), rs.getInt("event_version"),
            rs.getString("aggregate_id"), rs.getString("routing_key"), rs.getString("trace_id"),
            rs.getString("payload_json"), rs.getString("status"), rs.getInt("attempts"),
            instant(rs, "next_attempt_at"), nullableInstant(rs, "published_at"), rs.getString("last_error"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record Query(StringBuilder sql, List<Object> args) {}
    private record ReferenceRow(String recordId, String status) {}

    public record UploadPartRow(String uploadId, int partNumber, long contentLength, String contentMd5,
            String etag, Instant authorizedAt, Instant completedAt) {}

    public record ProcessingJob(String jobId, String mediaId, String jobType, String presetVersion,
            ProcessingStatus status, int attempts, String workerKey, String failureCode, String failureMessage,
            Instant startedAt, Instant finishedAt, Instant createdAt, Instant updatedAt) {}

    public record OutboxEvent(String eventId, String eventType, int eventVersion, String aggregateId,
            String routingKey, String traceId, String payloadJson, String status, int attempts,
            Instant nextAttemptAt, Instant publishedAt, String lastError, Instant createdAt, Instant updatedAt) {}

    public record ReferenceChange(String recordId, String eventId, String mediaId, String ownerService,
            String referenceType, String referenceId) {}
}
