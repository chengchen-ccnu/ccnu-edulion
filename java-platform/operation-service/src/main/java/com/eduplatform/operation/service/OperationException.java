package com.eduplatform.operation.service;

import com.eduplatform.shared.api.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 公告/消息可预期业务错误；只包含可返回给调用方的稳定代码和安全字段原因。 */
public class OperationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public OperationException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public OperationException(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldError> fieldErrors() { return fieldErrors; }
}
