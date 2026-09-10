package com.eduplatform.agentaccess.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Index Worker 专用 USER_PRIVATE 安全投影；不包含证据、画像、用量、会话原文或秘密。 */
public final class MemoryIndexDtos {
    private MemoryIndexDtos() {}

    public record Cursor(long sourceRevision, UUID entityId) {}
    public record Projection(UUID entityId, UUID subjectUserId, long sourceRevision,
            String educationStage, String subject, String memoryType, String text, String visibility,
            String accessScope, UUID courseId, String courseVersionId, UUID lessonId,
            boolean requiresCurrentCourseAccess, Instant expiresAt) {}
    public record Page(List<Projection> items, boolean hasMore, Cursor nextCursor) {}
}
