package com.eduplatform.media.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** 可返回浏览器的短期授权投影，包含必须原样发送的签名请求头。 */
public record StorageGrant(String method, URI url, Map<String, String> headers, Instant expiresAt) {
    public StorageGrant {
        headers = Map.copyOf(headers);
    }
}
