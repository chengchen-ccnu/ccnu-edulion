package com.eduplatform.agentaccess.service;

import com.eduplatform.shared.api.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 可安全映射到统一错误信封的 Agent 领域异常，不携带依赖响应体、SQL 或签名 URL。 */
public class AgentException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public AgentException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public AgentException(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldError> fieldErrors() { return fieldErrors; }
}
