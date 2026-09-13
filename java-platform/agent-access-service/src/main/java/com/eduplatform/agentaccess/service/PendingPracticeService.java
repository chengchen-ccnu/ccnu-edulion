package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import com.eduplatform.agentaccess.persistence.PendingPracticeRepository;
import com.eduplatform.agentaccess.persistence.PendingPracticeRepository.Row;
import com.eduplatform.agentaccess.web.AgentDtos;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 管理候选提案到自主练习结果的有限状态机。MySQL 是动作事实源，Redis 不参与确认、取消或恢复；
 * 跨 Learning 调用使用 actionId 幂等，超时未知时先查询而不把动作改成可重建的失败状态。
 */
@Service
public class PendingPracticeService {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private final PendingPracticeRepository repository;
    private final AgentRepository agents;
    private final AgentDomainClient domains;
    private final ObjectMapper mapper;

    public PendingPracticeService(PendingPracticeRepository repository, AgentRepository agents,
            AgentDomainClient domains, ObjectMapper mapper) {
        this.repository = repository; this.agents = agents; this.domains = domains; this.mapper = mapper;
    }

    /** Python FINAL_RESULT 进入持久事件前先经 Learning 复核并创建新动作；同 run 重放返回同一 action。 */
    @Transactional
    public AgentDtos.PendingPracticeAction acceptProposal(RunRow run, JsonNode proposal) {
        Row replay = repository.byRun(run.id()).orElse(null);
        if (replay != null) return projection(expireIfNeeded(replay));
        ParsedProposal parsed = parse(proposal);
        var criteria = new AgentInternalDtos.PracticeCriteria(parsed.topic(), parsed.difficulty(), parsed.requestedCount());
        var verified = domains.validatePracticeCandidates(run.ownerUserId(), criteria, parsed.candidates(), run.traceId());
        List<AgentDtos.PracticeCandidateQuestion> candidates = verified.candidates().stream()
                .limit(parsed.requestedCount()).map(value -> new AgentDtos.PracticeCandidateQuestion(
                        value.questionId(), value.questionVersionId(), value.questionType(), value.difficulty())).toList();
        if (agents.lockConversation(run.ownerUserId(), run.conversationId()).isEmpty()) throw notFound();
        Row previous = repository.current(run.ownerUserId(), run.conversationId()).map(this::expireIfNeeded).orElse(null);
        Instant now = Instant.now();
        if (previous != null && previous.status() == AgentDtos.PendingPracticeActionStatus.PENDING) {
            repository.invalidatePending(run.conversationId(), now);
            repository.audit(previous.id(), previous.ownerUserId(), null, "SUPERSEDED",
                    "PENDING", "CANCELLED", run.traceId(), now);
        }
        UUID actionId = UUID.randomUUID();
        AgentDtos.PracticeGenerationCriteria externalCriteria = new AgentDtos.PracticeGenerationCriteria(
                parsed.topic(), parsed.difficulty(), parsed.requestedCount());
        Row created = new Row(actionId, run.conversationId(), run.ownerUserId(), run.id(),
                write(externalCriteria), write(candidates), parsed.requestedCount(), candidates.size(),
                AgentDtos.PendingPracticeActionStatus.PENDING, 0, now.plus(DEFAULT_TTL), null, null,
                null, null, null, null, now, now);
        repository.insert(created);
        repository.audit(actionId, run.ownerUserId(), null, "CREATED", null, "PENDING", run.traceId(), now);
        return projection(created);
    }

    public AgentDtos.PendingPracticeAction get(UUID owner, UUID actionId) {
        return projection(expireIfNeeded(repository.owned(owner, actionId).orElseThrow(PendingPracticeService::notFound)));
    }

    public AgentDtos.PendingPracticeAction current(UUID owner, UUID conversationId) {
        if (agents.findConversation(owner, conversationId).isEmpty()) throw notFound();
        Row row = repository.current(owner, conversationId).orElseThrow(PendingPracticeService::notFound);
        row = expireIfNeeded(row);
        if (row.status() != AgentDtos.PendingPracticeActionStatus.PENDING) throw notFound();
        return projection(row);
    }

    /**
     * 把 Python 私有提案/自然语言控制转换为浏览器安全投影。输入事件先由严格协议校验器检查；
     * 输出移除 Python 字段，只增加 Java 已持久化并经过 owner/领域复核的 pending action。
     */
    public JsonNode prepareFinalEvent(RunRow run, UUID requestId, JsonNode internal) {
        if (!"FINAL_RESULT".equals(internal.path("eventType").asText())) return internal;
        ObjectNode event = (ObjectNode) internal.deepCopy();
        ObjectNode payload = (ObjectNode) event.path("payload");
        AgentDtos.PendingPracticeAction action = null;
        if (payload.has("practiceProposal")) {
            action = acceptProposal(run, payload.remove("practiceProposal"));
        } else if (payload.has("actionControl")) {
            String control = payload.remove("actionControl").asText();
            try {
                action = "CONFIRM_CURRENT".equals(control)
                        ? confirmCurrent(run.ownerUserId(), run.conversationId(), requestId, run.traceId())
                        : cancelCurrent(run.ownerUserId(), run.conversationId(), requestId, run.traceId());
            } catch (AgentException rejected) {
                payload.put("finalAnswer", rejected.getMessage());
            }
            if (action == null) payload.put("finalAnswer", "当前会话没有唯一、有效的待确认动作，请先生成新的组题方案。");
        }
        if (action != null) payload.set("pendingPracticeAction", mapper.valueToTree(action));
        return event;
    }

