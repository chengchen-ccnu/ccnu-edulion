package com.eduplatform.learning.service;

import com.eduplatform.shared.api.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 题库可预期业务错误；只包含可返回给调用方的稳定代码和安全字段原因。 */
public class QuestionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public QuestionException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public QuestionException(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldError> fieldErrors() { return fieldErrors; }
}
