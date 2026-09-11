package com.eduplatform.agentaccess.web;

import com.eduplatform.agentaccess.config.AgentProperties;
import com.eduplatform.agentaccess.service.AgentException;
import com.eduplatform.agentaccess.service.AgentInternalService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 仅接收 Python Runtime 服务身份回调；Gateway 不路由 internal 路径。 */
@RestController
@Validated
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class AgentInternalController {
    private final AgentInternalService service;
    private final AgentProperties properties;

    public AgentInternalController(AgentInternalService service, AgentProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/internal/v1/agent/runs/{runId}/course-candidates:verify")
    public AgentInternalDtos.VerifyCourseCandidatesResponse verify(@PathVariable UUID runId,
            @RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @RequestHeader(value = "X-Trace-Id", defaultValue = "internal-agent") String traceId,
            @Valid @RequestBody AgentInternalDtos.VerifyCourseCandidatesRequest request) {
        authorize(caller, token);
        return service.verify(runId, request, traceId);
    }

    @PostMapping("/internal/v1/agent/runs/{runId}/student-facts:query")
    public AgentInternalDtos.StudentFactsResponse facts(@PathVariable UUID runId,
            @RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @RequestHeader(value = "X-Trace-Id", defaultValue = "internal-agent") String traceId,
            @Valid @RequestBody AgentInternalDtos.StudentFactsRequest request) {
        authorize(caller, token);
        return service.facts(runId, request, traceId);
    }

    /** 固定 Runtime 身份可按活动 run 查询当前帮助版本；浏览器请求不会经过 Gateway 到达这里。 */
    @PostMapping("/internal/v1/agent/runs/{runId}/user-help:search")
    public AgentInternalDtos.UserHelpSearchResponse userHelp(@PathVariable UUID runId,
            @RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody AgentInternalDtos.UserHelpSearchRequest request) {
        authorize(caller, token);
        return service.userHelp(runId, request);
    }

    @PutMapping("/internal/v1/agent/runs/{runId}/result-report")
    public AgentInternalDtos.RunResultReportResponse report(@PathVariable UUID runId,
            @RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody AgentInternalDtos.RunResultReportRequest request) {
        authorize(caller, token);
        return service.report(runId, request);
    }

    private void authorize(String caller, String token) {
        String expected = properties.runtimeCallbackToken();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "edu-agent-runtime".equals(caller)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new AgentException(HttpStatus.UNAUTHORIZED, "AGENT.INTERNAL_UNAUTHORIZED", "内部调用身份无效");
        }
    }
}
