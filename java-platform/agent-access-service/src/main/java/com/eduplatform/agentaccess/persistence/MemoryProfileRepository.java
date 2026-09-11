package com.eduplatform.agentaccess.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 持久化长期记忆、证据、偏好、用量、安全审计和索引 Outbox。
 *
 * <p>输入只能是服务层校验后的安全短文本和稳定 ID；本仓储只访问 Agent Access 自有 Schema，
 * 不读取 Python 私有状态，也不跨服务或跨 Schema 查询授权事实。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class MemoryProfileRepository {
    private final JdbcTemplate jdbc;

    public MemoryProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingReport(UUID runId, UUID conversationId, UUID ownerUserId, UUID reportId,
            String reportJson, String traceId) {}
    public record MemoryRow(UUID id, UUID ownerUserId, UUID conversationId, String memoryType,
            String safeText, String status, long sourceRevision, String confidenceLevel,
            String accessScope, UUID courseId, String courseVersionId, UUID lessonId,
            String decayPolicy, Instant expiresAt, Instant lastVerifiedAt, Instant updatedAt) {}
    public record ProjectionPage(List<MemoryRow> items, boolean hasMore) {}
    public record OutboxRow(UUID eventId, String eventType, UUID aggregateId, String exchange,
            String routingKey, long sourceRevision, String traceId, String payloadJson, int attempts) {}
    public record PreferenceRow(UUID id, String kind, String value, String source, long revision) {}
    public record PreferenceCommandRow(String fingerprint, String value, long revision) {}

    /** 锁定一份待归一化报告，避免多个实例重复计量或产生同 revision 事件。 */
    public Optional<PendingReport> lockPendingReport(UUID runId) {
        return jdbc.query("""
                SELECT id,conversation_id,owner_user_id,result_report_id,result_report_json,trace_id
                FROM ai_run WHERE id=? AND result_report_id IS NOT NULL
                  AND result_normalization_status<>'NORMALIZED' FOR UPDATE
                """, this::pendingReport, runId.toString()).stream().findFirst();
    }

    /** 返回少量待重试报告；原始私有报告保留在 ai_run，不会因解析失败丢失。 */
    public List<UUID> pendingReportIds(int limit) {
        return jdbc.query("""
                SELECT id FROM ai_run WHERE result_report_id IS NOT NULL
                  AND result_normalization_status IN ('PENDING','FAILED')
                  AND result_normalization_attempts<10 ORDER BY result_reported_at,id LIMIT ?
                """, (rs, ignored) -> UUID.fromString(rs.getString(1)), limit);
    }

    public boolean usageExists(UUID runId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record WHERE run_id=?",
                Integer.class, runId.toString());
        return count != null && count > 0;
    }

    /** run_id/report_id 唯一约束是计量幂等事实，不依赖 MQ 或进程内锁。 */
    public void insertUsage(UUID runId, UUID reportId, UUID ownerUserId, String model, int modelCalls,
            int visionCalls, int toolAttempts, long promptTokens, long completionTokens,
            String phaseJson, String terminationReason, Instant now) {
        jdbc.update("""
                INSERT INTO ai_usage_record(id,run_id,report_id,owner_user_id,model_name,model_calls,
                    vision_calls,tool_attempts,prompt_tokens,completion_tokens,phase_durations_json,
                    termination_reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), runId.toString(), reportId.toString(), ownerUserId.toString(),
                model, modelCalls, visionCalls, toolAttempts, promptTokens, completionTokens, phaseJson,
                terminationReason, timestamp(now));
    }

    public void upsertSummary(UUID conversationId, UUID ownerUserId, long startSequence, long endSequence,
            String text, Instant now) {
        jdbc.queryForObject("SELECT id FROM ai_conversation WHERE id=? FOR UPDATE",
                String.class, conversationId.toString());
        Long revision = jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM ai_conversation_summary WHERE conversation_id=?",
                Long.class, conversationId.toString());
        jdbc.update("""
                INSERT INTO ai_conversation_summary(id,conversation_id,owner_user_id,start_sequence,end_sequence,
                    summary_text,source_type,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), conversationId.toString(), ownerUserId.toString(),
                startSequence, endSequence, text, "RUN_REPORT", revision, timestamp(now), timestamp(now));
    }

    public long[] runMessageRange(UUID runId) {
        return jdbc.queryForObject("""
                SELECT u.sequence_no,a.sequence_no FROM ai_run r
                JOIN ai_message u ON u.id=r.user_message_id JOIN ai_message a ON a.id=r.assistant_message_id
                WHERE r.id=?
                """, (rs, ignored) -> new long[] {rs.getLong(1), rs.getLong(2)}, runId.toString());
    }

    public Optional<MemoryRow> lockMemory(UUID owner, String type, String text) {
        return jdbc.query("SELECT * FROM ai_memory WHERE owner_user_id=? AND memory_type=? AND safe_text=? FOR UPDATE",
                this::memory, owner.toString(), type, text).stream().findFirst();
    }

    public MemoryRow insertMemory(MemoryRow row, Instant now) {
        long revision = nextMemoryRevision();
        jdbc.update("""
                INSERT INTO ai_memory(id,owner_user_id,conversation_id,memory_type,safe_text,status,
                    source_revision,confidence_level,access_scope,course_id,course_version_id,lesson_id,
                    decay_policy,expires_at,last_verified_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, row.id().toString(), row.ownerUserId().toString(), string(row.conversationId()),
                row.memoryType(), row.safeText(), row.status(), revision, row.confidenceLevel(),
                row.accessScope(), string(row.courseId()), row.courseVersionId(), string(row.lessonId()),
                row.decayPolicy(), timestamp(row.expiresAt()), timestamp(row.lastVerifiedAt()),
                timestamp(now), timestamp(now));
        return withRevision(row, revision, row.status(), row.confidenceLevel(), row.expiresAt(),
                row.lastVerifiedAt(), now);
    }

    /** 新的独立证据只提升一次 revision；系统检索本身不会续期或增强可信度。 */
    public MemoryRow reviseMemory(MemoryRow old, String confidence, Instant expiresAt, Instant verifiedAt,
            Instant now) {
        long revision = nextMemoryRevision();
        jdbc.update("""
                UPDATE ai_memory SET status='ACTIVE',source_revision=?,confidence_level=?,expires_at=?,
                    last_verified_at=?,updated_at=? WHERE id=?
                """, revision, confidence, timestamp(expiresAt), timestamp(verifiedAt), timestamp(now),
                old.id().toString());
        return new MemoryRow(old.id(), old.ownerUserId(), old.conversationId(), old.memoryType(), old.safeText(),
                "ACTIVE", revision, confidence, old.accessScope(), old.courseId(), old.courseVersionId(),
                old.lessonId(), old.decayPolicy(), expiresAt, verifiedAt, now);
    }

    public boolean insertEvidence(UUID memoryId, UUID owner, String sourceType, UUID sourceId,
            long sourceRevision, String strength, String relation, Instant now) {
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_memory_evidence WHERE memory_id=? AND source_type=?
                    AND source_id=? AND source_revision=?
                """, Integer.class, memoryId.toString(), sourceType, sourceId.toString(), sourceRevision);
        if (existing != null && existing > 0) return false;
        return jdbc.update("""
                INSERT INTO ai_memory_evidence(id,memory_id,owner_user_id,source_type,source_id,source_revision,
                    evidence_strength,relation_type,effective,created_at)
                VALUES(?,?,?,?,?,?,?,?,1,?)
                """, UUID.randomUUID().toString(), memoryId.toString(), owner.toString(), sourceType,
                sourceId.toString(), sourceRevision, strength, relation, timestamp(now)) == 1;
    }

    /** 新 revision 修正同一正式来源时，仅撤销旧证据的当前效力，历史行继续保留。 */
    public void supersedeEvidence(UUID owner, String sourceType, UUID sourceId, long revision) {
        jdbc.update("""
                UPDATE ai_memory_evidence SET effective=0 WHERE owner_user_id=? AND source_type=?
                    AND source_id=? AND source_revision<? AND effective=1
                """, owner.toString(), sourceType, sourceId.toString(), revision);
    }

    /** 返回同一正式来源已接受的最新 revision，用于拒绝乱序旧事实回滚画像。 */
    public Long latestEvidenceRevision(UUID owner, String sourceType, UUID sourceId) {
        return jdbc.queryForObject("""
                SELECT MAX(source_revision) FROM ai_memory_evidence
                WHERE owner_user_id=? AND source_type=? AND source_id=?
                """, Long.class, owner.toString(), sourceType, sourceId.toString());
    }

    /** 正式作答/批改才可更新少量主题等级；历史掌握位一旦出现不会因时间自动抹去。 */
    public void upsertTopic(UUID owner, String topic, String level, String confidence, long evidenceRevision,
            boolean mastered, Instant now) {
        int changed = jdbc.update("""
                UPDATE ai_profile_topic SET current_level=?,confidence_level=?,latest_evidence_revision=?,
                    last_verified_at=?,historical_mastery=CASE WHEN historical_mastery=1 OR ? THEN 1 ELSE 0 END,
                    revision=revision+1,updated_at=?
                WHERE owner_user_id=? AND topic_key=? AND latest_evidence_revision<?
                """, level, confidence, evidenceRevision, timestamp(now), mastered, timestamp(now),
                owner.toString(), topic, evidenceRevision);
        if (changed == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM ai_profile_topic WHERE owner_user_id=? AND topic_key=?",
                    Integer.class, owner.toString(), topic);
            if (exists != null && exists == 0) {
                jdbc.update("""
                        INSERT INTO ai_profile_topic(id,owner_user_id,topic_key,current_level,confidence_level,
                            latest_evidence_revision,last_verified_at,historical_mastery,revision,created_at,updated_at)
                        VALUES(?,?,?,?,?,?,?, ?,1,?,?)
                        """, UUID.randomUUID().toString(), owner.toString(), topic, level, confidence,
                        evidenceRevision, timestamp(now), mastered, timestamp(now), timestamp(now));
            }
        }
    }

    /** 明确偏好无到期时间；推断偏好必须携带 expiresAt，后续同类偏好按 revision 修正。 */
    public void upsertPreference(UUID owner, String kind, String value, String source,
            Instant expiresAt, Instant now) {
        int changed = jdbc.update("""
                UPDATE ai_preference SET preference_value=?,source_type=?,status='ACTIVE',revision=revision+1,
                    expires_at=?,updated_at=? WHERE owner_user_id=? AND preference_kind=?
                """, value, source, timestamp(expiresAt), timestamp(now), owner.toString(), kind);
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO ai_preference(id,owner_user_id,preference_kind,preference_value,source_type,status,
                        revision,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',1,?,?,?)
                    """, UUID.randomUUID().toString(), owner.toString(), kind, value, source,
                    timestamp(expiresAt), timestamp(now), timestamp(now));
        }
    }

    /** 只降低当前可信度；historical_mastery 和证据表保持不变。 */
    public int decayStaleTopicConfidence(Instant verifiedBefore, Instant now) {
        return jdbc.update("""
                UPDATE ai_profile_topic SET confidence_level='LOW',revision=revision+1,updated_at=?
                WHERE last_verified_at<? AND confidence_level<>'LOW'
                """, timestamp(now), timestamp(verifiedBefore));
    }

    public void insertSafety(UUID runId, UUID owner, String type, String reason, Instant now) {
        jdbc.update("INSERT INTO ai_safety_event(id,run_id,owner_user_id,event_type,safe_reason,created_at) VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(), runId.toString(), owner.toString(), type, reason, timestamp(now));
    }

    public void markNormalized(UUID runId, Instant now) {
        jdbc.update("""
                UPDATE ai_run SET result_normalization_status='NORMALIZED',result_normalization_attempts=result_normalization_attempts+1,
                    result_normalization_error=NULL,result_normalized_at=?,updated_at=? WHERE id=?
                """, timestamp(now), timestamp(now), runId.toString());
    }

    public void markNormalizationFailed(UUID runId, String error, Instant now) {
        jdbc.update("""
                UPDATE ai_run SET result_normalization_status='FAILED',result_normalization_attempts=result_normalization_attempts+1,
                    result_normalization_error=?,updated_at=? WHERE id=?
                """, error, timestamp(now), runId.toString());
    }

    public void insertOutbox(OutboxRow row, Instant now) {
        jdbc.update("""
                INSERT INTO ai_agent_index_outbox(event_id,event_type,aggregate_id,exchange_name,routing_key,
                    source_revision,trace_id,payload_json,status,attempts,next_attempt_at,last_error,occurred_at,published_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,'PENDING',0,?,NULL,?,NULL,?)
                """, row.eventId().toString(), row.eventType(), row.aggregateId().toString(), row.exchange(),
                row.routingKey(), row.sourceRevision(), row.traceId(), row.payloadJson(), timestamp(now),
                timestamp(now), timestamp(now));
    }

    public ProjectionPage page(long afterRevision, String afterId, UUID entityId, int size) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)";
        args.add(Timestamp.from(Instant.now()));
        if (entityId != null) {
            where += " AND id=?";
            args.add(entityId.toString());
        } else {
            where += " AND (source_revision>? OR (source_revision=? AND id>?))";
            args.add(afterRevision); args.add(afterRevision); args.add(afterId);
        }
        args.add(size + 1);
        List<MemoryRow> rows = jdbc.query("SELECT * FROM ai_memory" + where
                + " ORDER BY source_revision,id LIMIT ?", this::memory, args.toArray());
        boolean more = rows.size() > size;
        return new ProjectionPage(List.copyOf(more ? rows.subList(0, size) : rows), more);
    }

    public List<MemoryRow> expired(int limit, Instant now) {
        return jdbc.query("SELECT * FROM ai_memory WHERE status='ACTIVE' AND expires_at<=? ORDER BY expires_at,id LIMIT ?",
                this::memory, timestamp(now), limit);
    }

    public List<MemoryRow> activeForOwner(UUID owner, int limit, Instant now) {
        return jdbc.query("""
                SELECT * FROM ai_memory WHERE owner_user_id=? AND status='ACTIVE'
                    AND (expires_at IS NULL OR expires_at>?) ORDER BY updated_at DESC,id LIMIT ?
                """, this::memory, owner.toString(), timestamp(now), limit);
    }

    public List<MemoryRow> activeOfType(UUID owner, String type, Instant now) {
        return jdbc.query("""
                SELECT * FROM ai_memory WHERE owner_user_id=? AND memory_type=? AND status='ACTIVE'
                    AND (expires_at IS NULL OR expires_at>?) ORDER BY updated_at DESC,id
                """, this::memory, owner.toString(), type, timestamp(now));
    }

    public List<PreferenceRow> activePreferences(UUID owner, int limit, Instant now) {
        return jdbc.query("""
                SELECT id,preference_kind,preference_value,source_type,revision FROM ai_preference
                WHERE owner_user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)
                ORDER BY CASE source_type WHEN 'EXPLICIT' THEN 0 ELSE 1 END,updated_at DESC,id LIMIT ?
                """, (rs, ignored) -> new PreferenceRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5)), owner.toString(), timestamp(now), limit);
    }

    /**
     * 使用既有 revision 单例行串行化短偏好命令，封住“首次设置尚无 owner 行”时的并发空隙。
     * 该锁只覆盖一次本地事务，不改变 revision，也不创建新的偏好事实源。
     */
    public void lockPreferenceCommandLane() {
        jdbc.queryForObject("SELECT current_revision FROM ai_memory_revision_sequence WHERE singleton_id=1 FOR UPDATE",
                Long.class);
    }

    /** 锁定讲解偏好本体；不存在时由后续 INSERT 的唯一约束裁决首次并发写。 */
    public Optional<PreferenceRow> lockExplanationPreference(UUID owner) {
        return jdbc.query("SELECT id,preference_kind,preference_value,source_type,revision FROM ai_preference "
                        + "WHERE owner_user_id=? AND preference_kind='EXPLANATION_STYLE' FOR UPDATE",
                (rs, ignored) -> new PreferenceRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5)), owner.toString()).stream().findFirst();
    }

    /** 查询本人讲解偏好；调用方负责只公开 EXPLICIT 值。 */
    public Optional<PreferenceRow> findExplanationPreference(UUID owner) {
        return jdbc.query("SELECT id,preference_kind,preference_value,source_type,revision FROM ai_preference "
                        + "WHERE owner_user_id=? AND preference_kind='EXPLANATION_STYLE'",
                (rs, ignored) -> new PreferenceRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getLong(5)), owner.toString()).stream().findFirst();
    }

    /**
     * 把同一偏好本体原子改为用户明确值。推断值对公开 revision 视为 0，用户可直接覆盖；
     * 已有明确值必须精确匹配 expectedRevision，否则返回 -1 且不写入。
     */
    public long saveExplicitExplanationPreference(UUID owner, String value, long expectedRevision, Instant now) {
        PreferenceRow current = lockExplanationPreference(owner).orElse(null);
        long publicRevision = current != null && "EXPLICIT".equals(current.source()) ? current.revision() : 0;
        if (publicRevision != expectedRevision) return -1;
        if (current == null) {
            jdbc.update("INSERT INTO ai_preference(id,owner_user_id,preference_kind,preference_value,source_type,"
                            + "status,revision,expires_at,created_at,updated_at) "
                            + "VALUES(?,?,'EXPLANATION_STYLE',?,'EXPLICIT','ACTIVE',1,NULL,?,?)",
                    UUID.randomUUID().toString(), owner.toString(), value, timestamp(now), timestamp(now));
            return 1;
        }
        long next = current.revision() + 1;
        jdbc.update("UPDATE ai_preference SET preference_value=?,source_type='EXPLICIT',status='ACTIVE',"
                        + "revision=?,expires_at=NULL,updated_at=? WHERE id=? AND revision=?",
                value, next, timestamp(now), current.id().toString(), current.revision());
        return next;
    }

    /** 读取公开偏好命令的历史结果；该记录不包含推断偏好或画像。 */
    public Optional<PreferenceCommandRow> findPreferenceCommand(UUID owner, UUID clientRequestId) {
        return jdbc.query("SELECT request_fingerprint,preference_value,result_revision FROM ai_preference_command "
                        + "WHERE owner_user_id=? AND client_request_id=?",
                (rs, ignored) -> new PreferenceCommandRow(rs.getString(1), rs.getString(2), rs.getLong(3)),
                owner.toString(), clientRequestId.toString()).stream().findFirst();
    }

    public void insertPreferenceCommand(UUID owner, UUID clientRequestId, String fingerprint,
            String value, long revision, Instant now) {
        jdbc.update("INSERT INTO ai_preference_command(owner_user_id,client_request_id,request_fingerprint,"
                        + "preference_value,result_revision,created_at) VALUES(?,?,?,?,?,?)",
                owner.toString(), clientRequestId.toString(), fingerprint, value, revision, timestamp(now));
    }

    public MemoryRow expire(MemoryRow row, Instant now) {
        long revision = nextMemoryRevision();
        int changed = jdbc.update("UPDATE ai_memory SET status='EXPIRED',source_revision=?,updated_at=? WHERE id=? AND status='ACTIVE'",
                revision, timestamp(now), row.id().toString());
        if (changed == 0) return null;
        return new MemoryRow(row.id(), row.ownerUserId(), row.conversationId(), row.memoryType(), row.safeText(),
                "EXPIRED", revision, row.confidenceLevel(), row.accessScope(), row.courseId(), row.courseVersionId(),
                row.lessonId(), row.decayPolicy(), row.expiresAt(), row.lastVerifiedAt(), now);
    }

    public List<OutboxRow> pendingOutbox(Instant now, int limit) {
        return jdbc.query("SELECT * FROM ai_agent_index_outbox WHERE status='PENDING' AND next_attempt_at<=? ORDER BY occurred_at,event_id LIMIT ?",
                this::outbox, timestamp(now), limit);
    }

    public void markOutboxPublished(UUID eventId, Instant now) {
        jdbc.update("UPDATE ai_agent_index_outbox SET status='PUBLISHED',published_at=?,updated_at=? WHERE event_id=?",
                timestamp(now), timestamp(now), eventId.toString());
    }

    public void markOutboxFailure(UUID eventId, int attempts, boolean failed, Instant next, String error, Instant now) {
        jdbc.update("UPDATE ai_agent_index_outbox SET status=?,attempts=?,next_attempt_at=?,last_error=?,updated_at=? WHERE event_id=?",
                failed ? "FAILED" : "PENDING", attempts, timestamp(next), error, timestamp(now), eventId.toString());
    }

    private PendingReport pendingReport(ResultSet rs, int ignored) throws SQLException {
        return new PendingReport(uuid(rs, "id"), uuid(rs, "conversation_id"), uuid(rs, "owner_user_id"),
                uuid(rs, "result_report_id"), rs.getString("result_report_json"), rs.getString("trace_id"));
    }

    private MemoryRow memory(ResultSet rs, int ignored) throws SQLException {
        return new MemoryRow(uuid(rs, "id"), uuid(rs, "owner_user_id"), nullableUuid(rs, "conversation_id"),
                rs.getString("memory_type"), rs.getString("safe_text"), rs.getString("status"),
                rs.getLong("source_revision"), rs.getString("confidence_level"), rs.getString("access_scope"),
                nullableUuid(rs, "course_id"), rs.getString("course_version_id"), nullableUuid(rs, "lesson_id"),
                rs.getString("decay_policy"), nullableInstant(rs, "expires_at"),
                nullableInstant(rs, "last_verified_at"), rs.getTimestamp("updated_at").toInstant());
    }

    private OutboxRow outbox(ResultSet rs, int ignored) throws SQLException {
        return new OutboxRow(uuid(rs, "event_id"), rs.getString("event_type"), uuid(rs, "aggregate_id"),
                rs.getString("exchange_name"), rs.getString("routing_key"), rs.getLong("source_revision"),
                rs.getString("trace_id"), rs.getString("payload_json"), rs.getInt("attempts"));
    }

    private static UUID uuid(ResultSet rs, String name) throws SQLException { return UUID.fromString(rs.getString(name)); }
    private static UUID nullableUuid(ResultSet rs, String name) throws SQLException {
        String value = rs.getString(name); return value == null ? null : UUID.fromString(value);
    }
    private static Instant nullableInstant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String string(UUID value) { return value == null ? null : value.toString(); }

    /** 利用单例计数行的事务锁分配全局单调 revision，使增量事件与分页重建共用一套游标。 */
    private long nextMemoryRevision() {
        jdbc.update("UPDATE ai_memory_revision_sequence SET current_revision=current_revision+1 WHERE singleton_id=1");
        Long value = jdbc.queryForObject(
                "SELECT current_revision FROM ai_memory_revision_sequence WHERE singleton_id=1", Long.class);
        if (value == null || value < 1) throw new IllegalStateException("memory revision allocation failed");
        return value;
    }

    private static MemoryRow withRevision(MemoryRow old, long revision, String status, String confidence,
            Instant expiresAt, Instant verifiedAt, Instant now) {
        return new MemoryRow(old.id(), old.ownerUserId(), old.conversationId(), old.memoryType(), old.safeText(),
                status, revision, confidence, old.accessScope(), old.courseId(), old.courseVersionId(),
                old.lessonId(), old.decayPolicy(), expiresAt, verifiedAt, now);
    }
}
