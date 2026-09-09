package com.eduplatform.learning.service;

import com.eduplatform.learning.persistence.KnowledgeGraphRepository;
import com.eduplatform.learning.persistence.KnowledgeGraphRepository.EdgeRow;
import com.eduplatform.learning.persistence.KnowledgeGraphRepository.NodeRow;
import com.eduplatform.learning.persistence.KnowledgeGraphRepository.RelationRow;
import com.eduplatform.learning.persistence.KnowledgeGraphRepository.TaskRow;
import com.eduplatform.learning.web.KnowledgeGraphDtos;
import com.eduplatform.shared.api.FieldError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.eduplatform.learning.messaging.LearningEvents;

/**
 * T10B 图谱导入编排与教师只读查询。
 *
 * <p>节点先完整 staging，再接收显式 prerequisite→dependent 关系。最终批次一次性校验端点、
 * 学科/学段与 DAG；有任何结构或内容冲突时正式任务也不会写业务表。</p>
 */
@Service
@ConditionalOnBean(KnowledgeGraphRepository.class)
public class KnowledgeGraphService {
    private final KnowledgeGraphRepository repository;
    private final ObjectMapper mapper;
    private final LearningEvents events;

    public KnowledgeGraphService(KnowledgeGraphRepository repository, ObjectMapper mapper, LearningEvents events) {
        this.repository = repository; this.mapper = mapper; this.events = events;
    }

    /** 创建幂等任务；输入只有来源命名空间、文件摘要和预期计数。 */
    @Transactional
    public Creation create(String administratorId, KnowledgeGraphDtos.CreateImportRequest request) {
        String fingerprint = hash(request);
        var replay = repository.findByRequest(administratorId, request.clientRequestId().toString());
        if (replay.isPresent()) {
            if (!replay.get().requestFingerprint().equals(fingerprint)) throw conflict("KNOWLEDGE_GRAPH.IDEMPOTENCY_CONFLICT", "clientRequestId 已用于不同任务");
            return new Creation(response(replay.get()), false);
        }
        Instant now = Instant.now();
        TaskRow row = new TaskRow(UUID.randomUUID().toString(), administratorId, request.clientRequestId().toString(),
                fingerprint, request.sourceSystem(), request.dryRun(), request.nodeFileSha256(), request.relationFileSha256(),
                request.expectedNodeCount(), request.expectedRelationCount(), 0, 0,
                KnowledgeGraphDtos.Status.AWAITING_NODES, null, now, now);
        repository.insertTask(row);
        return new Creation(response(row), true);
    }

    public KnowledgeGraphDtos.ImportTask get(String administratorId, String taskId) {
        return response(owned(administratorId, taskId, false));
    }

    /** 节点批次按 sourceKnowId 幂等；同键不同摘要记冲突并在最终校验阻止正式导入。 */
    @Transactional
    public KnowledgeGraphDtos.BatchResult nodes(String administratorId, String taskId,
            KnowledgeGraphDtos.NodeBatchRequest request) {
        return nodes(administratorId, taskId, request, "agent-index-" + taskId);
    }

