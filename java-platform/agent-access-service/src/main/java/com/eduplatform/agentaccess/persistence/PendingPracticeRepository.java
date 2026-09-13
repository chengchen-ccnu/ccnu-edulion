package com.eduplatform.agentaccess.persistence;

import com.eduplatform.agentaccess.web.AgentDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 待确认动作仓储只访问 Agent Access 自有表；状态迁移均使用 revision/当前状态条件更新。 */
@Repository
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class PendingPracticeRepository {
    private final JdbcTemplate jdbc;

    public PendingPracticeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Row(UUID id, UUID conversationId, UUID ownerUserId, UUID sourceRunId,
            String criteriaJson, String candidateJson, int requestedCount, int actualCount,
            AgentDtos.PendingPracticeActionStatus status, long revision, Instant expiresAt,
            UUID confirmationRequestId, UUID cancellationRequestId, UUID autonomousPracticeId,
            UUID assessmentId, String startRoute, String failureCode, Instant createdAt, Instant updatedAt) {}

    public Optional<Row> byRun(UUID runId) {
        return jdbc.query("SELECT * FROM ai_pending_action WHERE source_run_id=?", this::map,
                runId.toString()).stream().findFirst();
    }
    public Optional<Row> owned(UUID owner, UUID actionId) {
        return jdbc.query("SELECT * FROM ai_pending_action WHERE owner_user_id=? AND id=?", this::map,
                owner.toString(), actionId.toString()).stream().findFirst();
    }
    public Optional<Row> lockOwned(UUID owner, UUID actionId) {
        return jdbc.query("SELECT * FROM ai_pending_action WHERE owner_user_id=? AND id=? FOR UPDATE", this::map,
                owner.toString(), actionId.toString()).stream().findFirst();
    }
    public Optional<Row> current(UUID owner, UUID conversationId) {
        return jdbc.query("SELECT * FROM ai_pending_action WHERE owner_user_id=? AND conversation_id=? AND status='PENDING' ORDER BY created_at DESC LIMIT 1",
                this::map, owner.toString(), conversationId.toString()).stream().findFirst();
    }
    public void invalidatePending(UUID conversationId, Instant now) {
        jdbc.update("UPDATE ai_pending_action SET status='CANCELLED',pending_slot=NULL,revision=revision+1,updated_at=? WHERE conversation_id=? AND status='PENDING'",
                ts(now), conversationId.toString());
    }
    public void insert(Row row) {
        jdbc.update("INSERT INTO ai_pending_action(id,conversation_id,owner_user_id,source_run_id,action_type,criteria_json,candidate_json,requested_count,actual_count,status,pending_slot,revision,expires_at,created_at,updated_at) VALUES(?,?,?,?,'CREATE_AUTONOMOUS_PRACTICE',?,?,?,?,?,1,0,?,?,?)",
                row.id().toString(), row.conversationId().toString(), row.ownerUserId().toString(),
                row.sourceRunId().toString(), row.criteriaJson(), row.candidateJson(), row.requestedCount(),
                row.actualCount(), row.status().name(), ts(row.expiresAt()), ts(row.createdAt()), ts(row.updatedAt()));
    }
    public int expire(UUID actionId, long revision, Instant now) {
        return jdbc.update("UPDATE ai_pending_action SET status='EXPIRED',pending_slot=NULL,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status='PENDING' AND expires_at<=?",
                ts(now), actionId.toString(), revision, ts(now));
    }
    public int confirm(UUID actionId, long revision, UUID requestId, Instant now) {
        return jdbc.update("UPDATE ai_pending_action SET status='EXECUTING',pending_slot=NULL,confirmation_request_id=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status='PENDING' AND expires_at>?",
                requestId.toString(), ts(now), actionId.toString(), revision, ts(now));
    }
    public int cancel(UUID actionId, long revision, UUID requestId, Instant now) {
        return jdbc.update("UPDATE ai_pending_action SET status='CANCELLED',pending_slot=NULL,cancellation_request_id=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status='PENDING' AND expires_at>?",
                requestId.toString(), ts(now), actionId.toString(), revision, ts(now));
    }
    public int succeed(UUID actionId, long revision, UUID practiceId, UUID assessmentId, String route, Instant now) {
        return jdbc.update("UPDATE ai_pending_action SET status='SUCCEEDED',autonomous_practice_id=?,assessment_id=?,start_route=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status='EXECUTING'",
                practiceId.toString(), assessmentId.toString(), route, ts(now), actionId.toString(), revision);
    }
    public int fail(UUID actionId, long revision, String code, Instant now) {
        return jdbc.update("UPDATE ai_pending_action SET status='FAILED',failure_code=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status='EXECUTING'",
                code, ts(now), actionId.toString(), revision);
    }
    public void audit(UUID actionId, UUID owner, UUID requestId, String event, String from, String to,
            String traceId, Instant now) {
        jdbc.update("INSERT INTO ai_pending_action_audit(id,action_id,owner_user_id,request_id,event_type,from_status,to_status,trace_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), actionId.toString(), owner.toString(),
                requestId == null ? null : requestId.toString(), event, from, to, traceId, ts(now));
    }

    private Row map(ResultSet rs, int ignored) throws SQLException {
        return new Row(uuid(rs, "id"), uuid(rs, "conversation_id"), uuid(rs, "owner_user_id"),
                uuid(rs, "source_run_id"), rs.getString("criteria_json"), rs.getString("candidate_json"),
                rs.getInt("requested_count"), rs.getInt("actual_count"),
                AgentDtos.PendingPracticeActionStatus.valueOf(rs.getString("status")), rs.getLong("revision"),
                rs.getTimestamp("expires_at").toInstant(), nullableUuid(rs, "confirmation_request_id"),
                nullableUuid(rs, "cancellation_request_id"), nullableUuid(rs, "autonomous_practice_id"),
                nullableUuid(rs, "assessment_id"), rs.getString("start_route"), rs.getString("failure_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private static UUID uuid(ResultSet rs, String field) throws SQLException { return UUID.fromString(rs.getString(field)); }
    private static UUID nullableUuid(ResultSet rs, String field) throws SQLException {
        String value = rs.getString(field); return value == null ? null : UUID.fromString(value);
    }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
