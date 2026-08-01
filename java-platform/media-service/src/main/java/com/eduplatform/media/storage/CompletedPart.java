package com.eduplatform.media.storage;

/** 客户端完成 Multipart 时提交的分片号和 OSS ETag。 */
public record CompletedPart(int partNumber, String etag) {}
