package com.eduplatform.media;

import com.eduplatform.media.storage.CompletedPart;
import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.storage.StorageGrant;
import com.eduplatform.media.storage.StorageObjectMetadata;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * H2 HTTP 集成测试使用的确定性内存对象存储，模拟签名头绑定、Multipart、HEAD 和精确删除。
 */
class FakeObjectStorage implements ObjectStorage {
    private final Map<String, GrantTarget> grants = new LinkedHashMap<>();
    private final Map<String, Stored> objects = new LinkedHashMap<>();
    private final Map<String, Multipart> multiparts = new LinkedHashMap<>();

    @Override
    public synchronized StorageGrant authorizePut(String key, String mime, String sha256, Duration ttl) {
        String token = token();
        grants.put(token, new GrantTarget(key, null, null, mime, sha256));
        return new StorageGrant("PUT", URI.create("https://oss.test/upload/" + token),
                Map.of("Content-Type", mime, "x-oss-meta-sha256", sha256), Instant.now().plus(ttl));
    }

    @Override
    public synchronized String initiateMultipart(String key, String mime, String sha256) {
        String uploadId = token();
        multiparts.put(uploadId, new Multipart(key, mime, sha256, new LinkedHashMap<>()));
        return uploadId;
    }

    @Override
    public synchronized StorageGrant authorizePart(String key, String uploadId, int partNumber,
            String contentMd5, Duration ttl) {
        String token = token();
        grants.put(token, new GrantTarget(key, uploadId, partNumber, null, null));
        return new StorageGrant("PUT", URI.create("https://oss.test/part/" + token), Map.of(), Instant.now().plus(ttl));
    }

    synchronized String upload(String signedUrl, byte[] bytes, String mimeType, String sha256) {
        String token = signedUrl.substring(signedUrl.lastIndexOf('/') + 1);
        GrantTarget target = grants.get(token);
        if (target == null) throw new IllegalArgumentException("unknown grant");
        String etag = digest(bytes);
        if (target.uploadId() == null) {
            if (!target.mime().equals(mimeType) || !target.sha256().equals(sha256)) {
                throw new IllegalArgumentException("signed headers mismatch");
            }
            objects.put(target.key(), new Stored(bytes.clone(), mimeType, etag, sha256));
        } else {
            multiparts.get(target.uploadId()).parts().put(target.partNumber(), new Part(bytes.clone(), etag));
        }
        return etag;
    }

    @Override
    public synchronized void completeMultipart(String key, String uploadId, List<CompletedPart> parts) {
        Multipart multipart = multiparts.remove(uploadId);
        if (multipart == null) throw new IllegalStateException("multipart missing");
        List<byte[]> chunks = new ArrayList<>();
        int length = 0;
        for (CompletedPart completed : parts) {
            Part part = multipart.parts().get(completed.partNumber());
            if (part == null || !part.etag().equals(completed.etag())) throw new IllegalStateException("part mismatch");
            chunks.add(part.bytes());
            length += part.bytes().length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, offset, chunk.length);
            offset += chunk.length;
        }
        objects.put(key, new Stored(joined, multipart.mime(), digest(joined), multipart.sha256()));
    }

    @Override public synchronized void abortMultipart(String key, String uploadId) { multiparts.remove(uploadId); }

    @Override
    public synchronized StorageObjectMetadata head(String key) {
        Stored stored = objects.get(key);
        if (stored == null) throw new IllegalStateException("object missing");
        return new StorageObjectMetadata(stored.bytes().length, stored.mime(), stored.etag(), stored.sha256());
    }

    @Override
    public synchronized void download(String key, Path destination) {
        try { Files.write(destination, objects.get(key).bytes()); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    @Override
    public synchronized StorageObjectMetadata put(String key, Path source, String mime) {
        try {
            byte[] bytes = Files.readAllBytes(source);
            objects.put(key, new Stored(bytes, mime, digest(bytes), null));
            return head(key);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public synchronized StorageGrant authorizeGet(String key, String name, String disposition, Duration ttl) {
        if (!objects.containsKey(key)) throw new IllegalStateException("object missing");
        String token = token();
        grants.put(token, new GrantTarget(key, null, null, null, null));
        return new StorageGrant("GET", URI.create("https://oss.test/read/" + token), Map.of(), Instant.now().plus(ttl));
    }

    @Override public synchronized void delete(String key) { objects.remove(key); }

    synchronized boolean hasObjectPrefix(String prefix) { return objects.keySet().stream().anyMatch(key -> key.startsWith(prefix)); }

    synchronized boolean hasObject(String key) { return objects.containsKey(key); }

    synchronized String readSigned(String signedUrl) {
        String token = signedUrl.substring(signedUrl.lastIndexOf('/') + 1);
        GrantTarget target = grants.get(token);
        if (target == null || !objects.containsKey(target.key())) throw new IllegalArgumentException("grant missing");
        return new String(objects.get(target.key()).bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String token() { return UUID.randomUUID().toString(); }

    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record GrantTarget(String key, String uploadId, Integer partNumber, String mime, String sha256) {}
    private record Stored(byte[] bytes, String mime, String etag, String sha256) {}
    private record Part(byte[] bytes, String etag) {}
    private record Multipart(String key, String mime, String sha256, Map<Integer, Part> parts) {}
}
