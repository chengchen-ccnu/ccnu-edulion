package com.eduplatform.course.domain;

/** course-service 内部课程分类投影；只有 ACTIVE 分类可用于公开筛选和课程编辑。 */
public class CategoryEntity {
    private String id;
    private String name;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
