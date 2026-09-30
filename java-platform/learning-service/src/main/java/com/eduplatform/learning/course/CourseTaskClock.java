package com.eduplatform.learning.course;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.progress.ProgressBufferStore.BufferedProgress;
import com.eduplatform.learning.service.QuestionException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在 Learning 课程行锁下同步 Course 权威区间并顺延已有任务/草稿。
 * 每次访问走实时 HTTP，事件仅用于主动追平；故障拒绝访问。锁顺序固定课程→测评→作答。
 */
@Service
public class CourseTaskClock {
    private final JdbcTemplate jdbc;
    private final CourseFactsClient courses;
    private final TransactionTemplate transactions;

    public CourseTaskClock(JdbcTemplate jdbc, CourseFactsClient courses, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.courses = courses; this.transactions = new TransactionTemplate(manager);
    }

    /** 持有课程锁直到领域调用完成；暂停返回安全错误，失败回滚本次补时与领域写入。 */
    public <T> T within(String courseId, String teacherId, boolean background, Supplier<T> operation) {
        if (courseId == null) return operation.get(); // AUTONOMOUS PRACTICE 不受课程暂停影响。
        return transactions.execute(status -> {
            CourseAvailability fact = synchronize(courseId);
            if (teacherId != null && !teacherId.equals(fact.ownerTeacherId())) throw notFound();
            if (fact.paused()) {
                if (background) return null;
                throw new CoursePausedException(fact.supportEmail());
            }
            return operation.get();
        });
    }

    /** 当前事务已持有课程锁时读取 Redis 代际；旧缓冲被逻辑舍弃，不允许恢复后重新物化。 */
    public long revision(String courseId) {
        return jdbc.query("SELECT availability_revision FROM learning_course_clock WHERE course_id=?",
                (rs, n) -> rs.getLong(1), courseId).stream().findFirst().orElse(0L);
    }

    /** 强制检查点/读取与 Worker 使用同一水位；旧聚合整个舍弃，不能保留其中暂停后的日增量。 */
    public boolean accepts(BufferedProgress snapshot) {
        return snapshot.availabilityRevision() == revision(snapshot.courseId());
    }

    /** 恢复后的事件至少在恢复时间之后；观看秒数最多计入恢复后已经实际经过的时间。 */
    public long acceptedWatchedSeconds(String courseId, Instant occurredAt, long watchedSeconds, Instant now) {
        Instant resumed = jdbc.query("SELECT last_resumed_at FROM learning_course_clock WHERE course_id=?",
                (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), courseId)
                .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (resumed == null) return watchedSeconds;
        if (!occurredAt.isAfter(resumed)) throw new QuestionException(HttpStatus.CONFLICT,
                "LEARNING.PROGRESS_PAUSE_BOUNDARY", "课程暂停前或暂停期间未保存的观看进度已舍弃，请重新同步");
        return Math.min(watchedSeconds, Math.max(0, Duration.between(resumed, now).getSeconds()));
    }

