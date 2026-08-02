package com.eduplatform.media.storage;

import com.eduplatform.media.service.MediaException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** OSS 未启用时的显式失败适配器；媒体 I/O 不会被伪装成功。 */
@Component
@ConditionalOnMissingBean(ObjectStorage.class)
public class DisabledObjectStorage implements ObjectStorage {
    private MediaException unavailable() {
        return new MediaException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA.STORAGE_UNAVAILABLE",
                "媒体存储暂时不可用，请稍后重试");
    }

    @Override public StorageGrant authorizePut(String key, String mime, String sha256, Duration ttl) { throw unavailable(); }
    @Override public String initiateMultipart(String key, String mime, String sha256) { throw unavailable(); }
    @Override public StorageGrant authorizePart(String key, String id, int number, String md5, Duration ttl) { throw unavailable(); }
    @Override public void completeMultipart(String key, String id, List<CompletedPart> parts) { throw unavailable(); }
    @Override public void abortMultipart(String key, String id) { throw unavailable(); }
    @Override public StorageObjectMetadata head(String key) { throw unavailable(); }
    @Override public void download(String key, Path destination) { throw unavailable(); }
    @Override public StorageObjectMetadata put(String key, Path source, String mime) { throw unavailable(); }
    @Override public StorageGrant authorizeGet(String key, String name, String disposition, Duration ttl) { throw unavailable(); }
    @Override public void delete(String key) { throw unavailable(); }
}
