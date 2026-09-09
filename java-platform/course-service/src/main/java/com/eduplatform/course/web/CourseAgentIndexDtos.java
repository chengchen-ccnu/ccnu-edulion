package com.eduplatform.course.web;

import java.util.List;

/** Worker 可读取的当前已发布 ARTICLE 安全文字投影。 */
public final class CourseAgentIndexDtos {
    private CourseAgentIndexDtos() {}
    public record Cursor(long sourceRevision, String entityId) {}
    public record Projection(String entityId, String courseId, String courseVersionId, String lessonId,
            long sourceRevision, String educationStage, String subject, String title,
            String articleMarkdown, String accessType) {}
    public record Page(List<Projection> items, boolean hasMore, Cursor nextCursor) {}
}
