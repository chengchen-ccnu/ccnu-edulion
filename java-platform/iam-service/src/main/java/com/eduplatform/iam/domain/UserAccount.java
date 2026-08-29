package com.eduplatform.iam.domain;

import java.time.Instant;

/** IAM 账号持久化投影；密码字段只保存单向哈希，角色和权限由关联表另行读取。 */
public class UserAccount {
    private String id;
    private String account;
    private String displayName;
    private String passwordHash;
    private String status;
    private String avatarUrl;
    private long sessionVersion;
    private long revision;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public long getSessionVersion() { return sessionVersion; }
    public void setSessionVersion(long sessionVersion) { this.sessionVersion = sessionVersion; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
