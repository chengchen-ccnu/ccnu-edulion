package com.eduplatform.course.service;

import com.eduplatform.course.persistence.EnrollmentAccessRepository;
import com.eduplatform.course.web.CourseAgentAccessDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 Course 当前发布版本和本地报名投影逐项二次授权索引候选。
 * entity/course/version/lesson/revision 任一不符、课程下架或报名取消均直接剔除。
 */
@Service
@ConditionalOnProperty(prefix = "platform.course.agent-access", name = "enabled", havingValue = "true")
public class CourseAgentAccessService {
    private final JdbcTemplate jdbc;
    private final EnrollmentAccessRepository enrollmentAccess;

    public CourseAgentAccessService(JdbcTemplate jdbc, EnrollmentAccessRepository enrollmentAccess) {
        this.jdbc = jdbc;
        this.enrollmentAccess = enrollmentAccess;
    }

    @Transactional(readOnly = true)
    public CourseAgentAccessDtos.VerifyResponse verify(CourseAgentAccessDtos.VerifyRequest request) {
        List<UUID> allowed = new ArrayList<>();
        for (CourseAgentAccessDtos.Candidate candidate : request.candidates()) {
            Projection projection = jdbc.query("""
                    SELECT cv.access_type
                    FROM course c JOIN course_version cv ON cv.id=c.published_version_id
                    JOIN chapter ch ON ch.course_version_id=cv.id
                    JOIN lesson l ON l.chapter_id=ch.id
                    LEFT JOIN course_text_index_revision ir ON ir.lesson_id=l.id
                    WHERE c.id=? AND c.publication_status='PUBLISHED' AND cv.lifecycle_status='PUBLISHED'
                      AND cv.id=? AND l.id=? AND l.id=? AND l.lesson_type='ARTICLE'
                      AND l.article_markdown IS NOT NULL AND l.article_markdown<>''
                      AND COALESCE(ir.revision,1)=?
                    """, (rs, ignored) -> new Projection(rs.getString(1)),
                    candidate.courseId().toString(), candidate.courseVersionId(),
                    candidate.lessonId().toString(), candidate.entityId().toString(),
                    candidate.sourceRevision()).stream().findFirst().orElse(null);
            if (projection != null && ("PUBLIC".equals(projection.accessType())
                    || enrollmentAccess.hasActive(request.ownerUserId().toString(), candidate.courseId().toString()))) {
                allowed.add(candidate.entityId());
            }
        }
        return new CourseAgentAccessDtos.VerifyResponse(List.copyOf(allowed));
    }

    private record Projection(String accessType) {}
}
