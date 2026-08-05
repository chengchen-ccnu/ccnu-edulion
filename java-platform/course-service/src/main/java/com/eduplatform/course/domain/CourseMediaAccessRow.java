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
}
