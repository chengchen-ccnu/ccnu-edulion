package com.eduplatform.agentaccess.web;

import com.eduplatform.agentaccess.service.AgentException;
import com.eduplatform.shared.api.FieldError;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** 只接受 Gateway 注入的用户 ID 和能力；浏览器自带同名头会先被 Gateway 清除。 */
public final class AgentIdentity {
    private AgentIdentity() {}

    public static UUID requireLearner(String userId, String permissions) {
        if (userId == null || permissions == null || Arrays.stream(permissions.split(","))
                .map(String::trim).noneMatch("learning:access"::equals)) {
            throw new AgentException(HttpStatus.FORBIDDEN, "IAM.FORBIDDEN", "无权访问该资源");
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException invalid) {
            throw new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "COMMON.VALIDATION_FAILED", "请求参数不合法",
                    List.of(new FieldError("X-User-Id", "用户身份格式不合法")));
        }
    }
}
