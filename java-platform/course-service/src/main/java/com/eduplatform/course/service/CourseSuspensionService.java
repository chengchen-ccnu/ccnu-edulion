package com.eduplatform.course.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.eduplatform.course.messaging.CourseOutboxEvent;
import com.eduplatform.course.persistence.CourseMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Course 独立系统暂停事实；输入是 IAM 单调能力命令，输出为暂停区间及同事务 Outbox，不拥有 IAM 原因。 */
@Service
public class CourseSuspensionService {
    public static final String EXCHANGE = "edu.course.availability.events.v1";
    public static final String ROUTING_KEY = "course.availability.changed.v1";
    private final JdbcTemplate jdbc;
    private final CourseMapper courses;
    private final ObjectMapper mapper;
    private final String supportEmail;

    public CourseSuspensionService(JdbcTemplate jdbc, CourseMapper courses, ObjectMapper mapper,
            @Value("${platform.course.suspension.support-email:support@example.invalid}") String supportEmail) {
        this.jdbc = jdbc; this.courses = courses; this.mapper = mapper; this.supportEmail = supportEmail;
    }

    /** 先锁 owner，再锁课程；发布与账号命令必须共用此顺序，重复初始化不改变能力事实。 */
    public TeacherState lockTeacher(String teacherId) {
        jdbc.update("INSERT INTO teacher_course_availability(teacher_id) VALUES(?) ON DUPLICATE KEY UPDATE teacher_id=teacher_id", teacherId);
        return jdbc.query("SELECT * FROM teacher_course_availability WHERE teacher_id=? FOR UPDATE",
                (rs, n) -> new TeacherState(teacherId, rs.getLong("access_revision"), rs.getString("suspension_id"),
                        rs.getBoolean("paused"), rs.getInt("affected_courses")), teacherId).get(0);
    }

