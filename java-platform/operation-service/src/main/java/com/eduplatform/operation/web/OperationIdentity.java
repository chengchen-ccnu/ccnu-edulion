package com.eduplatform.operation.web;

import com.eduplatform.operation.service.OperationException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/** 从Gateway重写的可信身份头读取本人ID及学习/教学能力；课程owner和报名事实仍由领域API裁决。 */
record OperationIdentity(String userId, Set<String> roles, Set<String> permissions) {
    /** 消息入口输入可信身份头，返回已认证本人；具体目标授权由消息服务重新校验。 */
    static OperationIdentity authenticated(String userId, String roles, String permissions) {
        return parse(userId, roles, permissions);
    }

    /** 学习能力与角色解耦，允许同时拥有教学能力的教师作为学习者。 */
    static OperationIdentity learner(String userId, String roles, String permissions) {
        OperationIdentity identity = parse(userId, roles, permissions);
        if (!identity.permissions.contains("learning:access")) forbidden();
        return identity;
    }

    /** 公告管理输入可信身份头，返回有teaching:access的本人；owner须另验，缺能力403。 */
    static OperationIdentity teaching(String userId, String roles, String permissions) {
        OperationIdentity identity = parse(userId, roles, permissions);
        if (!identity.permissions.contains("teaching:access")) forbidden();
        return identity;
    }

    private static OperationIdentity parse(String userId, String roles, String permissions) {
        try {
            if (userId == null || !UUID.fromString(userId).toString().equalsIgnoreCase(userId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new OperationException(HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录");
        }
        return new OperationIdentity(userId, split(roles), split(permissions));
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toSet());
    }

    private static void forbidden() {
        throw new OperationException(HttpStatus.FORBIDDEN, "IAM.FORBIDDEN", "无权访问该资源");
    }
}
