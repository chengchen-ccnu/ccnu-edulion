package com.eduplatform.course.service;

import com.eduplatform.shared.api.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

public class CourseException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldError> fieldErrors;

    public CourseException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public CourseException(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }

    public List<FieldError> fieldErrors() { return fieldErrors; }
}
