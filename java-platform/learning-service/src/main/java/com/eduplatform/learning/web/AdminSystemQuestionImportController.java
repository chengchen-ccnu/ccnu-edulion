package com.eduplatform.learning.web;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.service.LegacyQuestionImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员历史 JSON 系统题导入边界；所有任务、题目和图片均归当前管理员并冻结为 SYSTEM 作用域。 */
@RestController
@Validated
@ConditionalOnBean(QuestionRepository.class)
public class AdminSystemQuestionImportController {
    private final LegacyQuestionImportService imports;

    public AdminSystemQuestionImportController(LegacyQuestionImportService imports) { this.imports = imports; }

    /** 创建最多 1000 项的 SYSTEM dry-run 或正式任务；接口不接收服务器目录。 */
    @PostMapping("/api/v1/admin/legacy-question-imports")
    public ResponseEntity<QuestionDtos.ImportTaskResponse> create(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody QuestionDtos.CreateLegacyImportRequest request) {
        var identity = LearningIdentity.administrator(userId, roles, permissions);
        var result = imports.create(identity.userId(), QuestionModels.Scope.SYSTEM, request);
        return result.created() ? ResponseEntity.status(201).body(result.response()) : ResponseEntity.ok(result.response());
    }

    /** 顺序处理最多 20 项；正式批次创建 ACTIVE SYSTEM 题和 SYSTEM 图片资产。 */
    @PostMapping("/api/v1/admin/legacy-question-imports/{importTaskId}/batches")
    public QuestionDtos.LegacyImportBatchResult submit(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, @Valid @RequestBody QuestionDtos.LegacyImportBatchRequest request,
            HttpServletRequest servlet) {
        var identity = LearningIdentity.administrator(userId, roles, permissions);
        return imports.submit(identity.userId(), importTaskId, request, trace(servlet));
    }

    /** 返回管理员本人 SYSTEM 历史任务投影，供检查点恢复和安全汇总。 */
    @GetMapping("/api/v1/admin/question-imports/{importTaskId}")
    public QuestionDtos.ImportTaskResponse get(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId) {
        var identity = LearningIdentity.administrator(userId, roles, permissions);
        return imports.get(identity.userId(), importTaskId);
    }

    /** 分页返回无原文的逐项结果；只允许管理员读取本人 SYSTEM 任务。 */
    @GetMapping("/api/v1/admin/question-imports/{importTaskId}/items")
    public QuestionDtos.LegacyReviewPage items(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) QuestionModels.ImportOutcome outcome) {
        var identity = LearningIdentity.administrator(userId, roles, permissions);
        return imports.items(identity.userId(), importTaskId, page, size, outcome);
    }

    /** 精确清理未修改的当前 SYSTEM 任务资源；其他题目与媒体不受影响。 */
    @DeleteMapping("/api/v1/admin/legacy-question-imports/{importTaskId}/created-resources")
    public QuestionDtos.LegacyImportCleanupResult cleanup(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String importTaskId, HttpServletRequest servlet) {
        var identity = LearningIdentity.administrator(userId, roles, permissions);
        return imports.cleanup(identity.userId(), importTaskId, trace(servlet));
    }

    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(com.eduplatform.learning.config.TraceFilter.ATTRIBUTE);
    }
}
