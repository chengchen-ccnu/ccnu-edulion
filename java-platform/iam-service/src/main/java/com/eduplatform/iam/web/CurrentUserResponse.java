package com.eduplatform.iam.web;

import java.util.List;

public record CurrentUserResponse(String id, String displayName, String avatarUrl,
        List<String> roles, List<String> permissions, long sessionVersion) {}