    /** IAM 命令在 owner 锁下按课程 ID 每批 100 门推进；异常整体回滚，旧命令无副作用，恢复只匹配原周期。 */
    @Transactional
    public TeacherState change(String teacherId, String suspensionId, long revision, boolean paused, String traceId) {
        TeacherState current = lockTeacher(teacherId);
        if (revision < current.accessRevision()) return current;
        if (revision == current.accessRevision()) {
            if (paused != current.paused() || !Objects.equals(suspensionId, current.suspensionId())) throw conflict();
            return current;
        }
        if (current.paused() && !suspensionId.equals(current.suspensionId())) throw conflict();
        // 恢复后的周期不可再次使用；否则同一 suspensionId 可能产生重复补时或唯一约束异常。
        if (paused && !current.paused() && (suspensionId.equals(current.suspensionId()) || jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_suspension_interval i JOIN course c ON c.id=i.course_id WHERE c.owner_teacher_id=? AND i.suspension_id=?",
                Integer.class, teacherId, suspensionId) > 0)) throw conflict();
        int affected = 0;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String after = "";
        while (true) {
            List<CourseState> batch = jdbc.query("""
                    SELECT id,availability_revision FROM course
                    WHERE owner_teacher_id=? AND publication_status='PUBLISHED' AND id>? AND system_suspended=?
                    ORDER BY id LIMIT 100 FOR UPDATE
                    """, (rs, n) -> new CourseState(rs.getString(1), rs.getLong(2)), teacherId, after, !paused);
            if (batch.isEmpty()) break;
            for (CourseState course : batch) {
                after = course.id();
                Interval interval;
                long next = course.revision() + 1;
                if (paused) {
                    interval = new Interval(next, suspensionId, now, null);
                    jdbc.update("INSERT INTO course_suspension_interval(course_id,sequence,suspension_id,suspended_at) VALUES(?,?,?,?)",
                            course.id(), next, suspensionId, Timestamp.from(now));
                } else {
                    interval = jdbc.query("SELECT * FROM course_suspension_interval WHERE course_id=? AND suspension_id=? AND resumed_at IS NULL",
                            (rs, n) -> interval(rs), course.id(), suspensionId).stream().findFirst().orElse(null);
                    if (interval == null) continue;
                    now = now.isBefore(interval.suspendedAt()) ? interval.suspendedAt() : now;
                    jdbc.update("UPDATE course_suspension_interval SET resumed_at=? WHERE course_id=? AND sequence=?",
                            Timestamp.from(now), course.id(), interval.sequence());
                    interval = new Interval(interval.sequence(), suspensionId, interval.suspendedAt(), now);
                }
                jdbc.update("UPDATE course SET system_suspended=?,availability_revision=? WHERE id=?", paused, next, course.id());
                emit(course.id(), next, paused, interval, traceId, now);
                affected++;
            }
        }
        jdbc.update("UPDATE teacher_course_availability SET access_revision=?,suspension_id=?,paused=?,affected_courses=? WHERE teacher_id=?",
                revision, suspensionId, paused, affected, teacherId);
        return new TeacherState(teacherId, revision, suspensionId, paused, affected);
    }

    /** Learning 从完成区间水位读取最多 100 项；仍暂停项的 resumedAt 显式为空，不能当成已经恢复。 */
    @Transactional(readOnly = true)
    public Availability availability(String courseId, long afterSequence) {
        var states = jdbc.query("SELECT owner_teacher_id,availability_revision,system_suspended FROM course WHERE id=?",
                (rs, n) -> new Availability(courseId, rs.getString(1), rs.getLong(2), rs.getBoolean(3), supportEmail, List.of(), false), courseId);
        if (states.isEmpty()) throw new CourseException(HttpStatus.NOT_FOUND, "COURSE.NOT_FOUND", "课程不存在或不可见");
        List<Interval> values = jdbc.query("SELECT * FROM course_suspension_interval WHERE course_id=? AND sequence>? ORDER BY sequence LIMIT 101",
                (rs, n) -> interval(rs), courseId, afterSequence);
        Availability state = states.get(0);
        return new Availability(courseId, state.ownerTeacherId(), state.availabilityRevision(), state.paused(),
                supportEmail, values.subList(0, Math.min(100, values.size())), values.size() > 100);
    }

    /** 读取/媒体/旧内容身份均调用；只返回通用不可用说明，原因和 owner 不进入外部错误。 */
    public void requireActive(String courseId) {
        Boolean paused = jdbc.query("SELECT system_suspended FROM course WHERE id=?", (rs, n) -> rs.getBoolean(1), courseId)
                .stream().findFirst().orElse(false);
        if (paused) throw new CourseSuspendedException(supportEmail);
    }

    /** Media 已校验文件 owner 后实时验证其教学文件读取资格；只读本域撤权水位，不查 IAM/Media 表。 */
    public void requireTeacherMediaAccess(String teacherId) {
        boolean paused = jdbc.query("SELECT paused FROM teacher_course_availability WHERE teacher_id=?",
                (rs, n) -> rs.getBoolean(1), teacherId).stream().findFirst().orElse(false);
        if (paused) throw new CourseSuspendedException(supportEmail);
    }

    private static Interval interval(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp end = rs.getTimestamp("resumed_at");
        return new Interval(rs.getLong("sequence"), rs.getString("suspension_id"), rs.getTimestamp("suspended_at").toInstant(),
                end == null ? null : end.toInstant());
    }

    /** 事件只带公开状态与区间，复用已有有确认的 Outbox 发布器；不会发布撤权原因或媒体标识。 */
    private void emit(String courseId, long revision, boolean paused, Interval interval, String traceId, Instant now) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("courseId", courseId); payload.put("suspensionId", interval.suspensionId());
        payload.put("availabilityRevision", revision); payload.put("paused", paused);
        payload.put("suspendedAt", interval.suspendedAt()); payload.put("resumedAt", interval.resumedAt());
        // 全局 non_null 展示设置不能删掉事件契约要求的 resumedAt:null。
        tools.jackson.databind.node.ObjectNode payloadNode = mapper.valueToTree(payload);
        if (interval.resumedAt() == null) payloadNode.putNull("resumedAt");
        Map<String, Object> envelope = Map.of("eventId", id, "eventType", "CourseAvailabilityChanged", "eventVersion", 1,
                "aggregateId", courseId, "occurredAt", now, "traceId", traceId, "payload", payloadNode);
        courses.insertOutbox(new CourseOutboxEvent(id, "CourseAvailabilityChanged", 1, courseId, EXCHANGE, ROUTING_KEY,
                traceId, mapper.writeValueAsString(envelope), "PENDING", 0, now, null, null, now, now));
    }

    private static CourseException conflict() {
        return new CourseException(HttpStatus.CONFLICT, "COURSE.AVAILABILITY_CONFLICT", "课程系统可用性命令冲突");
    }
    public record TeacherState(String teacherId, long accessRevision, String suspensionId, boolean paused, int affectedCourses) {}
    private record CourseState(String id, long revision) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Interval(long sequence, String suspensionId, Instant suspendedAt, Instant resumedAt) {}
    public record Availability(String courseId, String ownerTeacherId, long availabilityRevision, boolean paused,
            String supportEmail, List<Interval> intervals, boolean hasMore) {}
}
