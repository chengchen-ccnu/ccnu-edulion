package com.eduplatform.course.domain;

/**
 * course-service 查询课时媒体授权事实的只读投影。
 *
 * <p>该投影只包含课程域已有的版本、所有权、访问类型、试看和媒体绑定事实；
 * 不包含 OSS Object Key、Bucket、签名 URL 或报名记录。</p>
 */
public class CourseMediaAccessRow {
    private String courseId;
    private String ownerTeacherId;
    private String versionId;
    private String lifecycleStatus;
    private String accessType;
    private String lessonId;
    private boolean previewable;
    private String resourceId;
    private String mediaId;
    private String purpose;
    private String lessonType;
    private String lessonTitle;
    private String articleMarkdown;
    private String displayName;
    private String presentation;
    private String originalFileName;
    private String mimeType;
    private long sizeBytes;
    private String variantNames;
    private String processingContractVersion;

    public String getCourseId() { return courseId; }
    public void setCourseId(String courseId) { this.courseId = courseId; }
    public String getOwnerTeacherId() { return ownerTeacherId; }
    public void setOwnerTeacherId(String ownerTeacherId) { this.ownerTeacherId = ownerTeacherId; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }
    public String getLessonId() { return lessonId; }
    public void setLessonId(String lessonId) { this.lessonId = lessonId; }
    public boolean isPreviewable() { return previewable; }
    public void setPreviewable(boolean previewable) { this.previewable = previewable; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getLessonType() { return lessonType; }
    public void setLessonType(String lessonType) { this.lessonType = lessonType; }
    public String getLessonTitle() { return lessonTitle; }
    public void setLessonTitle(String lessonTitle) { this.lessonTitle = lessonTitle; }
    public String getArticleMarkdown() { return articleMarkdown; }
    public void setArticleMarkdown(String articleMarkdown) { this.articleMarkdown = articleMarkdown; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPresentation() { return presentation; }
    public void setPresentation(String presentation) { this.presentation = presentation; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getVariantNames() { return variantNames; }
    public void setVariantNames(String variantNames) { this.variantNames = variantNames; }
    public String getProcessingContractVersion() { return processingContractVersion; }
    public void setProcessingContractVersion(String processingContractVersion) { this.processingContractVersion = processingContractVersion; }
}
