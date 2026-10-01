package com.eduplatform.course.web;

import com.eduplatform.course.config.TraceFilter;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.shared.api.ApiError;
import com.eduplatform.shared.api.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
/**
 * 把课程业务错误、参数校验和不可读请求统一转换为平台错误信封。
 * 他人课程与不存在资源沿用相同业务错误，未知异常不会泄露 SQL 或内部实现。
 */
public class ApiExceptionHandler {
    /** 暂停响应只包含统一信封和配置邮箱，不包含课程正文或内部撤权原因。 */
    @ExceptionHandler(com.eduplatform.course.service.CourseSuspendedException.class)
    ResponseEntity<java.util.Map<String, Object>> handleSuspended(
            com.eduplatform.course.service.CourseSuspendedException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(java.util.Map.of(
                "code", exception.code(), "message", exception.getMessage(),
                "traceId", request.getAttribute(TraceFilter.ATTRIBUTE), "timestamp", Instant.now(),
                "path", request.getRequestURI(), "fieldErrors", List.of(), "supportEmail", exception.supportEmail()));
    }

    @ExceptionHandler(CourseException.class)
    ResponseEntity<ApiError> handleCourse(CourseException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(error(exception.code(), exception.getMessage(), request, exception.fieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage()))
                .toList();
        return unprocessable(request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraint(ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getConstraintViolations().stream()
                .map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return unprocessable(request, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return unprocessable(request, List.of(new FieldError("request", "请求体格式或枚举值不合法")));
    }

    /** 内部 UUID/游标无法转换时仍按契约返回 422，不泄露框架异常。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(HttpServletRequest request) {
        return unprocessable(request, List.of(new FieldError("request", "资源 ID 或游标格式不合法")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("COMMON.INTERNAL_ERROR", "服务暂时不可用", request, List.of()));
    }

    private ResponseEntity<ApiError> unprocessable(HttpServletRequest request, List<FieldError> fields) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(error("COMMON.VALIDATION_FAILED", "请求参数不合法", request, fields));
    }

    private ApiError error(String code, String message, HttpServletRequest request, List<FieldError> fields) {
        return new ApiError(code, message, (String) request.getAttribute(TraceFilter.ATTRIBUTE), Instant.now(),
                request.getRequestURI(), fields);
    }
}
