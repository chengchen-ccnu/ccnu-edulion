package com.eduplatform.learning.web;

import com.eduplatform.learning.config.AgentIndexProperties;
import com.eduplatform.learning.service.AgentIndexProjectionService;
import com.eduplatform.learning.service.QuestionException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅向专用 Index Worker 暴露分页安全投影；Gateway 不路由 internal 路径。 */
@RestController
@Validated
@ConditionalOnProperty(prefix = "platform.learning.agent-index", name = "enabled", havingValue = "true")
public class AgentIndexController {
    private final AgentIndexProjectionService projections;
    private final AgentIndexProperties properties;

    public AgentIndexController(AgentIndexProjectionService projections, AgentIndexProperties properties) {
        this.projections = projections;
        this.properties = properties;
    }

    @GetMapping("/internal/v1/agent-index/questions")
    public AgentIndexDtos.QuestionPage questions(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @RequestParam(defaultValue = "0") @Min(0) long afterRevision,
            @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID afterEntityId,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
        authorize(caller, token);
        return projections.questions(afterRevision, afterEntityId.toString(),
                entityId == null ? null : entityId.toString(), size);
    }

    @GetMapping("/internal/v1/agent-index/knowledge-points")
    public AgentIndexDtos.KnowledgePage knowledge(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @RequestParam(defaultValue = "0") @Min(0) long afterRevision,
            @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID afterEntityId,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
        authorize(caller, token);
        return projections.knowledge(afterRevision, afterEntityId.toString(),
                entityId == null ? null : entityId.toString(), size);
    }

    private void authorize(String caller, String token) {
        String expected = properties.serviceToken();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "agent-index-worker".equals(caller)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new QuestionException(HttpStatus.UNAUTHORIZED, "AGENT_INDEX.UNAUTHORIZED", "内部调用身份无效");
    }
}
