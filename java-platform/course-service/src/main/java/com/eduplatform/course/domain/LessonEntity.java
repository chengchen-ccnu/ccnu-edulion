package com.eduplatform.course.domain;

public class LessonEntity {
    private String id;
    private String chapterId;
    private String title;
    private String lessonType;
    private int position;
    private boolean previewable;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getLessonType() { return lessonType; }
    public void setLessonType(String lessonType) { this.lessonType = lessonType; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isPreviewable() { return previewable; }
    public void setPreviewable(boolean previewable) { this.previewable = previewable; }
}
