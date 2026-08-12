package com.eduplatform.learning.web;

import com.eduplatform.learning.service.QuestionException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/** 从 Gateway 重写的可信身份头解析教师或管理员能力，禁止直接信任请求体中的 owner。 */
record LearningIdentity(String userId, Set<String> roles, Set<String> permissions) {
    static LearningIdentity teacher(String userId, String roles, String permissions) {
        LearningIdentity identity = parse(userId, roles, permissions);
        if (!identity.roles.contains("TEACHER")) forbidden();
        return identity;
    }

    static LearningIdentity administrator(String userId, String roles, String permissions) {
        LearningIdentity identity = parse(userId, roles, permissions);
        if (!identity.permissions.contains("administration:access")) forbidden();
        return identity;
    }

    private static LearningIdentity parse(String userId, String roles, String permissions) {
        try {
            if (userId == null || !UUID.fromString(userId).toString().equalsIgnoreCase(userId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new QuestionException(HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录");
        }
        return new LearningIdentity(userId, split(roles), split(permissions));
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).collect(Collectors.toSet());
    }

    private static void forbidden() {
        throw new QuestionException(HttpStatus.FORBIDDEN, "IAM.FORBIDDEN", "无权访问该资源");
    }
}
