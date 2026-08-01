package com.eduplatform.media.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.PutObjectRequest;
import com.eduplatform.media.config.MediaProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS V4 签名适配器。它只操作精确私有对象键，不列举 Bucket，
 * 也不会把 AccessKey、Bucket 或 Object Key 暴露到业务契约。
 */
@Component
@ConditionalOnProperty(prefix = "platform.media.oss", name = "enabled", havingValue = "true")
public class AliyunOssObjectStorage implements ObjectStorage {
    private final MediaProperties.Oss properties;
    private final OSS client;

    public AliyunOssObjectStorage(MediaProperties mediaProperties) {
        this.properties = mediaProperties.oss();
        requireConfigured(properties.endpoint(), "OSS_ENDPOINT");
        requireConfigured(properties.region(), "OSS_REGION");
        requireConfigured(properties.bucket(), "OSS_BUCKET");
        requireConfigured(properties.accessKeyId(), "OSS_ACCESS_KEY_ID");
        requireConfigured(properties.accessKeySecret(), "OSS_ACCESS_KEY_SECRET");
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setSignatureVersion(SignVersion.V4);
        DefaultCredentialProvider credentials = properties.securityToken() == null || properties.securityToken().isBlank()
                ? new DefaultCredentialProvider(properties.accessKeyId(), properties.accessKeySecret())
                : new DefaultCredentialProvider(properties.accessKeyId(), properties.accessKeySecret(), properties.securityToken());
        this.client = OSSClientBuilder.create()
                .endpoint(properties.endpoint())
                .region(properties.region())
                .credentialsProvider(credentials)
                .clientConfiguration(configuration)
                .build();
    }

    @Override
    /** 签发绑定 Content-Type 和 SHA-256 元数据的单文件 PUT URL。 */
    public StorageGrant authorizePut(String objectKey, String mimeType, String sha256, Duration ttl) {
        Map<String, String> headers = Map.of("Content-Type", mimeType, "x-oss-meta-sha256", sha256);
        return sign(objectKey, HttpMethod.PUT, headers, Map.of(), ttl);
    }

    @Override
    /** 初始化带 MIME 和 SHA-256 元数据的 OSS Multipart。 */
    public String initiateMultipart(String objectKey, String mimeType, String sha256) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(mimeType);
        metadata.addUserMetadata("sha256", sha256);
        InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(properties.bucket(), objectKey, metadata);
        return client.initiateMultipartUpload(request).getUploadId();
    }

    @Override
    /** 签发单个分片 URL，授权不能用于其他分片或对象。 */
    public StorageGrant authorizePart(String objectKey, String providerUploadId, int partNumber,
            String contentMd5, Duration ttl) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentMd5 != null && !contentMd5.isBlank()) headers.put("Content-MD5", contentMd5);
        return sign(objectKey, HttpMethod.PUT, headers,
                Map.of("partNumber", Integer.toString(partNumber), "uploadId", providerUploadId), ttl);
    }

    @Override
    /** 提交分片 ETag；SDK 会就地排序，因此必须传入可变列表。 */
    public void completeMultipart(String objectKey, String providerUploadId, List<CompletedPart> parts) {
        List<PartETag> etags = new ArrayList<>(parts.stream()
                .map(part -> new PartETag(part.partNumber(), stripQuotes(part.etag())))
                .toList());
        client.completeMultipartUpload(new CompleteMultipartUploadRequest(
                properties.bucket(), objectKey, providerUploadId, etags));
    }

    @Override
    /** 取消指定云端 Multipart，不影响其他上传。 */
    public void abortMultipart(String objectKey, String providerUploadId) {
        client.abortMultipartUpload(new AbortMultipartUploadRequest(properties.bucket(), objectKey, providerUploadId));
    }

    @Override
    /** 返回完成确认所需的大小、MIME、ETag 和自定义哈希。 */
    public StorageObjectMetadata head(String objectKey) {
        ObjectMetadata metadata = client.getObjectMetadata(properties.bucket(), objectKey);
        return new StorageObjectMetadata(metadata.getContentLength(), metadata.getContentType(), metadata.getETag(),
                metadata.getUserMetadata().get("sha256"));
    }

    @Override
    /** 将原始对象流式下载到 Worker 临时路径。 */
    public void download(String objectKey, Path destination) {
        try (InputStream input = client.getObject(properties.bucket(), objectKey).getObjectContent()) {
            Files.copy(input, destination);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist OSS object to worker scratch space", exception);
        }
    }

    @Override
    /** 上传 Worker 派生文件并立即 HEAD 复核。 */
    public StorageObjectMetadata put(String objectKey, Path source, String mimeType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(mimeType);
        client.putObject(new PutObjectRequest(properties.bucket(), objectKey, source.toFile(), metadata));
        return head(objectKey);
    }

    @Override
    /** 签发携带安全 Content-Disposition 的短期私有读取 URL。 */
    public StorageGrant authorizeGet(String objectKey, String downloadName, String disposition, Duration ttl) {
        String safeName = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");
        String value = disposition.toLowerCase() + "; filename*=UTF-8''" + safeName;
        return sign(objectKey, HttpMethod.GET, Map.of(), Map.of("response-content-disposition", value), ttl);
    }

    @Override
    /** 删除精确对象键，重复删除保持幂等。 */
    public void delete(String objectKey) {
        client.deleteObject(properties.bucket(), objectKey);
    }

    private StorageGrant sign(String objectKey, HttpMethod method, Map<String, String> headers,
            Map<String, String> query, Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(properties.bucket(), objectKey, method);
        request.setExpiration(Date.from(expiresAt));
        request.setHeaders(headers);
        query.forEach(request::addQueryParameter);
        return new StorageGrant(method.name(), URI.create(client.generatePresignedUrl(request).toString()), headers, expiresAt);
    }

    private static String stripQuotes(String value) {
        return value != null && value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static void requireConfigured(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured when OSS is enabled");
    }

    @PreDestroy
    void shutdown() {
        client.shutdown();
    }
}
