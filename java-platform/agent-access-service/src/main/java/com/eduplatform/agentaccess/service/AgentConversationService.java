package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.AgentRepository.ConversationRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.EventRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.MessageRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import com.eduplatform.agentaccess.web.AgentDtos;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理会话、消息、单活动运行和公开事件持久化。
 *
 * <p>领域授权在写事务前完成，依赖失败不会留下空消息；写入时再次锁会话并检查幂等键与活动槽，
 * 多实例竞争最终由唯一约束裁决。恢复读取只返回已保存事件，绝不自动重启 Python。</p>
 */
@Service
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class AgentConversationService {
    private final AgentRepository repository;
    private final AuthorizedContextResolver contexts;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public record PreparedRun(RunRow run, MessageRow userMessage, AgentDtos.AuthorizedContext context,
            boolean replayed) {}

    /** 会话创建结果同时标记本次是否真正插入，供 HTTP 层区分 201 与幂等重放的 200。 */
    public record ConversationCreation(AgentDtos.Conversation conversation, boolean created) {}

    public AgentConversationService(AgentRepository repository, AuthorizedContextResolver contexts,
            ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.contexts = contexts;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 重启后的进程没有旧 HTTP 流句柄；补写唯一失败终态后释放活动槽，恢复接口仍有完整事实。 */
    @PostConstruct
    void recoverOrphanedRuns() {
        repository.findOrphanedRuns().forEach(run -> terminate(run.id(), AgentDtos.RunStatus.FAILED,
                "AGENT.RUNTIME_RESTARTED", "AI 服务重启，本次回答已中断"));
    }

    /** 创建或按 owner+clientRequestId 找回会话；同键不同内容返回 409。 */
    public AgentDtos.Conversation create(UUID owner, AgentDtos.CreateConversationRequest input) {
        return createWithStatus(owner, input).conversation();
    }

    /** 创建会话并返回是否为首次写入；并发幂等命中也明确视为重放。 */
    public ConversationCreation createWithStatus(UUID owner, AgentDtos.CreateConversationRequest input) {
        String title = input.title() == null ? "新的 AI 对话" : input.title().strip();
        String fingerprint = fingerprint(Map.of("entryPoint", input.entryPoint().name(), "title", title));
        ConversationRow existing = repository.findConversationByCreateRequest(owner, input.clientRequestId()).orElse(null);
        if (existing != null) return new ConversationCreation(sameConversation(existing, fingerprint), false);
        Instant now = Instant.now();
        ConversationRow row = new ConversationRow(UUID.randomUUID(), owner, input.clientRequestId(), fingerprint,
                input.entryPoint(), title, AgentDtos.ConversationStatus.ACTIVE, 0, 1, now, now, now);
        try {
            repository.insertConversation(row);
            return new ConversationCreation(conversation(row), true);
        } catch (DuplicateKeyException race) {
            return new ConversationCreation(sameConversation(repository
                    .findConversationByCreateRequest(owner, input.clientRequestId()).orElseThrow(() -> race),
                    fingerprint), false);
        }
    }

    public AgentDtos.Conversation get(UUID owner, UUID id) {
        return conversation(requireConversation(owner, id));
    }

    public AgentDtos.ConversationPage list(UUID owner, int page, int size) {
        return new AgentDtos.ConversationPage(repository.listConversations(owner, page, size).stream()
                .map(this::conversation).toList(), page, size, repository.countConversations(owner));
    }

    public AgentDtos.MessagePage messages(UUID owner, UUID conversationId, int page, int size) {
        requireConversation(owner, conversationId);
        return new AgentDtos.MessagePage(repository.listMessages(conversationId, page, size).stream()
                .map(this::message).toList(), page, size, repository.countMessages(conversationId));
    }

    /**
     * 先查幂等命中；新请求才调用领域授权器。授权完成后在事务中重新检查并创建一对消息和 run。
     */
    public PreparedRun prepareRun(UUID owner, UUID conversationId, AgentDtos.SendMessageRequest input,
            String traceId) {
        ConversationRow conversation = requireConversation(owner, conversationId);
        String requestFingerprint = fingerprint(input);
        PreparedRun existing = existingRun(conversation, input.clientRequestId(), requestFingerprint);
        if (existing != null) return existing;
        AgentDtos.AuthorizedContext context = contexts.resolve(owner, conversation.entryPoint(), input.context(), traceId);
        return transactions.execute(status -> createRunLocked(owner, conversationId, input, requestFingerprint,
                context, traceId));
    }

    /** 保存一个经内部协议校验的连续公开事件，并同步终态答案/部分检查点。 */
    public AgentDtos.StreamEvent appendRuntimeEvent(UUID runId, JsonNode internal) {
        RunRow run = findRunWithoutOwner(runId);
        long sequence = internal.path("sequence").asLong(-1);
        String eventType = internal.path("eventType").asText();
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(internal.path("emittedAt").asText());
        } catch (RuntimeException invalid) {
            throw protocolFailure();
        }
        if (!runId.toString().equals(internal.path("runId").asText())
                || !run.traceId().equals(internal.path("traceId").asText())
                || sequence != run.lastEventSequence() + 1 || !allowedEvent(eventType)
                || internal.path("payload").isMissingNode()) {
            throw protocolFailure();
        }
        JsonNode payload = internal.path("payload");
        rejectSecrets(payload);
        AgentDtos.RunStatus status = "FINAL_RESULT".equals(eventType)
                ? terminalStatus(payload.path("terminalStatus").asText())
                : sequence == 1 ? AgentDtos.RunStatus.ACCEPTED : AgentDtos.RunStatus.RUNNING;
        if ((sequence == 1) != "RUN_ACCEPTED".equals(eventType)) throw protocolFailure();
        validateCorePayload(run, eventType, payload, status);
        Long terminalSequence = "FINAL_RESULT".equals(eventType) ? sequence : null;
        String assistantContent = null;
        AgentDtos.Completeness completeness = AgentDtos.Completeness.PARTIAL;
        if ("PARTIAL_CHECKPOINT".equals(eventType)) assistantContent = payload.path("text").asText(null);
        if (terminalSequence != null) {
            assistantContent = payload.path("finalAnswer").asText("回答已中断");
            completeness = status == AgentDtos.RunStatus.COMPLETED
                    ? AgentDtos.Completeness.COMPLETE : AgentDtos.Completeness.PARTIAL;
        }
        AgentDtos.StreamEvent external = new AgentDtos.StreamEvent(eventType, runId, run.assistantMessageId(),
                sequence, occurredAt, payload);
        String json = write(external);
        RunRow finalRun = run;
        String persistedAssistantContent = assistantContent;
        AgentDtos.Completeness persistedCompleteness = completeness;
        boolean inserted = Boolean.TRUE.equals(transactions.execute(tx -> repository.appendEvent(runId,
                finalRun.lastEventSequence(), sequence, eventType, json, occurredAt, status, terminalSequence,
                persistedAssistantContent, persistedCompleteness)));
        if (!inserted) throw protocolFailure();
        return external;
    }

    /** 在代理失败、断线或取消时生成唯一安全终态；已有终态保持不变。 */
    public AgentDtos.StreamEvent terminate(UUID runId, AgentDtos.RunStatus status, String code, String message) {
        RunRow run = findRunWithoutOwner(runId);
        if (run.terminal()) return null;
        if (run.lastEventSequence() == 0) {
            JsonNode accepted = mapper.valueToTree(Map.of("eventType", "RUN_ACCEPTED", "runId", runId.toString(),
                    "sequence", 1, "traceId", run.traceId(), "emittedAt", Instant.now().toString(),
                    "payload", Map.of("conversationId", run.conversationId().toString(),
                            "messageId", run.userMessageId().toString(), "replayed", false)));
            appendRuntimeEvent(runId, accepted);
            run = findRunWithoutOwner(runId);
        }
        long sequence = run.lastEventSequence() + 1;
        JsonNode payload = mapper.valueToTree(Map.of("terminal", true, "terminalStatus", status.name(),
                "answerCompleteness", "INTERRUPTED", "interruptionNotice", "回答已中断",
                "error", Map.of("code", code, "message", message, "retryable", false,
                        "traceId", run.traceId())));
        JsonNode internal = mapper.valueToTree(Map.of("eventType", "FINAL_RESULT", "runId", runId.toString(),
                "sequence", sequence, "traceId", run.traceId(), "emittedAt", Instant.now().toString(),
                "payload", payload));
        return appendRuntimeEvent(runId, internal);
    }

    public AgentDtos.RunEventPage events(UUID owner, UUID runId, long afterSequence) {
        RunRow run = repository.findRun(owner, runId).orElseThrow(AgentConversationService::notFound);
        return new AgentDtos.RunEventPage(run.id(), run.status(), repository.listEvents(runId, afterSequence).stream()
                .map(this::event).toList());
    }

    public RunRow requireRun(UUID owner, UUID runId) {
        return repository.findRun(owner, runId).orElseThrow(AgentConversationService::notFound);
    }

    public AgentDtos.CancelRunResponse savedCancellation(UUID runId, UUID cancelRequestId) {
        return repository.findCancellation(runId, cancelRequestId)
                .map(json -> read(json, AgentDtos.CancelRunResponse.class)).orElse(null);
    }

    public void saveCancellation(UUID runId, AgentDtos.CancelRunRequest request,
            AgentDtos.CancelRunResponse response) {
        try {
            repository.insertCancellation(runId, request.cancelRequestId(), request.reason().name(),
                    write(response), Instant.now());
        } catch (DuplicateKeyException ignored) {
            // 同一取消请求并发到达时，首个结果是唯一事实；调用方随后读取即可。
        }
    }

    private PreparedRun createRunLocked(UUID owner, UUID conversationId, AgentDtos.SendMessageRequest input,
            String requestFingerprint, AgentDtos.AuthorizedContext context, String traceId) {
        ConversationRow locked = repository.lockConversation(owner, conversationId)
                .orElseThrow(AgentConversationService::notFound);
        PreparedRun existing = existingRun(locked, input.clientRequestId(), requestFingerprint);
        if (existing != null) return existing;
        if (repository.findActiveRun(conversationId).isPresent()) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.RUN_CONFLICT", "当前会话已有回答正在生成");
        }
        Instant now = Instant.now();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String contextJson = write(context.stableContext());
        MessageRow user = new MessageRow(userMessageId, conversationId, input.clientRequestId(), requestFingerprint,
                AgentDtos.MessageRole.USER, input.content().strip(), AgentDtos.Completeness.COMPLETE, contextJson,
                locked.nextMessageSequence(), runId, now);
        MessageRow assistant = new MessageRow(assistantMessageId, conversationId, null, null,
                AgentDtos.MessageRole.ASSISTANT, "", AgentDtos.Completeness.PARTIAL, contextJson,
                locked.nextMessageSequence() + 1, runId, now);
        RunRow run = new RunRow(runId, conversationId, userMessageId, assistantMessageId, owner, traceId,
                AgentDtos.RunStatus.ACCEPTED, 0L, null, null, null, now, now);
        repository.insertMessage(user);
        repository.insertMessage(assistant);
        repository.insertRun(run);
        repository.advanceConversation(conversationId, locked.nextMessageSequence() + 2, now);
        return new PreparedRun(run, user, context, false);
    }

    private PreparedRun existingRun(ConversationRow conversation, UUID clientRequestId, String fingerprint) {
        MessageRow existing = repository.findUserMessageByRequest(conversation.id(), clientRequestId).orElse(null);
        if (existing == null) return null;
        if (!fingerprint.equals(existing.requestFingerprint())) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.IDEMPOTENCY_CONFLICT",
                    "clientRequestId 已用于不同的消息请求");
        }
        RunRow run = repository.findRunByUserMessage(existing.id()).orElseThrow(() ->
                new AgentException(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT.STATE_INVALID", "AI 会话状态异常"));
        AgentDtos.MessageContext stable = existing.contextJson() == null ? null
                : read(existing.contextJson(), AgentDtos.MessageContext.class);
        return new PreparedRun(run, existing, new AgentDtos.AuthorizedContext(stable, null), true);
    }

    private AgentDtos.Conversation sameConversation(ConversationRow existing, String fingerprint) {
        if (!fingerprint.equals(existing.requestFingerprint())) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.IDEMPOTENCY_CONFLICT",
                    "clientRequestId 已用于不同的会话请求");
        }
        return conversation(existing);
    }

    private ConversationRow requireConversation(UUID owner, UUID id) {
        return repository.findConversation(owner, id).orElseThrow(AgentConversationService::notFound);
    }

    private RunRow findRunWithoutOwner(UUID runId) {
        // 事件写入只由持有内部执行句柄的协调器调用；owner 校验已在创建/取消入口完成。
        return repository.findRunInternal(runId).orElseThrow(AgentConversationService::notFound);
    }

    private AgentDtos.Conversation conversation(ConversationRow row) {
        UUID active = repository.findActiveRun(row.id()).map(RunRow::id).orElse(null);
        return new AgentDtos.Conversation(row.id(), row.entryPoint(), row.title(), row.status(), row.revision(), active,
                row.createdAt(), row.updatedAt(), row.lastMessageAt());
    }

    private AgentDtos.Message message(MessageRow row) {
        AgentDtos.MessageContext context = row.contextJson() == null ? null
                : read(row.contextJson(), AgentDtos.MessageContext.class);
        return new AgentDtos.Message(row.id(), row.conversationId(), row.runId(), row.role(), row.content(),
                row.completeness(), context, row.sequenceNo(), row.createdAt());
    }

    private AgentDtos.StreamEvent event(EventRow row) {
        return read(row.eventJson(), AgentDtos.StreamEvent.class);
    }

    private static boolean allowedEvent(String value) {
        return List.of("RUN_ACCEPTED", "STAGE_CHANGED", "TEXT_DELTA", "CLARIFICATION_REQUIRED", "REFERENCE",
                "PARTIAL_CHECKPOINT", "HEARTBEAT", "RUN_ERROR", "FINAL_RESULT").contains(value);
    }

    /** 校验运行身份和终态最关键的不变量；字段级完整 Schema 仍由 T13.1 Python 端和契约测试约束。 */
    private static void validateCorePayload(RunRow run, String eventType, JsonNode payload,
            AgentDtos.RunStatus status) {
        if ("RUN_ACCEPTED".equals(eventType)) {
            if (!run.conversationId().toString().equals(payload.path("conversationId").asText())
                    || !run.userMessageId().toString().equals(payload.path("messageId").asText())
                    || !payload.path("replayed").isBoolean()) throw protocolFailure();
            return;
        }
        if (!"FINAL_RESULT".equals(eventType)) return;
        if (!payload.path("terminal").asBoolean(false)) throw protocolFailure();
        String completeness = payload.path("answerCompleteness").asText();
        if (status == AgentDtos.RunStatus.COMPLETED) {
            if (!"COMPLETE".equals(completeness) || payload.path("finalAnswer").asText().isBlank()) {
                throw protocolFailure();
            }
        } else if (status == AgentDtos.RunStatus.DEGRADED || status == AgentDtos.RunStatus.LIMIT_REACHED) {
            if (!"SIMPLIFIED".equals(completeness) || payload.path("finalAnswer").asText().isBlank()) {
                throw protocolFailure();
            }
        } else if (!"INTERRUPTED".equals(completeness)
                || !"回答已中断".equals(payload.path("interruptionNotice").asText())) {
            throw protocolFailure();
        }
    }

    private static AgentDtos.RunStatus terminalStatus(String value) {
        try {
            AgentDtos.RunStatus status = AgentDtos.RunStatus.valueOf(value);
            if (List.of(AgentDtos.RunStatus.ACCEPTED, AgentDtos.RunStatus.RUNNING).contains(status)) throw protocolFailure();
            return status;
        } catch (IllegalArgumentException invalid) {
            throw protocolFailure();
        }
    }

    /** 递归拒绝任何看起来像秘密或临时 URL 的字段，防止 Python 误把私有状态送入公开事件。 */
    private static void rejectSecrets(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase();
                if (key.contains("prompt") || key.contains("token") || key.contains("secret")
                        || key.contains("temporaryaccess") || key.equals("url")) throw protocolFailure();
                rejectSecrets(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(AgentConversationService::rejectSecrets);
        }
    }

    private String fingerprint(Object input) {
        try {
            byte[] json = mapper.writeValueAsBytes(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create request fingerprint", failure);
        }
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize Agent state", failure); }
    }

    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (Exception failure) { throw new IllegalStateException("Cannot read Agent state", failure); }
    }

    private static AgentException notFound() {
        return new AgentException(HttpStatus.NOT_FOUND, "AGENT.NOT_FOUND", "AI 会话或运行不存在");
    }

    private static AgentException protocolFailure() {
        return new AgentException(HttpStatus.BAD_GATEWAY, "AGENT.PROTOCOL_VIOLATION", "AI 运行协议响应不合法");
    }
}
