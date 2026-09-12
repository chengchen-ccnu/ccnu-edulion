package com.eduplatform.agentaccess.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonCreator;
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
    public enum ExplanationPreference { GUIDED_STEP_BY_STEP, COMPLETE_SOLUTION }
    public enum QuestionProjectionStatus { AVAILABLE, UNAVAILABLE }
    public enum MediaDisposition { INLINE, ATTACHMENT }

    /** 普通 bean 允许浏览器省略可选 title；必填字段仍由 Bean Validation 拒绝。 */
    public static final class CreateConversationRequest {
        @NotNull private UUID clientRequestId;
        @NotNull private EntryPoint entryPoint;
        @Size(min = 1, max = 80) @Pattern(regexp = "(?s).*\\S.*") private String title;

        public CreateConversationRequest() {}
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        public CreateConversationRequest(UUID clientRequestId, EntryPoint entryPoint, String title) {
            this.clientRequestId = clientRequestId;
            this.entryPoint = entryPoint;
            this.title = title;
        }
        public UUID clientRequestId() { return clientRequestId; }
        public EntryPoint entryPoint() { return entryPoint; }
        public String title() { return title; }
        public void setClientRequestId(UUID value) { clientRequestId = value; }
        public void setEntryPoint(EntryPoint value) { entryPoint = value; }
        public void setTitle(String value) { title = value; }
    }

    public record Conversation(UUID id, EntryPoint entryPoint, String title, ConversationStatus status,
            long revision, UUID activeRunId, Instant createdAt, Instant updatedAt, Instant lastMessageAt) {}

    public record ConversationPage(List<Conversation> items, int page, int size, long total) {}

    /** 所有上下文字段都是可选浏览器提示；普通 bean 与严格 Jackson 的缺省属性语义兼容。 */
    public static final class MessageContextRequest {
        private UUID questionId;
        private UUID courseId;
        private UUID lessonId;
        @Size(max = 8) private List<UUID> questionMediaIds;
        @Size(max = 8) private List<UUID> aiQuestionMediaIds;

        public MessageContextRequest() {}
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        public MessageContextRequest(UUID questionId, UUID courseId, UUID lessonId,
                List<UUID> questionMediaIds, List<UUID> aiQuestionMediaIds) {
            this.questionId = questionId;
            this.courseId = courseId;
            this.lessonId = lessonId;
            this.questionMediaIds = questionMediaIds;
            this.aiQuestionMediaIds = aiQuestionMediaIds;
        }
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        public MessageContextRequest(UUID questionId, UUID courseId, UUID lessonId, List<UUID> questionMediaIds) {
            this(questionId, courseId, lessonId, questionMediaIds, List.of());
        }
        public UUID questionId() { return questionId; }
        public UUID courseId() { return courseId; }
        public UUID lessonId() { return lessonId; }
        public List<UUID> questionMediaIds() { return questionMediaIds; }
        public List<UUID> aiQuestionMediaIds() { return aiQuestionMediaIds; }
        public void setQuestionId(UUID value) { questionId = value; }
        public void setCourseId(UUID value) { courseId = value; }
        public void setLessonId(UUID value) { lessonId = value; }
        public void setQuestionMediaIds(List<UUID> value) { questionMediaIds = value; }
        public void setAiQuestionMediaIds(List<UUID> value) { aiQuestionMediaIds = value; }
    }

    /** 发送请求允许省略 context；正文和幂等键仍为必填。 */
    public static final class SendMessageRequest {
        @NotNull private UUID clientRequestId;
        @NotBlank @Size(max = 10000) private String content;
        @Valid private MessageContextRequest context;

        public SendMessageRequest() {}
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        public SendMessageRequest(UUID clientRequestId, String content, MessageContextRequest context) {
            this.clientRequestId = clientRequestId;
            this.content = content;
            this.context = context;
        }
        public UUID clientRequestId() { return clientRequestId; }
        public String content() { return content; }
        public MessageContextRequest context() { return context; }
        public void setClientRequestId(UUID value) { clientRequestId = value; }
        public void setContent(String value) { content = value; }
        public void setContext(MessageContextRequest value) { context = value; }
    }

    public record UpdateExplanationPreferenceRequest(@NotNull UUID clientRequestId,
            @NotNull ExplanationPreference value, @NotNull @jakarta.validation.constraints.PositiveOrZero
            Long expectedRevision) {}

    /** 只公开学生主动设置的讲解方式；推断来源、画像、证据和记忆均不进入响应。 */
    public record ExplanationPreferenceView(ExplanationPreference value, long revision,
            boolean explicitlySet) {}

    public record RegenerateMessageRequest(@NotNull UUID clientRequestId) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageContext(UUID questionId, UUID questionVersionId, UUID courseId,
            String courseVersionId, UUID lessonId, List<UUID> mediaIds) {}

    public record QuestionContextOption(String key, String content) {}
    public record QuestionContextMedia(UUID id, UUID mediaId, String purpose, int sequence) {}
    public record QuestionContextChild(UUID questionId, UUID questionVersionId, int sequence, String type,
            String stem, List<QuestionContextOption> options, List<QuestionContextMedia> media) {}
    public record QuestionContextQuestion(UUID questionId, UUID questionVersionId, String type, String stem,
            List<QuestionContextOption> options, List<QuestionContextMedia> media,
            List<QuestionContextChild> children) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record QuestionContextProjection(QuestionProjectionStatus status, QuestionContextQuestion question) {}

    public record QuestionMediaAccessRequest(@NotBlank @Size(max = 80) String variant,
            @NotNull MediaDisposition disposition) {}
    public record MediaAccessGrant(UUID mediaId, String mimeType, long sizeBytes, String method, String url,
            Map<String, String> headers, Instant expiresAt) {}

    public record Message(UUID id, UUID conversationId, UUID runId, MessageRole role, String content,
            Completeness completeness, ContentState contentState, MessageContext context,
            QuestionContextProjection questionContextProjection,
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
