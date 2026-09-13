package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.SkillExecutionRepository;
import com.eduplatform.agentaccess.persistence.SkillExecutionRepository.Row;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理 Python Runtime 的 Skill 执行审计。开始失败会让 Python fail closed；终态更新不接收正文或输入输出。
 */
@Service
public class SkillExecutionService {
    private final AgentRepository runs;
    private final SkillExecutionRepository executions;
    private final ObjectMapper mapper;

    public SkillExecutionService(AgentRepository runs, SkillExecutionRepository executions, ObjectMapper mapper) {
        this.runs = runs;
        this.executions = executions;
        this.mapper = mapper;
    }

    /** 登记已选择 Skill。输入只含稳定身份和选择来源，输出说明首次创建或严格幂等重放。 */
    @Transactional
    public AgentInternalDtos.SkillExecutionResponse start(UUID runId,
            AgentInternalDtos.StartSkillExecutionRequest request) {
        var run = runs.findRunInternal(runId).orElseThrow(() ->
                new AgentException(HttpStatus.NOT_FOUND, "AGENT.NOT_FOUND", "运行不存在"));
        if (run.terminal()) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.RUN_TERMINAL", "运行已结束，不能选择 Skill");
        }
        Row proposed = new Row(request.executionId(), runId, request.sequence(), request.skillName(),
                request.skillVersion(), request.selectionSource(), "RUNNING", Instant.now(), null, null,
                "[]", null, null);
        try {
            executions.insert(proposed);
            return new AgentInternalDtos.SkillExecutionResponse(request.executionId(), "RUNNING", false);
        } catch (DuplicateKeyException conflict) {
            Row existing = executions.find(request.executionId()).orElseThrow(() ->
                    new AgentException(HttpStatus.CONFLICT, "AGENT.SKILL_EXECUTION_CONFLICT", "Skill 执行身份冲突"));
            if (!sameStart(existing, proposed)) {
                throw new AgentException(HttpStatus.CONFLICT, "AGENT.SKILL_EXECUTION_CONFLICT", "Skill 执行身份冲突");
            }
            return new AgentInternalDtos.SkillExecutionResponse(existing.executionId(), existing.status(), true);
        }
    }

    /** 把同一 execution 更新为完成、失败、取消或超时；相同终态重放无副作用。 */
    @Transactional
    public AgentInternalDtos.SkillExecutionResponse finish(UUID runId, UUID executionId,
            AgentInternalDtos.FinishSkillExecutionRequest request) {
        if (("SUCCEEDED".equals(request.status()) && request.failureCode() != null)
                || (!"SUCCEEDED".equals(request.status()) && request.failureCode() == null)) {
            throw new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT.SKILL_AUDIT_INVALID",
                    "Skill 失败终态必须包含安全失败码，成功终态不得包含失败码");
        }
        Row existing = executions.find(executionId).orElseThrow(() ->
                new AgentException(HttpStatus.NOT_FOUND, "AGENT.SKILL_EXECUTION_NOT_FOUND", "Skill 执行不存在"));
        if (!existing.runId().equals(runId)) {
            throw new AgentException(HttpStatus.NOT_FOUND, "AGENT.SKILL_EXECUTION_NOT_FOUND", "Skill 执行不存在");
        }
        String references;
        try {
            references = mapper.writeValueAsString(request.referencePaths());
        } catch (Exception failure) {
            throw new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT.SKILL_AUDIT_INVALID", "Skill 审计无法保存");
        }
        if (!"RUNNING".equals(existing.status())) {
            if (sameFinish(existing, request, references)) {
                return new AgentInternalDtos.SkillExecutionResponse(executionId, existing.status(), true);
            }
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.SKILL_EXECUTION_TERMINAL", "Skill 执行已终止");
        }
        Instant now = Instant.now();
        long duration = Math.max(0, Duration.between(existing.startedAt(), now).toMillis());
        boolean updated = executions.finish(executionId, runId, request.status(), now, duration, references,
                request.script(), request.failureCode());
        if (!updated) {
            Row raced = executions.find(executionId).orElseThrow(() ->
                    new AgentException(HttpStatus.NOT_FOUND, "AGENT.SKILL_EXECUTION_NOT_FOUND", "Skill 执行不存在"));
            if (sameFinish(raced, request, references)) {
                return new AgentInternalDtos.SkillExecutionResponse(executionId, raced.status(), true);
            }
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.SKILL_EXECUTION_TERMINAL", "Skill 执行已终止");
        }
        return new AgentInternalDtos.SkillExecutionResponse(executionId, request.status(), false);
    }

    private static boolean sameStart(Row left, Row right) {
        return left.runId().equals(right.runId()) && left.sequence() == right.sequence()
                && left.skillName().equals(right.skillName()) && left.skillVersion().equals(right.skillVersion())
                && left.selectionSource().equals(right.selectionSource());
    }

    private static boolean sameFinish(Row row, AgentInternalDtos.FinishSkillExecutionRequest request,
            String references) {
        return row.status().equals(request.status()) && row.referencePathsJson().equals(references)
                && java.util.Objects.equals(row.scriptPath(), request.script())
                && java.util.Objects.equals(row.failureCode(), request.failureCode());
    }
}
