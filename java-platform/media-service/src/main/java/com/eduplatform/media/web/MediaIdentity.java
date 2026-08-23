package com.eduplatform.media.web;

import com.eduplatform.media.service.MediaException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/**
 * 解析 Gateway 在内网路由中注入的可信用户 ID 和角色头。
 * <p>media-service 不解析浏览器 Token；未经 Gateway 的端口必须通过网络边界阻断外部访问。</p>
 */
record MediaIdentity(String userId, Set<String> roles, Set<String> permissions) {
    static MediaIdentity require(String userId, String roles) {
        return require(userId, roles, null);
    }

    /** 同时解析 Gateway 注入的角色与权限；管理员能力不能仅凭角色名称推导。 */
    static MediaIdentity require(String userId, String roles, String permissions) {
        if (userId == null || userId.isBlank() || !isUuid(userId)) {
            throw new MediaException(HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录");
        }
        return new MediaIdentity(userId, split(roles), split(permissions));
    }

    private static Set<String> split(String values) {
        return values == null ? Set.of() : Arrays.stream(values.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
