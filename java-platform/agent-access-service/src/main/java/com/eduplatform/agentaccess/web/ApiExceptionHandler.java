package com.eduplatform.agentaccess.web;

import com.eduplatform.agentaccess.config.TraceFilter;
import com.eduplatform.agentaccess.service.AgentException;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** 将 Agent 授权、幂等、并发和依赖错误映射为统一信封，避免泄露内部请求与临时 URL。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AgentException.class)
    ResponseEntity<ApiError> handleAgent(AgentException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage(),
                request, exception.fieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return invalid(request, exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage())).toList());
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> handleInvalid(Exception ignored, HttpServletRequest request) {
        return invalid(request, List.of(new FieldError("request", "请求字段格式或范围不合法")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("COMMON.INTERNAL_ERROR", "服务暂时不可用", request, List.of()));
    }

    private ResponseEntity<ApiError> invalid(HttpServletRequest request, List<FieldError> fields) {
        return ResponseEntity.unprocessableEntity()
                .body(error("COMMON.VALIDATION_FAILED", "请求参数不合法", request, fields));
    }

    private ApiError error(String code, String message, HttpServletRequest request, List<FieldError> fields) {
        return new ApiError(code, message, (String) request.getAttribute(TraceFilter.ATTRIBUTE), Instant.now(),
                request.getRequestURI(), fields);
    }
}
