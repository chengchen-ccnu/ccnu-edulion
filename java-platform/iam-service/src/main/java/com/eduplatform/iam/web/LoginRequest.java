package com.eduplatform.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 128) String account,
        @NotBlank @Size(min = 8, max = 128) String password) {}
