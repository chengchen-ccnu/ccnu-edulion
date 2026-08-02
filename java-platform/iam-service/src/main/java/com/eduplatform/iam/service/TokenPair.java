package com.eduplatform.iam.service;

/** IAM 内部令牌结果；refreshToken 只供控制器写入 HttpOnly Cookie，不进入 JSON DTO。 */
public record TokenPair(String accessToken, long expiresIn, String refreshToken) {}
