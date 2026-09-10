package com.eduplatform.learning.web;

import com.eduplatform.learning.config.AgentAccessProperties;
import com.eduplatform.learning.service.AgentAccessFactService;
import com.eduplatform.learning.service.QuestionException;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 只允许 Agent Access 服务读取当前用户安全学习事实，Gateway 不路由该路径。 */
@RestController
@ConditionalOnProperty(prefix = "platform.learning.agent-access", name = "enabled", havingValue = "true")
public class AgentAccessController {
    private final AgentAccessFactService service;
    private final AgentAccessProperties properties;

    public AgentAccessController(AgentAccessFactService service, AgentAccessProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/internal/v1/agent-access/student-facts:query")
    public AgentAccessDtos.QueryResponse query(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody AgentAccessDtos.QueryRequest request) {
        authorize(caller, token);
        return service.query(request);
    }

    private void authorize(String caller, String token) {
        String expected = properties.serviceToken();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "agent-access-service".equals(caller)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new QuestionException(HttpStatus.UNAUTHORIZED, "AGENT_ACCESS.UNAUTHORIZED", "内部调用身份无效");
    }
}