    /** 显式确认使用 revision CAS；重复确认与未知结果恢复都返回 Learning 中相同 actionId 的结果。 */
    public AgentDtos.PendingPracticeAction confirm(UUID owner, UUID actionId,
            AgentDtos.ConfirmPendingPracticeActionRequest request, String traceId) {
        return confirm(owner, actionId, request.requestId(), request.expectedRevision(), traceId);
    }

    public AgentDtos.PendingPracticeAction confirmCurrent(UUID owner, UUID conversationId, UUID requestId,
            String traceId) {
        Row current = repository.current(owner, conversationId).orElse(null);
        if (current == null) return null;
        return confirm(owner, current.id(), requestId, current.revision(), traceId);
    }

    private AgentDtos.PendingPracticeAction confirm(UUID owner, UUID actionId, UUID requestId,
            long expectedRevision, String traceId) {
        Row row = expireIfNeeded(repository.owned(owner, actionId).orElseThrow(PendingPracticeService::notFound));
        if (row.status() == AgentDtos.PendingPracticeActionStatus.SUCCEEDED) return projection(row);
        if (row.status() == AgentDtos.PendingPracticeActionStatus.EXECUTING) return recover(row, traceId);
        if (row.status() != AgentDtos.PendingPracticeActionStatus.PENDING) {
            throw conflict("AGENT.PENDING_ACTION_NOT_CONFIRMABLE", "待确认动作已取消、过期或失败");
        }
        if (row.actualCount() == 0) {
            throw new AgentException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "AGENT.PRACTICE_CANDIDATES_EMPTY", "没有可创建练习的有效题目");
        }
        if (row.revision() != expectedRevision || repository.confirm(actionId, expectedRevision, requestId, Instant.now()) == 0) {
            throw conflict("AGENT.PENDING_ACTION_REVISION_CONFLICT", "待确认动作 revision 已变化，请刷新后重试");
        }
        repository.audit(actionId, owner, requestId, "CONFIRMED", "PENDING", "EXECUTING", traceId, Instant.now());
        row = repository.owned(owner, actionId).orElseThrow();
        try {
            AgentInternalDtos.AutonomousPracticeResult created = domains.createAutonomousPractice(actionId, owner,
                    internalCriteria(row), candidateKeys(row), traceId);
            return succeed(row, created, traceId);
        } catch (AgentException unknown) {
            AgentInternalDtos.AutonomousPracticeResult recovered = domains.findAutonomousPractice(actionId, owner, traceId);
            if (recovered != null) return succeed(repository.owned(owner, actionId).orElseThrow(), recovered, traceId);
            if (unknown.status() == HttpStatus.UNPROCESSABLE_ENTITY) {
                Row latest = repository.owned(owner, actionId).orElseThrow();
                repository.fail(actionId, latest.revision(), unknown.code(), Instant.now());
            }
            throw unknown;
        }
    }

    /** 取消只允许 PENDING→CANCELLED；相同 requestId 重试返回同一动作且不删除任何领域事实。 */
    public AgentDtos.PendingPracticeAction cancel(UUID owner, UUID actionId,
            AgentDtos.CancelPendingPracticeActionRequest request, String traceId) {
        Row row = expireIfNeeded(repository.owned(owner, actionId).orElseThrow(PendingPracticeService::notFound));
        if (row.status() == AgentDtos.PendingPracticeActionStatus.CANCELLED
                && request.requestId().equals(row.cancellationRequestId())) return projection(row);
        if (row.status() != AgentDtos.PendingPracticeActionStatus.PENDING) {
            throw conflict("AGENT.PENDING_ACTION_NOT_CANCELLABLE", "待确认动作已执行、取消或过期");
        }
        if (row.revision() != request.expectedRevision()
                || repository.cancel(actionId, row.revision(), request.requestId(), Instant.now()) == 0) {
            throw conflict("AGENT.PENDING_ACTION_REVISION_CONFLICT", "待确认动作 revision 已变化，请刷新后重试");
        }
        repository.audit(actionId, owner, request.requestId(), "CANCELLED", "PENDING", "CANCELLED",
                traceId, Instant.now());
        return projection(repository.owned(owner, actionId).orElseThrow());
    }

    public AgentDtos.PendingPracticeAction cancelCurrent(UUID owner, UUID conversationId, UUID requestId,
            String traceId) {
        Row row = repository.current(owner, conversationId).orElse(null);
        if (row == null) return null;
        return cancel(owner, row.id(), new AgentDtos.CancelPendingPracticeActionRequest(requestId, row.revision()), traceId);
    }

    private AgentDtos.PendingPracticeAction recover(Row row, String traceId) {
        AgentInternalDtos.AutonomousPracticeResult recovered = domains.findAutonomousPractice(
                row.id(), row.ownerUserId(), traceId);
        return recovered == null ? projection(row) : succeed(row, recovered, traceId);
    }

    private AgentDtos.PendingPracticeAction succeed(Row row, AgentInternalDtos.AutonomousPracticeResult result,
            String traceId) {
        repository.succeed(row.id(), row.revision(), result.autonomousPracticeId(), result.assessmentId(),
                result.startRoute(), Instant.now());
        Row saved = repository.owned(row.ownerUserId(), row.id()).orElseThrow();
        repository.audit(row.id(), row.ownerUserId(), row.confirmationRequestId(), "SUCCEEDED", "EXECUTING",
                "SUCCEEDED", traceId, Instant.now());
        return projection(saved);
    }

    private Row expireIfNeeded(Row row) {
        if (row.status() == AgentDtos.PendingPracticeActionStatus.PENDING
                && !Instant.now().isBefore(row.expiresAt())) {
            Instant now = Instant.now();
            if (repository.expire(row.id(), row.revision(), now) == 1) {
                repository.audit(row.id(), row.ownerUserId(), null, "EXPIRED", "PENDING", "EXPIRED",
                        "pending-action-expiry", now);
            }
            return repository.owned(row.ownerUserId(), row.id()).orElseThrow();
        }
        return row;
    }

    private AgentDtos.PendingPracticeAction projection(Row row) {
        AgentDtos.AutonomousPracticeExecutionResult execution = row.autonomousPracticeId() == null ? null
                : new AgentDtos.AutonomousPracticeExecutionResult(row.autonomousPracticeId(), row.assessmentId(), row.startRoute());
        return new AgentDtos.PendingPracticeAction(row.id(), row.conversationId(),
                read(row.criteriaJson(), AgentDtos.PracticeGenerationCriteria.class), row.requestedCount(),
                row.actualCount(), read(row.candidateJson(), new TypeReference<List<AgentDtos.PracticeCandidateQuestion>>() {}),
                row.status(), row.revision(), row.expiresAt(), execution);
    }

    private AgentInternalDtos.PracticeCriteria internalCriteria(Row row) {
        AgentDtos.PracticeGenerationCriteria value = read(row.criteriaJson(), AgentDtos.PracticeGenerationCriteria.class);
        return new AgentInternalDtos.PracticeCriteria(value.topic(), value.difficulty(), value.requestedCount());
    }

    private List<AgentInternalDtos.PracticeCandidateKey> candidateKeys(Row row) {
        return read(row.candidateJson(), new TypeReference<List<AgentDtos.PracticeCandidateQuestion>>() {}).stream()
                .map(value -> new AgentInternalDtos.PracticeCandidateKey(value.questionId(), value.questionVersionId())).toList();
    }

    private ParsedProposal parse(JsonNode proposal) {
        if (!proposal.isObject() || !"public-practice-generation".equals(proposal.path("skillName").asText())
                || !"1.0.0".equals(proposal.path("skillVersion").asText())) throw protocol();
        String topic = proposal.path("topic").asText("").trim();
        int count = proposal.path("requestedCount").asInt(0);
        AgentDtos.PracticeDifficulty difficulty;
        try { difficulty = AgentDtos.PracticeDifficulty.valueOf(proposal.path("difficulty").asText()); }
        catch (RuntimeException invalid) { throw protocol(); }
        if (topic.isEmpty() || topic.length() > 200 || count < 1 || count > 20
                || !proposal.path("candidates").isArray() || proposal.path("candidates").size() > 20) throw protocol();
        List<AgentInternalDtos.PracticeCandidateKey> candidates = new ArrayList<>();
        var seen = new java.util.HashSet<UUID>();
        proposal.path("candidates").forEach(candidate -> {
            try {
                UUID questionId = UUID.fromString(candidate.path("questionId").asText());
                UUID versionId = UUID.fromString(candidate.path("questionVersionId").asText());
                if (seen.add(versionId)) candidates.add(new AgentInternalDtos.PracticeCandidateKey(questionId, versionId));
            } catch (RuntimeException invalid) { throw protocol(); }
        });
        return new ParsedProposal(topic, difficulty, count, List.copyOf(candidates));
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch (RuntimeException failure) { throw protocol(); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); } catch (RuntimeException failure) { throw protocol(); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return mapper.readValue(value, type); } catch (RuntimeException failure) { throw protocol(); }
    }
    private static AgentException protocol() { return new AgentException(HttpStatus.BAD_GATEWAY,
            "AGENT.PROTOCOL_VIOLATION", "AI 组题提案不合法"); }
    private static AgentException notFound() { return new AgentException(HttpStatus.NOT_FOUND,
            "AGENT.NOT_FOUND", "资源不存在或不可见"); }
    private static AgentException conflict(String code, String message) { return new AgentException(HttpStatus.CONFLICT, code, message); }
    private record ParsedProposal(String topic, AgentDtos.PracticeDifficulty difficulty, int requestedCount,
            List<AgentInternalDtos.PracticeCandidateKey> candidates) {}
}
