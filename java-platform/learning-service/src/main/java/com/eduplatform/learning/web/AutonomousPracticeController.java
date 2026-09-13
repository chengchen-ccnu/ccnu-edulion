package com.eduplatform.learning.web;

import com.eduplatform.learning.config.AgentAccessProperties;
import com.eduplatform.learning.service.AutonomousPracticeService;
import com.eduplatform.learning.service.QuestionException;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agent Access 内部组题命令与浏览器本人入口；两类调用共享 Learning 的自主练习事实源。 */
@RestController
public class AutonomousPracticeController {
    private final AutonomousPracticeService service;
    private final AgentAccessProperties properties;

    public AutonomousPracticeController(AutonomousPracticeService service, AgentAccessProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/internal/v1/agent-access/practice-candidates:validate")
    public AgentAccessDtos.ValidatePracticeCandidatesResponse validate(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody AgentAccessDtos.ValidatePracticeCandidatesRequest request) {
        authorize(caller, token);
        return service.validate(request);
    }

    @PostMapping("/internal/v1/agent-access/autonomous-practices")
    public AgentAccessDtos.AutonomousPracticeResult create(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody AgentAccessDtos.CreateAutonomousPracticeRequest request) {
        authorize(caller, token);
        return service.create(request);
    }

    @PostMapping("/internal/v1/agent-access/autonomous-practices:find-by-action")
    public AgentAccessDtos.AutonomousPracticeResult find(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody AgentAccessDtos.FindAutonomousPracticeRequest request) {
        authorize(caller, token);
        return service.find(request);
    }

    @GetMapping("/api/v1/learning/autonomous-practices/{autonomousPracticeId}/entry")
    public LearningDtos.AutonomousPracticeEntry entry(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID autonomousPracticeId, @RequestParam UUID requestId) {
        UUID owner = UUID.fromString(LearningIdentity.learner(userId, roles, permissions).userId());
        return service.entry(autonomousPracticeId, owner);
    }

    private void authorize(String caller, String token) {
        String expected = properties.serviceToken();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "agent-access-service".equals(caller)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new QuestionException(HttpStatus.UNAUTHORIZED,
                "AGENT_ACCESS.UNAUTHORIZED", "内部调用身份无效");
    }
}
