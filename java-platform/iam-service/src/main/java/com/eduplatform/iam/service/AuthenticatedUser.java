package com.eduplatform.iam.service;

import java.util.List;

/** 已从 IAM 数据库读取的当前用户及实时角色权限，用于签发 JWT 和 /users/me 投影。 */
public record AuthenticatedUser(String id, String displayName, String avatarUrl,
        List<String> roles, List<String> permissions, long sessionVersion) {}
