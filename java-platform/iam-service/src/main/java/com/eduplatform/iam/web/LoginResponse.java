package com.eduplatform.iam.web;

public record LoginResponse(String tokenType, String accessToken, long expiresIn) {}
