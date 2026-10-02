package com.eduplatform.iam.persistence;

import com.eduplatform.iam.domain.TeacherApplication;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 教师审核轮次、决定与 Outbox 的 IAM 本地存储端口。
 * 所有写入由调用服务的事务包裹；这里只读写 IAM Schema，不访问 Media 数据库。
 */
@Repository
public class TeacherReviewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TeacherReviewRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 创建一轮不可复用的申请材料引用；同一申请的 roundNumber 单调递增。 */
    public Round insertRound(String applicationId, String mediaId, String statement, Instant now) {
        int number = jdbc.queryForObject("SELECT COALESCE(MAX(round_number),0)+1 FROM teacher_application_round WHERE application_id=?",
                Integer.class, applicationId);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO teacher_application_round (id,application_id,round_number,qualification_media_id,statement,status,submitted_at) VALUES (?,?,?,?,?,'SUBMITTED',?)",
                id, applicationId, number, mediaId, statement, Timestamp.from(now));
        return round(id);
    }

    /** 按主键锁住申请，保证打开详情、决定与重提不会交错覆盖。 */
    public TeacherApplication lockApplication(String id) {
        List<TeacherApplication> values = jdbc.query("SELECT id,user_id,qualification_media_id,statement,status,rejection_reason,revision,submitted_at,updated_at FROM teacher_application WHERE id=? FOR UPDATE",
                (rs, ignored) -> application(rs), id);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 按申请 ID 读取当前投影；不存在返回 NULL，不触发审核状态变化。 */
    public TeacherApplication application(String id) {
        List<TeacherApplication> values = jdbc.query("SELECT id,user_id,qualification_media_id,statement,status,rejection_reason,revision,submitted_at,updated_at FROM teacher_application WHERE id=?",
                (rs, ignored) -> application(rs), id);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 只返回列表安全字段；精确身份搜索在子查询中进行，不把身份值投影给浏览器。 */
    public List<Summary> list(String status, String exact, boolean newest, long offset, int size) {
        String sql = "SELECT ta.id,ta.user_id,u.account,u.display_name,ta.status,ta.revision,ta.submitted_at "
                + "FROM teacher_application ta JOIN `user` u ON u.id=ta.user_id WHERE "
                + "(? IS NULL OR ta.status=?) AND (? IS NULL OR ta.id=? OR u.account=? OR EXISTS "
                + "(SELECT 1 FROM user_identity ui WHERE ui.user_id=u.id AND ui.normalized_value=?)) "
                + "ORDER BY ta.submitted_at " + (newest ? "DESC" : "ASC") + ", ta.id "
                + (newest ? "DESC" : "ASC") + " LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, ignored) -> new Summary(rs.getString("id"), rs.getString("user_id"),
                rs.getString("account"), rs.getString("display_name"), rs.getString("status"),
                rs.getLong("revision"), instant(rs, "submitted_at")),
                status, status, exact, exact, exact, exact, size, offset);
    }

    /** 使用与列表相同的状态和精确身份条件计数，供有界分页返回总数。 */
    public long count(String status, String exact) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM teacher_application ta JOIN `user` u ON u.id=ta.user_id "
                + "WHERE (? IS NULL OR ta.status=?) AND (? IS NULL OR ta.id=? OR u.account=? OR EXISTS "
                + "(SELECT 1 FROM user_identity ui WHERE ui.user_id=u.id AND ui.normalized_value=?))",
                Long.class, status, status, exact, exact, exact, exact);
    }

    /** 按申请 ID 返回不含联系方式与资质的管理摘要；不存在返回 NULL。 */
    public Summary summary(String id) {
        List<Summary> values = jdbc.query("SELECT ta.id,ta.user_id,u.account,u.display_name,ta.status,ta.revision,ta.submitted_at "
                + "FROM teacher_application ta JOIN `user` u ON u.id=ta.user_id WHERE ta.id=?",
                (rs, ignored) -> new Summary(rs.getString("id"), rs.getString("user_id"),
                        rs.getString("account"), rs.getString("display_name"), rs.getString("status"),
                        rs.getLong("revision"), instant(rs, "submitted_at")), id);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 返回申请最新轮次及其不可变决定；不存在返回 NULL。 */
    public Round latestRound(String applicationId) {
        List<Round> values = jdbc.query("SELECT r.*,d.actor_id,d.decided_at,d.rejection_reason FROM teacher_application_round r LEFT JOIN teacher_review_decision d ON d.round_id=r.id WHERE r.application_id=? ORDER BY r.round_number DESC LIMIT 1",
                ROUND, applicationId);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 按轮次 ID 返回材料与决定事实；不存在返回 NULL，由业务层再核对申请归属。 */
    public Round round(String id) {
        List<Round> values = jdbc.query("SELECT r.*,d.actor_id,d.decided_at,d.rejection_reason FROM teacher_application_round r LEFT JOIN teacher_review_decision d ON d.round_id=r.id WHERE r.id=?", ROUND, id);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 返回申请的全部历史轮次，按轮次号倒序供管理员详情展示。 */
    public List<Round> rounds(String applicationId) {
        return jdbc.query("SELECT r.*,d.actor_id,d.decided_at,d.rejection_reason FROM teacher_application_round r LEFT JOIN teacher_review_decision d ON d.round_id=r.id WHERE r.application_id=? ORDER BY r.round_number DESC",
                ROUND, applicationId);
    }

    /** 首次查看只从 SUBMITTED 推进一次；其他管理员打开不取得排他锁。 */
    public void startReview(String applicationId, String roundId, String actor, Instant now) {
        jdbc.update("UPDATE teacher_application SET status='UNDER_REVIEW', revision=revision+1, updated_at=? WHERE id=? AND status='SUBMITTED'",
                Timestamp.from(now), applicationId);
        jdbc.update("UPDATE teacher_application_round SET status='UNDER_REVIEW',first_viewed_by=?,first_viewed_at=? WHERE id=? AND status='SUBMITTED'",
                actor, Timestamp.from(now), roundId);
    }

    /** 决定只推进当前未决定轮次；调用方持有申请行锁并检查 revision。 */
    public void decide(String applicationId, String roundId, String decision, String reason, Instant now) {
        jdbc.update("UPDATE teacher_application SET status=?,rejection_reason=?,revision=revision+1,updated_at=? WHERE id=?",
                decision, reason, Timestamp.from(now), applicationId);
        jdbc.update("UPDATE teacher_application_round SET status=?,retention_until=? WHERE id=?",
                decision, "REJECTED".equals(decision) ? Timestamp.from(now.plusSeconds(7 * 86400L)) : null, roundId);
    }

    /** 在调用方事务追加一次决定事实；轮次唯一约束阻止覆盖既有决定。 */
    public void insertDecision(String id, String applicationId, String roundId, String actor,
            String decision, String reason, Instant now) {
        jdbc.update("INSERT INTO teacher_review_decision (id,application_id,round_id,actor_id,decision,rejection_reason,decided_at) VALUES (?,?,?,?,?,?,?)",
                id, applicationId, roundId, actor, decision, reason, Timestamp.from(now));
    }

    /** 按稳定请求 ID 读取已提交的指纹和原响应；没有记录返回 NULL。 */
    public Replay replay(String requestId) {
        List<Replay> values = jdbc.query("SELECT request_fingerprint,result_json FROM teacher_review_command WHERE client_request_id=?",
                (rs, ignored) -> new Replay(rs.getString(1), rs.getString(2)), requestId);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 把最终响应与请求指纹写入同一决定事务；请求 ID 冲突由调用方转为 409。 */
    public void insertCommand(String requestId, String applicationId, String fingerprint, String decisionId,
            Object result, Instant now) {
        jdbc.update("INSERT INTO teacher_review_command (client_request_id,application_id,request_fingerprint,decision_id,result_json,created_at) VALUES (?,?,?,?,?,?)",
                requestId, applicationId, fingerprint, decisionId, stringify(result), Timestamp.from(now));
    }

    /** 仅延后旧材料的释放截止；新轮次若复用同一 mediaId 仍另有有效引用。 */
    public void replaceRetention(String roundId, Instant now) {
        jdbc.update("UPDATE teacher_application_round SET retention_until=? WHERE id=? AND status='REJECTED'",
                Timestamp.from(now.plusSeconds(7 * 86400L)), roundId);
    }

    /** 记录引用绑定事件已经与申请同事务入队；历史 V2 申请升级时保持 NULL 等待有界补建。 */
    public int markBoundEnqueued(String roundId, Instant now) {
        return jdbc.update("UPDATE teacher_application_round SET bound_enqueued_at=? WHERE id=? AND bound_enqueued_at IS NULL",
                Timestamp.from(now), roundId);
    }

    /** 有界取出迁移回填且仍有效的旧轮次，供 Worker 补建引用绑定事件。 */
    public List<Round> missingBoundEvents(Instant now) {
        return jdbc.query("SELECT r.*,d.actor_id,d.decided_at,d.rejection_reason FROM teacher_application_round r LEFT JOIN teacher_review_decision d ON d.round_id=r.id WHERE r.bound_enqueued_at IS NULL AND (r.retention_until IS NULL OR r.retention_until>?) ORDER BY r.submitted_at,r.id LIMIT 50",
                ROUND, Timestamp.from(now));
    }

    /** 有界取出当前时间已到期且未排队释放的轮次，仍需由 Media 消费后才真正解引用。 */
    public List<Round> dueReleases(Instant now) {
        return jdbc.query("SELECT r.*,d.actor_id,d.decided_at,d.rejection_reason FROM teacher_application_round r LEFT JOIN teacher_review_decision d ON d.round_id=r.id WHERE r.retention_until<=? AND r.release_enqueued_at IS NULL ORDER BY r.retention_until,r.id LIMIT 50",
                ROUND, Timestamp.from(now));
    }

    /** 原子标记到期轮次已排队释放；返回 1 才能在同一事务写释放 Outbox。 */
    public int markReleased(String id, Instant now) {
        return jdbc.update("UPDATE teacher_application_round SET release_enqueued_at=? WHERE id=? AND release_enqueued_at IS NULL AND retention_until<=?",
                Timestamp.from(now), id, Timestamp.from(now));
    }

    /** 将媒体或 IAM 事件封入标准信封并写在当前事务中；只含稳定 ID 和审核状态。 */
    public void outbox(String type, String exchange, String routingKey, String aggregateId,
            Map<String, ?> payload, String traceId, Instant now) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> envelope = Map.of("eventId", id, "eventType", type, "eventVersion", 1,
                "aggregateId", aggregateId, "occurredAt", now.toString(), "traceId", traceId,
                "payload", payload);
        jdbc.update("INSERT INTO iam_outbox_event (event_id,event_type,event_version,exchange_name,routing_key,aggregate_id,trace_id,payload_json,status,next_attempt_at,created_at) VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?)",
                id, type, 1, exchange, routingKey, aggregateId, traceId, stringify(envelope),
                Timestamp.from(now), Timestamp.from(now));
    }

    /** 按到期时间读取最多 50 条待发事件；发布器还需检查同媒体前序事件是否已确认。 */
    public List<Outbox> pending(Instant now) {
        return jdbc.query("SELECT event_id,event_type,event_version,exchange_name,routing_key,trace_id,payload_json,attempts FROM iam_outbox_event WHERE status='PENDING' AND next_attempt_at<=? ORDER BY created_at,event_id LIMIT 50",
                (rs, ignored) -> new Outbox(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8)), Timestamp.from(now));
    }

    /** 同一媒体先绑定后释放；前序事件未确认时阻止后续事件越过它，FAILED 留待受控重放。 */
    public boolean hasEarlierUnpublished(String eventId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_outbox_event current_event
                JOIN iam_outbox_event older ON older.aggregate_id=current_event.aggregate_id
                  AND (older.created_at<current_event.created_at
                    OR (older.created_at=current_event.created_at AND older.event_id<current_event.event_id))
                  AND older.status<>'PUBLISHED'
                WHERE current_event.event_id=?
                """, Long.class, eventId);
        return count != null && count > 0;
    }

    /** Broker 确认且未 return 后才把指定事件标记为 PUBLISHED。 */
    public void published(String id, Instant now) {
        jdbc.update("UPDATE iam_outbox_event SET status='PUBLISHED',published_at=? WHERE event_id=? AND status='PENDING'",
                Timestamp.from(now), id);
    }

    /** 记录发布失败和下次重试时间；次数耗尽转 FAILED，保留原事件供受控修复。 */
    public void failed(String id, int attempts, boolean exhausted, String error, Instant next) {
        jdbc.update("UPDATE iam_outbox_event SET status=?,attempts=?,last_error=?,next_attempt_at=? WHERE event_id=? AND status='PENDING'",
                exhausted ? "FAILED" : "PENDING", attempts, error, Timestamp.from(next), id);
    }

    private String stringify(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException failure) { throw new IllegalStateException("Outbox JSON serialization failed", failure); }
    }

    private static TeacherApplication application(ResultSet rs) throws SQLException {
        TeacherApplication value = new TeacherApplication();
        value.setId(rs.getString("id")); value.setUserId(rs.getString("user_id"));
        value.setQualificationMediaId(rs.getString("qualification_media_id"));
        value.setStatement(rs.getString("statement")); value.setStatus(rs.getString("status"));
        value.setRejectionReason(rs.getString("rejection_reason")); value.setRevision(rs.getLong("revision"));
        value.setSubmittedAt(instant(rs, "submitted_at")); value.setUpdatedAt(instant(rs, "updated_at"));
        return value;
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Round> ROUND = (rs, ignored) -> new Round(rs.getString("id"),
            rs.getString("application_id"), rs.getInt("round_number"), rs.getString("qualification_media_id"),
            rs.getString("statement"), rs.getString("status"), instant(rs, "submitted_at"),
            rs.getString("first_viewed_by"), instant(rs, "first_viewed_at"),
            instant(rs, "retention_until"), instant(rs, "bound_enqueued_at"),
            instant(rs, "release_enqueued_at"),
            rs.getString("actor_id"), instant(rs, "decided_at"), rs.getString("rejection_reason"));

    public record Summary(String id, String userId, String account, String displayName, String status,
            long revision, Instant submittedAt) {}
    public record Round(String id, String applicationId, int number, String mediaId, String statement,
            String status, Instant submittedAt, String firstViewedBy, Instant firstViewedAt,
            Instant retentionUntil, Instant boundEnqueuedAt, Instant releaseEnqueuedAt, String decidedBy, Instant decidedAt,
            String rejectionReason) {}
    public record Replay(String fingerprint, String resultJson) {}
    public record Outbox(String eventId, String eventType, int eventVersion, String exchange,
            String routingKey, String traceId, String payloadJson, int attempts) {}
}
