package com.eduplatform.iam.domain;

import java.time.Instant;

/** Refresh Token 会话投影；tokenHash 用于轮换查找，familyId 用于重放后的整族撤销。 */
public class RefreshSession {
    private String id;
    private String familyId;
    private String userId;
    private String parentId;
    private String tokenHash;
    private String status;
    private Instant expiresAt;
    private long sessionVersion;
    private String deviceId;
    private String deviceName;
    private Instant createdAt;
    private Instant lastUsedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFamilyId() { return familyId; }
    public void setFamilyId(String familyId) { this.familyId = familyId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public long getSessionVersion() { return sessionVersion; }
    public void setSessionVersion(long sessionVersion) { this.sessionVersion = sessionVersion; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
