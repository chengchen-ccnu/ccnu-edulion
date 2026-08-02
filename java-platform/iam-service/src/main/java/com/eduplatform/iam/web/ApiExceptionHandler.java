package com.eduplatform.iam.web;

import com.eduplatform.iam.config.TraceFilter;
import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.shared.api.ApiError;
import com.eduplatform.shared.api.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
/**
 * 把 IAM 认证、校验和未知异常转换为含 Trace ID 的统一错误信封。
 * 刷新令牌无效或重放时同时清除 Cookie；未知异常不返回内部堆栈或敏感信息。
 */
public class ApiExceptionHandler {
    private final IamProperties properties;

    public ApiExceptionHandler(IamProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiError> handleAuth(AuthException exception, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        if (exception.status() == HttpStatus.TOO_MANY_REQUESTS) {
            response.header(HttpHeaders.RETRY_AFTER, "60");
        }
        if (request.getRequestURI().endsWith("/auth/refresh")
                && (exception.status() == HttpStatus.UNAUTHORIZED || exception.status() == HttpStatus.CONFLICT)) {
            ResponseCookie cleared = ResponseCookie.from(AuthController.REFRESH_COOKIE, "")
                    .httpOnly(true).secure(properties.refresh().cookieSecure()).sameSite("Strict")
                    .path("/api/v1/auth").maxAge(0).build();
            response.header(HttpHeaders.SET_COOKIE, cleared.toString());
        }
        return response.body(error(exception.code(), exception.getMessage(), request, List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(error("COMMON.VALIDATION_FAILED", "请求参数不合法", request, fields));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("COMMON.INTERNAL_ERROR", "服务暂时不可用", request, List.of()));
    }

    private ApiError error(String code, String message, HttpServletRequest request, List<FieldError> fields) {
        return new ApiError(code, message, (String) request.getAttribute(TraceFilter.ATTRIBUTE), Instant.now(),
                request.getRequestURI(), fields);
    }
}
