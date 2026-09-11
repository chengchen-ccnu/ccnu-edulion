package com.eduplatform.agentaccess.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 浏览器可见 Agent DTO；不包含角色声明、隐藏答案、临时媒体 URL 或 Python 私有状态。 */
public final class AgentDtos {
    private AgentDtos() {}

    public enum EntryPoint { INDEPENDENT_CHAT, PRACTICE_FLOATING }
    public enum ConversationStatus { ACTIVE, ARCHIVED }
    public enum RunStatus { ACCEPTED, RUNNING, COMPLETED, DEGRADED, LIMIT_REACHED, CANCELLED, FAILED }
    public enum MessageRole { USER, ASSISTANT }
    public enum Completeness { COMPLETE, PARTIAL }
    public enum ContentState { RETAINED, PURGED }
    public enum CancelReason { USER_STOP, CLIENT_DISCONNECTED, PAGE_CLOSED }

    public record CreateConversationRequest(@NotNull UUID clientRequestId, @NotNull EntryPoint entryPoint,
            @Size(min = 1, max = 80) @Pattern(regexp = "(?s).*\\S.*") String title) {}

    public record Conversation(UUID id, EntryPoint entryPoint, String title, ConversationStatus status,
            long revision, UUID activeRunId, Instant createdAt, Instant updatedAt, Instant lastMessageAt) {}

    public record ConversationPage(List<Conversation> items, int page, int size, long total) {}

    public record MessageContextRequest(UUID questionId, UUID courseId, UUID lessonId,
            @Size(max = 8) List<UUID> questionMediaIds, @Size(max = 8) List<UUID> aiQuestionMediaIds) {
        public MessageContextRequest(UUID questionId, UUID courseId, UUID lessonId, List<UUID> questionMediaIds) {
            this(questionId, courseId, lessonId, questionMediaIds, List.of());
        }
    }

    public record SendMessageRequest(@NotNull UUID clientRequestId,
            @NotBlank @Size(max = 10000) String content, @Valid MessageContextRequest context) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageContext(UUID questionId, UUID questionVersionId, UUID courseId,
            String courseVersionId, UUID lessonId, List<UUID> mediaIds) {}

    public record Message(UUID id, UUID conversationId, UUID runId, MessageRole role, String content,
            Completeness completeness, ContentState contentState, MessageContext context,
            long sequenceNo, Instant createdAt) {}

    /** Java 从已持久化消息构造的有界窗口；不包含当前用户消息、上下文 JSON 或临时授权。 */
    public record RecentMessage(UUID messageId, MessageRole role, String content,
            Completeness completeness, long sequenceNo) {}

    public record MessagePage(List<Message> items, int page, int size, long total) {}

    /** Python SSE 的浏览器安全投影；payload 已经过字段级秘密扫描。 */
    public record StreamEvent(String eventType, UUID runId, UUID messageId, long sequence,
            Instant occurredAt, JsonNode payload) {}

    public record RunEventPage(UUID runId, RunStatus status, List<StreamEvent> items) {}

    public record CancelRunRequest(@NotNull UUID cancelRequestId, @NotNull CancelReason reason) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CancelRunResponse(UUID runId, RunStatus status, boolean accepted, boolean terminal,
            Long terminalEventSequence) {}

    /** Java 授权器返回的运行时上下文；stableContext 可持久化，trustedContext 只用于当次内部调用。 */
    public record AuthorizedContext(MessageContext stableContext, Map<String, Object> trustedContext) {}
}
