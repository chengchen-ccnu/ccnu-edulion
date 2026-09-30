package com.eduplatform.learning.web;

import com.eduplatform.learning.config.TraceFilter;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.shared.api.ApiError;
import com.eduplatform.shared.api.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** 将题库权限、并发、参数和依赖错误映射为统一错误信封，不泄露答案、SQL 或签名 URL。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    /** 学习暂停响应提示逻辑舍弃未落库缓冲；只返回通用状态和配置邮箱。 */
    @ExceptionHandler(com.eduplatform.learning.course.CoursePausedException.class)
    ResponseEntity<java.util.Map<String, Object>> handlePaused(
            com.eduplatform.learning.course.CoursePausedException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(java.util.Map.of(
                "code", exception.code(), "message", exception.getMessage(),
                "traceId", request.getAttribute(TraceFilter.ATTRIBUTE), "timestamp", Instant.now(),
                "path", request.getRequestURI(), "fieldErrors", List.of(), "supportEmail", exception.supportEmail(),
                "progressBufferDiscarded", true));
    }

    @ExceptionHandler(QuestionException.class)
    ResponseEntity<ApiError> handleQuestion(QuestionException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage(), request,
                exception.fieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage())).toList();
        return invalid(request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraint(ConstraintViolationException exception, HttpServletRequest request) {
        return invalid(request, exception.getConstraintViolations().stream()
                .map(value -> new FieldError(value.getPropertyPath().toString(), value.getMessage())).toList());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return invalid(request, List.of(new FieldError(unreadableField(exception), "请求体字段格式或枚举值不合法")));
    }

    /** 查询参数枚举转换和方法级分页约束失败时统一返回 422，不把框架异常暴露为 500。 */
    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> handleRequestParameterValidation(Exception exception, HttpServletRequest request) {
        String field = exception instanceof MethodArgumentTypeMismatchException mismatch
                && SAFE_FIELD.matcher(mismatch.getName()).matches() ? mismatch.getName() : "request";
        return invalid(request, List.of(new FieldError(field, "查询参数格式、枚举值或范围不合法")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("COMMON.INTERNAL_ERROR", "服务暂时不可用", request, List.of()));
    }

    private ResponseEntity<ApiError> invalid(HttpServletRequest request, List<FieldError> fields) {
        return ResponseEntity.unprocessableEntity().body(error("COMMON.VALIDATION_FAILED", "请求参数不合法", request, fields));
    }

    /**
     * 只从 Jackson 的结构化引用链提取安全字段名和数组下标；不解析异常文本，也不暴露类名、请求体或堆栈。
     * 无结构化路径的截断 JSON 等错误统一回退到 request。
     */
    private static String unreadableField(HttpMessageNotReadableException exception) {
        JacksonException jackson = jacksonCause(exception);
        if (jackson == null) return "request";
        List<String> segments = new ArrayList<>();
        for (JacksonException.Reference reference : jackson.getPath()) {
            String property = reference.getPropertyName();
            if (property != null && SAFE_FIELD.matcher(property).matches()) {
                segments.add(property);
            } else if (reference.getIndex() >= 0 && !segments.isEmpty()) {
                int last = segments.size() - 1;
                segments.set(last, segments.get(last) + "[" + reference.getIndex() + "]");
            }
        }
        if (jackson instanceof UnrecognizedPropertyException unknown
                && SAFE_FIELD.matcher(unknown.getPropertyName()).matches()
                && (segments.isEmpty() || !unknown.getPropertyName().equals(segments.get(segments.size() - 1)))) {
            segments.add(unknown.getPropertyName());
        }
        return segments.isEmpty() ? "request" : String.join(".", segments);
    }

    private static JacksonException jacksonCause(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof JacksonException jackson) return jackson;
        }
        return null;
    }

    private ApiError error(String code, String message, HttpServletRequest request, List<FieldError> fields) {
        return new ApiError(code, message, (String) request.getAttribute(TraceFilter.ATTRIBUTE), Instant.now(),
                request.getRequestURI(), fields);
    }
}
