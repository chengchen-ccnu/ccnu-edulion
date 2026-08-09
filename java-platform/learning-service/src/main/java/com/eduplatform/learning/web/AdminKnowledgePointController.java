package com.eduplatform.learning.web;

import com.eduplatform.learning.service.KnowledgePointService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.eduplatform.learning.persistence.QuestionRepository;

/** 管理员知识点维护端点；角色名称不足以授权，必须同时具备 administration:access。 */
@RestController
@RequestMapping("/api/v1/admin/knowledge-points")
@ConditionalOnBean(QuestionRepository.class)
public class AdminKnowledgePointController {
    private final KnowledgePointService service;
    public AdminKnowledgePointController(KnowledgePointService service) { this.service = service; }

    @PostMapping
    /** 校验 administration:access 后创建节点，返回 201 与稳定知识点 ID。 */
    public ResponseEntity<QuestionDtos.KnowledgePointResponse> create(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody QuestionDtos.CreateKnowledgePointRequest request) {
        LearningIdentity.administrator(userId, roles, permissions);
        return ResponseEntity.status(201).body(service.create(request));
    }

    @PutMapping("/{knowledgePointId}")
    /** 以请求 revision 乐观修改节点；父级错配、环或并发冲突返回统一错误信封。 */
    public QuestionDtos.KnowledgePointResponse update(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String knowledgePointId, @Valid @RequestBody QuestionDtos.UpdateKnowledgePointRequest request) {
        LearningIdentity.administrator(userId, roles, permissions);
        return service.update(knowledgePointId, request);
    }
}
