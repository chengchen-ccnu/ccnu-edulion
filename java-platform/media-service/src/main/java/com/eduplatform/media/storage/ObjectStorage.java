package com.eduplatform.media.storage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * media-service 与私有对象存储之间的唯一端口。
 *
 * <p>方法只接收服务端生成的精确 Object Key，不提供 Bucket 列举或前缀删除；
 * 签名结果是短期单对象授权，不含长期凭据。</p>
 */
public interface ObjectStorage {
    /** 为小文件生成绑定 MIME 和 SHA-256 元数据的短期 PUT 授权。 */
    StorageGrant authorizePut(String objectKey, String mimeType, String sha256, Duration ttl);

    /** 初始化 Multipart，返回仅供本服务保存的云端上传 ID。 */
    String initiateMultipart(String objectKey, String mimeType, String sha256);

    /** 为指定分片生成短期 PUT 授权，可选绑定 Content-MD5。 */
    StorageGrant authorizePart(String objectKey, String providerUploadId, int partNumber,
            String contentMd5, Duration ttl);

    /** 使用已校验的有序 ETag 列表完成 Multipart。 */
    void completeMultipart(String objectKey, String providerUploadId, List<CompletedPart> parts);

    /** 幂等取消未完成 Multipart，失败时由调用方保留会话重试。 */
    void abortMultipart(String objectKey, String providerUploadId);

    /** 读取对象元数据，用于完成后的大小、MIME 和哈希复核。 */
    StorageObjectMetadata head(String objectKey);

    /** 下载原始对象到 Worker 临时路径，调用方负责清理。 */
    void download(String objectKey, Path destination);

    /** 上传 Worker 派生文件并返回可持久化的元数据。 */
    StorageObjectMetadata put(String objectKey, Path source, String mimeType);

    /** 为 READY 资产生成短期读授权，URL 不得持久化。 */
    StorageGrant authorizeGet(String objectKey, String downloadName, String disposition, Duration ttl);

    /** 删除一个精确 Object Key，实现必须允许重复调用。 */
    void delete(String objectKey);
}
