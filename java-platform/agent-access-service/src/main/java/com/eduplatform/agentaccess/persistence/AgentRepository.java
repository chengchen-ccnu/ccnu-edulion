package com.eduplatform.agentaccess.persistence;

import com.eduplatform.agentaccess.web.AgentDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * 只访问 Agent Access 自有表的 JDBC 仓储。
 *
 * <p>会话锁和数据库唯一约束共同处理多实例并发；本类不会查询 IAM、课程、题库或媒体 Schema。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class AgentRepository {
    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ConversationRow(UUID id, UUID ownerUserId, UUID createRequestId, String requestFingerprint,
            AgentDtos.EntryPoint entryPoint, String title, AgentDtos.ConversationStatus status, long revision,
            long nextMessageSequence, Instant createdAt, Instant updatedAt, Instant lastMessageAt) {}

    public record MessageRow(UUID id, UUID conversationId, UUID clientRequestId, String requestFingerprint,
            AgentDtos.MessageRole role, String content, AgentDtos.Completeness completeness, String contextJson,
            long sequenceNo, UUID runId, Instant createdAt) {}

    public record RunRow(UUID id, UUID conversationId, UUID userMessageId, UUID assistantMessageId,
            UUID ownerUserId, String traceId, AgentDtos.RunStatus status, Long lastEventSequence,
            Long terminalEventSequence, Instant startedAt, Instant completedAt, Instant createdAt, Instant updatedAt) {
        public boolean terminal() {
            return switch (status) {
                case COMPLETED, DEGRADED, LIMIT_REACHED, CANCELLED, FAILED -> true;
                default -> false;
            };
        }
    }

    public record EventRow(UUID runId, long sequence, String eventType, String eventJson, Instant occurredAt) {}
    public record ResultReportRow(UUID reportId, String json, Instant reportedAt) {}

    public Optional<ConversationRow> findConversationByCreateRequest(UUID owner, UUID requestId) {
        return jdbc.query("SELECT * FROM ai_conversation WHERE owner_user_id=? AND create_request_id=?",
                this::conversation, owner.toString(), requestId.toString()).stream().findFirst();
    }

    public Optional<ConversationRow> findConversation(UUID owner, UUID id) {
        return jdbc.query("SELECT * FROM ai_conversation WHERE owner_user_id=? AND id=?", this::conversation,
                owner.toString(), id.toString()).stream().findFirst();
    }

    public Optional<ConversationRow> lockConversation(UUID owner, UUID id) {
        return jdbc.query("SELECT * FROM ai_conversation WHERE owner_user_id=? AND id=? FOR UPDATE", this::conversation,
                owner.toString(), id.toString()).stream().findFirst();
    }

    public void insertConversation(ConversationRow row) {
        jdbc.update("""
                INSERT INTO ai_conversation(id,owner_user_id,create_request_id,request_fingerprint,entry_point,title,
                    status,revision,next_message_sequence,created_at,updated_at,last_message_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, row.id().toString(), row.ownerUserId().toString(), row.createRequestId().toString(),
                row.requestFingerprint(), row.entryPoint().name(), row.title(), row.status().name(), row.revision(),
                row.nextMessageSequence(), timestamp(row.createdAt()), timestamp(row.updatedAt()),
                timestamp(row.lastMessageAt()));
    }

    public List<ConversationRow> listConversations(UUID owner, int page, int size) {
        return jdbc.query("""
                SELECT * FROM ai_conversation WHERE owner_user_id=?
                ORDER BY last_message_at DESC, id DESC LIMIT ? OFFSET ?
                """, this::conversation, owner.toString(), size, (page - 1) * size);
    }

    public long countConversations(UUID owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE owner_user_id=?",
                Long.class, owner.toString());
    }

    public Optional<RunRow> findActiveRun(UUID conversationId) {
        return jdbc.query("SELECT * FROM ai_run WHERE conversation_id=? AND active_slot=1", this::run,
                conversationId.toString()).stream().findFirst();
    }

    public Optional<MessageRow> findUserMessageByRequest(UUID conversationId, UUID clientRequestId) {
        return jdbc.query("SELECT * FROM ai_message WHERE conversation_id=? AND client_request_id=?", this::message,
                conversationId.toString(), clientRequestId.toString()).stream().findFirst();
    }

    public Optional<RunRow> findRunByUserMessage(UUID messageId) {
        return jdbc.query("SELECT * FROM ai_run WHERE user_message_id=?", this::run,
                messageId.toString()).stream().findFirst();
    }

    public Optional<RunRow> findRun(UUID owner, UUID runId) {
        return jdbc.query("SELECT * FROM ai_run WHERE owner_user_id=? AND id=?", this::run,
                owner.toString(), runId.toString()).stream().findFirst();
    }

    /** 仅供持有服务内执行句柄的协调器按 runId 更新事件；HTTP 查询仍必须使用 owner 版本。 */
    public Optional<RunRow> findRunInternal(UUID runId) {
        return jdbc.query("SELECT * FROM ai_run WHERE id=?", this::run, runId.toString()).stream().findFirst();
    }

    public void insertMessage(MessageRow row) {
        jdbc.update("""
                INSERT INTO ai_message(id,conversation_id,client_request_id,request_fingerprint,role,content,
                    completeness,context_json,sequence_no,run_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, row.id().toString(), row.conversationId().toString(), string(row.clientRequestId()),
                row.requestFingerprint(), row.role().name(), row.content(), row.completeness().name(), row.contextJson(),
                row.sequenceNo(), string(row.runId()), timestamp(row.createdAt()));
    }

    public void insertRun(RunRow row) {
        jdbc.update("""
                INSERT INTO ai_run(id,conversation_id,user_message_id,assistant_message_id,owner_user_id,trace_id,
                    status,active_slot,last_event_sequence,terminal_event_sequence,started_at,completed_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, row.id().toString(), row.conversationId().toString(), row.userMessageId().toString(),
                row.assistantMessageId().toString(), row.ownerUserId().toString(), row.traceId(), row.status().name(),
                row.terminal() ? null : 1, row.lastEventSequence(), row.terminalEventSequence(),
                timestamp(row.startedAt()), timestamp(row.completedAt()), timestamp(row.createdAt()), timestamp(row.updatedAt()));
    }

    public void advanceConversation(UUID conversationId, long nextSequence, Instant at) {
        jdbc.update("UPDATE ai_conversation SET next_message_sequence=?,revision=revision+1,updated_at=?,last_message_at=? WHERE id=?",
                nextSequence, timestamp(at), timestamp(at), conversationId.toString());
    }

    public List<MessageRow> listMessages(UUID conversationId, int page, int size) {
        return jdbc.query("SELECT * FROM ai_message WHERE conversation_id=? ORDER BY sequence_no LIMIT ? OFFSET ?",
                this::message, conversationId.toString(), size, (page - 1) * size);
    }

    /** 按倒序读取当前消息之前的有限窗口；服务层完成字符预算后再恢复为 sequenceNo 升序。 */
    public List<MessageRow> listMessagesBefore(UUID conversationId, long beforeSequence, int size) {
        return jdbc.query("SELECT * FROM ai_message WHERE conversation_id=? AND sequence_no<? "
                        + "ORDER BY sequence_no DESC LIMIT ?", this::message,
                conversationId.toString(), beforeSequence, size);
    }

    public long countMessages(UUID conversationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?",
                Long.class, conversationId.toString());
    }

    public List<EventRow> listEvents(UUID runId, long afterSequence) {
        return jdbc.query("SELECT * FROM ai_run_event WHERE run_id=? AND sequence_no>? ORDER BY sequence_no",
                this::event, runId.toString(), afterSequence);
    }

    /** 连续序号由同一行的条件更新确认；并发或乱序事件不会插入事件表。 */
    public boolean appendEvent(UUID runId, long expectedPrevious, long sequence, String eventType,
            String eventJson, Instant occurredAt, AgentDtos.RunStatus newStatus, Long terminalSequence,
            String assistantContent, AgentDtos.Completeness completeness) {
        int updated = jdbc.update("""
                UPDATE ai_run SET last_event_sequence=?,status=?,terminal_event_sequence=?,active_slot=?,
                    started_at=COALESCE(started_at,?),completed_at=?,updated_at=?
                WHERE id=? AND last_event_sequence=? AND terminal_event_sequence IS NULL
                """, sequence, newStatus.name(), terminalSequence, terminalSequence == null ? 1 : null,
                timestamp(occurredAt), terminalSequence == null ? null : timestamp(occurredAt), timestamp(occurredAt),
                runId.toString(), expectedPrevious);
        if (updated != 1) return false;
        jdbc.update("INSERT INTO ai_run_event(run_id,sequence_no,event_type,event_json,occurred_at) VALUES(?,?,?,?,?)",
                runId.toString(), sequence, eventType, eventJson, timestamp(occurredAt));
        if (assistantContent != null) {
            jdbc.update("UPDATE ai_message SET content=?,completeness=? WHERE id=(SELECT assistant_message_id FROM ai_run WHERE id=?)",
                    assistantContent, completeness.name(), runId.toString());
        }
        return true;
    }

    public void insertCancellation(UUID runId, UUID requestId, String reason, String responseJson, Instant at) {
        jdbc.update("INSERT INTO ai_run_cancellation(run_id,cancel_request_id,reason,response_json,created_at) VALUES(?,?,?,?,?)",
                runId.toString(), requestId.toString(), reason, responseJson, timestamp(at));
    }

    public Optional<String> findCancellation(UUID runId, UUID requestId) {
        return jdbc.query("SELECT response_json FROM ai_run_cancellation WHERE run_id=? AND cancel_request_id=?",
                (rs, ignored) -> rs.getString(1), runId.toString(), requestId.toString()).stream().findFirst();
    }

    public Optional<ResultReportRow> findResultReport(UUID runId) {
        return jdbc.query("SELECT result_report_id,result_report_json,result_reported_at "
                        + "FROM ai_run WHERE id=? AND result_report_id IS NOT NULL",
                (rs, ignored) -> new ResultReportRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                        rs.getTimestamp(3).toInstant()), runId.toString()).stream().findFirst();
    }

    /** 仅在尚无报告时写入；并发重试由服务层读取首个事实后判定重放或冲突。 */
    public boolean insertResultReport(UUID runId, UUID reportId, String json, Instant at) {
        return jdbc.update("UPDATE ai_run SET result_report_id=?,result_report_json=?,result_reported_at=?,updated_at=? "
                        + "WHERE id=? AND result_report_id IS NULL",
                reportId.toString(), json, timestamp(at), timestamp(at), runId.toString()) == 1;
    }

    /** 返回重启后没有进程内执行句柄的活动运行，由服务层补写可恢复的失败终态事件。 */
    public List<RunRow> findOrphanedRuns() {
        return jdbc.query("SELECT * FROM ai_run WHERE active_slot=1 AND terminal_event_sequence IS NULL",
                this::run);
    }

    private ConversationRow conversation(ResultSet rs, int ignored) throws SQLException {
        return new ConversationRow(uuid(rs, "id"), uuid(rs, "owner_user_id"), uuid(rs, "create_request_id"),
                rs.getString("request_fingerprint"), AgentDtos.EntryPoint.valueOf(rs.getString("entry_point")),
                rs.getString("title"), AgentDtos.ConversationStatus.valueOf(rs.getString("status")),
                rs.getLong("revision"), rs.getLong("next_message_sequence"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "last_message_at"));
    }

    private MessageRow message(ResultSet rs, int ignored) throws SQLException {
        return new MessageRow(uuid(rs, "id"), uuid(rs, "conversation_id"), nullableUuid(rs, "client_request_id"),
                rs.getString("request_fingerprint"), AgentDtos.MessageRole.valueOf(rs.getString("role")),
                rs.getString("content"), AgentDtos.Completeness.valueOf(rs.getString("completeness")),
                rs.getString("context_json"), rs.getLong("sequence_no"), nullableUuid(rs, "run_id"),
                instant(rs, "created_at"));
    }

    private RunRow run(ResultSet rs, int ignored) throws SQLException {
        return new RunRow(uuid(rs, "id"), uuid(rs, "conversation_id"), uuid(rs, "user_message_id"),
                uuid(rs, "assistant_message_id"), uuid(rs, "owner_user_id"), rs.getString("trace_id"),
                AgentDtos.RunStatus.valueOf(rs.getString("status")), rs.getLong("last_event_sequence"),
                nullableLong(rs, "terminal_event_sequence"), nullableInstant(rs, "started_at"),
                nullableInstant(rs, "completed_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private EventRow event(ResultSet rs, int ignored) throws SQLException {
        return new EventRow(uuid(rs, "run_id"), rs.getLong("sequence_no"), rs.getString("event_type"),
                rs.getString("event_json"), instant(rs, "occurred_at"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException { return UUID.fromString(rs.getString(column)); }
    private static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column); return value == null ? null : UUID.fromString(value);
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException { return rs.getTimestamp(column).toInstant(); }
    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String string(UUID value) { return value == null ? null : value.toString(); }
}
