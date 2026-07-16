package com.eduplatform.shared.api;

import java.time.Instant;
import java.util.List;

/**
 * Technical error envelope shared by HTTP adapters only.
 * Domain entities and persistence models must never be placed in shared-kernel.
 */
public record ApiError(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        String path,
        List<FieldError> fieldErrors) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
}
