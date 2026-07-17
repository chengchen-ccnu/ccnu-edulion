package com.eduplatform.iam.service;

public record TokenPair(String accessToken, long expiresIn, String refreshToken) {}
