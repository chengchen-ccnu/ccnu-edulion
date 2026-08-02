package com.eduplatform.course.domain;

/** 属于单个课程版本的章节投影；position 在版本内唯一并由服务层保证连续。 */
public class ChapterEntity {
    private String id;
    private String courseVersionId;
    private String title;
    private int position;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCourseVersionId() { return courseVersionId; }
    public void setCourseVersionId(String courseVersionId) { this.courseVersionId = courseVersionId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