    /** Worker 同课程互斥；暂停或代际变化时 ACK 舍弃，依赖失败时抛出由 Worker 保留 dirty 重试。 */
    public boolean persistBuffered(BufferedProgress snapshot, LearningAssessmentRepository repository, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            CourseAvailability fact = synchronize(snapshot.courseId());
            if (fact.paused() || !accepts(snapshot)) return false;
            repository.applyBufferedProgress(snapshot.learnerId(), snapshot.courseId(), snapshot.lessonId(),
                    snapshot.contentRevisionId(), snapshot.positionSeconds(), snapshot.watchedSeconds(), snapshot.completed(),
                    snapshot.lastEventAt(), snapshot.version(), now, snapshot.watchedByDay(), snapshot.firstStartedDay());
            return true;
        }));
    }

    /** 消费者按 eventId 幂等；乱序提示读取当前权威事实并补齐区间，不应用旧 payload 覆盖新状态。 */
    public void consume(String eventId, String courseId, long revision) {
        transactions.executeWithoutResult(status -> {
            var seen = jdbc.queryForList("SELECT course_id,availability_revision FROM course_availability_consumed_event WHERE event_id=?", eventId);
            if (!seen.isEmpty()) {
                var previous = seen.get(0);
                if (!courseId.equals(previous.get("course_id")) || revision != ((Number) previous.get("availability_revision")).longValue()) throw unavailable();
                return;
            }
            CourseAvailability current = synchronize(courseId);
            if (revision > current.availabilityRevision()) throw unavailable();
            jdbc.update("INSERT INTO course_availability_consumed_event(event_id,course_id,availability_revision,consumed_at) VALUES(?,?,?,?)",
                    eventId, courseId, revision, Timestamp.from(Instant.now()));
        });
    }

    /** 同步所有尚未应用的有界分页，闭合区间一次补时；多页间权威 revision 改变则回滚，下一请求重试。 */
    private CourseAvailability synchronize(String courseId) {
        jdbc.update("INSERT INTO learning_course_clock(course_id) VALUES(?) ON DUPLICATE KEY UPDATE course_id=course_id", courseId);
        var local = jdbc.queryForMap("SELECT applied_sequence,availability_revision FROM learning_course_clock WHERE course_id=? FOR UPDATE", courseId);
        long cursor = ((Number) local.get("applied_sequence")).longValue();
        CourseAvailability fact = courses.availability(courseId, cursor, UUID.randomUUID().toString());
        if (fact == null || fact.availabilityRevision() < ((Number) local.get("availability_revision")).longValue()) throw unavailable();
        long revision = fact.availabilityRevision();
        Instant resumed = null;
        while (true) {
            if (fact.availabilityRevision() != revision) throw unavailable();
            for (var interval : fact.intervals()) {
                if (interval.sequence() <= cursor) throw unavailable();
                if (interval.resumedAt() == null) continue;
                int affected = extend(courseId, interval);
                jdbc.update("INSERT INTO learning_course_clock_interval(course_id,sequence,suspension_id,suspended_at,resumed_at,affected_assessments) VALUES(?,?,?,?,?,?)",
                        courseId, interval.sequence(), interval.suspensionId(), Timestamp.from(interval.suspendedAt()),
                        Timestamp.from(interval.resumedAt()), affected);
                cursor = interval.sequence(); resumed = interval.resumedAt();
            }
            if (!fact.hasMore()) break;
            fact = courses.availability(courseId, cursor, UUID.randomUUID().toString());
            if (fact == null) throw unavailable();
        }
        jdbc.update("UPDATE learning_course_clock SET applied_sequence=?,availability_revision=?,paused=?,last_resumed_at=COALESCE(?,last_resumed_at) WHERE course_id=?",
                cursor, revision, fact.paused(), resumed == null ? null : Timestamp.from(resumed), courseId);
        return fact;
    }

    /**
     * 每批最多锁 100 场 HOMEWORK/EXAM；仅暂停开始尚有剩余时间且未关闭/未公布的任务顺延。
     * 已过期草稿不重开，已提交 attempt 的截止与成绩不变；PRACTICE 只受访问门禁，不虚构任务时钟。
     */
    private int extend(String courseId, CourseAvailability.Interval interval) {
        long micros = Duration.between(interval.suspendedAt(), interval.resumedAt()).toNanos() / 1000;
        String after = "";
        int affected = 0;
        while (true) {
            List<Timing> batch = jdbc.query("""
                    SELECT id,opens_at,closes_at,late_until FROM assessment
                    WHERE course_id=? AND id>? AND status='PUBLISHED' AND close_status='OPEN'
                      AND assessment_type IN ('HOMEWORK','EXAM') AND published_at<=?
                      AND grade_publication_status='UNPUBLISHED' AND answer_publication_status='UNPUBLISHED'
                      AND (CASE WHEN late_policy='ALLOW_UNTIL' THEN late_until ELSE closes_at END IS NULL
                        OR CASE WHEN late_policy='ALLOW_UNTIL' THEN late_until ELSE closes_at END>?)
                    ORDER BY id LIMIT 100 FOR UPDATE
                    """, (rs, n) -> new Timing(rs.getString(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3)),
                            instant(rs.getTimestamp(4))), courseId, after, Timestamp.from(interval.suspendedAt()), Timestamp.from(interval.suspendedAt()));
            if (batch.isEmpty()) break;
            for (Timing task : batch) {
                after = task.id();
                Instant opens = task.opensAt() != null && !task.opensAt().isBefore(interval.suspendedAt())
                        ? shift(task.opensAt(), micros) : task.opensAt();
                jdbc.update("UPDATE assessment SET opens_at=?,closes_at=?,late_until=?,revision=revision+1,updated_at=? WHERE id=?",
                        timestamp(opens), timestamp(shift(task.closesAt(), micros)), timestamp(shift(task.lateUntil(), micros)),
                        Timestamp.from(interval.resumedAt()), task.id());
                jdbc.update("UPDATE assessment_attempt SET deadline_at=TIMESTAMPADD(MICROSECOND,?,deadline_at),revision=revision+1,updated_at=? WHERE assessment_id=? AND status='DRAFT' AND deadline_at>?",
                        micros, Timestamp.from(interval.resumedAt()), task.id(), Timestamp.from(interval.suspendedAt()));
                affected++;
            }
        }
        return affected;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant shift(Instant value, long micros) { return value == null ? null : value.plusNanos(micros * 1000); }
    private record Timing(String id, Instant opensAt, Instant closesAt, Instant lateUntil) {}
    private static QuestionException unavailable() { return new QuestionException(HttpStatus.SERVICE_UNAVAILABLE,
            "LEARNING.COURSE_DEPENDENCY_UNAVAILABLE", "课程服务暂时不可用，请稍后重试"); }
    private static QuestionException notFound() { return new QuestionException(HttpStatus.NOT_FOUND,
            "LEARNING.NOT_FOUND", "学习资源不存在或不可见"); }
}
