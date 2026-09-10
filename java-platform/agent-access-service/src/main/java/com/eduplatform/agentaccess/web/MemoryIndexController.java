package com.eduplatform.agentaccess.web;

import com.eduplatform.agentaccess.config.AgentIndexProperties;
import com.eduplatform.agentaccess.service.AgentException;
import com.eduplatform.agentaccess.service.MemoryIndexProjectionService;
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

/** 仅向固定 Index Worker 身份提供记忆分页/单实体投影；Gateway 不路由本路径。 */
@RestController
@Validated
@ConditionalOnProperty(prefix = "platform.agent-index", name = "enabled", havingValue = "true")
public class MemoryIndexController {
    private final MemoryIndexProjectionService service;
    private final AgentIndexProperties properties;

    public MemoryIndexController(MemoryIndexProjectionService service, AgentIndexProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * 按全局 revision 分页或按实体 ID 返回当前安全投影。调用方必须同时提供固定身份与部署令牌；
     * 未通过校验时返回统一 401，且不会查询或泄露任何用户记忆。
     */
    @GetMapping("/internal/v1/agent-index/student-memories")
    public MemoryIndexDtos.Page memories(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @RequestParam(defaultValue = "0") @Min(0) long afterRevision,
            @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID afterEntityId,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
        authorize(caller, token);
        return service.page(afterRevision, afterEntityId, entityId, size);
    }

    private void authorize(String caller, String token) {
        String expected = properties.serviceToken();
        boolean valid = expected != null && expected.getBytes(StandardCharsets.UTF_8).length >= 32
                && "agent-index-worker".equals(caller)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!valid) throw new AgentException(HttpStatus.UNAUTHORIZED, "AGENT_INDEX.UNAUTHORIZED", "内部调用身份无效");
    }
}
