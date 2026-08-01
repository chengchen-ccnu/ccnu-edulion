package com.eduplatform.media.storage;

/** OSS HEAD 或派生上传后返回的对象元数据，不包含签名 URL。 */
public record StorageObjectMetadata(long size, String mimeType, String etag, String sha256) {}
