package com.eduplatform.media.web;

import com.eduplatform.media.config.TraceFilter;
import com.eduplatform.media.service.MediaException;
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

/** 将业务、Bean Validation 和未预期错误统一适配为含 Trace ID 的平台错误信封。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** 暂停授权只显示通用说明与配置邮箱；不回显 Course 原始错误或文件定位信息。 */
    @ExceptionHandler(com.eduplatform.media.service.MediaCourseSuspendedException.class)
    ResponseEntity<java.util.Map<String,Object>> handleCourseSuspended(
            com.eduplatform.media.service.MediaCourseSuspendedException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(java.util.Map.of("code",exception.code(),
                "message",exception.getMessage(),"traceId",request.getAttribute(TraceFilter.ATTRIBUTE),
                "timestamp",Instant.now(),"path",request.getRequestURI(),"fieldErrors",List.of(),
                "supportEmail",exception.supportEmail()));
    }

    @ExceptionHandler(MediaException.class)
    ResponseEntity<ApiError> handleMedia(MediaException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(error(exception.code(), exception.getMessage(), request, exception.fieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage())).toList();
        return unprocessable(request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraint(ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getConstraintViolations().stream()
                .map(violation -> new FieldError(violation.getPropertyPath().toString(), violation.getMessage())).toList();
        return unprocessable(request, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return unprocessable(request, List.of(new FieldError("request", "请求体格式或枚举值不合法")));
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
