package com.eduplatform.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record LoginRequest(
        @NotBlank @Size(max = 254) String identity,
        @NotBlank @Size(min = 8, max = 128) String password,
        AccountLifecycleDtos.ArithmeticCaptchaAnswer captcha,
        UUID deviceId,
        @Size(max = 120) String deviceName) {
    public LoginRequest(String identity, String password) { this(identity, password, null, null, null); }
}
