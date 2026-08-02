package com.eduplatform.shared.api;

/** 统一错误信封中的单字段校验失败；field 使用契约字段路径，reason 不包含敏感输入值。 */
public record FieldError(String field, String reason) {
}
