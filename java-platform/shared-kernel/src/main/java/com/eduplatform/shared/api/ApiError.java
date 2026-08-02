package com.eduplatform.shared.api;

import java.time.Instant;
import java.util.List;

/**
 * 仅供各服务 HTTP 适配层复用的统一技术错误信封。
 *
 * <p>code 是稳定机器码，traceId 用于跨服务排查，fieldErrors 为空时规范化为空列表。
 * shared-kernel 不得因此引入领域实体、持久化模型或跨域 DTO。</p>
 */
public record ApiError(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        String path,
        List<FieldError> fieldErrors) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
}
