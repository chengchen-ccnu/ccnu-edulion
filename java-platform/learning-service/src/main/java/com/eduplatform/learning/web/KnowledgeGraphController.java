package com.eduplatform.learning.web;

import com.eduplatform.learning.service.KnowledgeGraphService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** T10B 管理员图谱迁移和教师有限只读查询 HTTP 边界；所有调用都由 Gateway 注入可信身份。 */
@RestController
@Validated
@ConditionalOnBean(KnowledgeGraphService.class)
public class KnowledgeGraphController {
    private final KnowledgeGraphService service;
    public KnowledgeGraphController(KnowledgeGraphService service) { this.service = service; }

    @PostMapping("/api/v1/admin/knowledge-graph-imports")
    /** 创建管理员迁移任务；角色名不能替代 administration:access。 */
    public ResponseEntity<KnowledgeGraphDtos.ImportTask> create(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody KnowledgeGraphDtos.CreateImportRequest request) {
        var identity = LearningIdentity.administrator(userId, roles, permissions);
        var created = service.create(identity.userId(), request);
        return created.created() ? ResponseEntity.created(URI.create("/api/v1/admin/knowledge-graph-imports/" + created.response().id())).body(created.response())
                : ResponseEntity.ok(created.response());
    }

    @PostMapping("/api/v1/admin/knowledge-graph-imports/{taskId}/node-batches")
    public KnowledgeGraphDtos.BatchResult nodes(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String taskId, @Valid @RequestBody KnowledgeGraphDtos.NodeBatchRequest request) {
        return service.nodes(LearningIdentity.administrator(userId, roles, permissions).userId(), taskId, request);
    }

    @PostMapping("/api/v1/admin/knowledge-graph-imports/{taskId}/relation-batches")
    public KnowledgeGraphDtos.BatchResult relations(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String taskId, @Valid @RequestBody KnowledgeGraphDtos.RelationBatchRequest request) {
        return service.relations(LearningIdentity.administrator(userId, roles, permissions).userId(), taskId, request);
    }

    @GetMapping("/api/v1/admin/knowledge-graph-imports/{taskId}")
    public KnowledgeGraphDtos.ImportTask task(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String taskId) {
        return service.get(LearningIdentity.administrator(userId, roles, permissions).userId(), taskId);
    }

    @GetMapping("/api/v1/admin/knowledge-graph-imports/{taskId}/issues")
    public KnowledgeGraphDtos.ImportIssuePage issues(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String taskId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.issues(LearningIdentity.administrator(userId, roles, permissions).userId(), taskId, page, size);
    }

    @GetMapping("/api/v1/teacher/knowledge-points/search")
    public KnowledgeGraphDtos.SearchPage search(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam String normalizedStage,
            @RequestParam(required = false) String knowledgeTypeId, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        LearningIdentity.teacher(userId, roles, permissions);
        if (!"PRIMARY".equals(normalizedStage) && !"JUNIOR".equals(normalizedStage)) {
            throw new com.eduplatform.learning.service.QuestionException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "KNOWLEDGE_GRAPH.STAGE_UNSUPPORTED", "题目编辑器只支持小学和初中学段");
        }
        return service.search(normalizedStage, knowledgeTypeId, keyword, page, size);
    }

    @GetMapping("/api/v1/teacher/knowledge-points/{knowledgePointId}/graph")
    public KnowledgeGraphDtos.Graph graph(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String knowledgePointId, @RequestParam(defaultValue = "1") @Min(1) @Max(5) int depth) {
        LearningIdentity.teacher(userId, roles, permissions);
        return service.graph(knowledgePointId, depth);
    }
}
