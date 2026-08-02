package com.eduplatform.media.service;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaVariant;
import com.eduplatform.media.domain.MediaVariantComponent;
import com.eduplatform.media.domain.TemporaryAccessObject;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.storage.ObjectKeyFactory;
import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.storage.StorageGrant;
import com.eduplatform.media.web.MediaDtos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 为私有 HLS 变体生成一次性短期播放清单。
 *
 * <p>原始清单只含相对切片名；授权时逐个签发切片 GET，将短期 URL 写入临时清单对象，
 * 再签发清单 GET。数据库只记录临时对象精确键和过期时间，清理器到期删除，不保存任何签名 URL。</p>
 */
@Component
public class HlsAccessGrantService {
    public static final String HLS_MIME = "application/vnd.apple.mpegurl";
    private final MediaRepository repository;
    private final ObjectStorage storage;
    private final ObjectKeyFactory keys;
    private final MediaProperties properties;
    private final TransactionTemplate transactions;

    public HlsAccessGrantService(MediaRepository repository, ObjectStorage storage, ObjectKeyFactory keys,
            MediaProperties properties, TransactionTemplate transactions) {
        this.repository = repository;
        this.storage = storage;
        this.keys = keys;
        this.properties = properties;
        this.transactions = transactions;
    }

    /**
     * 生成短期 HLS 授权并记录审计/临时对象。
     *
     * @param asset 已确认属于调用者且 READY 的视频资产
     * @param variant HLS 播放清单派生对象
     * @param actorId 当前上传者身份
     * @param disposition 只影响最终清单响应头；切片固定 INLINE
     * @param traceId 调用链标识
     * @return 临时播放清单的短期 GET 授权
     */
    public MediaDtos.AccessGrant issue(MediaAsset asset, MediaVariant variant, String actorId,
            MediaDtos.Disposition disposition, String traceId) {
        String grantId = UUID.randomUUID().toString();
        String temporaryKey = keys.temporaryManifest(asset.mediaId(), grantId);
        Path scratch = null;
        boolean uploaded = false;
        try {
            scratch = Files.createTempDirectory("edu-hls-grant-");
            Path source = scratch.resolve("source.m3u8");
            Path rewritten = scratch.resolve("authorized.m3u8");
            storage.download(variant.objectKey(), source);
            Map<String, MediaVariantComponent> components = new HashMap<>();
            for (MediaVariantComponent component : repository.listVariantComponents(variant.variantId())) {
                components.put(component.componentName(), component);
            }
            List<StorageGrant> segmentGrants = new ArrayList<>();
            List<String> output = new ArrayList<>();
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    output.add(line);
                    continue;
                }
                MediaVariantComponent component = components.get(value);
                if (component == null) throw new IllegalStateException("HLS playlist component is not registered");
                StorageGrant segment = storage.authorizeGet(component.objectKey(), component.componentName(),
                        "INLINE", properties.oss().accessGrantTtl());
                segmentGrants.add(segment);
                output.add(segment.url().toString());
            }
            if (segmentGrants.size() != components.size() || segmentGrants.isEmpty()) {
                throw new IllegalStateException("HLS component registry does not match playlist");
            }
            Files.write(rewritten, output, StandardCharsets.UTF_8);
            storage.put(temporaryKey, rewritten, HLS_MIME);
            uploaded = true;
            StorageGrant playlistGrant = storage.authorizeGet(temporaryKey, variant.variantName() + ".m3u8",
                    disposition.name(), properties.oss().accessGrantTtl());
            java.time.Instant expiresAt = segmentGrants.stream().map(StorageGrant::expiresAt)
                    .min(Comparator.naturalOrder()).orElse(playlistGrant.expiresAt());
            if (playlistGrant.expiresAt().isBefore(expiresAt)) expiresAt = playlistGrant.expiresAt();
            java.time.Instant finalExpiresAt = expiresAt;
            transactions.executeWithoutResult(status -> {
                repository.insertAccessAudit(grantId, asset.mediaId(), actorId, variant.variantName(),
                        disposition.name(), finalExpiresAt, traceId, java.time.Instant.now());
                repository.insertTemporaryAccessObject(new TemporaryAccessObject(grantId, asset.mediaId(),
                        variant.variantName(), temporaryKey, finalExpiresAt, null, java.time.Instant.now()));
            });
            return new MediaDtos.AccessGrant(playlistGrant.method(), playlistGrant.url().toString(),
                    playlistGrant.headers(), finalExpiresAt);
        } catch (IOException | RuntimeException failure) {
            if (uploaded) {
                try { storage.delete(temporaryKey); } catch (RuntimeException ignored) { }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("HLS access manifest scratch space failed", failure);
        } finally {
            if (scratch != null) deleteScratch(scratch);
        }
    }

    private static void deleteScratch(Path scratch) {
        try (var paths = Files.walk(scratch)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
