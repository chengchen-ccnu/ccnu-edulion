package com.eduplatform.media.service;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaAssetScope;
import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.domain.ProcessingStatus;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.domain.UploadSession;
import com.eduplatform.media.domain.UploadStatus;
import com.eduplatform.media.messaging.MediaEventFactory;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.storage.ObjectKeyFactory;
import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.storage.StorageObjectMetadata;
import com.eduplatform.media.web.MediaDtos;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 由 media-service 独占执行历史 HTTPS 图片转存并创建稳定 QUESTION_IMAGE 资产。
 *
 * <p>外部 URL 只传给下载端口和哈希函数，不写表、事件、审计或日志。单图先下载到有界内存，
 * 再经随机临时文件交给对象存储端口；成功后资产直接 READY，失败时精确删除已写对象。</p>
 */
@Service
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class ExternalImageTransferService {
    private final MediaRepository repository; private final ExternalImageDownloader downloader;
    private final ObjectStorage storage; private final ObjectKeyFactory keys; private final MediaProperties properties;
    private final MediaEventFactory events; private final TransactionTemplate transactions;

    public ExternalImageTransferService(MediaRepository repository, ExternalImageDownloader downloader,
            ObjectStorage storage, ObjectKeyFactory keys, MediaProperties properties, MediaEventFactory events,
            TransactionTemplate transactions) {
        this.repository = repository; this.downloader = downloader; this.storage = storage; this.keys = keys;
        this.properties = properties; this.events = events; this.transactions = transactions;
    }

    /**
     * 幂等转存一张图片。
     * @param roles Gateway 传入角色；TEACHER 资产要求教师角色
     * @param permissions Gateway 传入权限；SYSTEM 资产必须具备 administration:access
     * @return READY 资产与是否首次创建；重放不再次联网或写 OSS
     */
    public Result transfer(String ownerId, Set<String> roles, Set<String> permissions,
            MediaDtos.ExternalImageTransferRequest request, String traceId) {
        if (request.scope() == MediaAssetScope.SYSTEM && !permissions.contains("administration:access")) throw new MediaException(HttpStatus.FORBIDDEN,
                "MEDIA.PURPOSE_FORBIDDEN", "只有管理员可以创建系统题目图片");
        if (request.scope() == MediaAssetScope.TEACHER && !roles.contains("TEACHER")) throw new MediaException(HttpStatus.FORBIDDEN,
                "MEDIA.PURPOSE_FORBIDDEN", "当前角色不能转存题目图片");
        String fingerprint = sha256("QUESTION_IMAGE\0" + request.scope() + "\0" + request.sourceUrl());
        var replay = repository.findUploadByOwnerAndClient(ownerId, request.clientRequestId().toString());
        if (replay.isPresent()) return replay(replay.get(), fingerprint);
        URI source;
        try {
            source = URI.create(request.sourceUrl());
        } catch (IllegalArgumentException exception) {
            throw new MediaException(HttpStatus.UNPROCESSABLE_CONTENT, "MEDIA.EXTERNAL_IMAGE_INVALID",
                    "外部图片地址格式不合法");
        }
        ExternalImageDownloader.DownloadedImage downloaded = downloader.download(source);
        requireImageSignature(downloaded.bytes(), downloaded.mimeType());
        String extension = extension(downloaded.mimeType()); String objectKey = keys.original(MediaPurpose.QUESTION_IMAGE, extension);
        String digest = sha256(downloaded.bytes()); Path temporary = null; boolean stored = false;
        try {
            temporary = Files.createTempFile("edu-external-question-image-", "." + extension);
            Files.write(temporary, downloaded.bytes());
            StorageObjectMetadata metadata = storage.put(objectKey, temporary, downloaded.mimeType()); stored = true;
            Instant now = Instant.now(); String uploadId = UUID.randomUUID().toString(); String mediaId = UUID.randomUUID().toString();
            UploadSession session = new UploadSession(uploadId, ownerId, request.clientRequestId().toString(), fingerprint,
                    MediaPurpose.QUESTION_IMAGE, UploadMode.SINGLE_PUT, UploadStatus.READY,
                    "legacy-question-image." + extension, extension, downloaded.mimeType(), downloaded.bytes().length,
                    digest, objectKey, null, null, digest, mediaId, now.plus(properties.upload().sessionTtl()), now, now);
            MediaAsset asset = new MediaAsset(mediaId, uploadId, ownerId, request.scope(), MediaPurpose.QUESTION_IMAGE,
                    MediaStatus.READY, ProcessingStatus.SUCCEEDED, null, objectKey, session.originalFilename(),
                    downloaded.mimeType(), downloaded.bytes().length, digest, metadata.etag(), null, null,
                    now.plus(properties.maintenance().unboundRetention()), null, now, now);
            try {
                transactions.executeWithoutResult(status -> {
                    repository.insertUpload(session); repository.insertAsset(asset);
                    repository.insertOutbox(events.create("MediaReady", mediaId, "media.ready.v1", traceId,
                            Map.of("mediaId", mediaId, "purpose", MediaPurpose.QUESTION_IMAGE.name(),
                                    "status", MediaStatus.READY.name(), "variants", List.of()), now));
                    repository.insertAudit(UUID.randomUUID().toString(), mediaId, uploadId, ownerId,
                            "EXTERNAL_QUESTION_IMAGE_TRANSFERRED", null, MediaStatus.READY.name(), traceId,
                            "allow-listed HTTPS source transferred", now);
                });
            } catch (DuplicateKeyException duplicate) {
                storage.delete(objectKey); stored = false;
                return replay(repository.findUploadByOwnerAndClient(ownerId, request.clientRequestId().toString())
                        .orElseThrow(), fingerprint);
            }
            return new Result(response(asset), true);
        } catch (MediaException exception) {
            throw exception;
        } catch (Exception exception) {
            if (stored) try { storage.delete(objectKey); } catch (RuntimeException ignored) { }
            throw new MediaException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA.STORAGE_UNAVAILABLE",
                    "媒体存储暂时不可用，请稍后重试");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    private Result replay(UploadSession session, String fingerprint) {
        if (!session.requestFingerprint().equals(fingerprint) || session.purpose() != MediaPurpose.QUESTION_IMAGE
                || session.mediaId() == null) throw new MediaException(HttpStatus.CONFLICT,
                "MEDIA.IDEMPOTENCY_CONFLICT", "clientRequestId 已用于不同的媒体请求");
        MediaAsset asset = repository.findAssetOwned(session.mediaId(), session.ownerId())
                .orElseThrow(() -> new MediaException(HttpStatus.CONFLICT, "MEDIA.IDEMPOTENCY_CONFLICT", "媒体幂等记录不完整"));
        return new Result(response(asset), false);
    }

    private MediaDtos.Asset response(MediaAsset asset) {
        return new MediaDtos.Asset(asset.mediaId(), asset.scope(), asset.purpose(), asset.status(), asset.processingStatus(),
                asset.processingContractVersion(), asset.originalFilename(), asset.mimeType(), asset.size(), asset.sha256(), List.of(),
                asset.failureCode(), asset.failureMessage(), asset.createdAt(), asset.updatedAt());
    }
    private static String extension(String mime) { return switch (mime) {
        case "image/jpeg" -> "jpg"; case "image/png" -> "png"; case "image/webp" -> "webp";
        default -> throw new MediaException(HttpStatus.UNPROCESSABLE_CONTENT, "MEDIA.EXTERNAL_IMAGE_INVALID", "外部图片类型不支持"); };
    }
    /** 响应 MIME 之外再校验常见图片魔数，拒绝把 HTML/脚本伪装成题目图片。 */
    private static void requireImageSignature(byte[] bytes, String mime) {
        boolean valid = switch (mime) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                    && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "image/webp" -> bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                    && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                    && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
        if (!valid) throw new MediaException(HttpStatus.UNPROCESSABLE_CONTENT,
                "MEDIA.EXTERNAL_IMAGE_INVALID", "外部图片内容与声明类型不一致");
    }
    private static String sha256(String value) { return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public record Result(MediaDtos.Asset asset, boolean created) {}
}
