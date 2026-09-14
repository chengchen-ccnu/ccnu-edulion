package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.AgentRepository.ConversationRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.EventRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.MessageRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import com.eduplatform.agentaccess.persistence.AgentRepository.ConversationCommandRow;
import com.eduplatform.agentaccess.service.RunGovernance.Lease;
import com.eduplatform.agentaccess.service.RunGovernance.Reservation;
import com.eduplatform.agentaccess.web.AgentDtos;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(token|signature|access[ _-]?key|api[ _-]?key|authorization|x-oss-[a-z-]+)\\s*"
                    + "(?:[=:]\\s*|\\s+)(?:bearer\\s+)?[^\\s,;]+");
    private final AgentRepository repository;
    private final AuthorizedContextResolver contexts;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final com.eduplatform.agentaccess.config.AgentProperties properties;
    private final com.eduplatform.agentaccess.config.AgentGovernanceProperties governanceProperties;
    private final RunGovernance governance;
    private final AgentDomainClient domains;

    public record PreparedRun(RunRow run, MessageRow userMessage, AgentDtos.AuthorizedContext context,
            List<AgentDtos.RecentMessage> recentMessages, Map<String, Object> requestedSkill,
            boolean replayed, Lease lease) {}

    /** 会话创建结果同时标记本次是否真正插入，供 HTTP 层区分 201 与幂等重放的 200。 */
    public record ConversationCreation(AgentDtos.Conversation conversation, boolean created) {}

    public AgentConversationService(AgentRepository repository, AuthorizedContextResolver contexts,
            ObjectMapper mapper, PlatformTransactionManager transactionManager,
            com.eduplatform.agentaccess.config.AgentProperties properties,
            com.eduplatform.agentaccess.config.AgentGovernanceProperties governanceProperties,
            RunGovernance governance, AgentDomainClient domains) {
        this.repository = repository;
        this.contexts = contexts;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.governanceProperties = governanceProperties;
        this.governance = governance;
        this.domains = domains;
    }

    /**
     * 启动时只收口已超过租约宽限且 Redis 明确没有有效租约的 run。
     * Redis 不可用或 owner 正在续租时保持 MySQL 活动态，避免新实例误杀其他实例。
     */
    @PostConstruct
    @Scheduled(fixedDelayString = "${platform.agent-governance.orphan-scan-interval:15s}")
    public void recoverOrphanedRuns() {
        Instant cutoff = Instant.now().minus(governanceProperties.leaseTtl().multipliedBy(2));
        for (RunRow run : repository.findOrphanedRuns()) {
            if (run.updatedAt().isAfter(cutoff)) continue;
            try {
                if (!governance.hasValidLease(run.id())) {
                    terminate(run.id(), AgentDtos.RunStatus.FAILED,
                            "AGENT.ORPHANED_RUN", "AI 服务中断，本次回答未完成");
                }
            } catch (AgentException unavailable) {
                return;
            } catch (RuntimeException raced) {
                // owner 刚好持久化了新事件时条件序号会拒绝恢复写；下一轮只读事实即可。
            }
        }
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

    /**
     * 修改本人 ACTIVE 会话标题。requestId 的首个响应保存在既有领域库的新命令表中，网络重试
     * 返回同一投影；同键不同参数和 revision 竞争均为明确 409，不覆盖其他窗口的修改。
     */
    public AgentDtos.Conversation rename(UUID owner, UUID conversationId,
            AgentDtos.RenameConversationRequest input) {
        String title = input.title().strip();
        if (title.isBlank() || title.codePointCount(0, title.length()) > 80
                || title.codePoints().anyMatch(Character::isISOControl)) {
            throw new AgentException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "AGENT.CONVERSATION_TITLE_INVALID", "会话标题不能为空、超过 80 个字符或包含控制字符");
        }
        String fingerprint = fingerprint(Map.of("type", "RENAME", "conversationId", conversationId,
                "title", title, "expectedRevision", input.expectedRevision()));
        return transactions.execute(status -> {
            ConversationCommandRow replay = checkedCommand(owner, input.requestId(), conversationId,
                    "RENAME", fingerprint);
            if (replay != null) return read(replay.responseJson(), AgentDtos.Conversation.class);
            ConversationRow locked = repository.lockConversation(owner, conversationId)
                    .orElseThrow(AgentConversationService::notFound);
            // 并发重试可能在等待会话行锁期间由首个请求写入命令事实；加锁后必须再次读取，
            // 否则同一稳定 requestId 会被误报为 revision 冲突。
            replay = checkedCommand(owner, input.requestId(), conversationId, "RENAME", fingerprint);
            if (replay != null) return read(replay.responseJson(), AgentDtos.Conversation.class);
            if (locked.revision() != input.expectedRevision()
                    || repository.renameConversation(conversationId, locked.revision(), title, Instant.now()) != 1) {
                throw new AgentException(HttpStatus.CONFLICT, "AGENT.CONVERSATION_REVISION_CONFLICT",
                        "会话 revision 已变化，请重新加载后重试");
            }
            AgentDtos.Conversation result = conversation(repository.findConversation(owner, conversationId).orElseThrow());
            repository.insertConversationCommand(new ConversationCommandRow(owner, input.requestId(), conversationId,
                    "RENAME", fingerprint, write(result), Instant.now()));
            return result;
        });
    }

    /**
     * 将本人静止的 ACTIVE 会话归档。活动 run 返回 409，调用方必须先使用现有取消链路；归档不删除
     * 消息、运行、Skill 审计、用量或记忆，后续 owner 查询也统一按安全 404 收口。
     */
    public void archive(UUID owner, UUID conversationId, AgentDtos.ArchiveConversationRequest input) {
        String fingerprint = fingerprint(Map.of("type", "ARCHIVE", "conversationId", conversationId,
                "expectedRevision", input.expectedRevision()));
        transactions.executeWithoutResult(status -> {
            ConversationCommandRow replay = checkedCommand(owner, input.requestId(), conversationId,
                    "ARCHIVE", fingerprint);
            if (replay != null) return;
            ConversationRow locked = repository.lockConversation(owner, conversationId).orElse(null);
            if (locked == null) {
                replay = checkedCommand(owner, input.requestId(), conversationId, "ARCHIVE", fingerprint);
                if (replay != null) return;
                throw notFound();
            }
            if (repository.findActiveRun(conversationId).isPresent()) {
                throw new AgentException(HttpStatus.CONFLICT, "AGENT.CONVERSATION_RUN_ACTIVE",
                        "回答仍在生成，请先停止生成再删除对话");
            }
            if (locked.revision() != input.expectedRevision()
                    || repository.archiveConversation(conversationId, locked.revision(), Instant.now()) != 1) {
                throw new AgentException(HttpStatus.CONFLICT, "AGENT.CONVERSATION_REVISION_CONFLICT",
                        "会话 revision 已变化，请重新加载后重试");
            }
            repository.insertConversationCommand(new ConversationCommandRow(owner, input.requestId(), conversationId,
                    "ARCHIVE", fingerprint, null, Instant.now()));
        });
    }

    private ConversationCommandRow checkedCommand(UUID owner, UUID requestId, UUID conversationId,
            String type, String fingerprint) {
        ConversationCommandRow row = repository.findConversationCommand(owner, requestId).orElse(null);
        if (row != null && (!row.conversationId().equals(conversationId) || !row.commandType().equals(type)
                || !row.requestFingerprint().equals(fingerprint))) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.IDEMPOTENCY_CONFLICT",
                    "requestId 已用于不同的会话命令");
        }
        return row;
    }

    public AgentDtos.MessagePage messages(UUID owner, UUID conversationId, int page, int size) {
        return messages(owner, conversationId, page, size, "message-page");
    }

    /**
     * 读取本人消息页并为悬浮会话批量补充精确历史题目版本。
     *
     * <p>一页先按 questionId+questionVersionId 去重，再进行一次最多 100 项的 Learning 调用。
     * 单项不存在或依赖不可用时显式返回 UNAVAILABLE，绝不改查题库当前版本，也不影响其他消息读取。</p>
     */
    public AgentDtos.MessagePage messages(UUID owner, UUID conversationId, int page, int size, String traceId) {
        ConversationRow conversation = requireConversation(owner, conversationId);
        List<MessageRow> rows = repository.listMessages(conversationId, page, size);
        Map<String, AgentDtos.QuestionContextQuestion> questions = conversation.entryPoint()
                == AgentDtos.EntryPoint.PRACTICE_FLOATING ? resolveQuestionProjections(owner, rows, traceId) : Map.of();
        return new AgentDtos.MessagePage(rows.stream().map(row -> message(row, conversation.entryPoint(), questions))
                .toList(), page, size, repository.countMessages(conversationId));
    }

    /** 为本人悬浮会话的已保存 USER 消息题图申请短期访问，题目版本不接受浏览器覆盖。 */
    public AgentDtos.MediaAccessGrant authorizeQuestionMedia(UUID owner, UUID conversationId, UUID messageId,
            UUID mediaId, AgentDtos.QuestionMediaAccessRequest input, String traceId) {
        ConversationRow conversation = requireConversation(owner, conversationId);
        if (conversation.entryPoint() != AgentDtos.EntryPoint.PRACTICE_FLOATING) throw notFound();
        MessageRow message = repository.findUserMessage(owner, conversationId, messageId)
                .orElseThrow(AgentConversationService::notFound);
        AgentDtos.MessageContext context = message.contextJson() == null ? null
                : read(message.contextJson(), AgentDtos.MessageContext.class);
        if (context == null || context.questionId() == null || context.questionVersionId() == null) throw notFound();
        var grant = domains.authorizeQuestionVersionMedia(owner, context.questionId(), context.questionVersionId(),
                mediaId, input.variant(), input.disposition().name(), traceId);
        return new AgentDtos.MediaAccessGrant(grant.mediaId(), grant.mimeType(), grant.sizeBytes(), grant.method(),
                grant.url(), grant.headers(), grant.expiresAt());
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
        Reservation reservation = governance.reserve(owner, conversationId, input.clientRequestId());
        if (!reservation.acquired()) return awaitIdempotentWinner(conversation, input, requestFingerprint);
        AgentDtos.AuthorizedContext context;
        try {
            context = contexts.resolve(owner, conversation.entryPoint(), input.context(), traceId);
        } catch (RuntimeException failure) {
            governance.rollback(reservation);
            throw failure;
        }
        PreparedRun prepared;
        try {
            prepared = transactions.execute(status -> createRunLocked(owner, conversationId, input,
                    requestFingerprint, context, traceId));
        } catch (RuntimeException failure) {
            governance.rollback(reservation);
            throw failure;
        }
        if (prepared.replayed()) {
            governance.rollback(reservation);
            return prepared;
        }
        try {
            Lease lease = governance.bind(reservation, prepared.run());
            return new PreparedRun(prepared.run(), prepared.userMessage(), prepared.context(),
                    prepared.recentMessages(), prepared.requestedSkill(), false, lease);
        } catch (RuntimeException failure) {
            governance.rollback(reservation);
            terminate(prepared.run().id(), AgentDtos.RunStatus.FAILED,
                    "AGENT.GOVERNANCE_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试");
            throw failure;
        }
    }

    /**
     * 从当前会话最后一条有效用户消息启动一次新 run。请求不接受正文或上下文，始终复用已保存快照；
     * 同 clientRequestId 重试只恢复同一 run，且继续经过既有 Redis 额度、租约和 SSE 协调器。
     */
    public PreparedRun prepareRegeneration(UUID owner, UUID conversationId, UUID messageId,
            AgentDtos.RegenerateMessageRequest input, String traceId) {
        ConversationRow conversation = requireConversation(owner, conversationId);
        PreparedRun existing = existingRegeneration(conversation, messageId, input.clientRequestId());
        if (existing != null) return existing;
        MessageRow original = requireLastUserMessage(owner, conversationId, messageId);
        if (repository.findActiveRun(conversationId).isPresent()) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.RUN_CONFLICT", "当前会话已有回答正在生成");
        }
        UUID governanceRequest = UUID.nameUUIDFromBytes(
                ("regeneration:" + input.clientRequestId()).getBytes(StandardCharsets.UTF_8));
        Reservation reservation = governance.reserve(owner, conversationId, governanceRequest);
        if (!reservation.acquired()) {
            return awaitRegenerationWinner(conversation, messageId, input.clientRequestId());
        }
        AgentDtos.AuthorizedContext context;
        try {
            context = reauthorizeSavedContext(owner, conversation.entryPoint(), original, traceId);
        } catch (RuntimeException failure) {
            governance.rollback(reservation);
            throw failure;
        }
        PreparedRun prepared;
        try {
            prepared = transactions.execute(status -> createRegenerationLocked(owner, conversationId, messageId,
                    input.clientRequestId(), context, traceId));
        } catch (RuntimeException failure) {
            governance.rollback(reservation);
            throw failure;
        }
        if (prepared.replayed()) {
            governance.rollback(reservation);
            return prepared;
        }
        try {
            Lease lease = governance.bind(reservation, prepared.run());
            return new PreparedRun(prepared.run(), prepared.userMessage(), prepared.context(),
                    prepared.recentMessages(), prepared.requestedSkill(), false, lease);
        } catch (RuntimeException failure) {
            governance.rollback(reservation);
            terminate(prepared.run().id(), AgentDtos.RunStatus.FAILED,
                    "AGENT.GOVERNANCE_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试");
            throw failure;
        }
    }

    /** 保存一个经内部协议校验的连续公开事件，并同步终态答案/部分检查点。 */
    public AgentDtos.StreamEvent appendRuntimeEvent(UUID runId, JsonNode internal) {
        RunRow run = findRunWithoutOwner(runId);
        AgentStreamEventValidator.validate(internal, runId, run.traceId());
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
                || sequence != run.lastEventSequence() + 1) {
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
                AgentDtos.MessageRole.USER, input.content().strip(), AgentDtos.Completeness.COMPLETE,
                AgentDtos.ContentState.RETAINED, contextJson, locked.nextMessageSequence(), runId, now);
        MessageRow assistant = new MessageRow(assistantMessageId, conversationId, null, null,
                AgentDtos.MessageRole.ASSISTANT, "", AgentDtos.Completeness.PARTIAL,
                AgentDtos.ContentState.RETAINED, contextJson, locked.nextMessageSequence() + 1, runId, now);
        RunRow run = new RunRow(runId, conversationId, userMessageId, assistantMessageId, owner, traceId,
                AgentDtos.RunStatus.ACCEPTED, 0L, null, null, null, now, now);
        repository.insertMessage(user);
        repository.insertMessage(assistant);
        if (input.context() != null && input.context().aiQuestionMediaIds() != null) {
            for (UUID mediaId : new LinkedHashSet<>(input.context().aiQuestionMediaIds())) {
                repository.insertMessageMediaReference(userMessageId, mediaId, now);
                repository.insertMediaOutbox(mediaReferenceEvent("MediaReferenceBound", mediaId,
                        userMessageId, traceId, now), now);
            }
        }
        repository.insertRun(run);
        repository.advanceConversation(conversationId, locked.nextMessageSequence() + 2, now);
        Map<String, Object> requestedSkill = input.selectedCapability() == null ? null
                : Map.of("skillName", "public-practice-generation", "selectionSource", "USER_EXPLICIT");
        Map<String, Object> trusted = new LinkedHashMap<>(context.trustedContext());
        previousSkillOffer(conversationId, user.sequenceNo()).ifPresent(offer -> trusted.put("activeSkillOffer", offer));
        AgentDtos.AuthorizedContext runtimeContext = new AgentDtos.AuthorizedContext(
                context.stableContext(), Map.copyOf(trusted));
        return new PreparedRun(run, user, runtimeContext, recentMessages(conversationId, user.sequenceNo()),
                requestedSkill, false, null);
    }

    /** 会话锁内再次确认最后一问和活动槽，随后仅追加 assistant 消息与新 run，旧记录不修改。 */
    private PreparedRun createRegenerationLocked(UUID owner, UUID conversationId, UUID messageId,
            UUID clientRequestId, AgentDtos.AuthorizedContext context, String traceId) {
        ConversationRow locked = repository.lockConversation(owner, conversationId)
                .orElseThrow(AgentConversationService::notFound);
        PreparedRun existing = existingRegeneration(locked, messageId, clientRequestId);
        if (existing != null) return existing;
        MessageRow original = requireLastUserMessage(owner, conversationId, messageId);
        if (repository.findActiveRun(conversationId).isPresent()) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.RUN_CONFLICT", "当前会话已有回答正在生成");
        }
        Instant now = Instant.now();
        UUID assistantMessageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        MessageRow assistant = new MessageRow(assistantMessageId, conversationId, null, null,
                AgentDtos.MessageRole.ASSISTANT, "", AgentDtos.Completeness.PARTIAL,
                AgentDtos.ContentState.RETAINED, original.contextJson(), locked.nextMessageSequence(), runId, now);
        RunRow run = new RunRow(runId, conversationId, original.id(), assistantMessageId, owner, traceId,
                AgentDtos.RunStatus.ACCEPTED, 0L, null, null, null, now, now);
        repository.insertMessage(assistant);
        repository.insertRun(run, clientRequestId);
        repository.advanceConversation(conversationId, locked.nextMessageSequence() + 1, now);
        return new PreparedRun(run, original, context, recentMessages(conversationId, original.sequenceNo()),
                null, false, null);
    }

    private PreparedRun existingRegeneration(ConversationRow conversation, UUID requestedMessageId,
            UUID clientRequestId) {
        RunRow run = repository.findRunByRegenerationRequest(conversation.id(), clientRequestId).orElse(null);
        if (run == null) return null;
        if (!run.userMessageId().equals(requestedMessageId)) {
            throw new AgentException(HttpStatus.CONFLICT, "AGENT.IDEMPOTENCY_CONFLICT",
                    "clientRequestId 已用于不同的重新生成请求");
        }
        MessageRow original = repository.findUserMessage(conversation.ownerUserId(), conversation.id(),
                run.userMessageId()).orElseThrow(AgentConversationService::notFound);
        AgentDtos.MessageContext stable = original.contextJson() == null ? null
                : read(original.contextJson(), AgentDtos.MessageContext.class);
        return new PreparedRun(run, original, new AgentDtos.AuthorizedContext(stable, null), List.of(), null, true, null);
    }

    /** 重新校验原快照中的稳定 ID；当前领域投影若已变化则拒绝，而不是偷换为最新页面上下文。 */
    private AgentDtos.AuthorizedContext reauthorizeSavedContext(UUID owner, AgentDtos.EntryPoint entryPoint,
            MessageRow original, String traceId) {
        AgentDtos.MessageContext stable = original.contextJson() == null ? null
                : read(original.contextJson(), AgentDtos.MessageContext.class);
        if (stable == null) return contexts.resolve(owner, entryPoint, null, traceId);
        List<UUID> aiMedia = repository.boundMessageMedia(original.id()).stream()
                .map(AgentRepository.MediaReferenceRow::mediaId).toList();
        List<UUID> questionMedia = stable.mediaIds() == null ? List.of() : stable.mediaIds().stream()
                .filter(id -> !aiMedia.contains(id)).toList();
        AgentDtos.AuthorizedContext refreshed = contexts.resolveSaved(owner, entryPoint, stable,
                questionMedia, aiMedia, traceId);
        if (!sameStableContext(stable, refreshed.stableContext())) throw notFound();
        return new AgentDtos.AuthorizedContext(stable, refreshed.trustedContext());
    }

    private static boolean sameStableContext(AgentDtos.MessageContext expected, AgentDtos.MessageContext actual) {
        return actual != null && java.util.Objects.equals(expected.questionId(), actual.questionId())
                && java.util.Objects.equals(expected.questionVersionId(), actual.questionVersionId())
                && java.util.Objects.equals(expected.courseId(), actual.courseId())
                && java.util.Objects.equals(expected.courseVersionId(), actual.courseVersionId())
                && java.util.Objects.equals(expected.lessonId(), actual.lessonId())
                && new LinkedHashSet<>(expected.mediaIds() == null ? List.of() : expected.mediaIds())
                        .equals(new LinkedHashSet<>(actual.mediaIds() == null ? List.of() : actual.mediaIds()));
    }

    private MessageRow requireLastUserMessage(UUID owner, UUID conversationId, UUID messageId) {
        MessageRow selected = repository.findUserMessage(owner, conversationId, messageId)
                .orElseThrow(AgentConversationService::notFound);
        MessageRow last = repository.findLastRetainedUserMessage(conversationId)
                .orElseThrow(AgentConversationService::notFound);
        if (!selected.id().equals(last.id()) || selected.content().isBlank()) throw notFound();
        return selected;
    }

    /** 相同重新生成请求在另一实例创建时有界等待 MySQL 唯一事实，不第二次占用额度。 */
    private PreparedRun awaitRegenerationWinner(ConversationRow conversation, UUID messageId,
            UUID clientRequestId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (Instant.now().isBefore(deadline)) {
            PreparedRun existing = existingRegeneration(conversation, messageId, clientRequestId);
            if (existing != null) return existing;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AgentException(HttpStatus.CONFLICT, "AGENT.REQUEST_IN_PROGRESS", "相同请求正在处理中，请稍后重试");
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
        return new PreparedRun(run, existing, new AgentDtos.AuthorizedContext(stable, null), List.of(), null, true, null);
    }

    /** 同一幂等键正在另一线程授权时有界等待其 MySQL 事实；超时返回冲突而不再占额度。 */
    private PreparedRun awaitIdempotentWinner(ConversationRow conversation, AgentDtos.SendMessageRequest input,
            String fingerprint) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (Instant.now().isBefore(deadline)) {
            PreparedRun existing = existingRun(conversation, input.clientRequestId(), fingerprint);
            if (existing != null) return existing;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AgentException(HttpStatus.CONFLICT, "AGENT.REQUEST_IN_PROGRESS", "相同请求正在处理中，请稍后重试");
    }

    /**
     * 从 Agent Access 已持久化消息构造最近完整轮次。输入是当前 user 的 sequenceNo，输出按升序；
     * 当前消息及其占位助手消息不会进入窗口，URL/Token 会在跨进程前再次删除。
     */
    private List<AgentDtos.RecentMessage> recentMessages(UUID conversationId, long currentSequence) {
        int maxMessages = Math.max(0, Math.min(24, properties.recentMessageMaxTurns() * 2));
        int maxChars = Math.max(0, Math.min(32_000, properties.recentMessageMaxChars()));
        if (maxMessages == 0 || maxChars == 0) return List.of();
        List<AgentDtos.RecentMessage> newest = new ArrayList<>();
        int chars = 0;
        for (MessageRow row : repository.listMessagesBefore(conversationId, currentSequence, maxMessages)) {
            String safe = safeHistoryContent(row.content());
            if (safe.isBlank()) continue;
            int remaining = maxChars - chars;
            if (remaining <= 0) break;
            if (safe.length() > remaining) safe = safe.substring(0, remaining);
            newest.add(new AgentDtos.RecentMessage(row.id(), row.role(), safe, row.completeness(), row.sequenceNo()));
            chars += safe.length();
        }
        Collections.reverse(newest);
        return List.copyOf(newest);
    }

    /** 将上一轮浏览器安全 offer 重新映射为内部受信 Skill 名；普通文字和更早历史均不会参与。 */
    private java.util.Optional<Map<String, Object>> previousSkillOffer(UUID conversationId, long currentSequence) {
        EventRow row = repository.previousFinalEvent(conversationId, currentSequence).orElse(null);
        if (row == null) return java.util.Optional.empty();
        try {
            JsonNode event = mapper.readTree(row.eventJson());
            JsonNode offer = event.path("payload").path("skillOffer");
            if (!"GENERATE_AUTONOMOUS_PRACTICE".equals(offer.path("capability").asText())) {
                return java.util.Optional.empty();
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("offerId", UUID.fromString(offer.path("offerId").asText()));
            value.put("skillName", "public-practice-generation");
            value.put("topic", offer.path("topic").asText());
            if (offer.has("difficulty")) value.put("difficulty", offer.path("difficulty").asText());
            if (offer.has("questionCount")) value.put("questionCount", offer.path("questionCount").asInt());
            value.put("offeredAfterMessageId", UUID.fromString(event.path("messageId").asText()));
            return java.util.Optional.of(Map.copyOf(value));
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty();
        }
    }

    private static String safeHistoryContent(String content) {
        String safe = URL.matcher(content == null ? "" : content).replaceAll("[已移除临时地址]");
        safe = SECRET.matcher(safe).replaceAll("[已移除秘密]");
        return safe.replace("rawToolResult", "[已移除工具原始结果]");
    }

    /** 构造既有 Media v1 引用信封；正文只含稳定 ID，不含图片 URL、对象 Key 或凭据。 */
    private AgentRepository.MediaOutboxRow mediaReferenceEvent(String eventType, UUID mediaId,
            UUID messageId, String traceId, Instant now) {
        UUID eventId = UUID.randomUUID();
        String routing = "MediaReferenceBound".equals(eventType)
                ? "media.reference.bound.v1" : "media.reference.released.v1";
        Map<String, Object> payload = Map.of("mediaId", mediaId, "ownerService", "agent-access",
                "referenceType", "AI_MESSAGE_IMAGE", "referenceId", messageId.toString());
        String envelope = write(Map.of("eventId", eventId, "eventType", eventType, "eventVersion", 1,
                "aggregateId", mediaId, "occurredAt", now, "traceId", traceId, "payload", payload));
        return new AgentRepository.MediaOutboxRow(eventId, eventType, mediaId, "edu.media.events.v1",
                routing, traceId, envelope, 0);
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
        return message(row, null, Map.of());
    }

    private AgentDtos.Message message(MessageRow row, AgentDtos.EntryPoint entryPoint,
            Map<String, AgentDtos.QuestionContextQuestion> questions) {
        AgentDtos.MessageContext context = row.contextJson() == null ? null
                : read(row.contextJson(), AgentDtos.MessageContext.class);
        AgentDtos.QuestionContextProjection projection = null;
        if (entryPoint == AgentDtos.EntryPoint.PRACTICE_FLOATING
                && row.role() == AgentDtos.MessageRole.USER && context != null
                && context.questionId() != null && context.questionVersionId() != null) {
            var question = questions.get(questionKey(context.questionId(), context.questionVersionId()));
            projection = new AgentDtos.QuestionContextProjection(question == null
                    ? AgentDtos.QuestionProjectionStatus.UNAVAILABLE : AgentDtos.QuestionProjectionStatus.AVAILABLE,
                    question);
        }
        return new AgentDtos.Message(row.id(), row.conversationId(), row.runId(), row.role(), row.content(),
                row.completeness(), row.contentState(), context, projection, row.sequenceNo(), row.createdAt());
    }

    private Map<String, AgentDtos.QuestionContextQuestion> resolveQuestionProjections(UUID owner,
            List<MessageRow> rows, String traceId) {
        var keys = new java.util.LinkedHashMap<String, AgentInternalDtos.QuestionVersionKey>();
        for (MessageRow row : rows) {
            if (row.role() != AgentDtos.MessageRole.USER || row.contextJson() == null) continue;
            AgentDtos.MessageContext context = read(row.contextJson(), AgentDtos.MessageContext.class);
            if (context.questionId() == null || context.questionVersionId() == null) continue;
            keys.putIfAbsent(questionKey(context.questionId(), context.questionVersionId()),
                    new AgentInternalDtos.QuestionVersionKey(context.questionId(), context.questionVersionId()));
        }
        if (keys.isEmpty()) return Map.of();
        try {
            Map<String, AgentDtos.QuestionContextQuestion> result = new java.util.LinkedHashMap<>();
            for (var value : domains.projectQuestionVersions(owner, List.copyOf(keys.values()), traceId)) {
                if (value == null || value.questionId() == null || value.questionVersionId() == null) continue;
                String key = questionKey(value.questionId(), value.questionVersionId());
                if (!keys.containsKey(key)) continue;
                result.put(key, question(value));
            }
            return Map.copyOf(result);
        } catch (AgentException unavailable) {
            if (unavailable.status() == HttpStatus.SERVICE_UNAVAILABLE) return Map.of();
            throw unavailable;
        }
    }

    private static AgentDtos.QuestionContextQuestion question(
            AgentInternalDtos.QuestionVersionProjection value) {
        return new AgentDtos.QuestionContextQuestion(value.questionId(), value.questionVersionId(), value.type(),
                value.stem(), options(value.options()), media(value.media()), value.children().stream().map(child ->
                new AgentDtos.QuestionContextChild(child.questionId(), child.questionVersionId(), child.sequence(),
                        child.type(), child.stem(), options(child.options()), media(child.media()))).toList());
    }

    private static List<AgentDtos.QuestionContextOption> options(List<AgentInternalDtos.QuestionOption> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> new AgentDtos.QuestionContextOption(value.key(), value.content())).toList();
    }

    private static List<AgentDtos.QuestionContextMedia> media(List<AgentInternalDtos.QuestionMedia> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> new AgentDtos.QuestionContextMedia(value.id(), value.mediaId(), value.purpose(),
                        value.sequence())).toList();
    }

    private static String questionKey(UUID questionId, UUID questionVersionId) {
        return questionId + ":" + questionVersionId;
    }

    private AgentDtos.StreamEvent event(EventRow row) {
        return read(row.eventJson(), AgentDtos.StreamEvent.class);
    }

    /** 在字段级白名单后再次校验与数据库 run 绑定的身份和终态不变量。 */
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
        } else if (node.isTextual()) {
            String value = node.asText();
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            if (URL.matcher(value).find() || SECRET.matcher(value).find()
                    || normalized.contains("rawtoolresult") || normalized.contains("chainofthought")
                    || normalized.contains("chain of thought") || normalized.contains("system prompt")
                    || normalized.contains("prompt=")) {
                throw protocolFailure();
            }
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