    /** 带入口 Trace 的节点批次；旧测试/内部调用签名仍委托到稳定安全 Trace。 */
    @Transactional
    public KnowledgeGraphDtos.BatchResult nodes(String administratorId, String taskId,
            KnowledgeGraphDtos.NodeBatchRequest request, String traceId) {
        TaskRow task = owned(administratorId, taskId, true);
        String fingerprint = hash(request);
        var replay = repository.findBatch(taskId, request.batchId().toString());
        if (replay.isPresent()) return replay(task, request.batchId().toString(), "NODE", fingerprint, replay.get());
        if (task.status() != KnowledgeGraphDtos.Status.AWAITING_NODES) throw conflict("KNOWLEDGE_GRAPH.NODE_PHASE_CLOSED", "节点阶段已经结束");
        int inserted = 0; Instant now = Instant.now();
        Set<String> inBatch = new HashSet<>();
        for (var node : request.nodes()) {
            String subject = hash(node.sourceKnowId());
            if (!inBatch.add(node.sourceKnowId())) {
                repository.issue(taskId, "NODE_DUPLICATE", KnowledgeGraphDtos.SubjectKind.NODE, subject, 1, now); continue;
            }
            var existing = repository.findStagedNode(taskId, node.sourceKnowId());
            if (existing.isPresent()) {
                repository.issue(taskId, existing.get().contentSha256().equals(node.contentSha256()) ? "NODE_DUPLICATE" : "NODE_CONTENT_CONFLICT",
                        KnowledgeGraphDtos.SubjectKind.NODE, subject, 1, now); continue;
            }
            repository.insertNode(taskId, node, now); inserted++;
        }
        int projected = task.processedNodeCount() + request.nodes().size();
        if (projected > task.expectedNodeCount()) throw invalid("nodes", "节点数超过任务 expectedNodeCount");
        KnowledgeGraphDtos.Status next = request.finalBatch() ? KnowledgeGraphDtos.Status.AWAITING_RELATIONS : task.status();
        if (request.finalBatch() && projected != task.expectedNodeCount()) throw invalid("nodes", "最终节点批次后的数量与 expectedNodeCount 不一致");
        repository.insertBatch(taskId, request.batchId().toString(), "NODE", fingerprint, request.finalBatch(), request.nodes().size(), now);
        repository.advance(taskId, request.nodes().size(), 0, next, now);
        if (request.finalBatch() && task.expectedRelationCount() == 0) return finish(administratorId, taskId,
                request.batchId().toString(), request.nodes().size(), traceId);
        return new KnowledgeGraphDtos.BatchResult(taskId, request.batchId().toString(), false, request.nodes().size(), next);
    }

    /** 关系端点字段已经是业务方向；接口不接受含义相反的 Neo4j start/end。 */
    @Transactional
    public KnowledgeGraphDtos.BatchResult relations(String administratorId, String taskId,
            KnowledgeGraphDtos.RelationBatchRequest request) {
        return relations(administratorId, taskId, request, "agent-index-" + taskId);
    }

    /** 带入口 Trace 的关系批次；正式落库与 Agent Outbox 保持同一事务。 */
    @Transactional
    public KnowledgeGraphDtos.BatchResult relations(String administratorId, String taskId,
            KnowledgeGraphDtos.RelationBatchRequest request, String traceId) {
        TaskRow task = owned(administratorId, taskId, true);
        String fingerprint = hash(request);
        var replay = repository.findBatch(taskId, request.batchId().toString());
        if (replay.isPresent()) return replay(task, request.batchId().toString(), "RELATION", fingerprint, replay.get());
        if (task.status() != KnowledgeGraphDtos.Status.AWAITING_RELATIONS) throw conflict("KNOWLEDGE_GRAPH.RELATION_PHASE_INVALID", "必须先完成全部节点批次");
        int inserted = 0; Instant now = Instant.now(); Set<String> inBatch = new HashSet<>();
        for (var relation : request.relations()) {
            String key = relation.prerequisiteSourceKnowId() + "\0" + relation.dependentSourceKnowId();
            String subject = hash(key);
            if (!inBatch.add(key) || repository.findStagedRelation(taskId, relation.prerequisiteSourceKnowId(), relation.dependentSourceKnowId()).isPresent()) {
                repository.issue(taskId, "RELATION_DUPLICATE", KnowledgeGraphDtos.SubjectKind.RELATION, subject, 1, now); continue;
            }
            repository.insertRelation(taskId, relation, now); inserted++;
        }
        int projected = task.processedRelationCount() + request.relations().size();
        if (projected > task.expectedRelationCount()) throw invalid("relations", "关系数超过任务 expectedRelationCount");
        if (request.finalBatch() && projected != task.expectedRelationCount()) throw invalid("relations", "最终关系批次后的数量与 expectedRelationCount 不一致");
        repository.insertBatch(taskId, request.batchId().toString(), "RELATION", fingerprint, request.finalBatch(), request.relations().size(), now);
        repository.advance(taskId, 0, request.relations().size(), request.finalBatch() ? KnowledgeGraphDtos.Status.VALIDATING : task.status(), now);
        return request.finalBatch() ? finish(administratorId, taskId, request.batchId().toString(), request.relations().size(), traceId)
                : new KnowledgeGraphDtos.BatchResult(taskId, request.batchId().toString(), false, request.relations().size(), task.status());
    }

    public KnowledgeGraphDtos.ImportIssuePage issues(String administratorId, String taskId, int page, int size) {
        owned(administratorId, taskId, false);
        return repository.issues(taskId, page, size);
    }

    /** 教师搜索始终分页且只返回 enabled 节点。 */
    public KnowledgeGraphDtos.SearchPage search(String stageCode, String typeId, String keyword, int page, int size) {
        return repository.search(stageCode, typeId, keyword, page, size);
    }

