package com.eduplatform.course.domain;

import java.time.Instant;

/** 课程聚合根持久化投影，保存所有者及当前草稿/发布版本指针，不承载版本正文。 */
public class CourseEntity {
    private String id;
    private String createRequestId;
    private String ownerTeacherId;
    private String currentDraftVersionId;
    private String publishedVersionId;
    private String publicationStatus;
    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private long rowVersion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCreateRequestId() { return createRequestId; }
    public void setCreateRequestId(String createRequestId) { this.createRequestId = createRequestId; }
    public String getOwnerTeacherId() { return ownerTeacherId; }
    public void setOwnerTeacherId(String ownerTeacherId) { this.ownerTeacherId = ownerTeacherId; }
    public String getCurrentDraftVersionId() { return currentDraftVersionId; }
    public void setCurrentDraftVersionId(String currentDraftVersionId) { this.currentDraftVersionId = currentDraftVersionId; }
    public String getPublishedVersionId() { return publishedVersionId; }
    public void setPublishedVersionId(String publishedVersionId) { this.publishedVersionId = publishedVersionId; }
    public String getPublicationStatus() { return publicationStatus; }
    public void setPublicationStatus(String publicationStatus) { this.publicationStatus = publicationStatus; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getRowVersion() { return rowVersion; }
    public void setRowVersion(long rowVersion) { this.rowVersion = rowVersion; }
}
