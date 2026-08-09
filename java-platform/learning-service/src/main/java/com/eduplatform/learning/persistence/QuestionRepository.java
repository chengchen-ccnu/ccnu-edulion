package com.eduplatform.learning.persistence;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.Aggregate;
import com.eduplatform.learning.domain.QuestionModels.ImportTask;
import com.eduplatform.learning.domain.QuestionModels.KnowledgePoint;
import com.eduplatform.learning.domain.QuestionModels.Media;
import com.eduplatform.learning.domain.QuestionModels.Option;
import com.eduplatform.learning.domain.QuestionModels.RowError;
import com.eduplatform.learning.domain.QuestionModels.Version;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.ObjectMapper;

/**
 * learning-service 题库持久化边界。
 *
 * <p>只访问本服务 Flyway V1 表；题目版本没有更新方法，媒体只保存稳定 ID。动态列表参数均通过
 * 命名参数绑定，避免把筛选值拼入 SQL。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true")
public class QuestionRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;

    public QuestionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.mapper = mapper;
    }

    /** 按启用状态和可选年级读取知识点，结果供服务层组树。 */
    public List<KnowledgePoint> listKnowledgePoints(boolean enabledOnly, QuestionModels.Grade grade) {
        String sql = "SELECT * FROM knowledge_point WHERE 1=1"
                + (enabledOnly ? " AND enabled=TRUE" : "")
                + (grade == null ? "" : " AND grade=?")
                + " ORDER BY parent_id, sort_order, code";
        return grade == null ? jdbc.query(sql, this::knowledge) : jdbc.query(sql, this::knowledge, grade.name());
    }

    /** 按稳定 ID 查询单个知识点，不跨 Schema 读取其他领域。 */
    public Optional<KnowledgePoint> findKnowledgePoint(String id) {
        return jdbc.query("SELECT * FROM knowledge_point WHERE id=?", this::knowledge, id).stream().findFirst();
    }

    /** 批量读取题目绑定的知识点；空输入直接返回空集合。 */
    public List<KnowledgePoint> findKnowledgePoints(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return named.query("SELECT * FROM knowledge_point WHERE id IN (:ids) ORDER BY sort_order, code",
                Map.of("ids", ids), this::knowledge);
    }

    /** 按导入模板的稳定编码批量解析知识点。 */
    public List<KnowledgePoint> findKnowledgePointsByCodes(List<String> codes) {
        if (codes.isEmpty()) return List.of();
        return named.query("SELECT * FROM knowledge_point WHERE code IN (:codes) ORDER BY sort_order,code",
                Map.of("codes", codes), this::knowledge);
    }

    /** 插入管理员维护的知识点；唯一编码和父级约束由数据库再次保证。 */
    public void insertKnowledgePoint(KnowledgePoint value) {
        jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.code(), value.name(), value.parentId(), value.stage().name(), value.grade().name(),
                value.subject().name(), value.sortOrder(), value.enabled(), value.revision(), ts(value.createdAt()), ts(value.updatedAt()));
    }

    /** 以 expectedRevision 乐观更新可变字段，返回受影响行数供冲突判断。 */
    public int updateKnowledgePoint(String id, long expectedRevision, String name, String parentId,
            QuestionModels.Grade grade, int sortOrder, boolean enabled, Instant now) {
        return jdbc.update("UPDATE knowledge_point SET name=?,parent_id=?,grade=?,sort_order=?,enabled=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                name, parentId, grade.name(), sortOrder, enabled, ts(now), id, expectedRevision);
    }

    /** 同时按题目 ID 和 owner 查询聚合，避免泄露他人题目是否存在。 */
    public Optional<Aggregate> findAggregate(String id, String ownerId) {
        return jdbc.query("SELECT * FROM question_aggregate WHERE id=? AND owner_id=?", this::aggregate, id, ownerId)
                .stream().findFirst();
    }

    /** 插入稳定题目聚合；初始当前版本指针稍后在同一事务设置。 */
    public void insertAggregate(Aggregate value) {
        jdbc.update("INSERT INTO question_aggregate(id,owner_id,status,current_version_id,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                value.id(), value.ownerId(), value.status().name(), value.currentVersionId(), value.revision(),
                ts(value.createdAt()), ts(value.updatedAt()));
    }

    /** 只在当前指针为空时绑定首个不可变版本。 */
    public void setInitialVersion(String questionId, String versionId, Instant now) {
        jdbc.update("UPDATE question_aggregate SET current_version_id=?,updated_at=? WHERE id=? AND current_version_id IS NULL",
                versionId, ts(now), questionId);
    }

    /** 乐观移动当前版本指针并递增 revision；归档聚合不会被更新。 */
    public int moveToVersion(String questionId, String ownerId, long expectedRevision, String versionId, Instant now) {
        return jdbc.update("UPDATE question_aggregate SET current_version_id=?,revision=revision+1,updated_at=? WHERE id=? AND owner_id=? AND revision=? AND status<>?",
                versionId, ts(now), questionId, ownerId, expectedRevision, QuestionModels.Status.ARCHIVED.name());
    }

    /** 乐观更新题目生命周期，返回行数供服务层报告并发冲突。 */
    public int updateStatus(String questionId, String ownerId, long expectedRevision, QuestionModels.Status target, Instant now) {
        return jdbc.update("UPDATE question_aggregate SET status=?,revision=revision+1,updated_at=? WHERE id=? AND owner_id=? AND revision=?",
                target.name(), ts(now), questionId, ownerId, expectedRevision);
    }

    /** 计算同一聚合的下一个单调版本号；唯一约束处理并发兜底。 */
    public int nextVersionNumber(String questionId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM question_version WHERE question_id=?",
                Integer.class, questionId);
        return value == null ? 1 : value;
    }

    /** 一次写入不可变版本及其同域知识点/媒体关联；本类不提供历史版本 UPDATE。 */
    public void insertVersion(Version value, String createdBy) {
        jdbc.update("INSERT INTO question_version(id,question_id,version_number,question_type,difficulty,stage,grade,subject,stem_md,options_json,answers_json,analysis_md,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.questionId(), value.versionNumber(), value.type().name(), value.difficulty().name(),
                value.stage().name(), value.grade().name(), value.subject().name(), value.stem(), json(value.options()),
                json(value.answers()), value.analysis(), createdBy, ts(value.createdAt()));
        for (int index = 0; index < value.knowledgePoints().size(); index++) {
            jdbc.update("INSERT INTO question_version_knowledge_point(question_version_id,knowledge_point_id,sequence_no) VALUES(?,?,?)",
                    value.id(), value.knowledgePoints().get(index).id(), index + 1);
        }
        for (Media media : value.media()) {
            jdbc.update("INSERT INTO question_version_media(id,question_version_id,media_id,purpose,sequence_no,created_at) VALUES(?,?,?,?,?,?)",
                    media.id(), value.id(), media.mediaId(), media.purpose().name(), media.sequence(), ts(value.createdAt()));
        }
    }

    /** 读取一个完整教师版本，包括答案、解析、知识点和稳定媒体引用。 */
    public Optional<Version> findVersion(String versionId) {
        return jdbc.query("SELECT * FROM question_version WHERE id=?", (rs, row) -> version(rs), versionId)
                .stream().findFirst();
    }

    /** 查询教师创建题目的幂等指纹与原资源 ID。 */
    public Optional<Idempotency> findIdempotency(String ownerId, String requestId) {
        return jdbc.query("SELECT request_fingerprint,resource_type,resource_id FROM question_idempotency_request WHERE owner_id=? AND client_request_id=?",
                (rs, row) -> new Idempotency(rs.getString(1), rs.getString(2), rs.getString(3)), ownerId, requestId)
                .stream().findFirst();
    }

    /** 与题目创建事务一起保存 clientRequestId 指纹，唯一键阻止并发重复。 */
    public void insertIdempotency(String ownerId, String requestId, String fingerprint, String type,
            String resourceId, Instant now) {
        jdbc.update("INSERT INTO question_idempotency_request(owner_id,client_request_id,request_fingerprint,resource_type,resource_id,created_at) VALUES(?,?,?,?,?,?)",
                ownerId, requestId, fingerprint, type, resourceId, ts(now));
    }

    /** 查询教师当前版本列表；返回行绝不读取 answers_json 或 analysis_md。 */
    public PagedRows listQuestions(String ownerId, int page, int size, String keyword, QuestionModels.Type type,
            QuestionModels.Status status, QuestionModels.Difficulty difficulty, String knowledgePointId) {
        StringBuilder where = new StringBuilder(" FROM question_aggregate q JOIN question_version v ON v.id=q.current_version_id WHERE q.owner_id=:ownerId");
        Map<String, Object> args = new HashMap<>(); args.put("ownerId", ownerId);
        if (keyword != null && !keyword.isBlank()) { where.append(" AND v.stem_md LIKE :keyword"); args.put("keyword", "%" + keyword.trim() + "%"); }
        if (type != null) { where.append(" AND v.question_type=:type"); args.put("type", type.name()); }
        if (status != null) { where.append(" AND q.status=:status"); args.put("status", status.name()); }
        if (difficulty != null) { where.append(" AND v.difficulty=:difficulty"); args.put("difficulty", difficulty.name()); }
        if (knowledgePointId != null) {
            where.append(" AND EXISTS(SELECT 1 FROM question_version_knowledge_point kp WHERE kp.question_version_id=v.id AND kp.knowledge_point_id=:kp)");
            args.put("kp", knowledgePointId);
        }
        long total = named.queryForObject("SELECT COUNT(*)" + where, args, Long.class);
        args.put("limit", size); args.put("offset", (page - 1) * size);
        List<SummaryRow> rows = named.query("SELECT q.id,q.status,q.revision,q.current_version_id,q.created_at,q.updated_at,v.version_number,v.question_type,v.difficulty,v.grade,v.stem_md" + where
                        + " ORDER BY q.updated_at DESC,q.id DESC LIMIT :limit OFFSET :offset", args,
                (rs, row) -> new SummaryRow(rs.getString("id"), QuestionModels.Status.valueOf(rs.getString("status")),
                        rs.getLong("revision"), rs.getString("current_version_id"), rs.getInt("version_number"),
                        QuestionModels.Type.valueOf(rs.getString("question_type")), QuestionModels.Difficulty.valueOf(rs.getString("difficulty")),
                        QuestionModels.Grade.valueOf(rs.getString("grade")), rs.getString("stem_md"),
                        instant(rs, "created_at"), instant(rs, "updated_at"), knowledgeIds(rs.getString("current_version_id"))));
        return new PagedRows(rows, total);
    }

    /** 插入等待异步消费的教师导入任务，不保存源文件地址。 */
    public void insertImportTask(ImportTask value) {
        jdbc.update("INSERT INTO question_import_task(id,owner_id,client_request_id,request_fingerprint,source_media_id,source_format,status,progress,total_rows,processed_rows,success_count,failure_count,failure_code,failure_message,retain_until,source_released_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.ownerId(), value.clientRequestId(), value.requestFingerprint(), value.sourceMediaId(),
                value.sourceFormat().name(), value.status().name(), value.progress(), value.totalRows(), value.processedRows(),
                value.successCount(), value.failureCount(), value.failureCode(), value.failureMessage(), ts(value.retainUntil()),
                value.sourceReleasedAt() == null ? null : ts(value.sourceReleasedAt()), ts(value.createdAt()), ts(value.updatedAt()));
    }

    /** Worker 按任务 ID 查询内部导入事实。 */
    public Optional<ImportTask> findImportTask(String id) {
        return jdbc.query("SELECT * FROM question_import_task WHERE id=?", this::importTask, id).stream().findFirst();
    }

    /** 教师按 owner 查询导入任务，不暴露他人任务。 */
    public Optional<ImportTask> findImportTask(String id, String ownerId) {
        return jdbc.query("SELECT * FROM question_import_task WHERE id=? AND owner_id=?", this::importTask, id, ownerId).stream().findFirst();
    }

    /** 查询导入创建幂等键及原任务。 */
    public Optional<ImportTask> findImportByRequest(String ownerId, String requestId) {
        return jdbc.query("SELECT * FROM question_import_task WHERE owner_id=? AND client_request_id=?", this::importTask,
                ownerId, requestId).stream().findFirst();
    }

    /** 按教师和创建时间倒序分页查询导入任务。 */
    public List<ImportTask> listImports(String ownerId, int page, int size) {
        return jdbc.query("SELECT * FROM question_import_task WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::importTask, ownerId, size, (page - 1) * size);
    }

    /** 返回教师本人导入任务总数，供稳定分页元数据使用。 */
    public long countImports(String ownerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM question_import_task WHERE owner_id=?", Long.class, ownerId);
    }

    /** 将可处理任务置为 PROCESSING 并重置可重试的进度/行错误。 */
    public void startImport(String id, Instant now) {
        jdbc.update("UPDATE question_import_task SET status=?,progress=0,total_rows=0,processed_rows=0,success_count=0,failure_count=0,failure_code=NULL,failure_message=NULL,updated_at=? WHERE id=? AND status IN (?,?)",
                QuestionModels.ImportStatus.PROCESSING.name(), ts(now), id, QuestionModels.ImportStatus.PENDING.name(),
                QuestionModels.ImportStatus.PROCESSING.name());
        jdbc.update("DELETE FROM question_import_row_error WHERE import_task_id=?", id);
    }

    /** 在较长导入处理中提供可轮询的近实时计数；最终完成事务仍会覆盖为权威结果。 */
    public void updateImportProgress(String id, int total, int processed, int success, int failure, Instant now) {
        int progress = total == 0 ? 100 : Math.min(99, processed * 100 / total);
        jdbc.update("UPDATE question_import_task SET progress=?,total_rows=?,processed_rows=?,success_count=?,failure_count=?,updated_at=? WHERE id=? AND status=?",
                progress, total, processed, success, failure, ts(now), id, QuestionModels.ImportStatus.PROCESSING.name());
    }

    /** 原子保存最终计数和行错误，并计算完成、部分成功或失败状态。 */
    public void completeImport(String id, int total, int success, List<RowError> errors, Instant retainUntil, Instant now) {
        for (RowError error : errors) {
            jdbc.update("INSERT INTO question_import_row_error(id,import_task_id,row_number,error_code,error_message,created_at) VALUES(?,?,?,?,?,?)",
                    error.id(), id, error.rowNumber(), error.code(), error.message(), ts(error.createdAt()));
        }
        QuestionModels.ImportStatus status = errors.isEmpty() ? QuestionModels.ImportStatus.COMPLETED
                : (success == 0 ? QuestionModels.ImportStatus.FAILED : QuestionModels.ImportStatus.PARTIAL_SUCCESS);
        jdbc.update("UPDATE question_import_task SET status=?,progress=100,total_rows=?,processed_rows=?,success_count=?,failure_count=?,retain_until=?,updated_at=? WHERE id=?",
                status.name(), total, total, success, errors.size(), ts(retainUntil), ts(now), id);
    }

    /** 重试耗尽后写安全失败码与保留期限，不记录异常体或源内容。 */
    public void failImport(String id, String code, String message, Instant retainUntil, Instant now) {
        jdbc.update("UPDATE question_import_task SET status=?,progress=100,failure_code=?,failure_message=?,retain_until=?,updated_at=? WHERE id=?",
                QuestionModels.ImportStatus.FAILED.name(), code, message, ts(retainUntil), ts(now), id);
    }

    /** 按行号返回可展示的脱敏导入错误。 */
    public List<RowError> importErrors(String taskId) {
        return jdbc.query("SELECT * FROM question_import_row_error WHERE import_task_id=? ORDER BY row_number,id",
                (rs, row) -> new RowError(rs.getString("id"), rs.getString("import_task_id"), rs.getInt("row_number"),
                        rs.getString("error_code"), rs.getString("error_message"), instant(rs, "created_at")), taskId);
    }

    /** 查找已到保留期限且尚未解除源引用的终态任务。 */
    public List<ImportTask> releasableImports(Instant now, int limit) {
        return jdbc.query("SELECT * FROM question_import_task WHERE status IN (?,?,?) AND retain_until<=? AND source_released_at IS NULL ORDER BY retain_until,id LIMIT ?",
                this::importTask, QuestionModels.ImportStatus.COMPLETED.name(), QuestionModels.ImportStatus.PARTIAL_SUCCESS.name(),
                QuestionModels.ImportStatus.FAILED.name(), ts(now), limit);
    }

    /** 幂等标记源引用已解除，返回 1 时调用方才写 Released Outbox。 */
    public int markSourceReleased(String id, Instant now) {
        return jdbc.update("UPDATE question_import_task SET source_released_at=?,updated_at=? WHERE id=? AND source_released_at IS NULL",
                ts(now), ts(now), id);
    }

    /** 在调用方业务事务内写可靠事件，Broker 不可用不丢失业务事实。 */
    public void insertOutbox(Outbox event) {
        jdbc.update("INSERT INTO learning_outbox_event(event_id,event_type,event_version,aggregate_id,exchange_name,routing_key,trace_id,payload_json,status,attempts,next_attempt_at,last_error,occurred_at,published_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                event.eventId(), event.eventType(), event.eventVersion(), event.aggregateId(), event.exchange(), event.routingKey(),
                event.traceId(), event.payloadJson(), event.status(), event.attempts(), ts(event.nextAttemptAt()), event.lastError(),
                ts(event.occurredAt()), event.publishedAt() == null ? null : ts(event.publishedAt()), ts(event.updatedAt()));
    }

    /** 读取达到下次尝试时间的有限批次 PENDING 事件。 */
    public List<Outbox> pendingOutbox(Instant now, int limit) {
        return jdbc.query("SELECT * FROM learning_outbox_event WHERE status='PENDING' AND next_attempt_at<=? ORDER BY occurred_at,event_id LIMIT ?",
                this::outbox, ts(now), limit);
    }

    /** 仅在 mandatory confirm 成功后标记事件已发布。 */
    public void markOutboxPublished(String eventId, Instant now) {
        jdbc.update("UPDATE learning_outbox_event SET status='PUBLISHED',published_at=?,updated_at=? WHERE event_id=?",
                ts(now), ts(now), eventId);
    }

    /** 保存有限退避结果；耗尽时转为 FAILED 供审计/人工处理。 */
    public void markOutboxFailure(String eventId, int attempts, boolean exhausted, Instant next, String error, Instant now) {
        jdbc.update("UPDATE learning_outbox_event SET status=?,attempts=?,next_attempt_at=?,last_error=?,updated_at=? WHERE event_id=?",
                exhausted ? "FAILED" : "PENDING", attempts, ts(next), error, ts(now), eventId);
    }

    /** 判断标准事件 ID 是否已经被本服务持久消费。 */
    public boolean consumed(String eventId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM learning_consumed_event WHERE event_id=?", Integer.class, eventId);
        return count != null && count > 0;
    }

    /** 在最终业务事务中记录 eventId，唯一键提供至少一次投递幂等。 */
    public void markConsumed(String eventId, String eventType, String consumer, Instant now) {
        jdbc.update("INSERT INTO learning_consumed_event(event_id,event_type,consumer_name,consumed_at) VALUES(?,?,?,?)",
                eventId, eventType, consumer, ts(now));
    }

    private Version version(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        return new Version(id, rs.getString("question_id"), rs.getInt("version_number"),
                QuestionModels.Type.valueOf(rs.getString("question_type")),
                QuestionModels.Difficulty.valueOf(rs.getString("difficulty")), QuestionModels.Stage.valueOf(rs.getString("stage")),
                QuestionModels.Grade.valueOf(rs.getString("grade")), QuestionModels.Subject.valueOf(rs.getString("subject")),
                rs.getString("stem_md"), List.of(read(rs.getString("options_json"), Option[].class)),
                List.of(read(rs.getString("answers_json"), String[].class)), rs.getString("analysis_md"),
                versionKnowledge(id), versionMedia(id), instant(rs, "created_at"));
    }

    private List<KnowledgePoint> versionKnowledge(String versionId) {
        return jdbc.query("SELECT k.* FROM question_version_knowledge_point r JOIN knowledge_point k ON k.id=r.knowledge_point_id WHERE r.question_version_id=? ORDER BY r.sequence_no",
                this::knowledge, versionId);
    }

    private List<String> knowledgeIds(String versionId) {
        return jdbc.query("SELECT knowledge_point_id FROM question_version_knowledge_point WHERE question_version_id=? ORDER BY sequence_no",
                (rs, row) -> rs.getString(1), versionId);
    }

    private List<Media> versionMedia(String versionId) {
        return jdbc.query("SELECT * FROM question_version_media WHERE question_version_id=? ORDER BY purpose,sequence_no",
                (rs, row) -> new Media(rs.getString("id"), rs.getString("media_id"),
                        QuestionModels.MediaPurpose.valueOf(rs.getString("purpose")), rs.getInt("sequence_no")), versionId);
    }

    private KnowledgePoint knowledge(ResultSet rs, int row) throws SQLException {
        return new KnowledgePoint(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("parent_id"),
                QuestionModels.Stage.valueOf(rs.getString("stage")), QuestionModels.Grade.valueOf(rs.getString("grade")),
                QuestionModels.Subject.valueOf(rs.getString("subject")), rs.getInt("sort_order"), rs.getBoolean("enabled"),
                rs.getLong("revision"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private Aggregate aggregate(ResultSet rs, int row) throws SQLException {
        return new Aggregate(rs.getString("id"), rs.getString("owner_id"), QuestionModels.Status.valueOf(rs.getString("status")),
                rs.getString("current_version_id"), rs.getLong("revision"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private ImportTask importTask(ResultSet rs, int row) throws SQLException {
        return new ImportTask(rs.getString("id"), rs.getString("owner_id"), rs.getString("client_request_id"),
                rs.getString("request_fingerprint"), rs.getString("source_media_id"),
                QuestionModels.ImportFormat.valueOf(rs.getString("source_format")), QuestionModels.ImportStatus.valueOf(rs.getString("status")),
                rs.getInt("progress"), rs.getInt("total_rows"), rs.getInt("processed_rows"), rs.getInt("success_count"),
                rs.getInt("failure_count"), rs.getString("failure_code"), rs.getString("failure_message"),
                instant(rs, "retain_until"), nullableInstant(rs, "source_released_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private Outbox outbox(ResultSet rs, int row) throws SQLException {
        return new Outbox(rs.getString("event_id"), rs.getString("event_type"), rs.getInt("event_version"),
                rs.getString("aggregate_id"), rs.getString("exchange_name"), rs.getString("routing_key"), rs.getString("trace_id"),
                rs.getString("payload_json"), rs.getString("status"), rs.getInt("attempts"), instant(rs, "next_attempt_at"),
                rs.getString("last_error"), instant(rs, "occurred_at"), nullableInstant(rs, "published_at"), instant(rs, "updated_at"));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("JSON serialization failed", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); } catch (Exception exception) { throw new IllegalStateException("Stored question JSON is invalid", exception); }
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { return rs.getTimestamp(column).toInstant(); }
    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    public record Idempotency(String fingerprint, String resourceType, String resourceId) {}
    public record SummaryRow(String id, QuestionModels.Status status, long revision, String currentVersionId,
            int versionNumber, QuestionModels.Type type, QuestionModels.Difficulty difficulty, QuestionModels.Grade grade,
            String stem, Instant createdAt, Instant updatedAt, List<String> knowledgePointIds) {}
    public record PagedRows(List<SummaryRow> rows, long total) {}
    public record Outbox(String eventId, String eventType, int eventVersion, String aggregateId, String exchange,
            String routingKey, String traceId, String payloadJson, String status, int attempts, Instant nextAttemptAt,
            String lastError, Instant occurredAt, Instant publishedAt, Instant updatedAt) {}
}
