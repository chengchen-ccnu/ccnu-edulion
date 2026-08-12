package com.eduplatform.learning.web;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.service.KnowledgePointService;
import com.eduplatform.learning.service.QuestionImportService;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.service.LegacyQuestionImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.eduplatform.learning.persistence.QuestionRepository;

/** 教师本人题库、知识点只读与导入任务 HTTP 边界；owner 始终取 Gateway 可信头。 */
@RestController
@Validated
@ConditionalOnBean(QuestionRepository.class)
public class TeacherQuestionController {
    private final QuestionService questions; private final KnowledgePointService knowledge; private final QuestionImportService imports;
    private final LegacyQuestionImportService legacyImports;

    public TeacherQuestionController(QuestionService questions, KnowledgePointService knowledge, QuestionImportService imports,
            LegacyQuestionImportService legacyImports) {
        this.questions = questions; this.knowledge = knowledge; this.imports = imports; this.legacyImports = legacyImports;
    }

    @GetMapping("/api/v1/teacher/knowledge-points")
    /** 为教师返回可绑定的启用知识点树；可按年级或父节点缩小范围。 */
    public List<QuestionDtos.KnowledgePointResponse> knowledgePoints(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(required = false) QuestionModels.Grade grade,
            @RequestParam(required = false) String parentId) {
        LearningIdentity.teacher(userId, roles, permissions);
        return knowledge.teacherTree(grade, parentId);
    }

