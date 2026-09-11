package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.AgentRepository.ResultReportRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 处理 Python 私有回调。所有领域读取先按 runId 从 ai_run 取可信 owner，绝不接受请求中的 userId。
 * Course/Learning 超时或异常会原样安全失败，不会扩大为公开或整课检索。
 */
@Service
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class AgentInternalService {
    private final AgentRepository repository;
    private final AgentDomainClient domains;
    private final ObjectMapper mapper;
    private final MemoryFactService memoryFacts;
    private final UserHelpKnowledgeBase userHelp;

    public AgentInternalService(AgentRepository repository, AgentDomainClient domains, ObjectMapper mapper,
            MemoryFactService memoryFacts, UserHelpKnowledgeBase userHelp) {
        this.repository = repository;
        this.domains = domains;
        this.mapper = mapper;
        this.memoryFacts = memoryFacts;
        this.userHelp = userHelp;
    }

    public AgentInternalDtos.VerifyCourseCandidatesResponse verify(UUID runId,
            AgentInternalDtos.VerifyCourseCandidatesRequest request, String traceId) {
        RunRow run = requireRun(runId);
        Set<UUID> allowed = domains.verifyCourseCandidates(run.ownerUserId(), request.candidates(), traceId);
        return new AgentInternalDtos.VerifyCourseCandidatesResponse(request.candidates().stream()
                .map(AgentInternalDtos.CourseCandidate::entityId).filter(allowed::contains).toList());
    }

    public AgentInternalDtos.StudentFactsResponse facts(UUID runId,
            AgentInternalDtos.StudentFactsRequest request, String traceId) {
        RunRow run = requireRun(runId);
        List<AgentInternalDtos.SafeFact> facts = domains.readStudentFacts(run.ownerUserId(), request, traceId);
        return new AgentInternalDtos.StudentFactsResponse(facts);
    }

    /**
     * 按真实 run 读取当前帮助版本。活动 run 的 owner 只从 ai_run 取得；请求无法选择用户、版本或路由。
     */
    public AgentInternalDtos.UserHelpSearchResponse userHelp(UUID runId,
            AgentInternalDtos.UserHelpSearchRequest request) {
        RunRow run = requireRun(runId);
        if (run.terminal()) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.RUN_TERMINAL", "运行已结束，不能继续调用工具");
        }
        // 读取 owner 是授权边界的一部分；当前只读帮助不需要把 owner 传给知识库，也不会回显该身份。
        if (run.ownerUserId() == null) {
            throw new AgentException(HttpStatus.NOT_FOUND, "AGENT.NOT_FOUND", "运行不存在");
        }
        var result = userHelp.search(request.query(), request.limit());
        return new AgentInternalDtos.UserHelpSearchResponse(result.version(), result.evidence().stream()
                .map(item -> new AgentInternalDtos.UserHelpEvidence(item.evidenceId(), item.title(), item.summary(),
                        item.version(), item.directAnswer(), item.appRoute()))
                .toList());
    }

    /**
     * 终态后保存一次有界私有报告。同 reportId/同内容为幂等重放；任一字段变化返回 409，
     * 从而不会把不同运行结果静默覆盖。首次保存与 T14.2.2 事实归一化同事务提交。
     */
    @Transactional
    public AgentInternalDtos.RunResultReportResponse report(UUID runId,
            AgentInternalDtos.RunResultReportRequest request) {
        RunRow run = requireRun(runId);
        if (!run.terminal()) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.RUN_NOT_TERMINAL", "运行尚未进入终态");
        }
        String json;
        try {
            json = mapper.writeValueAsString(request);
        } catch (Exception failure) {
            throw new AgentException(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT.REPORT_INVALID", "运行报告无法保存");
        }
        if (json.length() > 32_000) {
            throw new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT.REPORT_TOO_LARGE", "运行报告超过限制");
        }
        ResultReportRow existing = repository.findResultReport(runId).orElse(null);
        if (existing != null) return sameReport(runId, request, json, existing);
        boolean inserted;
        try {
            inserted = repository.insertResultReport(runId, request.reportId(), json, Instant.now());
        } catch (DuplicateKeyException conflict) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.REPORT_IDEMPOTENCY_CONFLICT",
                    "reportId 已用于其他运行");
        }
        if (!inserted) {
            return sameReport(runId, request, json, repository.findResultReport(runId).orElseThrow());
        }
        // 归一化使用 savepoint：失败时回滚全部派生写入，但保留原私有报告和脱敏 FAILED 状态供重试。
        try {
            memoryFacts.normalize(runId);
        } catch (RuntimeException failure) {
            memoryFacts.recordNormalizationFailure(runId, failure);
        }
        return new AgentInternalDtos.RunResultReportResponse(runId, request.reportId(), false);
    }

    private AgentInternalDtos.RunResultReportResponse sameReport(UUID runId,
            AgentInternalDtos.RunResultReportRequest request, String json, ResultReportRow existing) {
        if (!existing.reportId().equals(request.reportId()) || !existing.json().equals(json)) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.REPORT_IDEMPOTENCY_CONFLICT",
                    "该运行已保存不同的结果报告");
        }
        return new AgentInternalDtos.RunResultReportResponse(runId, request.reportId(), true);
    }

    private RunRow requireRun(UUID runId) {
        return repository.findRunInternal(runId).orElseThrow(() ->
                new AgentException(HttpStatus.NOT_FOUND, "AGENT.NOT_FOUND", "运行不存在"));
    }

}
