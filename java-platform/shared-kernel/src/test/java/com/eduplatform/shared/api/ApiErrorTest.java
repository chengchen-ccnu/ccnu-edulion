package com.eduplatform.shared.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiErrorTest {

    @Test
    void fieldErrorsAreDefensivelyCopied() {
        var fields = new ArrayList<>(List.of(new FieldError("email", "invalid")));
        var error = new ApiError(
                "IAM.INVALID_CREDENTIALS",
                "Invalid credentials",
                "trace-1",
                Instant.EPOCH,
                "/api/v1/auth/login",
                fields);

        fields.clear();

        assertEquals(1, error.fieldErrors().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> error.fieldErrors().add(new FieldError("password", "missing")));
    }
}
