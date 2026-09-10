package com.eduplatform.course.web;

import com.eduplatform.course.config.AgentAccessProperties;
import com.eduplatform.course.service.CourseAgentAccessService;
import com.eduplatform.course.service.CourseException;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 只允许 Agent Access 服务调用的课程候选批量二次授权入口。 */
@RestController
@ConditionalOnProperty(prefix = "platform.course.agent-access", name = "enabled", havingValue = "true")
public class CourseAgentAccessController {
    private final CourseAgentAccessService service;
    private final AgentAccessProperties properties;

    public CourseAgentAccessController(CourseAgentAccessService service, AgentAccessProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/internal/v1/agent-access/course-candidates:verify")
    public CourseAgentAccessDtos.VerifyResponse verify(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody CourseAgentAccessDtos.VerifyRequest request) {
        authorize(caller, token);
        return service.verify(request);
    }

    private void authorize(String caller, String token) {
        String expected = properties.serviceToken();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "agent-access-service".equals(caller)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new CourseException(HttpStatus.UNAUTHORIZED, "AGENT_ACCESS.UNAUTHORIZED", "内部调用身份无效");
    }
}
