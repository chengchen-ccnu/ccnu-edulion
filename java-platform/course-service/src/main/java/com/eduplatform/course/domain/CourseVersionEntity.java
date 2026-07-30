package com.eduplatform.course.domain;

import java.time.Instant;

public class CourseVersionEntity {
    private String id;
    private String courseId;
    private String ownerTeacherId;
    private int versionNumber;
    private String versionLabel;
    private String lifecycleStatus;
    private String title;
    private String summary;
    private String description;
    private String coverUrl;
    private String instructorDisplayName;
    private String categoryId;
    private String categoryName;
    private String level;
    private String accessType;
    private String curriculumHash;
    private Instant createdAt;
    private Instant updatedAt;
    private long rowVersion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCourseId() { return courseId; }
    public void setCourseId(String courseId) { this.courseId = courseId; }
    public String getOwnerTeacherId() { return ownerTeacherId; }
    public void setOwnerTeacherId(String ownerTeacherId) { this.ownerTeacherId = ownerTeacherId; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getInstructorDisplayName() { return instructorDisplayName; }
    public void setInstructorDisplayName(String instructorDisplayName) { this.instructorDisplayName = instructorDisplayName; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }
    public String getCurriculumHash() { return curriculumHash; }
    public void setCurriculumHash(String curriculumHash) { this.curriculumHash = curriculumHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getRowVersion() { return rowVersion; }
    public void setRowVersion(long rowVersion) { this.rowVersion = rowVersion; }
}
