package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.MemoryProfileRepository;
import com.eduplatform.agentaccess.persistence.MemoryProfileRepository.MemoryRow;
import com.eduplatform.agentaccess.persistence.MemoryProfileRepository.OutboxRow;
import com.eduplatform.agentaccess.persistence.MemoryProfileRepository.PendingReport;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 把 Python 私有运行报告归一化为用量、摘要、偏好、长期记忆、证据和索引事件。
 *
 * <p>聊天候选永远按弱证据处理；“我懂了”与单次“我不会”不会形成掌握或能力画像。
 * 正式作答/批改只能经 {@link #recordLearningEvidence} 的结构化 Java 入口写入，本服务不会从聊天文本猜测主题等级。</p>
 */
@Service
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class MemoryFactService {
    public static final String INDEX_EXCHANGE = "edu.agent.index.events.v1";
    public static final String MEMORY_ROUTING_KEY = "agent.index.student-memory.changed.v1";
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern SECRET = Pattern.compile("(?i)(token|authorization|accesskey|signature|password)\\s*[:=]");
    private final MemoryProfileRepository repository;
    private final ObjectMapper mapper;

    public MemoryFactService(MemoryProfileRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * 在一个事务内锁定 run 并完成一次规范化。run 用量事实和归一化状态共同保证重复调用为 no-op。
     * @param runId 已保存终态私有报告的运行 ID
     * @return true 表示本次完成了规范化，false 表示此前已经完成或当前没有报告
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NESTED)
    public boolean normalize(UUID runId) {
        PendingReport pending = repository.lockPendingReport(runId).orElse(null);
        if (pending == null) return false;
        try {
            AgentInternalDtos.RunResultReportRequest report = mapper.readValue(
                    pending.reportJson(), AgentInternalDtos.RunResultReportRequest.class);
            validateReportIdentity(pending, report);
            Instant now = Instant.now();
            if (!repository.usageExists(runId)) {
                repository.insertUsage(runId, pending.reportId(), pending.ownerUserId(), report.model(),
                        report.modelCalls(), report.visionCalls(), report.toolAttempts(), report.promptTokens(),
                        report.completionTokens(), mapper.writeValueAsString(report.phaseDurationsMs()),
                        report.terminationReason(), now);
            }
            List<String> summaryParts = new ArrayList<>();
            int candidateIndex = 0;
            for (String raw : report.memoryCandidates()) {
                normalizeCandidate(pending, raw, candidateIndex++, summaryParts, now);
            }
            if (!summaryParts.isEmpty()) {
                long[] range = repository.runMessageRange(runId);
                String summary = String.join("；", summaryParts);
                repository.upsertSummary(pending.conversationId(), pending.ownerUserId(), range[0], range[1],
                        summary.substring(0, Math.min(4000, summary.length())), now);
            }
            repository.markNormalized(runId, now);
            return true;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            // 异常必须逃出事务以回滚用量、记忆、画像和 Outbox 的所有部分写入。
            throw new IllegalStateException("memory report normalization failed", failure);
        }
    }

    /** 归一化 savepoint 已回滚后，外层事务只记录可重试状态，不保存异常消息。 */
    @Transactional
    public void recordNormalizationFailure(UUID runId, Throwable failure) {
        repository.markNormalizationFailed(runId, failure.getClass().getSimpleName(), Instant.now());
    }

    private void validateReportIdentity(PendingReport pending, AgentInternalDtos.RunResultReportRequest report) {
        if (!pending.reportId().equals(report.reportId())) {
            throw new IllegalArgumentException("report identity mismatch");
        }
    }

    private void normalizeCandidate(PendingReport pending, String raw, int candidateIndex,
            List<String> summaryParts, Instant now) throws Exception {
        String candidate = safeCandidate(raw);
        if (candidate == null) {
            repository.insertSafety(pending.runId(), pending.ownerUserId(), "MEMORY_CANDIDATE_REJECTED",
                    "候选不符合长期事实边界", now);
            return;
        }
        String memoryType;
        String text;
        String confidence;
        String decay;
        Instant expiresAt;
        String preferenceSource = null;
        String summaryText;
        if (candidate.startsWith("明确偏好：")) {
            memoryType = "PREFERENCE";
            text = candidate.substring("明确偏好：".length()).trim();
            if (text.isEmpty() || text.length() > 80) throw new IllegalArgumentException("invalid preference");
            confidence = "HIGH";
            decay = "EXPLICIT_STABLE";
            expiresAt = null;
            preferenceSource = "EXPLICIT";
            summaryText = "已确认讲解偏好：" + text;
        } else if (candidate.startsWith("推断偏好：")) {
            memoryType = "PREFERENCE";
            text = candidate.substring("推断偏好：".length()).trim();
            if (text.isEmpty() || text.length() > 80) throw new IllegalArgumentException("invalid preference");
            confidence = "LOW";
            decay = "INFERRED_30D";
            expiresAt = now.plus(30, ChronoUnit.DAYS);
            preferenceSource = "INFERRED";
            summaryText = "待确认的讲解偏好：" + text;
        } else if (candidate.startsWith("未解决卡点：")) {
            memoryType = "UNRESOLVED_POINT";
            text = candidate.substring("未解决卡点：".length()).trim();
            confidence = "LOW";
            decay = "UNRESOLVED_30D";
            expiresAt = now.plus(30, ChronoUnit.DAYS);
            summaryText = "仍需跟进的卡点：" + truncate(text, 240);
        } else if (candidate.startsWith("用户自述的重复卡点：")) {
            memoryType = "SELF_REPORTED_PATTERN";
            text = candidate.substring("用户自述的重复卡点：".length()).trim();
            confidence = "LOW";
            decay = "CHAT_WEAK_14D";
            expiresAt = now.plus(14, ChronoUnit.DAYS);
            summaryText = "用户自述的待验证模式：" + truncate(text, 240);
        } else {
            repository.insertSafety(pending.runId(), pending.ownerUserId(), "MEMORY_CANDIDATE_REJECTED",
                    "候选类型未获准持久化", now);
            return;
        }
        if (text.isBlank() || text.contains("我懂了") || text.equals("我不会") || text.length() > 500) {
            repository.insertSafety(pending.runId(), pending.ownerUserId(), "MEMORY_CANDIDATE_REJECTED",
                    "聊天自述不能形成掌握结论", now);
            return;
        }
        if (preferenceSource != null) {
            repository.upsertPreference(pending.ownerUserId(), preferenceKind(text), text,
                    preferenceSource, expiresAt, now);
        }
        summaryParts.add(summaryText);
        if ("PREFERENCE".equals(memoryType)) {
            // 同类新偏好是对旧偏好的明确修正；旧证据保留，但安全投影必须发 tombstone。
            for (MemoryRow prior : repository.activeOfType(pending.ownerUserId(), "PREFERENCE", now)) {
                if (!prior.safeText().equals(text)
                        && preferenceKind(prior.safeText()).equals(preferenceKind(text))) {
                    MemoryRow retired = repository.expire(prior, now);
                    if (retired != null) {
                        repository.insertOutbox(event(retired, "DELETE", "EXPIRED", pending.traceId(), now), now);
                    }
                }
            }
        }
        MemoryRow memory = repository.lockMemory(pending.ownerUserId(), memoryType, text).orElse(null);
        boolean newEvidence;
        if (memory == null) {
            UUID memoryId = UUID.nameUUIDFromBytes((pending.ownerUserId() + "|" + memoryType + "|" + text)
                    .getBytes(StandardCharsets.UTF_8));
            memory = new MemoryRow(memoryId, pending.ownerUserId(), pending.conversationId(), memoryType,
                    text, "ACTIVE", 1, confidence, "USER_PRIVATE", null, null, null, decay,
                    expiresAt, null, now);
            memory = repository.insertMemory(memory, now);
            newEvidence = repository.insertEvidence(memory.id(), pending.ownerUserId(), "CHAT_CANDIDATE",
                    pending.runId(), candidateIndex + 1L, "WEAK", "SUPPORT", now);
        } else {
            newEvidence = repository.insertEvidence(memory.id(), pending.ownerUserId(), "CHAT_CANDIDATE",
                    pending.runId(), candidateIndex + 1L, "WEAK", "SUPPORT", now);
            if (newEvidence) memory = repository.reviseMemory(memory, confidence, expiresAt, null, now);
        }
        if (newEvidence) repository.insertOutbox(event(memory, "UPSERT", "ACTIVE", pending.traceId(), now), now);
    }

    /** 到期只降低当前可召回状态并写 tombstone；证据行和历史掌握事实不删除。 */
    @Transactional
    public int expireDue(Instant now, int limit) {
        int count = 0;
        for (MemoryRow row : repository.expired(limit, now)) {
            MemoryRow expired = repository.expire(row, now);
            if (expired != null) {
                repository.insertOutbox(event(expired, "DELETE", "EXPIRED", "agent-memory-expiry", now), now);
                count++;
            }
        }
        return count;
    }

    /** 长期未被正式证据再验证时只降低当前可信度，不删除当前等级或历史掌握位。 */
    @Transactional
    public int decayStaleTopicConfidence(Instant now) {
        return repository.decayStaleTopicConfidence(now.minus(90, ChronoUnit.DAYS), now);
    }

    /**
     * 接收已经由 Java 领域 API 授权的正式学习证据。聊天调用方不得使用此入口；相同来源 revision
     * 只生效一次，较新批改会撤销旧判断的当前效力但保留历史行。
     *
     * @param owner IAM 全局 userId
     * @param sourceType 仅 STUDENT_SUBMISSION 或 TEACHER_GRADING
     * @param sourceId 正式 attempt/批改稳定 ID
     * @param sourceRevision 来源 revision，必须单调增加
     * @param topicKey 有界宏观主题名，不是完整题干或答案
     * @param level MASTERED、DEVELOPING 或 NEEDS_SUPPORT
     * @param traceId 领域调用 trace
     */
    @Transactional
    public void recordLearningEvidence(UUID owner, String sourceType, UUID sourceId, long sourceRevision,
            String topicKey, String level, String traceId) {
        if (!List.of("STUDENT_SUBMISSION", "TEACHER_GRADING").contains(sourceType)
                || sourceRevision < 1 || topicKey == null || topicKey.isBlank() || topicKey.length() > 120
                || !List.of("MASTERED", "DEVELOPING", "NEEDS_SUPPORT").contains(level)) {
            throw new IllegalArgumentException("formal learning evidence is invalid");
        }
        Instant now = Instant.now();
        Long latest = repository.latestEvidenceRevision(owner, sourceType, sourceId);
        if (latest != null && sourceRevision <= latest) return;
        String safeText = "学习主题：" + topicKey;
        MemoryRow memory = repository.lockMemory(owner, "LEARNING_EVIDENCE", safeText).orElse(null);
        boolean created = memory == null;
        if (memory == null) {
            UUID id = UUID.nameUUIDFromBytes((owner + "|LEARNING_EVIDENCE|" + safeText)
                    .getBytes(StandardCharsets.UTF_8));
            memory = new MemoryRow(id, owner, null, "LEARNING_EVIDENCE", safeText, "ACTIVE", 1,
                    "HIGH", "USER_PRIVATE", null, null, null, "FORMAL_EVIDENCE_DECAY", null, now, now);
            memory = repository.insertMemory(memory, now);
        }
        repository.supersedeEvidence(owner, sourceType, sourceId, sourceRevision);
        boolean inserted = repository.insertEvidence(memory.id(), owner, sourceType, sourceId,
                sourceRevision, "STRONG", "SUPPORT", now);
        if (!inserted) return;
        if (!created) {
            memory = repository.reviseMemory(memory, "HIGH", null, now, now);
        }
        repository.upsertTopic(owner, topicKey, level, "HIGH", memory.sourceRevision(),
                "MASTERED".equals(level), now);
        repository.insertOutbox(event(memory, "UPSERT", "ACTIVE", traceId, now), now);
    }

    private OutboxRow event(MemoryRow memory, String operation, String state, String traceId, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceDomain", "AGENT_ACCESS");
        payload.put("entityType", "STUDENT_MEMORY");
        payload.put("entityId", memory.id().toString());
        payload.put("subjectUserId", memory.ownerUserId().toString());
        payload.put("sourceRevision", memory.sourceRevision());
        payload.put("operation", operation);
        payload.put("sourceState", state);
        payload.put("educationStage", "JUNIOR");
        payload.put("visibility", "USER_PRIVATE");
        payload.put("authorization", Map.of("ownerScope", "SUBJECT_USER_ONLY",
                "projectionApi", "AGENT_ACCESS_MEMORY_SAFE_PROJECTION"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "StudentMemoryIndexProjectionChanged");
        envelope.put("eventVersion", 1);
        envelope.put("aggregateId", memory.id().toString());
        envelope.put("occurredAt", now.toString());
        envelope.put("traceId", safeTrace(traceId));
        envelope.put("payload", payload);
        try {
            return new OutboxRow(eventId, "StudentMemoryIndexProjectionChanged", memory.id(), INDEX_EXCHANGE,
                    MEMORY_ROUTING_KEY, memory.sourceRevision(), safeTrace(traceId),
                    mapper.writeValueAsString(envelope), 0);
        } catch (Exception failure) {
            throw new IllegalStateException("memory event serialization failed", failure);
        }
    }

    private static String safeCandidate(String raw) {
        if (raw == null) return null;
        String value = URL.matcher(raw.strip()).replaceAll("[已移除地址]");
        if (SECRET.matcher(value).find() || value.contains("rawToolResult") || value.length() > 500) return null;
        return value;
    }

    private static String preferenceKind(String value) {
        if (value.contains("提示") || value.contains("引导")) return "HINT_FIRST";
        if (value.contains("详细") || value.contains("简洁")) return "DETAIL_LEVEL";
        return "EXPLANATION_STYLE";
    }

    private static String safeTrace(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,64}") ? value : "agent-memory-sync";
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
