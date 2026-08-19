package com.eduplatform.learning.persistence;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.domain.QuestionModels.Aggregate;
import com.eduplatform.learning.domain.QuestionModels.ImportTask;
import com.eduplatform.learning.domain.QuestionModels.LegacyBatch;
import com.eduplatform.learning.domain.QuestionModels.LegacySource;
import com.eduplatform.learning.domain.QuestionModels.LegacyReviewItem;
import com.eduplatform.learning.domain.QuestionModels.KnowledgePoint;
import com.eduplatform.learning.domain.QuestionModels.Media;
import com.eduplatform.learning.domain.QuestionModels.Option;
import com.eduplatform.learning.domain.QuestionModels.RowError;
import com.eduplatform.learning.domain.QuestionModels.Version;
import com.eduplatform.learning.importing.LegacyQuestionJsonParser.LegacyTag;
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
 * <p>只访问本服务 Flyway 向前迁移表；题目版本没有更新方法，媒体只保存稳定 ID。动态列表参数均通过
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
        // 旧树接口只服务管理员手工知识树；图谱来源必须走分页搜索，避免一次返回数千个根节点。
        String sql = "SELECT * FROM knowledge_point WHERE source_system IS NULL"
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

    /** 只允许教师路由把根题目作为独立资源访问；组合子聚合只能随父版本投影读取。 */
    public Optional<Aggregate> findRootAggregate(String id, String ownerId) {
        return jdbc.query("SELECT * FROM question_aggregate WHERE id=? AND owner_id=? AND parent_question_id IS NULL",
                this::aggregate, id, ownerId).stream().findFirst();
    }

    /** 插入稳定题目聚合；初始当前版本指针稍后在同一事务设置。 */
    public void insertAggregate(Aggregate value) {
        insertAggregate(value, null);
    }

    /** 插入根题目或指定组合父题下的隐藏子聚合；子聚合不会进入教师题库根列表。 */
    public void insertAggregate(Aggregate value, String parentQuestionId) {
        jdbc.update("INSERT INTO question_aggregate(id,owner_id,status,current_version_id,revision,created_at,updated_at,parent_question_id) VALUES(?,?,?,?,?,?,?,?)",
                value.id(), value.ownerId(), value.status().name(), value.currentVersionId(), value.revision(),
                ts(value.createdAt()), ts(value.updatedAt()), parentQuestionId);
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

    /** 历史导入创建版本后补写 tags 的来源语义；知识点 UUID 与顺序行本身保持不可变。 */
    public void applyLegacyKnowledgeMetadata(String questionId, List<LegacyTag> tags) {
        if (tags.isEmpty()) return;
        String versionId = jdbc.queryForObject("SELECT current_version_id FROM question_aggregate WHERE id=?", String.class, questionId);
        for (LegacyTag tag : tags) {
            jdbc.update("UPDATE question_version_knowledge_point SET priority=?,core_flag=?,exam_point_flag=?,last_exam_point_flag=?,source_tag_sort_index=? WHERE question_version_id=? AND knowledge_point_id=?",
                    tag.priority(), tag.coreFlag(), tag.examPointFlag(), tag.lastExamPointFlag(), tag.sortIndex(),
                    versionId, tag.knowledgePointId());
        }
    }

    /** 读取一个完整教师版本，包括答案、解析、知识点和稳定媒体引用。 */
    public Optional<Version> findVersion(String versionId) {
        return jdbc.query("SELECT * FROM question_version WHERE id=?", (rs, row) -> version(rs), versionId)
                .stream().findFirst();
    }

    /** 按聚合和版本 ID 同时读取，防止借版本 UUID 跨题目查看历史内容。 */
    public Optional<Version> findVersion(String questionId, String versionId) {
        return jdbc.query("SELECT * FROM question_version WHERE id=? AND question_id=?",
                (rs, row) -> version(rs), versionId, questionId).stream().findFirst();
    }

    /** 分页读取不可变版本摘要；查询不会更新聚合当前指针。 */
    public List<VersionSummaryRow> listVersions(String questionId, String currentVersionId, int page, int size) {
        return jdbc.query("SELECT v.id,v.version_number,v.question_type,v.difficulty,v.stem_md,v.created_at,"
                        + "(SELECT COUNT(*) FROM question_version_child c WHERE c.parent_question_version_id=v.id) child_count "
                        + "FROM question_version v WHERE v.question_id=? ORDER BY v.version_number DESC LIMIT ? OFFSET ?",
                (rs, row) -> new VersionSummaryRow(rs.getString("id"), rs.getInt("version_number"),
                        QuestionModels.Type.valueOf(rs.getString("question_type")),
                        QuestionModels.Difficulty.valueOf(rs.getString("difficulty")), rs.getString("stem_md"),
                        rs.getInt("child_count"), instant(rs, "created_at"), currentVersionId.equals(rs.getString("id"))),
                questionId, size, (page - 1) * size);
    }

    /** 返回聚合不可变版本总数。 */
    public long countVersions(String questionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM question_version WHERE question_id=?", Long.class, questionId);
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
        StringBuilder where = new StringBuilder(" FROM question_aggregate q JOIN question_version v ON v.id=q.current_version_id WHERE q.owner_id=:ownerId AND q.parent_question_id IS NULL");
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
        jdbc.update("INSERT INTO question_import_task(id,owner_id,client_request_id,request_fingerprint,source_media_id,source_format,status,progress,total_rows,processed_rows,success_count,review_count,skipped_count,failure_count,source_system,source_root_fingerprint,dry_run,max_items,failure_code,failure_message,retain_until,source_released_at,cleaned_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.ownerId(), value.clientRequestId(), value.requestFingerprint(), value.sourceMediaId(),
                value.sourceFormat().name(), value.status().name(), value.progress(), value.totalRows(), value.processedRows(),
                value.successCount(), value.reviewCount(), value.skippedCount(), value.failureCount(), value.sourceSystem(),
                value.sourceRootFingerprint(), value.dryRun(), value.maxItems(), value.failureCode(), value.failureMessage(),
                ts(value.retainUntil()), value.sourceReleasedAt() == null ? null : ts(value.sourceReleasedAt()),
                value.cleanedAt() == null ? null : ts(value.cleanedAt()), ts(value.createdAt()), ts(value.updatedAt()));
    }

    /** Worker 按任务 ID 查询内部导入事实。 */
    public Optional<ImportTask> findImportTask(String id) {
        return jdbc.query("SELECT * FROM question_import_task WHERE id=?", this::importTask, id).stream().findFirst();
    }

    /** 教师按 owner 查询导入任务，不暴露他人任务。 */
    public Optional<ImportTask> findImportTask(String id, String ownerId) {
        return jdbc.query("SELECT * FROM question_import_task WHERE id=? AND owner_id=?", this::importTask, id, ownerId).stream().findFirst();
    }

    /** 锁定一个导入任务直至当前事务结束，串行化不同 batchId 的上限累计与清理。 */
    public Optional<ImportTask> findImportTaskForUpdate(String id, String ownerId) {
        return jdbc.query("SELECT * FROM question_import_task WHERE id=? AND owner_id=? FOR UPDATE",
                this::importTask, id, ownerId).stream().findFirst();
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
            jdbc.update("INSERT INTO question_import_row_error(id,import_task_id,source_row_number,error_code,error_message,created_at) VALUES(?,?,?,?,?,?)",
                    error.id(), id, error.rowNumber(), error.code(), error.message(), ts(error.createdAt()));
        }
        QuestionModels.ImportStatus status = errors.isEmpty() ? QuestionModels.ImportStatus.COMPLETED
                : (success == 0 ? QuestionModels.ImportStatus.FAILED : QuestionModels.ImportStatus.PARTIAL_SUCCESS);
        jdbc.update("UPDATE question_import_task SET status=?,progress=100,total_rows=?,processed_rows=?,success_count=?,failure_count=?,retain_until=?,updated_at=? WHERE id=?",
                status.name(), total, total, success, errors.size(), ts(retainUntil), ts(now), id);
    }

    /** 保存一个 T09A 批次的无内容计数；主键保证重复 batchId 不能二次累计。 */
    public void insertLegacyBatch(LegacyBatch value) {
        jdbc.update("INSERT INTO question_legacy_import_batch(import_task_id,batch_id,request_fingerprint,final_batch,processed_count,imported_count,valid_count,review_count,skipped_count,failed_count,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                value.importTaskId(), value.batchId(), value.requestFingerprint(), value.finalBatch(), value.processedCount(),
                value.importedCount(), value.validCount(), value.reviewCount(), value.skippedCount(),
                value.failedCount(), value.resultJson(), ts(value.createdAt()));
    }

    /** 按任务/batchId 查询安全重放计数，不读取或保存原始 JSON。 */
    public Optional<LegacyBatch> findLegacyBatch(String taskId, String batchId) {
        return jdbc.query("SELECT * FROM question_legacy_import_batch WHERE import_task_id=? AND batch_id=?",
                (rs, row) -> new LegacyBatch(rs.getString("import_task_id"), rs.getString("batch_id"),
                        rs.getString("request_fingerprint"), rs.getBoolean("final_batch"), rs.getInt("processed_count"),
                        rs.getInt("imported_count"), rs.getInt("valid_count"), rs.getInt("review_count"),
                        rs.getInt("skipped_count"), rs.getInt("failed_count"), rs.getString("result_json"), instant(rs, "created_at")),
                taskId, batchId).stream().findFirst();
    }

    /** 保存一项来源幂等引用和复核摘要；不保存题干、答案、外部 URL 或源路径明文。 */
    public void insertLegacySource(LegacySource value) {
        jdbc.update("INSERT INTO question_legacy_source_ref(id,import_task_id,batch_id,owner_id,source_system,external_id,dry_run,content_sha256,relative_path_sha256,path_edition,json_edition,path_grade,json_grade,original_difficulty,outcome,question_id,issue_codes_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.importTaskId(), value.batchId(), value.ownerId(), value.sourceSystem(),
                value.externalId(), value.dryRun(), value.contentSha256(), value.relativePathSha256(), value.pathEdition(),
                value.jsonEdition(), value.pathGrade(), value.jsonGrade(), value.originalDifficulty(), value.outcome().name(),
                value.questionId(), json(value.issueCodes()), ts(value.createdAt()), ts(value.updatedAt()));
    }

    /** 为每个已处理历史 JSON 项保存可刷新投影；只含来源元数据、结果和问题码。 */
    public void insertLegacyReviewItem(LegacyReviewItem value) {
        jdbc.update("INSERT INTO question_legacy_review_item(id,import_task_id,batch_id,owner_id,source_ref_id,external_id,content_sha256,path_edition,json_edition,path_grade,json_grade,original_difficulty,outcome,question_id,issue_codes_json,revision,resolution_action,resolved_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.importTaskId(), value.batchId(), value.ownerId(), value.sourceRefId(),
                value.externalId(), value.contentSha256(), value.pathEdition(), value.jsonEdition(), value.pathGrade(),
                value.jsonGrade(), value.originalDifficulty(), value.outcome().name(), value.questionId(),
                json(value.issueCodes()), value.revision(), value.resolutionAction() == null ? null : value.resolutionAction().name(),
                value.resolvedAt() == null ? null : ts(value.resolvedAt()), ts(value.createdAt()), ts(value.updatedAt()));
    }

    /** 按 owner/task 分页读取复核项，避免枚举他人任务。 */
    public List<LegacyReviewItem> listLegacyReviewItems(String ownerId, String taskId, int page, int size,
            QuestionModels.ImportOutcome outcome) {
        String filter = outcome == null ? "" : " AND outcome=?";
        String sql = "SELECT * FROM question_legacy_review_item WHERE owner_id=? AND import_task_id=?" + filter
                + " ORDER BY created_at,id LIMIT ? OFFSET ?";
        return outcome == null
                ? jdbc.query(sql, this::legacyReviewItem, ownerId, taskId, size, (page - 1) * size)
                : jdbc.query(sql, this::legacyReviewItem, ownerId, taskId, outcome.name(), size, (page - 1) * size);
    }

    /** 返回符合可选结果筛选的复核项总数。 */
    public long countLegacyReviewItems(String ownerId, String taskId, QuestionModels.ImportOutcome outcome) {
        String sql = "SELECT COUNT(*) FROM question_legacy_review_item WHERE owner_id=? AND import_task_id=?"
                + (outcome == null ? "" : " AND outcome=?");
        return outcome == null ? jdbc.queryForObject(sql, Long.class, ownerId, taskId)
                : jdbc.queryForObject(sql, Long.class, ownerId, taskId, outcome.name());
    }

    /** 精确读取一个 owner/task 下的复核项；其他 owner 一律表现为不存在。 */
    public Optional<LegacyReviewItem> findLegacyReviewItem(String ownerId, String taskId, String itemId) {
        return jdbc.query("SELECT * FROM question_legacy_review_item WHERE id=? AND import_task_id=? AND owner_id=?",
                this::legacyReviewItem, itemId, taskId, ownerId).stream().findFirst();
    }

    /** 锁定待解决项，串行化 revision 校验与终态决定。 */
    public Optional<LegacyReviewItem> findLegacyReviewItemForUpdate(String ownerId, String taskId, String itemId) {
        return jdbc.query("SELECT * FROM question_legacy_review_item WHERE id=? AND import_task_id=? AND owner_id=? FOR UPDATE",
                this::legacyReviewItem, itemId, taskId, ownerId).stream().findFirst();
    }

    /** 查找解决请求幂等记录，结果题目 ID 可为空（确认跳过）。 */
    public Optional<LegacyResolutionRow> findLegacyResolution(String ownerId, String clientRequestId) {
        return jdbc.query("SELECT * FROM question_legacy_item_resolution WHERE owner_id=? AND client_request_id=?",
                (rs, row) -> new LegacyResolutionRow(rs.getString("review_item_id"),
                        rs.getString("request_fingerprint"), rs.getString("action"),
                        rs.getString("resulting_question_id")), ownerId, clientRequestId).stream().findFirst();
    }

    /**
     * 在任务行锁内用当前读复查解决请求；MySQL REPEATABLE READ 下不得沿用事务开始时的普通查询快照。
     */
    public Optional<LegacyResolutionRow> findLegacyResolutionForUpdate(String ownerId, String clientRequestId) {
        return jdbc.query("SELECT * FROM question_legacy_item_resolution WHERE owner_id=? AND client_request_id=? FOR UPDATE",
                (rs, row) -> new LegacyResolutionRow(rs.getString("review_item_id"),
                        rs.getString("request_fingerprint"), rs.getString("action"),
                        rs.getString("resulting_question_id")), ownerId, clientRequestId).stream().findFirst();
    }

    /** 乐观把 NEEDS_REVIEW 项置为人工终态；返回 0 表示 revision 或状态冲突。 */
    public int resolveLegacyReviewItem(String ownerId, String taskId, String itemId, long expectedRevision,
            QuestionModels.ResolutionAction action, QuestionModels.ImportOutcome outcome, String questionId, Instant now) {
        return jdbc.update("UPDATE question_legacy_review_item SET outcome=?,question_id=?,revision=revision+1,resolution_action=?,resolved_at=?,updated_at=? WHERE id=? AND import_task_id=? AND owner_id=? AND revision=? AND outcome=? AND resolution_action IS NULL",
                outcome.name(), questionId, action.name(), ts(now), ts(now), itemId, taskId, ownerId,
                expectedRevision, QuestionModels.ImportOutcome.NEEDS_REVIEW.name());
    }

    /** 同步来源幂等引用的人工终态，使后续详情保留来源信息且同源重放得到一致结果。 */
    public void resolveLegacySource(String ownerId, String sourceRefId, QuestionModels.ImportOutcome outcome,
            String questionId, Instant now) {
        jdbc.update("UPDATE question_legacy_source_ref SET outcome=?,question_id=?,updated_at=? WHERE id=? AND owner_id=?",
                outcome.name(), questionId, ts(now), sourceRefId, ownerId);
    }

    /** 与项状态和题目创建同事务保存幂等决定。 */
    public void insertLegacyResolution(String ownerId, String taskId, String itemId, String clientRequestId,
            String fingerprint, QuestionModels.ResolutionAction action, String questionId, Instant now) {
        jdbc.update("INSERT INTO question_legacy_item_resolution(id,owner_id,import_task_id,review_item_id,client_request_id,request_fingerprint,action,resulting_question_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(), ownerId, taskId, itemId, clientRequestId, fingerprint,
                action.name(), questionId, ts(now));
    }

    /**
     * 仅在最终批次形成的可复核终态中累计人工结果，不改变 processedRows 或任务硬上限。
     * @return 更新行数；0 表示任务不是 COMPLETED/PARTIAL_SUCCESS，调用方必须回滚整次解决事务
     */
    public int applyLegacyResolution(String taskId, QuestionModels.ResolutionAction action, Instant now) {
        int imported = action == QuestionModels.ResolutionAction.IMPORT_CORRECTED ? 1 : 0;
        int skipped = action == QuestionModels.ResolutionAction.CONFIRM_SKIP ? 1 : 0;
        return jdbc.update("UPDATE question_import_task SET status=CASE WHEN review_count<=1 AND failure_count=0 THEN 'COMPLETED' ELSE 'PARTIAL_SUCCESS' END,review_count=GREATEST(0,review_count-1),success_count=success_count+?,skipped_count=skipped_count+?,updated_at=? WHERE id=? AND status IN ('COMPLETED','PARTIAL_SUCCESS')",
                imported, skipped, ts(now), taskId);
    }

    /** 统计已显式解决项，供 CSV/XLSX 与 JSON 共用的任务投影展示。 */
    public int countResolvedLegacyItems(String taskId) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM question_legacy_review_item WHERE import_task_id=? AND resolution_action IS NOT NULL",
                Integer.class, taskId);
        return value == null ? 0 : value;
    }

    /** 用 owner/sourceSystem/externalId 查幂等来源，内容哈希变化由服务层转人工复核。 */
    public Optional<LegacySource> findLegacySource(String ownerId, String sourceSystem, String externalId, boolean dryRun) {
        return jdbc.query("SELECT * FROM question_legacy_source_ref WHERE owner_id=? AND source_system=? AND external_id=? AND dry_run=?",
                this::legacySource, ownerId, sourceSystem, externalId, dryRun).stream().findFirst();
    }

    /** 返回教师详情所需的历史来源元数据；无历史来源的普通 T09 题目返回空。 */
    public Optional<LegacySource> findLegacySourceByQuestion(String questionId) {
        return jdbc.query("SELECT * FROM question_legacy_source_ref WHERE question_id=?",
                this::legacySource, questionId).stream().findFirst();
    }

    /** 返回一个批次的来源结果用于 batchId 安全重放；仅包含来源键、题目 ID 和问题码。 */
    public List<LegacySource> legacySourcesByBatch(String taskId, String batchId) {
        return jdbc.query("SELECT * FROM question_legacy_source_ref WHERE import_task_id=? AND batch_id=? ORDER BY created_at,id",
                this::legacySource, taskId, batchId);
    }

    /** 记录来源内容冲突等无法写入唯一来源行的问题；只保存哈希和问题码。 */
    public void insertLegacyIssue(String taskId, String batchId, String externalId, String contentSha256,
            String issueCode, Instant now) {
        jdbc.update("INSERT INTO question_legacy_import_issue(id,import_task_id,batch_id,external_id,content_sha256,issue_code,created_at) VALUES(?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(), taskId, batchId, externalId, contentSha256, issueCode, ts(now));
    }

    /** 原子累计一个已确认批次；finalBatch 决定任务是否进入终态。 */
    public void applyLegacyBatchProgress(String taskId, LegacyBatch batch, boolean finalBatch, Instant retainUntil, Instant now) {
        jdbc.update("UPDATE question_import_task SET status=CASE WHEN ? THEN CASE WHEN review_count+?=0 AND failure_count+?=0 THEN 'COMPLETED' ELSE 'PARTIAL_SUCCESS' END ELSE 'PROCESSING' END,total_rows=CASE WHEN ? THEN processed_rows+? ELSE total_rows END,processed_rows=processed_rows+?,success_count=success_count+?,review_count=review_count+?,skipped_count=skipped_count+?,failure_count=failure_count+?,progress=CASE WHEN ? THEN 100 ELSE LEAST(99,(processed_rows+?)*100/NULLIF(max_items,0)) END,retain_until=?,updated_at=? WHERE id=?",
                finalBatch, batch.reviewCount(), batch.failedCount(), finalBatch, batch.processedCount(), batch.processedCount(),
                batch.importedCount() + batch.validCount(), batch.reviewCount(), batch.skippedCount(),
                batch.failedCount(), finalBatch, batch.processedCount(), ts(retainUntil), ts(now), taskId);
    }

    /** 将不可变父版本连接到精确子版本；历史读取不再跟随子聚合当前指针漂移。 */
    public void insertVersionChildren(String parentQuestionId, String parentVersionId, List<ChildLink> children) {
        for (int index = 0; index < children.size(); index++) {
            ChildLink child = children.get(index);
            jdbc.update("UPDATE question_aggregate SET parent_question_id=? WHERE id=? AND (parent_question_id IS NULL OR parent_question_id=?)",
                    parentQuestionId, child.questionId(), parentQuestionId);
            jdbc.update("INSERT INTO question_version_child(parent_question_version_id,child_question_id,child_question_version_id,sequence_no) VALUES(?,?,?,?)",
                    parentVersionId, child.questionId(), child.versionId(), index + 1);
        }
    }

    /** 兼容 T09A 导入：把现有子聚合的当前版本冻结到新关系列。 */
    public void insertCurrentVersionChildren(String parentQuestionId, String parentVersionId,
            List<String> childQuestionIds) {
        List<ChildLink> links = childQuestionIds.stream().map(id -> new ChildLink(id,
                jdbc.queryForObject("SELECT current_version_id FROM question_aggregate WHERE id=?", String.class, id))).toList();
        insertVersionChildren(parentQuestionId, parentVersionId, links);
    }

    /** 返回不可变父版本的有序子版本键，完整内容由 findVersion 精确读取。 */
    public List<ChildSnapshotRow> versionChildren(String parentVersionId) {
        return jdbc.query("SELECT child_question_id,child_question_version_id,sequence_no FROM question_version_child WHERE parent_question_version_id=? ORDER BY sequence_no",
                (rs, row) -> new ChildSnapshotRow(rs.getString(1), rs.getString(2), rs.getInt(3)), parentVersionId);
    }

    /** 重试耗尽后写安全失败码与保留期限，不记录异常体或源内容。 */
    public void failImport(String id, String code, String message, Instant retainUntil, Instant now) {
        jdbc.update("UPDATE question_import_task SET status=?,progress=100,failure_code=?,failure_message=?,retain_until=?,updated_at=? WHERE id=?",
                QuestionModels.ImportStatus.FAILED.name(), code, message, ts(retainUntil), ts(now), id);
    }

    /** 按行号返回可展示的脱敏导入错误。 */
    public List<RowError> importErrors(String taskId) {
        return jdbc.query("SELECT * FROM question_import_row_error WHERE import_task_id=? ORDER BY source_row_number,id",
                (rs, row) -> new RowError(rs.getString("id"), rs.getString("import_task_id"), rs.getInt("source_row_number"),
                        rs.getString("error_code"), rs.getString("error_message"), instant(rs, "created_at")), taskId);
    }

    /** 查找已到保留期限且尚未解除源引用的终态任务。 */
    public List<ImportTask> releasableImports(Instant now, int limit) {
        return jdbc.query("SELECT * FROM question_import_task WHERE status IN (?,?,?) AND retain_until<=? AND source_media_id IS NOT NULL AND source_released_at IS NULL ORDER BY retain_until,id LIMIT ?",
                this::importTask, QuestionModels.ImportStatus.COMPLETED.name(), QuestionModels.ImportStatus.PARTIAL_SUCCESS.name(),
                QuestionModels.ImportStatus.FAILED.name(), ts(now), limit);
    }

    /** 幂等标记源引用已解除，返回 1 时调用方才写 Released Outbox。 */
    public int markSourceReleased(String id, Instant now) {
        return jdbc.update("UPDATE question_import_task SET source_released_at=?,updated_at=? WHERE id=? AND source_released_at IS NULL",
                ts(now), ts(now), id);
    }

    /** 返回任务实际创建的题目 ID；dry-run/复核项不会出现在结果中。 */
    public List<String> legacyCreatedQuestionIds(String taskId, String ownerId) {
        return jdbc.query("SELECT question_id FROM question_legacy_source_ref WHERE import_task_id=? AND owner_id=? AND question_id IS NOT NULL UNION SELECT question_id FROM question_legacy_review_item WHERE import_task_id=? AND owner_id=? AND question_id IS NOT NULL",
                (rs, row) -> rs.getString(1), taskId, ownerId, taskId, ownerId);
    }

    /** 返回待清理版本媒体引用，调用方先写 Released Outbox 再删除本域关系。 */
    public List<Media> mediaForQuestions(List<String> questionIds) {
        if (questionIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(questionIds.size(), "?"));
        List<String> all = new ArrayList<>(questionIds);
        all.addAll(jdbc.query("SELECT DISTINCT c.child_question_id FROM question_version_child c JOIN question_version v ON v.id=c.parent_question_version_id WHERE v.question_id IN (" + placeholders + ")",
                (rs, row) -> rs.getString(1), questionIds.toArray()));
        all = all.stream().distinct().toList();
        placeholders = String.join(",", java.util.Collections.nCopies(all.size(), "?"));
        return jdbc.query("SELECT m.* FROM question_version_media m JOIN question_version v ON v.id=m.question_version_id WHERE v.question_id IN (" + placeholders + ") ORDER BY m.id",
                (rs, row) -> new Media(rs.getString("id"), rs.getString("media_id"),
                        QuestionModels.MediaPurpose.valueOf(rs.getString("purpose")), rs.getInt("sequence_no")),
                all.toArray());
    }

    /**
     * 精确删除一个验收任务创建且仍为 DRAFT 的题目图；调用方必须已写媒体解除事件。
     * @return 实际删除的聚合数；其他任务、非 DRAFT 题目和历史源目录均不受影响
     */
    public CleanupCounts deleteLegacyTaskQuestions(String taskId, String ownerId, Instant now) {
        List<String> roots = legacyCreatedQuestionIds(taskId, ownerId);
        if (roots.isEmpty()) {
            jdbc.update("DELETE FROM question_legacy_item_resolution WHERE import_task_id=? AND owner_id=?", taskId, ownerId);
            jdbc.update("DELETE FROM question_legacy_review_item WHERE import_task_id=? AND owner_id=?", taskId, ownerId);
            int refs = jdbc.update("DELETE FROM question_legacy_source_ref WHERE import_task_id=? AND owner_id=?", taskId, ownerId);
            jdbc.update("UPDATE question_import_task SET status='CLEANED',cleaned_at=?,updated_at=? WHERE id=?", ts(now), ts(now), taskId);
            return new CleanupCounts(0, refs);
        }
        String rootMarks = String.join(",", java.util.Collections.nCopies(roots.size(), "?"));
        List<String> all = new ArrayList<>(roots);
        all.addAll(jdbc.query("SELECT DISTINCT c.child_question_id FROM question_version_child c JOIN question_version v ON v.id=c.parent_question_version_id WHERE v.question_id IN (" + rootMarks + ")",
                (rs, row) -> rs.getString(1), roots.toArray()));
        all = all.stream().distinct().toList();
        String marks = String.join(",", java.util.Collections.nCopies(all.size(), "?"));
        Integer unsafe = jdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate WHERE id IN (" + marks + ") AND (owner_id<>? OR status<>'DRAFT')",
                Integer.class, concat(all, ownerId));
        if (unsafe != null && unsafe > 0) throw new IllegalStateException("Legacy cleanup includes non-DRAFT or foreign questions");
        jdbc.update("DELETE FROM question_legacy_item_resolution WHERE import_task_id=? AND owner_id=?", taskId, ownerId);
        jdbc.update("DELETE FROM question_legacy_review_item WHERE import_task_id=? AND owner_id=?", taskId, ownerId);
        int refs = jdbc.update("DELETE FROM question_legacy_source_ref WHERE import_task_id=? AND owner_id=?", taskId, ownerId);
        jdbc.update("DELETE FROM question_version_child WHERE parent_question_version_id IN (SELECT id FROM question_version WHERE question_id IN (" + marks + ")) OR child_question_id IN (" + marks + ")",
                concat(all, all));
        jdbc.update("DELETE FROM question_version_media WHERE question_version_id IN (SELECT id FROM question_version WHERE question_id IN (" + marks + "))", all.toArray());
        jdbc.update("DELETE FROM question_version_knowledge_point WHERE question_version_id IN (SELECT id FROM question_version WHERE question_id IN (" + marks + "))", all.toArray());
        jdbc.update("DELETE FROM question_idempotency_request WHERE resource_type='QUESTION' AND resource_id IN (" + marks + ")", all.toArray());
        jdbc.update("DELETE FROM question_version WHERE question_id IN (" + marks + ")", all.toArray());
        jdbc.update("UPDATE question_aggregate SET parent_question_id=NULL WHERE id IN (" + marks + ")", all.toArray());
        int deleted = jdbc.update("DELETE FROM question_aggregate WHERE id IN (" + marks + ")", all.toArray());
        jdbc.update("UPDATE question_import_task SET status='CLEANED',cleaned_at=?,updated_at=? WHERE id=?", ts(now), ts(now), taskId);
        return new CleanupCounts(deleted, refs);
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
        String stage = rs.getString("stage"), grade = rs.getString("grade"), subject = rs.getString("subject");
        return new KnowledgePoint(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("parent_id"),
                stage == null ? null : QuestionModels.Stage.valueOf(stage), grade == null ? null : QuestionModels.Grade.valueOf(grade),
                subject == null ? null : QuestionModels.Subject.valueOf(subject), rs.getInt("sort_order"), rs.getBoolean("enabled"),
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
                rs.getInt("review_count"), rs.getInt("skipped_count"), rs.getInt("failure_count"),
                rs.getString("source_system"), rs.getString("source_root_fingerprint"), rs.getBoolean("dry_run"),
                nullableInt(rs, "max_items"), rs.getString("failure_code"), rs.getString("failure_message"),
                instant(rs, "retain_until"), nullableInstant(rs, "source_released_at"), nullableInstant(rs, "cleaned_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private LegacySource legacySource(ResultSet rs, int row) throws SQLException {
        return new LegacySource(rs.getString("id"), rs.getString("import_task_id"), rs.getString("batch_id"),
                rs.getString("owner_id"), rs.getString("source_system"), rs.getString("external_id"), rs.getBoolean("dry_run"),
                rs.getString("content_sha256"), rs.getString("relative_path_sha256"), rs.getString("path_edition"),
                rs.getString("json_edition"), rs.getString("path_grade"), rs.getString("json_grade"), rs.getString("original_difficulty"),
                QuestionModels.ImportOutcome.valueOf(rs.getString("outcome")), rs.getString("question_id"),
                List.of(read(rs.getString("issue_codes_json"), String[].class)), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private LegacyReviewItem legacyReviewItem(ResultSet rs, int row) throws SQLException {
        String action = rs.getString("resolution_action");
        return new LegacyReviewItem(rs.getString("id"), rs.getString("import_task_id"), rs.getString("batch_id"),
                rs.getString("owner_id"), rs.getString("source_ref_id"), rs.getString("external_id"),
                rs.getString("content_sha256"), rs.getString("path_edition"), rs.getString("json_edition"),
                rs.getString("path_grade"), rs.getString("json_grade"), rs.getString("original_difficulty"),
                QuestionModels.ImportOutcome.valueOf(rs.getString("outcome")), rs.getString("question_id"),
                List.of(read(rs.getString("issue_codes_json"), String[].class)), rs.getLong("revision"),
                action == null ? null : QuestionModels.ResolutionAction.valueOf(action),
                nullableInstant(rs, "resolved_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
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
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
    private static Object[] concat(List<String> first, Object last) {
        List<Object> values = new ArrayList<>(first); values.add(last); return values.toArray();
    }
    private static Object[] concat(List<String> first, List<String> second) {
        List<Object> values = new ArrayList<>(first); values.addAll(second); return values.toArray();
    }

    public record Idempotency(String fingerprint, String resourceType, String resourceId) {}
    public record SummaryRow(String id, QuestionModels.Status status, long revision, String currentVersionId,
            int versionNumber, QuestionModels.Type type, QuestionModels.Difficulty difficulty, QuestionModels.Grade grade,
            String stem, Instant createdAt, Instant updatedAt, List<String> knowledgePointIds) {}
    public record PagedRows(List<SummaryRow> rows, long total) {}
    public record VersionSummaryRow(String id, int versionNumber, QuestionModels.Type type,
            QuestionModels.Difficulty difficulty, String stem, int childCount, Instant createdAt, boolean current) {}
    public record ChildLink(String questionId, String versionId) {}
    public record ChildSnapshotRow(String questionId, String versionId, int sequence) {}
    public record LegacyResolutionRow(String itemId, String fingerprint, String action, String questionId) {}
    public record CleanupCounts(int questionsDeleted, int sourceReferencesDeleted) {}
    public record Outbox(String eventId, String eventType, int eventVersion, String aggregateId, String exchange,
            String routingKey, String traceId, String payloadJson, String status, int attempts, Instant nextAttemptAt,
            String lastError, Instant occurredAt, Instant publishedAt, Instant updatedAt) {}
}
