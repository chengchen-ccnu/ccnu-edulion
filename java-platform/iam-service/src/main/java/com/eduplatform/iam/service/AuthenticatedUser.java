package com.eduplatform.iam.service;

import java.util.List;

public record AuthenticatedUser(String id, String displayName, String avatarUrl,
        List<String> roles, List<String> permissions) {}
