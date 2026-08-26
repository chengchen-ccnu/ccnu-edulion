package com.eduplatform.course.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * course-service 本地报名访问投影。只保存播放授权所需的 learner/course/status 事实，
 * 不复制报名领域表，也不跨 Schema 查询 learning-service。
 */
@Repository
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true")
public class EnrollmentAccessRepository {
    private final JdbcTemplate jdbc;

    public EnrollmentAccessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** @return 当前访问者是否具有 ACTIVE 报名事实；MySQL 投影是播放决策的本地事实来源。 */
    public boolean hasActive(String learnerId, String courseId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM course_enrollment_access WHERE learner_id=? AND course_id=? AND status='ACTIVE'",
                Integer.class, learnerId, courseId);
        return count != null && count > 0;
    }

    /**
     * eventId 先写消费幂等表并与投影更新共用事务；同一事件重复投递无副作用，
     * changedAt 较旧的乱序事件只记为已消费，不允许访问状态倒退。
     */
    @Transactional
    public void apply(String eventId, String enrollmentId, String learnerId, String courseId,
            String status, Instant changedAt, Instant now) {
        try {
            jdbc.update("INSERT INTO consumed_learning_event(event_id,event_type,aggregate_id,consumed_at) VALUES(?,'EnrollmentAccessChanged',?,?)",
                    eventId, enrollmentId, Timestamp.from(now));
        } catch (DuplicateKeyException replay) {
            return;
        }
        Projection current = jdbc.query("SELECT status,changed_at FROM course_enrollment_access WHERE learner_id=? AND course_id=? FOR UPDATE",
                (rs, row) -> new Projection(rs.getString("status"), rs.getTimestamp("changed_at").toInstant()),
                learnerId, courseId).stream().findFirst().orElse(null);
        if (current == null) {
            jdbc.update("INSERT INTO course_enrollment_access(learner_id,course_id,enrollment_id,status,changed_at,updated_at) VALUES(?,?,?,?,?,?)",
                    learnerId, courseId, enrollmentId, status, Timestamp.from(changedAt), Timestamp.from(now));
        } else if (!changedAt.isBefore(current.changedAt())) {
            jdbc.update("UPDATE course_enrollment_access SET enrollment_id=?,status=?,changed_at=?,updated_at=? WHERE learner_id=? AND course_id=?",
                    enrollmentId, status, Timestamp.from(changedAt), Timestamp.from(now), learnerId, courseId);
        }
    }

    private record Projection(String status, Instant changedAt) {}
}
