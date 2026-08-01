package com.eduplatform.media.service;

import com.eduplatform.shared.api.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 媒体 API 可预期业务错误，同时携带 HTTP 状态、稳定错误码与字段错误。 */
public class MediaException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public MediaException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public MediaException(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }

    public List<FieldError> fieldErrors() { return fieldErrors; }
}
