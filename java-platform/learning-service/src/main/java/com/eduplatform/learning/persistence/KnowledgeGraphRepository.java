package com.eduplatform.learning.persistence;

import com.eduplatform.learning.web.KnowledgeGraphDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** T10B 图谱持久化边界；所有查询和外键都限定在 learning Schema。 */
@Repository
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true")
public class KnowledgeGraphRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;

    public KnowledgeGraphRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc); this.mapper = mapper;
    }

    /** 读取管理员的幂等任务，不返回文件路径或来源内容。 */
    public Optional<TaskRow> findByRequest(String administratorId, String requestId) {
        return jdbc.query("SELECT * FROM knowledge_graph_import_task WHERE administrator_id=? AND client_request_id=?",
                this::task, administratorId, requestId).stream().findFirst();
    }

    public Optional<TaskRow> findTask(String administratorId, String taskId, boolean lock) {
        return jdbc.query("SELECT * FROM knowledge_graph_import_task WHERE id=? AND administrator_id=?" + (lock ? " FOR UPDATE" : ""),
                this::task, taskId, administratorId).stream().findFirst();
    }

    public void insertTask(TaskRow row) {
        jdbc.update("INSERT INTO knowledge_graph_import_task(id,administrator_id,client_request_id,request_fingerprint,source_system,dry_run,node_file_sha256,relation_file_sha256,expected_node_count,expected_relation_count,processed_node_count,processed_relation_count,status,summary_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.administratorId(), row.clientRequestId(), row.requestFingerprint(), row.sourceSystem(),
                row.dryRun(), row.nodeFileSha256(), row.relationFileSha256(), row.expectedNodeCount(),
                row.expectedRelationCount(), row.processedNodeCount(), row.processedRelationCount(), row.status().name(),
                null, ts(row.createdAt()), ts(row.updatedAt()));
    }

    public Optional<BatchRow> findBatch(String taskId, String batchId) {
        return jdbc.query("SELECT * FROM knowledge_graph_import_batch WHERE import_task_id=? AND batch_id=?",
                this::batch, taskId, batchId).stream().findFirst();
    }

    public void insertBatch(String taskId, String batchId, String kind, String fingerprint,
            boolean finalBatch, int accepted, Instant now) {
        jdbc.update("INSERT INTO knowledge_graph_import_batch(import_task_id,batch_id,batch_kind,request_fingerprint,final_batch,accepted_count,created_at) VALUES(?,?,?,?,?,?,?)",
                taskId, batchId, kind, fingerprint, finalBatch, accepted, ts(now));
    }

    public Optional<NodeRow> findStagedNode(String taskId, String sourceKnowId) {
        return jdbc.query("SELECT * FROM knowledge_graph_import_node WHERE import_task_id=? AND source_know_id=?",
                this::node, taskId, sourceKnowId).stream().findFirst();
    }

    public void insertNode(String taskId, KnowledgeGraphDtos.NodeInput node, Instant now) {
        jdbc.update("INSERT INTO knowledge_graph_import_node(import_task_id,source_know_id,name,discipline_code,education_stage_code,knowledge_type_id,knowledge_type_colour,level_sort_index,node_kind,source_parent_key,source_status,content_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                taskId, node.sourceKnowId(), node.name().trim(), node.disciplineCode(), node.educationStageCode(),
                node.knowledgeTypeId(), node.knowledgeTypeColour(), node.levelSortIndex(), node.nodeKind(),
                node.sourceParentKey(), node.sourceStatus(), node.contentSha256(), ts(now));
    }

    public List<NodeRow> nodes(String taskId) {
        return jdbc.query("SELECT * FROM knowledge_graph_import_node WHERE import_task_id=? ORDER BY source_know_id",
                this::node, taskId);
    }

    public Optional<RelationRow> findStagedRelation(String taskId, String prerequisite, String dependent) {
        return jdbc.query("SELECT * FROM knowledge_graph_import_relation WHERE import_task_id=? AND prerequisite_source_know_id=? AND dependent_source_know_id=?",
                this::relation, taskId, prerequisite, dependent).stream().findFirst();
    }

    public void insertRelation(String taskId, KnowledgeGraphDtos.RelationInput relation, Instant now) {
        jdbc.update("INSERT INTO knowledge_graph_import_relation(import_task_id,prerequisite_source_know_id,dependent_source_know_id,source_relation_type,content_sha256,created_at) VALUES(?,?,?,?,?,?)",
                taskId, relation.prerequisiteSourceKnowId(), relation.dependentSourceKnowId(),
                relation.sourceRelationType(), relation.contentSha256(), ts(now));
    }

    public List<RelationRow> relations(String taskId) {
        return jdbc.query("SELECT * FROM knowledge_graph_import_relation WHERE import_task_id=? ORDER BY prerequisite_source_know_id,dependent_source_know_id",
                this::relation, taskId);
    }

    public void advance(String taskId, int nodes, int relations, KnowledgeGraphDtos.Status status, Instant now) {
        jdbc.update("UPDATE knowledge_graph_import_task SET processed_node_count=processed_node_count+?,processed_relation_count=processed_relation_count+?,status=?,updated_at=? WHERE id=?",
                nodes, relations, status.name(), ts(now), taskId);
    }

    public void complete(String taskId, KnowledgeGraphDtos.Status status,
            KnowledgeGraphDtos.ImportSummary summary, Instant now) {
        jdbc.update("UPDATE knowledge_graph_import_task SET status=?,summary_json=?,updated_at=? WHERE id=?",
                status.name(), json(summary), ts(now), taskId);
    }

    public Optional<SourcePointRow> findSourcePoint(String sourceSystem, String sourceKnowId) {
        return jdbc.query("SELECT * FROM knowledge_point WHERE source_system=? AND source_know_id=?",
                this::sourcePoint, sourceSystem, sourceKnowId).stream().findFirst();
    }

    /** 正式导入节点默认 disabled，且不把来源 parent key 写入任一自外键。 */
    public String insertSourcePoint(String sourceSystem, NodeRow node, Instant now) {
        String id = UUID.randomUUID().toString();
        String code = "KG_" + UUID.nameUUIDFromBytes((sourceSystem + "\0" + node.sourceKnowId())
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at,source_system,source_know_id,discipline_code,education_stage_code,normalized_stage,source_parent_key,taxonomy_parent_id,knowledge_type_id,knowledge_type_colour,level_sort_index,node_kind,source_status,source_content_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, code, node.name(), null, null, null, "MATHEMATICS", node.levelSortIndex(), false, 0,
                ts(now), ts(now), sourceSystem, node.sourceKnowId(), node.disciplineCode(), node.educationStageCode(),
                null, node.sourceParentKey(), null, node.knowledgeTypeId(), node.knowledgeTypeColour(),
                node.levelSortIndex(), node.nodeKind(), node.sourceStatus(), node.contentSha256());
        return id;
    }

    public boolean relationExists(String prerequisiteId, String dependentId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_point_prerequisite WHERE prerequisite_id=? AND dependent_id=? AND relation_type='PREREQUISITE'",
                Integer.class, prerequisiteId, dependentId);
        return count != null && count > 0;
    }

    public void insertBusinessRelation(String prerequisiteId, String dependentId, String sourceSystem,
            String sourceRelationType, Instant now) {
        jdbc.update("INSERT INTO knowledge_point_prerequisite(id,prerequisite_id,dependent_id,relation_type,source_system,source_relation_type,revision,created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), prerequisiteId, dependentId, "PREREQUISITE", sourceSystem,
                sourceRelationType, 0, ts(now));
    }

    /** 返回同一来源已落库关系的来源业务键，用于正式写入前与新图合并做环检测。 */
    public List<RelationRow> existingSourceRelations(String sourceSystem) {
        return jdbc.query("SELECT p.source_know_id prerequisite_source_know_id,d.source_know_id dependent_source_know_id,r.source_relation_type,NULL content_sha256 FROM knowledge_point_prerequisite r JOIN knowledge_point p ON p.id=r.prerequisite_id JOIN knowledge_point d ON d.id=r.dependent_id WHERE p.source_system=? AND d.source_system=?",
                this::relation, sourceSystem, sourceSystem);
    }

    public void issue(String taskId, String code, KnowledgeGraphDtos.SubjectKind kind,
            String subjectFingerprint, int count, Instant now) {
        int updated = jdbc.update("UPDATE knowledge_graph_import_issue SET occurrence_count=occurrence_count+? WHERE import_task_id=? AND issue_code=? AND subject_kind=? AND subject_fingerprint=?",
                count, taskId, code, kind.name(), subjectFingerprint);
        if (updated == 0) jdbc.update("INSERT INTO knowledge_graph_import_issue(id,import_task_id,issue_code,subject_kind,subject_fingerprint,occurrence_count,created_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), taskId, code, kind.name(), subjectFingerprint, count, ts(now));
    }

    public int issueCount(String taskId, String code) {
        Integer count = jdbc.queryForObject("SELECT COALESCE(SUM(occurrence_count),0) FROM knowledge_graph_import_issue WHERE import_task_id=? AND issue_code=?",
                Integer.class, taskId, code);
        return count == null ? 0 : count;
    }

    public KnowledgeGraphDtos.ImportIssuePage issues(String taskId, int page, int size) {
        List<KnowledgeGraphDtos.ImportIssue> items = jdbc.query("SELECT * FROM knowledge_graph_import_issue WHERE import_task_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                (rs, row) -> new KnowledgeGraphDtos.ImportIssue(rs.getString("id"), rs.getString("issue_code"),
                        KnowledgeGraphDtos.SubjectKind.valueOf(rs.getString("subject_kind")),
                        rs.getInt("occurrence_count"), instant(rs, "created_at")), taskId, size, (page - 1) * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_graph_import_issue WHERE import_task_id=?", Long.class, taskId);
        return new KnowledgeGraphDtos.ImportIssuePage(items, page, size, total == null ? 0 : total);
    }

    public KnowledgeGraphDtos.SearchPage search(String normalizedStage, String typeId, String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" FROM knowledge_point WHERE enabled=TRUE AND normalized_stage=:stage");
        Map<String, Object> args = new HashMap<>(); args.put("stage", normalizedStage); args.put("size", size); args.put("offset", (page - 1) * size);
        if (typeId != null && !typeId.isBlank()) { where.append(" AND knowledge_type_id=:type"); args.put("type", typeId); }
        if (keyword != null && !keyword.isBlank()) { where.append(" AND name LIKE :keyword"); args.put("keyword", "%" + keyword.trim() + "%"); }
        List<KnowledgeGraphDtos.SearchItem> items = named.query("SELECT *" + where + " ORDER BY level_sort_index,name,id LIMIT :size OFFSET :offset", args, this::searchItem);
        long total = named.queryForObject("SELECT COUNT(*)" + where, args, Long.class);
        return new KnowledgeGraphDtos.SearchPage(items, page, size, total);
    }

    public Optional<KnowledgeGraphDtos.SearchItem> enabledPoint(String id) {
        return jdbc.query("SELECT * FROM knowledge_point WHERE id=? AND enabled=TRUE", this::searchItem, id).stream().findFirst();
    }

    public List<EdgeRow> adjacentEdges(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return named.query("SELECT prerequisite_id,dependent_id FROM knowledge_point_prerequisite WHERE prerequisite_id IN (:ids) OR dependent_id IN (:ids)",
                Map.of("ids", ids), (rs, row) -> new EdgeRow(rs.getString(1), rs.getString(2)));
    }

    public List<KnowledgeGraphDtos.SearchItem> enabledPoints(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        return named.query("SELECT * FROM knowledge_point WHERE id IN (:ids) AND enabled=TRUE ORDER BY level_sort_index,name,id",
                Map.of("ids", ids), this::searchItem);
    }

    private TaskRow task(ResultSet rs, int row) throws SQLException {
        String summary = rs.getString("summary_json");
        return new TaskRow(rs.getString("id"), rs.getString("administrator_id"), rs.getString("client_request_id"),
                rs.getString("request_fingerprint"), rs.getString("source_system"), rs.getBoolean("dry_run"),
                rs.getString("node_file_sha256"), rs.getString("relation_file_sha256"), rs.getInt("expected_node_count"),
                rs.getInt("expected_relation_count"), rs.getInt("processed_node_count"), rs.getInt("processed_relation_count"),
                KnowledgeGraphDtos.Status.valueOf(rs.getString("status")), summary == null ? null : read(summary),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }
    private BatchRow batch(ResultSet rs, int row) throws SQLException { return new BatchRow(rs.getString("batch_kind"), rs.getString("request_fingerprint"), rs.getBoolean("final_batch"), rs.getInt("accepted_count")); }
    private NodeRow node(ResultSet rs, int row) throws SQLException { return new NodeRow(rs.getString("source_know_id"), rs.getString("name"), rs.getString("discipline_code"), rs.getString("education_stage_code"), rs.getString("knowledge_type_id"), rs.getString("knowledge_type_colour"), rs.getInt("level_sort_index"), rs.getString("node_kind"), rs.getString("source_parent_key"), rs.getString("source_status"), rs.getString("content_sha256")); }
    private RelationRow relation(ResultSet rs, int row) throws SQLException { return new RelationRow(rs.getString("prerequisite_source_know_id"), rs.getString("dependent_source_know_id"), rs.getString("source_relation_type"), rs.getString("content_sha256")); }
    private SourcePointRow sourcePoint(ResultSet rs, int row) throws SQLException { return new SourcePointRow(rs.getString("id"), rs.getString("source_content_sha256"), rs.getString("name"), rs.getString("knowledge_type_id"), rs.getString("education_stage_code"), rs.getString("normalized_stage"), rs.getBoolean("enabled")); }
    private KnowledgeGraphDtos.SearchItem searchItem(ResultSet rs, int row) throws SQLException { return new KnowledgeGraphDtos.SearchItem(rs.getString("id"), rs.getString("name"), rs.getString("normalized_stage"), rs.getString("knowledge_type_id"), rs.getString("knowledge_type_name"), rs.getString("knowledge_type_colour"), rs.getInt("level_sort_index"), rs.getBoolean("enabled")); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Knowledge graph summary serialization failed", e); } }
    private KnowledgeGraphDtos.ImportSummary read(String value) { try { return mapper.readValue(value, KnowledgeGraphDtos.ImportSummary.class); } catch (Exception e) { throw new IllegalStateException("Stored knowledge graph summary is invalid", e); } }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { return rs.getTimestamp(column).toInstant(); }

    public record TaskRow(String id, String administratorId, String clientRequestId, String requestFingerprint,
            String sourceSystem, boolean dryRun, String nodeFileSha256, String relationFileSha256,
            int expectedNodeCount, int expectedRelationCount, int processedNodeCount, int processedRelationCount,
            KnowledgeGraphDtos.Status status, KnowledgeGraphDtos.ImportSummary summary, Instant createdAt, Instant updatedAt) {}
    public record BatchRow(String kind, String fingerprint, boolean finalBatch, int acceptedCount) {}
    public record NodeRow(String sourceKnowId, String name, String disciplineCode, String educationStageCode,
            String knowledgeTypeId, String knowledgeTypeColour, int levelSortIndex, String nodeKind,
            String sourceParentKey, String sourceStatus, String contentSha256) {}
    public record RelationRow(String prerequisiteSourceKnowId, String dependentSourceKnowId,
            String sourceRelationType, String contentSha256) {}
    public record SourcePointRow(String id, String contentSha256, String name, String knowledgeTypeId,
            String educationStageCode, String normalizedStage, boolean enabled) {}
    public record EdgeRow(String prerequisiteId, String dependentId) {}
}
