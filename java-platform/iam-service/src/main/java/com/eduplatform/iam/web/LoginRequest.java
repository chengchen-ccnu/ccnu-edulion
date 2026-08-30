package com.eduplatform.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record LoginRequest(
        @NotBlank @Size(max = 128) String account,
        @NotBlank @Size(min = 8, max = 128) String password,
        UUID deviceId,
        @Size(max = 120) String deviceName) {
    public LoginRequest(String account, String password) { this(account, password, null, null); }
}
