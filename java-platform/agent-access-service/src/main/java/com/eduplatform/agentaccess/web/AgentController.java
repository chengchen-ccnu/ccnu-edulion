package com.eduplatform.agentaccess.web;

import com.eduplatform.agentaccess.config.TraceFilter;
import com.eduplatform.agentaccess.service.AgentConversationService;
import com.eduplatform.agentaccess.service.AgentRunCoordinator;
import com.eduplatform.agentaccess.service.ExplanationPreferenceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 浏览器 AI 会话入口；owner 与 learning:access 每次请求都重新校验。 */
@RestController
@Validated
@RequestMapping("/api/v1/ai")
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class AgentController {
    private final AgentConversationService conversations;
    private final AgentRunCoordinator runs;
    private final ExplanationPreferenceService preferences;

    public AgentController(AgentConversationService conversations, AgentRunCoordinator runs,
            ExplanationPreferenceService preferences) {
        this.conversations = conversations;
        this.runs = runs;
        this.preferences = preferences;
    }

    @PostMapping("/conversations")
    public ResponseEntity<AgentDtos.Conversation> create(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody AgentDtos.CreateConversationRequest input) {
        var result = conversations.createWithStatus(AgentIdentity.requireLearner(userId, permissions), input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.conversation());
    }

    @GetMapping("/conversations")
    public AgentDtos.ConversationPage list(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return conversations.list(AgentIdentity.requireLearner(userId, permissions), page, size);
    }

    @GetMapping("/conversations/{conversationId}")
    public AgentDtos.Conversation get(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID conversationId) {
        return conversations.get(AgentIdentity.requireLearner(userId, permissions), conversationId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public AgentDtos.MessagePage messages(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID conversationId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest servlet) {
        return conversations.messages(AgentIdentity.requireLearner(userId, permissions), conversationId, page, size,
                (String) servlet.getAttribute(TraceFilter.ATTRIBUTE));
    }

    /** 题目身份来自本人已保存消息，浏览器只选择该投影中展示的 mediaId 与访问动作。 */
    @PostMapping("/conversations/{conversationId}/messages/{messageId}/question-media/{mediaId}/access-grants")
    public ResponseEntity<AgentDtos.MediaAccessGrant> authorizeQuestionMedia(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID conversationId, @PathVariable UUID messageId, @PathVariable UUID mediaId,
            @Valid @RequestBody AgentDtos.QuestionMediaAccessRequest input, HttpServletRequest servlet) {
        var grant = conversations.authorizeQuestionMedia(AgentIdentity.requireLearner(userId, permissions),
                conversationId, messageId, mediaId, input,
                (String) servlet.getAttribute(TraceFilter.ATTRIBUTE));
        return ResponseEntity.status(HttpStatus.CREATED).body(grant);
    }

    /** 消息写入和 run 创建先提交，再开始 SSE；响应头返回稳定 runId 供取消和恢复。 */
    @PostMapping(value = "/conversations/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> send(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID conversationId, @Valid @RequestBody AgentDtos.SendMessageRequest input,
            HttpServletRequest servlet) {
        UUID owner = AgentIdentity.requireLearner(userId, permissions);
        var prepared = conversations.prepareRun(owner, conversationId, input,
                (String) servlet.getAttribute(TraceFilter.ATTRIBUTE));
        return ResponseEntity.ok().header("X-Agent-Run-Id", prepared.run().id().toString())
                .body(runs.open(prepared));
    }

    /** 仅基于最后一条已保存用户消息及其授权快照重新生成，不接受浏览器重传正文或媒体。 */
    @PostMapping(value = "/conversations/{conversationId}/messages/{messageId}/regenerations",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> regenerate(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID conversationId, @PathVariable UUID messageId,
            @Valid @RequestBody AgentDtos.RegenerateMessageRequest input, HttpServletRequest servlet) {
        UUID owner = AgentIdentity.requireLearner(userId, permissions);
        var prepared = conversations.prepareRegeneration(owner, conversationId, messageId, input,
                (String) servlet.getAttribute(TraceFilter.ATTRIBUTE));
        return ResponseEntity.ok().header("X-Agent-Run-Id", prepared.run().id().toString())
                .body(runs.open(prepared));
    }

    @GetMapping("/runs/{runId}/events")
    public AgentDtos.RunEventPage events(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID runId, @RequestParam(defaultValue = "0") @Min(0) long afterSequence) {
        return conversations.events(AgentIdentity.requireLearner(userId, permissions), runId, afterSequence);
    }

    @PostMapping("/runs/{runId}/cancel")
    public AgentDtos.CancelRunResponse cancel(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable UUID runId, @Valid @RequestBody AgentDtos.CancelRunRequest input) {
        return runs.cancel(AgentIdentity.requireLearner(userId, permissions), runId, input);
    }

    /** 只返回本人主动设置的讲解偏好；未设置时返回分步引导默认值。 */
    @GetMapping("/preferences/explanation")
    public AgentDtos.ExplanationPreferenceView getExplanationPreference(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions) {
        return preferences.get(AgentIdentity.requireLearner(userId, permissions));
    }

    /** 使用 expectedRevision 修改本人偏好；稳定 clientRequestId 重试返回同一次结果。 */
    @PutMapping("/preferences/explanation")
    public AgentDtos.ExplanationPreferenceView putExplanationPreference(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody AgentDtos.UpdateExplanationPreferenceRequest input) {
        return preferences.update(AgentIdentity.requireLearner(userId, permissions), input);
    }
}
