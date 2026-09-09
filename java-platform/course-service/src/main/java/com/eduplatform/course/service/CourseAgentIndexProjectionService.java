package com.eduplatform.course.service;

import com.eduplatform.course.web.CourseAgentIndexDtos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 从 Course 自有表读取当前 PUBLISHED ARTICLE；索引可见性不代替在线用户课程访问复核。
 * 平台当前课程边界为初中数学，故投影固定 JUNIOR/MATH；后续扩学段前必须版本化契约。
 */
@Service
@ConditionalOnProperty(prefix = "platform.course.agent-index", name = "enabled", havingValue = "true")
public class CourseAgentIndexProjectionService {
    private final JdbcTemplate jdbc;

    public CourseAgentIndexProjectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public CourseAgentIndexDtos.Page page(long afterRevision, String afterId, String entityId, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                WHERE c.publication_status='PUBLISHED' AND cv.id=c.published_version_id
                  AND cv.lifecycle_status='PUBLISHED' AND l.lesson_type='ARTICLE'
                  AND l.article_markdown IS NOT NULL AND l.article_markdown<>''
                """);
        if (entityId != null) {
            where.append(" AND l.id=?"); args.add(entityId);
        } else {
            where.append(" AND (COALESCE(ir.revision,1)>? OR (COALESCE(ir.revision,1)=? AND l.id>?))");
            args.add(afterRevision); args.add(afterRevision); args.add(afterId);
        }
        args.add(size + 1);
        List<CourseAgentIndexDtos.Projection> rows = jdbc.query("""
                SELECT l.id entity_id,c.id course_id,cv.id course_version_id,l.id lesson_id,
                       COALESCE(ir.revision,1) source_revision,l.title,l.article_markdown,cv.access_type
                FROM course c JOIN course_version cv ON cv.course_id=c.id
                JOIN chapter ch ON ch.course_version_id=cv.id JOIN lesson l ON l.chapter_id=ch.id
                LEFT JOIN course_text_index_revision ir ON ir.lesson_id=l.id
                """ + where + " ORDER BY COALESCE(ir.revision,1),l.id LIMIT ?", (rs, ignored) ->
                new CourseAgentIndexDtos.Projection(rs.getString("entity_id"), rs.getString("course_id"),
                        rs.getString("course_version_id"), rs.getString("lesson_id"),
                        rs.getLong("source_revision"), "JUNIOR", "MATH", rs.getString("title"),
                        rs.getString("article_markdown"), "PUBLIC".equals(rs.getString("access_type"))
                                ? "FREE" : "PAID"), args.toArray());
        boolean more = rows.size() > size;
        List<CourseAgentIndexDtos.Projection> items = more ? rows.subList(0, size) : rows;
        CourseAgentIndexDtos.Cursor cursor = items.isEmpty() ? null : new CourseAgentIndexDtos.Cursor(
                items.get(items.size() - 1).sourceRevision(), items.get(items.size() - 1).entityId());
        return new CourseAgentIndexDtos.Page(List.copyOf(items), more, cursor);
    }
}
