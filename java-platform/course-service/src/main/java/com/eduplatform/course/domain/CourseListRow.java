package com.eduplatform.course.domain;

public class CourseListRow {
    private String id;
    private String title;
    private String summary;
    private String coverUrl;
    private String teacherId;
    private String teacherDisplayName;
    private String categoryId;
    private String categoryName;
    private String level;
    private String accessType;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public String getTeacherId() { return teacherId; }
    public void setTeacherId(String teacherId) { this.teacherId = teacherId; }
    public String getTeacherDisplayName() { return teacherDisplayName; }
    public void setTeacherDisplayName(String teacherDisplayName) { this.teacherDisplayName = teacherDisplayName; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }
}