    /** 以最多五轮 BFS 构造有限邻域；完整 3,671 节点不会被一次返回。 */
    public KnowledgeGraphDtos.Graph graph(String id, int depth) {
        var center = repository.enabledPoint(id).orElseThrow(KnowledgeGraphService::notFound);
        Set<String> seen = new LinkedHashSet<>(); seen.add(id); Set<String> frontier = Set.of(id);
        Map<String, EdgeRow> edges = new LinkedHashMap<>();
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            List<EdgeRow> rows = repository.adjacentEdges(List.copyOf(frontier)); Set<String> next = new LinkedHashSet<>();
            for (EdgeRow edge : rows) {
                edges.put(edge.prerequisiteId() + "\0" + edge.dependentId(), edge);
                if (seen.add(edge.prerequisiteId())) next.add(edge.prerequisiteId());
                if (seen.add(edge.dependentId())) next.add(edge.dependentId());
            }
            frontier = next;
        }
        List<KnowledgeGraphDtos.SearchItem> nodes = repository.enabledPoints(List.copyOf(seen));
        Map<String, KnowledgeGraphDtos.SearchItem> byId = new HashMap<>(); nodes.forEach(item -> byId.put(item.id(), item));
        List<KnowledgeGraphDtos.SearchItem> prerequisites = edges.values().stream().filter(e -> e.dependentId().equals(id))
                .map(e -> byId.get(e.prerequisiteId())).filter(java.util.Objects::nonNull).toList();
        List<KnowledgeGraphDtos.SearchItem> dependents = edges.values().stream().filter(e -> e.prerequisiteId().equals(id))
                .map(e -> byId.get(e.dependentId())).filter(java.util.Objects::nonNull).toList();
        List<KnowledgeGraphDtos.GraphEdge> projectedEdges = edges.values().stream()
                .filter(e -> byId.containsKey(e.prerequisiteId()) && byId.containsKey(e.dependentId()))
                .map(e -> new KnowledgeGraphDtos.GraphEdge(e.prerequisiteId(), e.dependentId(), "PREREQUISITE")).toList();
        return new KnowledgeGraphDtos.Graph(center, prerequisites, dependents, nodes, projectedEdges, depth);
    }

    private KnowledgeGraphDtos.BatchResult finish(String administratorId, String taskId, String batchId,
            int accepted, String traceId) {
        TaskRow task = owned(administratorId, taskId, true); Instant now = Instant.now();
        List<NodeRow> nodes = repository.nodes(taskId); List<RelationRow> relations = repository.relations(taskId);
        Map<String, NodeRow> bySource = new LinkedHashMap<>(); nodes.forEach(node -> bySource.put(node.sourceKnowId(), node));
        int missing = 0, self = 0, crossDiscipline = 0, crossStage = 0; Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>(); Map<String, Integer> outDegree = new HashMap<>(); Set<String> validEdges = new HashSet<>();
        for (String id : bySource.keySet()) { inDegree.put(id, 0); outDegree.put(id, 0); }
        for (RelationRow relation : relations) {
            NodeRow from = bySource.get(relation.prerequisiteSourceKnowId()); NodeRow to = bySource.get(relation.dependentSourceKnowId());
            String subject = hash(relation.prerequisiteSourceKnowId() + "\0" + relation.dependentSourceKnowId());
            if (from == null || to == null) { missing++; repository.issue(taskId, "ENDPOINT_MISSING", KnowledgeGraphDtos.SubjectKind.RELATION, subject, 1, now); continue; }
            if (from.sourceKnowId().equals(to.sourceKnowId())) { self++; repository.issue(taskId, "SELF_LOOP", KnowledgeGraphDtos.SubjectKind.RELATION, subject, 1, now); continue; }
            if (!from.disciplineCode().equals(to.disciplineCode())) { repository.issue(taskId, "CROSS_DISCIPLINE", KnowledgeGraphDtos.SubjectKind.RELATION, subject, 1, now); crossDiscipline++; continue; }
            if (!from.educationStageCode().equals(to.educationStageCode())) { repository.issue(taskId, "CROSS_STAGE", KnowledgeGraphDtos.SubjectKind.RELATION, subject, 1, now); crossStage++; continue; }
            validEdges.add(relation.prerequisiteSourceKnowId() + "\0" + relation.dependentSourceKnowId());
            outgoing.computeIfAbsent(from.sourceKnowId(), ignored -> new ArrayList<>()).add(to.sourceKnowId());
            outDegree.compute(from.sourceKnowId(), (key, value) -> value + 1); inDegree.compute(to.sourceKnowId(), (key, value) -> value + 1);
        }
        GraphMetrics metrics = metrics(bySource.keySet(), outgoing, inDegree);
        boolean combinedCycle = metrics.cycle() || combinedCycle(task.sourceSystem(), bySource.keySet(), relations);
        if (combinedCycle) repository.issue(taskId, "CYCLE_DETECTED", KnowledgeGraphDtos.SubjectKind.TASK, hash(taskId + ":cycle"), 1, now);
        MappingValidation mapping = validateConfirmedMappings(taskId, nodes, now);
        int unresolvedParents = (int) nodes.stream().map(NodeRow::sourceParentKey).filter(java.util.Objects::nonNull)
                .filter(value -> !value.isBlank() && !bySource.containsKey(value)).distinct().count();
        if (unresolvedParents > 0) repository.issue(taskId, "SOURCE_PARENT_UNRESOLVED", KnowledgeGraphDtos.SubjectKind.TASK, hash(taskId + ":parents"), unresolvedParents, now);
        int addedNodes = 0, reusedNodes = 0, conflictNodes = 0;
        Map<String, String> businessIds = new HashMap<>();
        for (NodeRow node : nodes) {
            var existing = repository.findSourcePoint(task.sourceSystem(), node.sourceKnowId());
            if (existing.isEmpty()) addedNodes++;
            else if (existing.get().contentSha256().equals(node.contentSha256())) { reusedNodes++; businessIds.put(node.sourceKnowId(), existing.get().id()); }
            else { conflictNodes++; repository.issue(taskId, "NODE_CONTENT_CONFLICT", KnowledgeGraphDtos.SubjectKind.NODE, hash(node.sourceKnowId()), 1, now); }
        }
        int addedRelations = 0, reusedRelations = 0;
        for (RelationRow relation : relations) {
            String edgeKey = relation.prerequisiteSourceKnowId() + "\0" + relation.dependentSourceKnowId();
            if (!validEdges.contains(edgeKey)) continue;
            String from = businessIds.get(relation.prerequisiteSourceKnowId()), to = businessIds.get(relation.dependentSourceKnowId());
            if (from != null && to != null && repository.relationExists(from, to)) reusedRelations++; else addedRelations++;
        }
        int duplicateNodes = repository.issueCount(taskId, "NODE_DUPLICATE");
        int duplicateEdges = repository.issueCount(taskId, "RELATION_DUPLICATE");
        int skippedRelations = Math.max(0, task.expectedRelationCount() - addedRelations - reusedRelations);
        int structural = missing + self + crossDiscipline + crossStage + conflictNodes
                + mapping.invalidNodeCount() + (combinedCycle ? 1 : 0);
        int isolated = (int) bySource.keySet().stream().filter(id -> inDegree.getOrDefault(id, 0) == 0 && outDegree.getOrDefault(id, 0) == 0).count();
        Map<String, Integer> stages = new java.util.TreeMap<>(), types = new java.util.TreeMap<>(), names = new HashMap<>();
        nodes.forEach(node -> { stages.merge(node.educationStageCode(), 1, Integer::sum); types.merge(node.knowledgeTypeId(), 1, Integer::sum); names.merge(node.name(), 1, Integer::sum); });
        int duplicateNames = (int) names.values().stream().filter(count -> count > 1).count();
        var summary = new KnowledgeGraphDtos.ImportSummary(task.expectedNodeCount(), task.expectedRelationCount(), stages, types,
                mapping.invalidNodeCount(), duplicateNames, isolated, unresolvedParents,
                addedNodes, reusedNodes, conflictNodes, addedRelations, reusedRelations, skippedRelations,
                missing, duplicateEdges, self, crossDiscipline, crossStage, combinedCycle, inDegree.values().stream().mapToInt(Integer::intValue).max().orElse(0),
                outDegree.values().stream().mapToInt(Integer::intValue).max().orElse(0), metrics.longest());
        if (!task.dryRun() && structural == 0 && duplicateNodes == 0 && duplicateEdges == 0) {
            java.util.Set<String> changed = importBusiness(task, nodes, relations, businessIds, now);
            repository.bumpIndexRevisions(changed, now);
            changed.forEach(id -> repository.indexSourceRevision(id).ifPresent(revision ->
                    repository.insertOutbox(events.knowledgeIndexChanged(id, revision, traceId, now))));
        }
        KnowledgeGraphDtos.Status status = structural + duplicateNodes + duplicateEdges > 0
                ? KnowledgeGraphDtos.Status.COMPLETED_WITH_ISSUES : KnowledgeGraphDtos.Status.COMPLETED;
        repository.complete(taskId, status, summary, now);
        return new KnowledgeGraphDtos.BatchResult(taskId, batchId, false, accepted, status);
    }

    private java.util.Set<String> importBusiness(TaskRow task, List<NodeRow> nodes, List<RelationRow> relations,
            Map<String, String> businessIds, Instant now) {
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        for (NodeRow node : nodes) {
            if (!businessIds.containsKey(node.sourceKnowId())) {
                String id = repository.insertSourcePoint(task.sourceSystem(), node, now);
                businessIds.put(node.sourceKnowId(), id);
                changed.add(id);
            }
        }
        for (RelationRow relation : relations) {
            String from = businessIds.get(relation.prerequisiteSourceKnowId()), to = businessIds.get(relation.dependentSourceKnowId());
            if (!repository.relationExists(from, to)) {
                repository.insertBusinessRelation(from, to, task.sourceSystem(), relation.sourceRelationType(), now);
                changed.add(from); changed.add(to);
            }
        }
        return changed;
    }

    /**
     * 复核负责人确认的来源语义，防止调用方只打开脚本确认开关却提交空映射或错误映射。
     * 输入是当前任务的完整 staging 节点；返回不合规节点数，并以安全聚合问题阻止正式业务写入。
     */
    private MappingValidation validateConfirmedMappings(String taskId, List<NodeRow> nodes, Instant now) {
        Set<String> invalidNodes = new HashSet<>(), conflictingTypes = new HashSet<>();
        Map<String, String> typeNames = new HashMap<>(), typeStages = new HashMap<>();
        int invalidStages = 0, invalidTypeNames = 0, unconfirmedStatuses = 0;
        for (NodeRow node : nodes) {
            if (!confirmedStage(node.educationStageCode(), node.normalizedStage())) {
                invalidNodes.add(node.sourceKnowId()); invalidStages++;
            }
            if (!"知识点".equals(node.knowledgeTypeName()) && !"题型".equals(node.knowledgeTypeName())) {
                invalidNodes.add(node.sourceKnowId()); invalidTypeNames++;
            }
            if (!"0".equals(node.sourceStatus())) {
                invalidNodes.add(node.sourceKnowId()); unconfirmedStatuses++;
            }
            String previousName = typeNames.putIfAbsent(node.knowledgeTypeId(), node.knowledgeTypeName());
            String previousStage = typeStages.putIfAbsent(node.knowledgeTypeId(), node.normalizedStage());
            if ((previousName != null && !previousName.equals(node.knowledgeTypeName()))
                    || (previousStage != null && !previousStage.equals(node.normalizedStage()))) {
                conflictingTypes.add(node.knowledgeTypeId());
            }
        }
        if (!conflictingTypes.isEmpty()) {
            nodes.stream().filter(node -> conflictingTypes.contains(node.knowledgeTypeId()))
                    .map(NodeRow::sourceKnowId).forEach(invalidNodes::add);
        }
        aggregateMappingIssue(taskId, "STAGE_MAPPING_INVALID", invalidStages, now);
        aggregateMappingIssue(taskId, "TYPE_NAME_INVALID", invalidTypeNames, now);
        aggregateMappingIssue(taskId, "SOURCE_STATUS_UNCONFIRMED", unconfirmedStatuses, now);
        aggregateMappingIssue(taskId, "TYPE_MAPPING_CONFLICT", conflictingTypes.size(), now);
        return new MappingValidation(invalidNodes.size());
    }

    /** 来源学段代码只接受本次业务负责人已经确认的三组精确映射。 */
    private static boolean confirmedStage(String source, String normalized) {
        return switch (source) {
            case "02" -> "PRIMARY".equals(normalized);
            case "03" -> "JUNIOR".equals(normalized);
            case "04" -> "SENIOR".equals(normalized);
            default -> false;
        };
    }

    /** 映射问题只保存问题码、数量和任务级指纹，不保存来源 ID 或名称。 */
    private void aggregateMappingIssue(String taskId, String code, int count, Instant now) {
        if (count > 0) repository.issue(taskId, code, KnowledgeGraphDtos.SubjectKind.TASK,
                hash(taskId + ":mapping:" + code), count, now);
    }

    private static GraphMetrics metrics(Set<String> nodes, Map<String, List<String>> outgoing, Map<String, Integer> originalIn) {
        Map<String, Integer> in = new HashMap<>(originalIn); ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String, Integer> longest = new HashMap<>();
        nodes.stream().filter(id -> in.getOrDefault(id, 0) == 0).sorted().forEach(id -> { queue.add(id); longest.put(id, 0); });
        int visited = 0, maximum = 0;
        while (!queue.isEmpty()) {
            String current = queue.remove(); visited++;
            for (String next : outgoing.getOrDefault(current, List.of())) {
                int distance = Math.max(longest.getOrDefault(next, 1), longest.getOrDefault(current, 1) + 1);
                longest.put(next, distance); maximum = Math.max(maximum, distance);
                if (in.compute(next, (key, value) -> value - 1) == 0) queue.add(next);
            }
        }
        return new GraphMetrics(visited != nodes.size(), visited == nodes.size() ? maximum : 0);
    }

    private boolean combinedCycle(String sourceSystem, Set<String> nodeIds, List<RelationRow> incoming) {
        Map<String, List<String>> outgoing = new HashMap<>(); Map<String, Integer> in = new HashMap<>();
        nodeIds.forEach(id -> in.put(id, 0)); Set<String> edges = new HashSet<>();
        List<RelationRow> combined = new ArrayList<>(repository.existingSourceRelations(sourceSystem)); combined.addAll(incoming);
        for (RelationRow relation : combined) {
            if (!nodeIds.contains(relation.prerequisiteSourceKnowId()) || !nodeIds.contains(relation.dependentSourceKnowId())) continue;
            String key = relation.prerequisiteSourceKnowId() + "\0" + relation.dependentSourceKnowId();
            if (!edges.add(key)) continue;
            outgoing.computeIfAbsent(relation.prerequisiteSourceKnowId(), ignored -> new ArrayList<>()).add(relation.dependentSourceKnowId());
            in.compute(relation.dependentSourceKnowId(), (ignored, value) -> value + 1);
        }
        return metrics(nodeIds, outgoing, in).cycle();
    }

    private KnowledgeGraphDtos.BatchResult replay(TaskRow task, String batchId, String kind, String fingerprint,
            KnowledgeGraphRepository.BatchRow batch) {
        if (!batch.kind().equals(kind) || !batch.fingerprint().equals(fingerprint)) throw conflict("KNOWLEDGE_GRAPH.BATCH_CONFLICT", "batchId 已用于不同内容");
        return new KnowledgeGraphDtos.BatchResult(task.id(), batchId, true, batch.acceptedCount(), task.status());
    }
    private TaskRow owned(String administratorId, String taskId, boolean lock) { return repository.findTask(administratorId, taskId, lock).orElseThrow(KnowledgeGraphService::notFound); }
    private static KnowledgeGraphDtos.ImportTask response(TaskRow row) { return new KnowledgeGraphDtos.ImportTask(row.id(), row.sourceSystem(), row.dryRun(), row.status(), row.expectedNodeCount(), row.expectedRelationCount(), row.processedNodeCount(), row.processedRelationCount(), row.summary(), row.createdAt(), row.updatedAt()); }
    private String hash(Object value) { try { return hash(mapper.writeValueAsString(value)); } catch (Exception e) { throw new IllegalArgumentException("请求无法生成稳定指纹", e); } }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static QuestionException notFound() { return new QuestionException(HttpStatus.NOT_FOUND, "KNOWLEDGE_GRAPH.NOT_FOUND", "知识图谱任务或知识点不存在"); }
    private static QuestionException conflict(String code, String message) { return new QuestionException(HttpStatus.CONFLICT, code, message); }
    private static QuestionException invalid(String field, String message) { return new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "KNOWLEDGE_GRAPH.INVALID", message, List.of(new FieldError(field, message))); }

    public record Creation(KnowledgeGraphDtos.ImportTask response, boolean created) {}
    private record GraphMetrics(boolean cycle, int longest) {}
    private record MappingValidation(int invalidNodeCount) {}
}
