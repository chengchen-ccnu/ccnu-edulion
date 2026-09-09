package com.eduplatform.course.web;

import com.eduplatform.course.config.AgentIndexProperties;
import com.eduplatform.course.service.CourseAgentIndexProjectionService;
import com.eduplatform.course.service.CourseException;
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

/** 仅服务间开放课程文字分页源；路径不会经 Gateway 暴露给浏览器。 */
@RestController
@Validated
@ConditionalOnProperty(prefix = "platform.course.agent-index", name = "enabled", havingValue = "true")
public class CourseAgentIndexController {
    private final CourseAgentIndexProjectionService projections;
    private final AgentIndexProperties properties;

    public CourseAgentIndexController(CourseAgentIndexProjectionService projections, AgentIndexProperties properties) {
        this.projections = projections; this.properties = properties;
    }

    @GetMapping("/internal/v1/agent-index/course-texts")
    public CourseAgentIndexDtos.Page page(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @RequestParam(defaultValue = "0") @Min(0) long afterRevision,
            @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID afterEntityId,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
        authorize(caller, token);
        return projections.page(afterRevision, afterEntityId.toString(),
                entityId == null ? null : entityId.toString(), size);
    }

    private void authorize(String caller, String token) {
        String expected = properties.serviceToken();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "agent-index-worker".equals(caller)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new CourseException(HttpStatus.UNAUTHORIZED, "AGENT_INDEX.UNAUTHORIZED", "内部调用身份无效");
    }
}
