package com.eduplatform.media.service;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.domain.MediaVariant;
import com.eduplatform.media.domain.ProcessingStatus;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.domain.UploadSession;
import com.eduplatform.media.domain.UploadStatus;
import com.eduplatform.media.messaging.MediaEventFactory;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.persistence.MediaRepository.ProcessingJob;
import com.eduplatform.media.persistence.MediaRepository.UploadPartRow;
import com.eduplatform.media.storage.CompletedPart;
import com.eduplatform.media.storage.ObjectKeyFactory;
import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.storage.StorageGrant;
import com.eduplatform.media.storage.StorageObjectMetadata;
import com.eduplatform.media.web.MediaDtos;
import com.eduplatform.shared.api.FieldError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 媒体上传与资产查询的业务编排层。
 *
 * <p>本类执行用途/权限/配额校验，调用 OSS 签名或 HEAD，并在本地数据库事务中创建
 * 稳定资产、处理任务与 Outbox。它不代理文件字节，不判断课程或题目可见性。</p>
 */
@Service
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class MediaAssetService {
    private static final Logger LOG = LoggerFactory.getLogger(MediaAssetService.class);
    static final long OWNER_ACTIVE_QUOTA = 20L * 1024 * 1024 * 1024;
    static final int OWNER_ACTIVE_SESSION_LIMIT = 20;
    public static final String PROCESS_JOB = "COURSE_MEDIA_PROCESSING";
    public static final String PRESET_VERSION = "hls-v1";

    private final MediaRepository repository;
    private final MediaPurposePolicy policy;
    private final ObjectStorage storage;
    private final ObjectKeyFactory keyFactory;
    private final MediaProperties properties;
    private final MediaEventFactory events;
    private final TransactionTemplate transactions;
    private final HlsAccessGrantService hlsAccess;

    public MediaAssetService(MediaRepository repository, MediaPurposePolicy policy, ObjectStorage storage,
            ObjectKeyFactory keyFactory, MediaProperties properties, MediaEventFactory events,
            TransactionTemplate transactions, HlsAccessGrantService hlsAccess) {
        this.repository = repository;
        this.policy = policy;
        this.storage = storage;
        this.keyFactory = keyFactory;
        this.properties = properties;
        this.events = events;
        this.transactions = transactions;
        this.hlsAccess = hlsAccess;
    }

    /**
     * 创建或幂等重放上传会话。相同 owner/clientRequestId 只能对应一份不可变文件声明。
     * @return 会话和是否首次创建；小文件同时包含短期 PUT 授权
     * @throws MediaException 角色/用途/文件/配额不合法，或幂等键冲突时抛出
     */
    public CreateResult create(String ownerId, Set<String> roles, MediaDtos.CreateUploadRequest request, String traceId) {
        MediaPurposePolicy.ValidatedUpload validated = policy.validate(request, roles,
                properties.upload().multipartThreshold());
        String fingerprint = fingerprint(request.purpose().name(), validated.filename(), Long.toString(request.size()),
                validated.mimeType(), request.sha256().toLowerCase());
        OptionalUpload replay = existingCreate(ownerId, request.clientRequestId().toString(), fingerprint);
        if (replay.value() != null) return new CreateResult(toUpload(replay.value(), true), false);

        Instant now = Instant.now();
        if (repository.activeSessionCount(ownerId, now) >= OWNER_ACTIVE_SESSION_LIMIT
                || repository.activeDeclaredBytes(ownerId, now) + request.size() > OWNER_ACTIVE_QUOTA) {
            throw new MediaException(HttpStatus.UNPROCESSABLE_CONTENT, "MEDIA.QUOTA_EXCEEDED", "媒体上传配额不足");
        }
        String uploadId = UUID.randomUUID().toString();
        String objectKey = keyFactory.original(request.purpose(), validated.extension());
        String providerUploadId = null;
        if (validated.mode() == UploadMode.MULTIPART) {
            providerUploadId = storageCall(() -> storage.initiateMultipart(objectKey, validated.mimeType(),
                    request.sha256().toLowerCase()));
        }
        UploadSession session = new UploadSession(uploadId, ownerId, request.clientRequestId().toString(), fingerprint,
                request.purpose(), validated.mode(), UploadStatus.UPLOADING, validated.filename(), validated.extension(),
                validated.mimeType(), request.size(), request.sha256().toLowerCase(), objectKey, providerUploadId,
                validated.mode() == UploadMode.MULTIPART ? properties.upload().partSize() : null,
                null, null, now.plus(properties.upload().sessionTtl()), now, now);
        try {
            repository.insertUpload(session);
        } catch (DuplicateKeyException duplicate) {
            if (providerUploadId != null) safeAbort(objectKey, providerUploadId);
            UploadSession winner = existingCreate(ownerId, request.clientRequestId().toString(), fingerprint).value();
            return new CreateResult(toUpload(winner, true), false);
        }
        repository.insertAudit(UUID.randomUUID().toString(), null, uploadId, ownerId, "UPLOAD_SESSION_CREATED",
                null, UploadStatus.UPLOADING.name(), traceId, request.purpose().name(), now);
        return new CreateResult(toUpload(session, true), true);
    }

    /** 查询当前用户所有的上传会话及已授权分片，不返回云端内部 ID。 */
    public MediaDtos.UploadSession getUpload(String uploadId, String ownerId) {
        return toUpload(requireUpload(uploadId, ownerId), false);
    }

    /**
     * 为 Multipart 的一个分片生成短期授权，并幂等记录分片声明。
     * @throws MediaException 会话非当前用户、非可上传状态或分片大小/编号不符合策略时抛出
     */
    public MediaDtos.UploadGrant authorizePart(String uploadId, String ownerId,
            MediaDtos.AuthorizePartRequest request) {
        UploadSession session = requireActiveUpload(uploadId, ownerId);
        if (session.mode() != UploadMode.MULTIPART) conflict("MEDIA.UPLOAD_MODE_CONFLICT", "该会话不是分片上传");
        long partSize = session.partSize();
        int partCount = Math.toIntExact((session.declaredSize() + partSize - 1) / partSize);
        if (request.partNumber() > partCount) invalid("partNumber", "分片号超过文件所需分片数");
        long expected = request.partNumber() == partCount
                ? session.declaredSize() - partSize * (partCount - 1L) : partSize;
        if (request.contentLength() != expected) invalid("contentLength", "分片长度与服务端规划不一致");
        repository.findPart(uploadId, request.partNumber()).ifPresent(existing -> {
            if (existing.contentLength() != request.contentLength()
                    || !Objects.equals(existing.contentMd5(), request.contentMd5())) {
                conflict("MEDIA.IDEMPOTENCY_CONFLICT", "分片号已用于不同的分片元数据");
            }
        });
        StorageGrant grant = storageCall(() -> storage.authorizePart(session.objectKey(),
                session.providerUploadId(), request.partNumber(), request.contentMd5(), properties.upload().grantTtl()));
        if (repository.findPart(uploadId, request.partNumber()).isEmpty()) {
            repository.insertPart(new UploadPartRow(uploadId, request.partNumber(), request.contentLength(),
                    request.contentMd5(), null, Instant.now(), null));
        }
        return grant(grant);
    }

    /**
     * 确认浏览器直传结果并创建稳定媒体资产。
     *
     * <p>Multipart 先提交分片，然后通过 OSS HEAD 复核大小、MIME 和哈希元数据；
     * 资产、PENDING 处理任务与 MediaUploaded Outbox 在同一数据库事务内提交。</p>
     * @return 资产当前状态与是否首次接受处理
     */
    public CompleteResult complete(String uploadId, String ownerId, MediaDtos.CompleteUploadRequest request,
            String traceId) {
        UploadSession session = requireUpload(uploadId, ownerId);
        String completionFingerprint = completionFingerprint(request);
        if (session.mediaId() != null) {
            if (!Objects.equals(session.completionFingerprint(), completionFingerprint)) {
                conflict("MEDIA.IDEMPOTENCY_CONFLICT", "上传已使用不同的完成数据确认");
            }
            return new CompleteResult(toAsset(requireAssetOwned(session.mediaId(), ownerId)), false);
        }
        requireActiveUpload(session);
        if (!session.declaredSha256().equalsIgnoreCase(request.sha256())) {
            invalid("sha256", "完成哈希与创建会话时声明不一致");
        }
        List<CompletedPart> parts = validateCompletionParts(session, request.parts());
        repository.setUploadStatus(uploadId, UploadStatus.VERIFYING, Instant.now());
        if (session.mode() == UploadMode.MULTIPART) {
            try {
                storage.completeMultipart(session.objectKey(), session.providerUploadId(), parts);
            } catch (RuntimeException completionFailure) {
                try {
                    storage.head(session.objectKey());
                } catch (RuntimeException headFailure) {
                    LOG.warn("Multipart completion failed with {}: {}; verification failed with {}",
                            completionFailure.getClass().getSimpleName(), completionFailure.getMessage(),
                            headFailure.getClass().getSimpleName());
                    throw storageUnavailable(completionFailure);
                }
            }
        } else if (!parts.isEmpty()) {
            invalid("parts", "单对象上传完成时 parts 必须为空");
        }
        StorageObjectMetadata metadata = storageCall(() -> storage.head(session.objectKey()));
        verifyMetadata(session, metadata);
        Instant now = Instant.now();
        MediaAsset created = transactions.execute(status -> {
            UploadSession locked = repository.lockUploadOwned(uploadId, ownerId).orElseThrow(this::notFound);
            if (locked.mediaId() != null) {
                if (!Objects.equals(locked.completionFingerprint(), completionFingerprint)) {
                    conflict("MEDIA.IDEMPOTENCY_CONFLICT", "上传已使用不同的完成数据确认");
                }
                return requireAssetOwned(locked.mediaId(), ownerId);
            }
            String mediaId = UUID.randomUUID().toString();
            MediaAsset asset = new MediaAsset(mediaId, uploadId, ownerId, locked.purpose(), MediaStatus.PROCESSING,
                    ProcessingStatus.PENDING, locked.objectKey(), locked.originalFilename(), metadata.mimeType(),
                    metadata.size(), locked.declaredSha256(), metadata.etag(), null, null,
                    now.plus(properties.maintenance().unboundRetention()), null, now, now);
            repository.insertAsset(asset);
            repository.insertProcessingJob(new ProcessingJob(UUID.randomUUID().toString(), mediaId, PROCESS_JOB,
                    PRESET_VERSION, ProcessingStatus.PENDING, 0, null, null, null, null, null, now, now));
            repository.finalizeUpload(uploadId, mediaId, completionFingerprint, UploadStatus.PROCESSING, now);
            repository.insertOutbox(events.create("MediaUploaded", mediaId, "media.uploaded.v1", traceId,
                    Map.of("mediaId", mediaId, "purpose", locked.purpose().name(), "ownerId", ownerId,
                            "mimeType", metadata.mimeType(), "size", metadata.size(),
                            "sha256", locked.declaredSha256()), now));
            repository.insertAudit(UUID.randomUUID().toString(), mediaId, uploadId, ownerId, "UPLOAD_VERIFIED",
                    UploadStatus.VERIFYING.name(), UploadStatus.PROCESSING.name(), traceId, "OSS HEAD verified", now);
            return asset;
        });
        return new CompleteResult(toAsset(created), true);
    }

    /** 幂等取消未完成会话，同时精确删除对象或取消 Multipart；已生成资产时拒绝。 */
    public void cancel(String uploadId, String ownerId, String traceId) {
        UploadSession session = requireUpload(uploadId, ownerId);
        if (session.status() == UploadStatus.CANCELED || session.status() == UploadStatus.EXPIRED) return;
        if (session.mediaId() != null || Set.of(UploadStatus.UPLOADED, UploadStatus.VERIFYING,
                UploadStatus.PROCESSING, UploadStatus.READY).contains(session.status())) {
            conflict("MEDIA.UPLOAD_STATE_CONFLICT", "已完成的上传不能取消");
        }
        if (session.mode() == UploadMode.MULTIPART && session.providerUploadId() != null) {
            storageCall(() -> { storage.abortMultipart(session.objectKey(), session.providerUploadId()); return null; });
        } else {
            storageCall(() -> { storage.delete(session.objectKey()); return null; });
        }
        Instant now = Instant.now();
        repository.setUploadStatus(uploadId, UploadStatus.CANCELED, now);
        repository.insertAudit(UUID.randomUUID().toString(), null, uploadId, ownerId, "UPLOAD_CANCELED",
                session.status().name(), UploadStatus.CANCELED.name(), traceId, null, now);
    }

    /** 查询当前用户所有的资产和处理状态，不包含 Object Key 或 URL。 */
    public MediaDtos.Asset getAsset(String mediaId, String ownerId) {
        return toAsset(requireAssetOwned(mediaId, ownerId));
    }

    /** 按用途/状态筛选当前用户资产，使用从 1 开始的稳定分页。 */
    public MediaDtos.AssetPage listAssets(String ownerId, MediaPurpose purpose, MediaStatus status, int page, int size) {
        long total = repository.countAssetsOwned(ownerId, purpose, status);
        List<MediaDtos.Asset> items = repository.listAssetsOwned(ownerId, purpose, status,
                ((long) page - 1L) * size, size).stream().map(this::toAsset).toList();
        return new MediaDtos.AssetPage(items, page, size, total);
    }

    /**
     * 绑定前验证资产的 owner、精确用途和 READY 状态，不返回 Object Key 或签名 URL。
     *
     * @throws MediaException 他人/不存在资产按 404，错用途按 422，未就绪按 409
     */
    public MediaDtos.BindingValidation validateBinding(String mediaId, String ownerId,
            MediaDtos.BindingValidationRequest request) {
        MediaAsset asset = requireAssetOwned(mediaId, ownerId);
        if (asset.purpose() != request.purpose()) {
            invalid("purpose", "媒体用途与绑定字段不匹配");
        }
        if (asset.status() != MediaStatus.READY) {
            conflict("MEDIA.NOT_READY", "媒体尚未处理完成");
        }
        return new MediaDtos.BindingValidation(mediaId, asset.purpose(), asset.status(), true);
    }

    /**
     * 为当前上传者的 READY 资产签发短期读授权并写入审计。
     * <p>T07 不接受客户端声明课程可见性；后续领域访问必须通过服务端授权链路扩展。</p>
     */
    public MediaDtos.AccessGrant access(String mediaId, String ownerId,
            MediaDtos.CreateAccessGrantRequest request, String traceId) {
        MediaAsset asset = requireAssetOwned(mediaId, ownerId);
        if (asset.status() != MediaStatus.READY) conflict("MEDIA.NOT_READY", "媒体尚未处理完成");
        String objectKey;
        String downloadName;
        if ("ORIGINAL".equals(request.variant())) {
            objectKey = asset.objectKey();
            downloadName = asset.originalFilename();
        } else {
            MediaVariant variant = repository.findVariant(mediaId, request.variant())
                    .orElseThrow(() -> new MediaException(HttpStatus.NOT_FOUND, "MEDIA.VARIANT_NOT_FOUND", "媒体派生版本不存在"));
            if (HlsAccessGrantService.HLS_MIME.equals(variant.mimeType())) {
                return storageCall(() -> hlsAccess.issue(asset, variant, ownerId, request.disposition(), traceId));
            }
            objectKey = variant.objectKey();
            downloadName = request.variant();
        }
        StorageGrant grant = storageCall(() -> storage.authorizeGet(objectKey, downloadName,
                request.disposition().name(), properties.oss().accessGrantTtl()));
        repository.insertAccessAudit(UUID.randomUUID().toString(), mediaId, ownerId, request.variant(),
                request.disposition().name(), grant.expiresAt(), traceId, Instant.now());
        return new MediaDtos.AccessGrant(grant.method(), grant.url().toString(), grant.headers(), grant.expiresAt());
    }

    private OptionalUpload existingCreate(String ownerId, String clientRequestId, String fingerprint) {
        UploadSession existing = repository.findUploadByOwnerAndClient(ownerId, clientRequestId).orElse(null);
        if (existing != null && !existing.requestFingerprint().equals(fingerprint)) {
            conflict("MEDIA.IDEMPOTENCY_CONFLICT", "clientRequestId 已用于不同的上传请求");
        }
        return new OptionalUpload(existing);
    }

    private UploadSession requireUpload(String uploadId, String ownerId) {
        return repository.findUploadOwned(uploadId, ownerId).orElseThrow(this::notFound);
    }

    private UploadSession requireActiveUpload(String uploadId, String ownerId) {
        return requireActiveUpload(requireUpload(uploadId, ownerId));
    }

    private UploadSession requireActiveUpload(UploadSession session) {
        if (session.expiresAt().isBefore(Instant.now())) {
            repository.setUploadStatus(session.uploadId(), UploadStatus.EXPIRED, Instant.now());
            conflict("MEDIA.UPLOAD_EXPIRED", "上传会话已过期");
        }
        if (!Set.of(UploadStatus.CREATED, UploadStatus.UPLOADING, UploadStatus.VERIFYING).contains(session.status())) {
            conflict("MEDIA.UPLOAD_STATE_CONFLICT", "当前上传状态不允许该操作");
        }
        return session;
    }

    private MediaAsset requireAssetOwned(String mediaId, String ownerId) {
        return repository.findAssetOwned(mediaId, ownerId).orElseThrow(this::notFound);
    }

    private List<CompletedPart> validateCompletionParts(UploadSession session, List<MediaDtos.CompletedPart> submitted) {
        if (session.mode() == UploadMode.SINGLE_PUT) return List.of();
        if (submitted.isEmpty()) invalid("parts", "分片上传必须提交 parts");
        List<MediaDtos.CompletedPart> ordered = new ArrayList<>(submitted);
        ordered.sort(Comparator.comparingInt(MediaDtos.CompletedPart::partNumber));
        if (!ordered.equals(submitted)) invalid("parts", "parts 必须按 partNumber 升序提交");
        List<UploadPartRow> authorized = repository.listParts(session.uploadId());
        if (authorized.size() != ordered.size()) invalid("parts", "完成分片与已授权分片不一致");
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).partNumber() != authorized.get(index).partNumber()) {
                invalid("parts", "完成分片与已授权分片不一致");
            }
            repository.completePart(session.uploadId(), ordered.get(index).partNumber(), ordered.get(index).etag(), Instant.now());
        }
        return ordered.stream().map(part -> new CompletedPart(part.partNumber(), part.etag())).toList();
    }

    private void verifyMetadata(UploadSession session, StorageObjectMetadata metadata) {
        if (metadata.size() != session.declaredSize()) invalid("size", "OSS 对象大小与声明不一致");
        if (metadata.mimeType() == null || !metadata.mimeType().equalsIgnoreCase(session.declaredMimeType())) {
            invalid("mimeType", "OSS 对象 MIME 与声明不一致");
        }
        if (metadata.sha256() == null || !metadata.sha256().equalsIgnoreCase(session.declaredSha256())) {
            invalid("sha256", "OSS 对象哈希元数据缺失或不一致");
        }
    }

    private MediaDtos.UploadSession toUpload(UploadSession value, boolean includeGrant) {
        MediaDtos.UploadGrant uploadGrant = null;
        if (includeGrant && value.mode() == UploadMode.SINGLE_PUT && value.expiresAt().isAfter(Instant.now())
                && value.mediaId() == null && value.status() == UploadStatus.UPLOADING) {
            uploadGrant = grant(storageCall(() -> storage.authorizePut(value.objectKey(), value.declaredMimeType(),
                    value.declaredSha256(), properties.upload().grantTtl())));
        }
        List<MediaDtos.UploadedPart> parts = repository.listParts(value.uploadId()).stream()
                .filter(part -> part.etag() != null)
                .map(part -> new MediaDtos.UploadedPart(part.partNumber(), part.etag(), part.contentLength())).toList();
        MediaDtos.MultipartUpload multipart = value.mode() == UploadMode.MULTIPART
                ? new MediaDtos.MultipartUpload(value.partSize(),
                        Math.toIntExact((value.declaredSize() + value.partSize() - 1) / value.partSize()), parts)
                : null;
        return new MediaDtos.UploadSession(value.uploadId(), value.clientRequestId(), value.purpose(), value.mode(),
                value.status(), value.originalFilename(), value.declaredSize(), value.declaredMimeType(),
                value.declaredSha256(), uploadGrant, multipart, value.mediaId(), value.expiresAt(), value.createdAt());
    }

    private MediaDtos.Asset toAsset(MediaAsset asset) {
        List<MediaDtos.Variant> variants = repository.listVariants(asset.mediaId()).stream()
                .map(value -> new MediaDtos.Variant(value.variantName(), value.mimeType(), value.size(),
                        repository.listVariantComponents(value.variantId()).size())).toList();
        return new MediaDtos.Asset(asset.mediaId(), asset.purpose(), asset.status(), asset.processingStatus(),
                asset.originalFilename(), asset.mimeType(), asset.size(), asset.sha256(), variants,
                asset.failureCode(), asset.failureMessage(), asset.createdAt(), asset.updatedAt());
    }

    private static MediaDtos.UploadGrant grant(StorageGrant value) {
        return new MediaDtos.UploadGrant(value.method(), value.url().toString(), value.headers(), value.expiresAt());
    }

    private static String completionFingerprint(MediaDtos.CompleteUploadRequest request) {
        StringBuilder value = new StringBuilder(request.sha256().toLowerCase());
        request.parts().forEach(part -> value.append('|').append(part.partNumber()).append(':').append(part.etag()));
        return fingerprint(value.toString());
    }

    private static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T storageCall(StorageOperation<T> operation) {
        try {
            return operation.call();
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.warn("Object storage operation failed with {}: {}", exception.getClass().getSimpleName(),
                    exception.getMessage());
            throw storageUnavailable(exception);
        }
    }

    private MediaException storageUnavailable(RuntimeException cause) {
        return new MediaException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA.STORAGE_UNAVAILABLE",
                "媒体存储暂时不可用，请稍后重试");
    }

    private void safeAbort(String objectKey, String providerUploadId) {
        try { storage.abortMultipart(objectKey, providerUploadId); } catch (RuntimeException ignored) { }
    }

    private MediaException notFound() {
        return new MediaException(HttpStatus.NOT_FOUND, "MEDIA.NOT_FOUND", "媒体资源不存在或不可见");
    }

    private static void invalid(String field, String reason) {
        throw new MediaException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED", "请求参数不合法",
                List.of(new FieldError(field, reason)));
    }

    private static void conflict(String code, String message) {
        throw new MediaException(HttpStatus.CONFLICT, code, message);
    }

    @FunctionalInterface
    private interface StorageOperation<T> { T call(); }

    private record OptionalUpload(UploadSession value) {}
    public record CreateResult(MediaDtos.UploadSession session, boolean created) {}
    public record CompleteResult(MediaDtos.Asset asset, boolean accepted) {}
}