    @GetMapping("/api/v1/teacher/questions")
    /** 分页筛选本人当前题目摘要，响应不包含答案和完整解析。 */
    public QuestionDtos.QuestionPage list(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) QuestionModels.Type type,
            @RequestParam(required = false) QuestionModels.Status status,
            @RequestParam(required = false) QuestionModels.Difficulty difficulty,
            @RequestParam(required = false) String knowledgePointId) {
        var identity = LearningIdentity.teacher(userId, roles, permissions);
        return questions.list(identity.userId(), page, size, keyword, type, status, difficulty, knowledgePointId);
    }

    @PostMapping("/api/v1/teacher/questions")
    /** 幂等创建本人 DRAFT 与版本 1；新建返回 201，安全重放返回 200。 */
    public ResponseEntity<QuestionDtos.QuestionResponse> create(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody QuestionDtos.CreateQuestionRequest request, HttpServletRequest servlet) {
        var identity = LearningIdentity.teacher(userId, roles, permissions);
        var created = questions.create(identity.userId(), request.clientRequestId().toString(), request.version(), trace(servlet));
        return created.created() ? ResponseEntity.created(URI.create("/api/v1/teacher/questions/" + created.response().id())).body(created.response())
                : ResponseEntity.ok(created.response());
    }

    @GetMapping("/api/v1/teacher/questions/{questionId}")
    /** 返回本人题目的完整当前版本；他人题目按 404 隐藏存在性。 */
    public QuestionDtos.QuestionResponse get(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String questionId) {
        return questions.get(LearningIdentity.teacher(userId, roles, permissions).userId(), questionId);
    }

    @PutMapping("/api/v1/teacher/questions/{questionId}")
    /** 校验 expectedRevision 后插入新版本，不覆盖历史版本。 */
    public QuestionDtos.QuestionResponse update(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String questionId, @Valid @RequestBody QuestionDtos.UpdateQuestionRequest request,
            HttpServletRequest servlet) {
        return questions.update(LearningIdentity.teacher(userId, roles, permissions).userId(), questionId,
                request.expectedRevision(), request.version(), trace(servlet));
    }

    @PutMapping("/api/v1/teacher/questions/{questionId}/status")
    /** 将本人题目激活或归档；归档后不允许重新激活或修改。 */
    public QuestionDtos.QuestionResponse status(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String questionId, @Valid @RequestBody QuestionDtos.UpdateStatusRequest request) {
        return questions.status(LearningIdentity.teacher(userId, roles, permissions).userId(), questionId,
                request.expectedRevision(), request.status());
    }

    @GetMapping("/api/v1/teacher/questions/{questionId}/versions")
    /** 分页返回本人题目的不可变版本历史；读取不会移动当前指针。 */
    public QuestionDtos.VersionPage versions(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String questionId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return questions.versions(LearningIdentity.teacher(userId, roles, permissions).userId(), questionId, page, size);
    }

    @GetMapping("/api/v1/teacher/questions/{questionId}/versions/{versionId}")
    /** 返回本人题目的指定历史版本完整结构；他人题目或不归属版本统一 404。 */
    public QuestionDtos.VersionResponse version(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String questionId, @PathVariable String versionId) {
        return questions.version(LearningIdentity.teacher(userId, roles, permissions).userId(), questionId, versionId);
    }

    @GetMapping("/api/v1/teacher/question-imports")
    /** 分页返回本人导入任务状态、进度和计数摘要。 */
    public QuestionDtos.ImportPage imports(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return imports.list(LearningIdentity.teacher(userId, roles, permissions).userId(), page, size);
    }

    @PostMapping("/api/v1/teacher/question-imports")
    /** 校验本人 READY 导入源后创建异步任务；新建返回 202，幂等重放返回 200。 */
    public ResponseEntity<QuestionDtos.ImportTaskResponse> createImport(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody QuestionDtos.CreateImportRequest request, HttpServletRequest servlet) {
        var result = imports.create(LearningIdentity.teacher(userId, roles, permissions).userId(), request, trace(servlet));
        return result.created() ? ResponseEntity.accepted().body(result.response()) : ResponseEntity.ok(result.response());
    }

    @GetMapping("/api/v1/teacher/question-imports/{importTaskId}")
    /** 返回本人任务完整进度和逐行错误，不暴露源文件短期授权。 */
    public QuestionDtos.ImportTaskResponse getImport(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId) {
        return imports.get(LearningIdentity.teacher(userId, roles, permissions).userId(), importTaskId);
    }

    @GetMapping("/api/v1/teacher/question-imports/{importTaskId}/items")
    /** 分页返回本人历史 JSON 逐项结果；CSV/XLSX 或他人任务按不存在处理。 */
    public QuestionDtos.LegacyReviewPage importItems(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) QuestionModels.ImportOutcome outcome) {
        return legacyImports.items(LearningIdentity.teacher(userId, roles, permissions).userId(), importTaskId,
                page, size, outcome);
    }

    @GetMapping("/api/v1/teacher/question-imports/{importTaskId}/items/{itemId}")
    /** 返回可刷新恢复的单项复核投影，不回显原始 JSON、题干或答案。 */
    public QuestionDtos.LegacyReviewItem importItem(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, @PathVariable String itemId) {
        return legacyImports.item(LearningIdentity.teacher(userId, roles, permissions).userId(), importTaskId, itemId);
    }

    @PostMapping("/api/v1/teacher/question-imports/{importTaskId}/items/{itemId}/resolution")
    /** 以 revision 和 clientRequestId 幂等执行结构化修正后导入或确认跳过。 */
    public QuestionDtos.LegacyReviewItem resolveImportItem(@RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, @PathVariable String itemId,
            @Valid @RequestBody QuestionDtos.ResolveLegacyItemRequest request, HttpServletRequest servlet) {
        return legacyImports.resolve(LearningIdentity.teacher(userId, roles, permissions).userId(), importTaskId,
                itemId, request, trace(servlet));
    }

    @PostMapping("/api/v1/teacher/legacy-question-imports")
    /** 创建默认由迁移脚本驱动的受控 JSON 任务；接口自身不遍历目录或自动全量导入。 */
    public ResponseEntity<QuestionDtos.ImportTaskResponse> createLegacyImport(
            @RequestHeader("X-User-Id") String userId, @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody QuestionDtos.CreateLegacyImportRequest request) {
        var result = legacyImports.create(LearningIdentity.teacher(userId, roles, permissions).userId(), request);
        return result.created() ? ResponseEntity.status(201).body(result.response()) : ResponseEntity.ok(result.response());
    }

    @PostMapping("/api/v1/teacher/legacy-question-imports/{importTaskId}/batches")
    /** 同步处理最多 20 项并只返回来源键、计数和问题码；不回显原题内容。 */
    public QuestionDtos.LegacyImportBatchResult submitLegacyBatch(
            @RequestHeader("X-User-Id") String userId, @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, @Valid @RequestBody QuestionDtos.LegacyImportBatchRequest request,
            HttpServletRequest servlet) {
        return legacyImports.submit(LearningIdentity.teacher(userId, roles, permissions).userId(), importTaskId,
                request, trace(servlet));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/teacher/legacy-question-imports/{importTaskId}/created-resources")
    /** 精确清理该任务创建且仍为 DRAFT 的题目，并通过 Outbox 解除媒体引用。 */
    public QuestionDtos.LegacyImportCleanupResult cleanupLegacyImport(
            @RequestHeader("X-User-Id") String userId, @RequestHeader("X-User-Roles") String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, HttpServletRequest servlet) {
        return legacyImports.cleanup(LearningIdentity.teacher(userId, roles, permissions).userId(), importTaskId, trace(servlet));
    }

    private static String trace(HttpServletRequest request) { return (String) request.getAttribute(com.eduplatform.learning.config.TraceFilter.ATTRIBUTE); }
}
