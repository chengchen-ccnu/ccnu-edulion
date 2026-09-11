package com.eduplatform.agentaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.agentaccess.runtime.AgentRuntimeClient;
import com.eduplatform.agentaccess.persistence.AgentRepository;
import com.eduplatform.agentaccess.service.AgentConversationService;
import com.eduplatform.agentaccess.service.AgentException;
import com.eduplatform.agentaccess.service.AgentRunCoordinator;
import com.eduplatform.agentaccess.service.AgentDomainClient;
import com.eduplatform.agentaccess.service.AgentInternalService;
import com.eduplatform.agentaccess.service.MemoryFactService;
import com.eduplatform.agentaccess.service.MemoryContextProvider;
import com.eduplatform.agentaccess.service.MemoryIndexProjectionService;
import com.eduplatform.agentaccess.service.MessageRetentionService;
import com.eduplatform.agentaccess.service.RunGovernance;
import com.eduplatform.agentaccess.service.AuthorizedContextResolver;
import com.eduplatform.agentaccess.web.AgentDtos;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用 H2 与协议替身验证独立域表、幂等、单活动 run、流终态和 owner 隔离。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentAccessIntegrationTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000102");

    @Autowired AgentConversationService conversations;
    @Autowired AgentRunCoordinator coordinator;
    @Autowired FakeRuntime runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AgentInternalService internal;
    @Autowired MemoryFactService memoryFacts;
    @Autowired MemoryContextProvider memoryContext;
    @Autowired MemoryIndexProjectionService memoryIndex;
    @Autowired MessageRetentionService retention;
    @Autowired FakeDomain domains;
    @Autowired FakeGovernance governance;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_media_outbox");
        jdbc.update("DELETE FROM ai_message_media_reference");
        jdbc.update("DELETE FROM ai_agent_index_outbox");
        jdbc.update("DELETE FROM ai_memory_evidence");
        jdbc.update("DELETE FROM ai_memory");
        jdbc.update("DELETE FROM ai_conversation_summary");
        jdbc.update("DELETE FROM ai_preference");
        jdbc.update("DELETE FROM ai_profile_topic");
        jdbc.update("DELETE FROM ai_usage_record");
        jdbc.update("DELETE FROM ai_safety_event");
        jdbc.update("DELETE FROM ai_tool_call");
        jdbc.update("DELETE FROM ai_run_cancellation");
        jdbc.update("DELETE FROM ai_run_event");
        jdbc.update("DELETE FROM ai_run");
        jdbc.update("DELETE FROM ai_message");
        jdbc.update("DELETE FROM ai_conversation");
        jdbc.update("UPDATE ai_memory_revision_sequence SET current_revision=0 WHERE singleton_id=1");
        jdbc.update("UPDATE ai_message_retention_cursor SET last_created_at=NULL,last_message_id=NULL WHERE singleton_id=1");
        runtime.reset();
        domains.reset();
        governance.reset();
    }

    @Test
    void conversationAndMessageIdempotencyAndOwnerIsolation() {
        UUID createRequest = UUID.randomUUID();
        var create = new AgentDtos.CreateConversationRequest(createRequest,
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, "代数答疑");
        var first = conversations.create(OWNER, create);
        var replay = conversations.create(OWNER, create);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> conversations.create(OWNER, new AgentDtos.CreateConversationRequest(createRequest,
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, "不同标题")))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> conversations.get(OTHER, first.id()))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.NOT_FOUND"));

        UUID sendRequest = UUID.randomUUID();
        var request = new AgentDtos.SendMessageRequest(sendRequest, "请解释一元一次方程", null);
        var run = conversations.prepareRun(OWNER, first.id(), request, "trace-agent-idempotency");
        var same = conversations.prepareRun(OWNER, first.id(), request, "trace-agent-retry");
        assertThat(same.replayed()).isTrue();
        assertThat(same.run().id()).isEqualTo(run.run().id());
        assertThatThrownBy(() -> conversations.prepareRun(OWNER, first.id(),
                new AgentDtos.SendMessageRequest(UUID.randomUUID(), "另一个问题", null), "trace-agent-conflict"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.RUN_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE conversation_id=? AND active_slot=1",
                Integer.class, first.id().toString())).isEqualTo(1);
    }

    @Test
    /** Redis 治理不可用时新运行在写 message/run 前返回 503，既有会话读取仍来自 MySQL。 */
    void governanceFailureRejectsNewRunBeforeBusinessFactsButKeepsReadsAvailable() {
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        governance.failure = true;
        assertThatThrownBy(() -> conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "不会被写入", null), "trace-governance-down"))
                .isInstanceOfSatisfying(AgentException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("AGENT.GOVERNANCE_UNAVAILABLE");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=?", Integer.class,
                conversation.id().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE conversation_id=?", Integer.class,
                conversation.id().toString())).isZero();
        assertThat(conversations.get(OWNER, conversation.id()).id()).isEqualTo(conversation.id());
    }

    @Test
    void protocolSubstituteCompletesAndPersistsRecoverableEvents() {
        var prepared = newRun("trace-agent-complete");
        coordinator.open(prepared);
        runtime.emit(event(prepared, 1, "RUN_ACCEPTED", Map.of("conversationId",
                prepared.run().conversationId().toString(), "messageId", prepared.userMessage().id().toString(),
                "replayed", false)));
        runtime.emit(event(prepared, 2, "TEXT_DELTA", Map.of("delta", "先移项。")));
        runtime.emit(event(prepared, 3, "FINAL_RESULT", Map.of("terminal", true,
                "terminalStatus", "COMPLETED", "finalAnswer", "先移项，再合并同类项。",
                "answerCompleteness", "COMPLETE")));

        var saved = conversations.events(OWNER, prepared.run().id(), 0);
        assertThat(saved.status()).isEqualTo(AgentDtos.RunStatus.COMPLETED);
        assertThat(saved.items()).extracting(AgentDtos.StreamEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(conversations.messages(OWNER, prepared.run().conversationId(), 1, 20).items())
                .filteredOn(message -> message.role() == AgentDtos.MessageRole.ASSISTANT)
                .singleElement().satisfies(message -> {
                    assertThat(message.content()).isEqualTo("先移项，再合并同类项。");
                    assertThat(message.completeness()).isEqualTo(AgentDtos.Completeness.COMPLETE);
                });
    }

    @Test
    /** Python 若混入错误 trace 或跳过首事件，Java 不代理该事件，并用唯一 FAILED 终态安全收口。 */
    void protocolSubstituteRejectsMismatchedRunIdentity() {
        var prepared = newRun("trace-agent-invalid");
        coordinator.open(prepared);
        JsonNode invalid = event(prepared, 1, "RUN_ACCEPTED", Map.of(
                "conversationId", prepared.run().conversationId().toString(),
                "messageId", prepared.userMessage().id().toString(), "replayed", false));
        ((tools.jackson.databind.node.ObjectNode) invalid).put("traceId", "trace-other-run");
        runtime.emit(invalid);

        var saved = conversations.events(OWNER, prepared.run().id(), 0);
        assertThat(saved.status()).isEqualTo(AgentDtos.RunStatus.FAILED);
        assertThat(saved.items()).extracting(AgentDtos.StreamEvent::eventType)
                .containsExactly("RUN_ACCEPTED", "FINAL_RESULT");
        assertThat(saved.items().get(1).payload().path("error").path("traceId").asText())
                .isEqualTo(prepared.run().traceId());
    }

    @Test
    /** 无本地执行句柄的幂等恢复只跟随 MySQL 保存事件，不会误杀 run 或重启 Python。 */
    void replayWithoutLocalHandleDoesNotTerminateActiveRun() {
        var prepared = newRun("trace-agent-replay-snapshot");
        var replayed = conversations.prepareRun(OWNER, prepared.run().conversationId(),
                new AgentDtos.SendMessageRequest(prepared.userMessage().clientRequestId(),
                        prepared.userMessage().content(), null), "trace-agent-retry");
        assertThat(replayed.replayed()).isTrue();

        coordinator.open(replayed);

        assertThat(runtime.streamCalls).isZero();
        assertThat(conversations.requireRun(OWNER, prepared.run().id()).status())
                .isEqualTo(AgentDtos.RunStatus.ACCEPTED);
        assertThat(conversations.events(OWNER, prepared.run().id(), 0).items()).isEmpty();
    }

    @Test
    /** 新实例恢复时保留有有效租约的他实例 run；租约明确不存在后才有界写唯一失败终态。 */
    void startupRecoveryIsLeaseAwareAndClosesOnlyRealOrphan() {
        var prepared = newRun("trace-lease-aware-recovery");
        jdbc.update("UPDATE ai_run SET updated_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), prepared.run().id().toString());
        governance.valid.add(prepared.run().id());
        conversations.recoverOrphanedRuns();
        assertThat(conversations.requireRun(OWNER, prepared.run().id()).terminal()).isFalse();

        governance.valid.remove(prepared.run().id());
        conversations.recoverOrphanedRuns();
        assertThat(conversations.requireRun(OWNER, prepared.run().id()).status()).isEqualTo(AgentDtos.RunStatus.FAILED);
        assertThat(conversations.events(OWNER, prepared.run().id(), 0).items())
                .filteredOn(event -> "FINAL_RESULT".equals(event.eventType())).hasSize(1);
    }

    @Test
    void runtimeFailureAndCancellationProduceOneTerminalAndReleaseActiveSlot() {
        var failed = newRun("trace-agent-failure");
        coordinator.open(failed);
        runtime.fail();
        var failedEvents = conversations.events(OWNER, failed.run().id(), 0);
        assertThat(failedEvents.status()).isEqualTo(AgentDtos.RunStatus.FAILED);
        assertThat(failedEvents.items()).extracting(AgentDtos.StreamEvent::eventType)
                .containsExactly("RUN_ACCEPTED", "FINAL_RESULT");

        var cancelled = newRun("trace-agent-cancel");
        coordinator.open(cancelled);
        UUID cancelRequest = UUID.randomUUID();
        var input = new AgentDtos.CancelRunRequest(cancelRequest, AgentDtos.CancelReason.USER_STOP);
        var first = coordinator.cancel(OWNER, cancelled.run().id(), input);
        var replay = coordinator.cancel(OWNER, cancelled.run().id(), input);
        assertThat(first.status()).isEqualTo(AgentDtos.RunStatus.CANCELLED);
        assertThat(replay).isEqualTo(first);
        assertThat(conversations.events(OWNER, cancelled.run().id(), 0).items())
                .filteredOn(event -> "FINAL_RESULT".equals(event.eventType())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE active_slot=1", Integer.class)).isZero();
    }

    @Test
    /** 过期正文按有限批次清空并显式标记 PURGED，真实存在的 AI 图片引用只释放一次。 */
    void messageRetentionPurgesBodyAndReleasesStableMediaReference() {
        var prepared = terminalRun("trace-retention");
        UUID mediaId = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(181L * 86400);
        jdbc.update("UPDATE ai_message SET created_at=? WHERE id IN (?,?)", java.sql.Timestamp.from(old),
                prepared.run().userMessageId().toString(), prepared.run().assistantMessageId().toString());
        jdbc.update("INSERT INTO ai_message_media_reference(message_id,media_id,purpose,status,created_at) "
                        + "VALUES(?,?,'AI_QUESTION_IMAGE','BOUND',?)",
                prepared.run().userMessageId().toString(), mediaId.toString(), java.sql.Timestamp.from(old));

        retention.purgeDue();
        retention.purgeDue();

        assertThat(jdbc.queryForObject("SELECT content_state FROM ai_message WHERE id=?", String.class,
                prepared.run().userMessageId().toString())).isEqualTo("PURGED");
        assertThat(jdbc.queryForObject("SELECT content FROM ai_message WHERE id=?", String.class,
                prepared.run().userMessageId().toString())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_message_media_reference WHERE message_id=?",
                String.class, prepared.run().userMessageId().toString())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_media_outbox WHERE event_type='MediaReferenceReleased'",
                Integer.class)).isEqualTo(1);
        assertThat(conversations.messages(OWNER, prepared.run().conversationId(), 1, 20).items())
                .filteredOn(item -> item.id().equals(prepared.run().userMessageId())).singleElement()
                .satisfies(item -> assertThat(item.contentState()).isEqualTo(AgentDtos.ContentState.PURGED));
    }

    @Test
    /** AI 图片请求只写稳定 mediaId/messageId 和既有 MediaReferenceBound v1，不保存短期地址。 */
    void aiQuestionImageCreatesStableReferenceAndOutbox() {
        UUID mediaId = UUID.randomUUID();
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        var context = new AgentDtos.MessageContextRequest(null, null, null, List.of(), List.of(mediaId));
        var prepared = conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "请看这张题图", context), "trace-ai-image-reference");

        assertThat(jdbc.queryForObject("SELECT media_id FROM ai_message_media_reference WHERE message_id=?",
                String.class, prepared.userMessage().id().toString())).isEqualTo(mediaId.toString());
        String payload = jdbc.queryForObject("SELECT payload_json FROM ai_media_outbox", String.class);
        assertThat(payload).contains("MediaReferenceBound", mediaId.toString(), prepared.userMessage().id().toString())
                .doesNotContain("http://", "https://", "objectKey", "bucket", "token");
    }

    @Test
    /** 最近窗口只来自已落库旧消息，按序、有界、保留 PARTIAL，并在跨进程前移除 URL/Token。 */
    void recentMessageWindowIsBoundedSanitizedAndExcludesCurrentMessage() {
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        AgentConversationService.PreparedRun latest = null;
        for (int index = 0; index < 7; index++) {
            latest = conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                    UUID.randomUUID(), "第" + index + "轮 https://temporary.invalid/x token=secret", null),
                    "trace-history-" + index);
            coordinator.open(latest);
            runtime.emit(event(latest, 1, "RUN_ACCEPTED", Map.of("conversationId", conversation.id().toString(),
                    "messageId", latest.userMessage().id().toString(), "replayed", false)));
            runtime.emit(event(latest, 2, "FINAL_RESULT", Map.of("terminal", true,
                    "terminalStatus", index == 5 ? "DEGRADED" : "COMPLETED",
                    "finalAnswer", "回答" + index + " signature=hidden Authorization: Bearer bearer-hidden",
                    "answerCompleteness",
                    index == 5 ? "SIMPLIFIED" : "COMPLETE")));
        }
        var next = conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "当前消息不得重复", null), "trace-history-next");
        assertThat(next.recentMessages()).hasSizeLessThanOrEqualTo(12);
        assertThat(next.recentMessages()).extracting(AgentDtos.RecentMessage::sequenceNo).isSorted();
        assertThat(next.recentMessages()).extracting(AgentDtos.RecentMessage::messageId)
                .doesNotContain(next.userMessage().id());
        assertThat(next.recentMessages()).extracting(AgentDtos.RecentMessage::completeness)
                .contains(AgentDtos.Completeness.PARTIAL);
        assertThat(next.recentMessages().stream().mapToInt(item -> item.content().length()).sum())
                .isLessThanOrEqualTo(16_000);
        assertThat(next.recentMessages()).allSatisfy(item ->
                assertThat(item.content()).doesNotContain("https://", "secret", "hidden", "bearer-hidden"));
    }

    @Test
    /** Course/Learning 回调只使用 run 的数据库 owner；依赖失败保持关闭，不采信任何外部用户字段。 */
    void internalCallbacksDeriveOwnerFromRunAndFailClosed() {
        var prepared = newRun("trace-internal-owner");
        UUID entity = UUID.randomUUID();
        domains.allowed = Set.of(entity);
        var candidate = new AgentInternalDtos.CourseCandidate(entity, UUID.randomUUID(), "v1", entity, 1L);
        var response = internal.verify(prepared.run().id(),
                new AgentInternalDtos.VerifyCourseCandidatesRequest(List.of(candidate)), prepared.run().traceId());
        assertThat(response.allowedEntityIds()).containsExactly(entity);
        assertThat(domains.lastOwner).isEqualTo(OWNER);

        domains.failure = true;
        assertThatThrownBy(() -> internal.facts(prepared.run().id(), new AgentInternalDtos.StudentFactsRequest(
                AgentInternalDtos.StudentFactTool.STUDENT_PROGRESS, List.of(), 8), prepared.run().traceId()))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    /** Runtime 回环 HTTP 请求不携带 userId；Controller 认证后仍由 run 反查 owner。 */
    void internalHttpCallbackAuthenticatesRuntimeAndDerivesOwner() throws Exception {
        var prepared = newRun("trace-internal-http");
        UUID entity = UUID.randomUUID();
        domains.allowed = Set.of(entity);
        String body = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "entityId", entity, "courseId", UUID.randomUUID(), "courseVersionId", "v1",
                "lessonId", entity, "sourceRevision", 1))));
        var uri = java.net.URI.create("http://127.0.0.1:" + port + "/internal/v1/agent/runs/"
                + prepared.run().id() + "/course-candidates:verify");
        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("X-Internal-Caller", "edu-agent-runtime")
                .header("X-Internal-Service-Token", "test-only-runtime-callback-token-32-bytes")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(entity.toString()).doesNotContain("userId");
        assertThat(domains.lastOwner).isEqualTo(OWNER);
    }

    @Test
    /** 私有结果报告只允许终态保存；同 ID 同内容重放，不同内容冲突且不进入公开消息/事件。 */
    void privateResultReportIsTerminalAndIdempotent() {
        var prepared = newRun("trace-private-report");
        UUID reportId = UUID.randomUUID();
        var report = report(reportId, "COMPLETED");
        assertThatThrownBy(() -> internal.report(prepared.run().id(), report))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.RUN_NOT_TERMINAL"));
        coordinator.open(prepared);
        runtime.emit(event(prepared, 1, "RUN_ACCEPTED", Map.of("conversationId",
                prepared.run().conversationId().toString(), "messageId", prepared.userMessage().id().toString(),
                "replayed", false)));
        runtime.emit(event(prepared, 2, "FINAL_RESULT", Map.of("terminal", true,
                "terminalStatus", "COMPLETED", "finalAnswer", "完成", "answerCompleteness", "COMPLETE")));
        assertThat(internal.report(prepared.run().id(), report).replayed()).isFalse();
        assertThat(internal.report(prepared.run().id(), report).replayed()).isTrue();
        assertThatThrownBy(() -> internal.report(prepared.run().id(), report(reportId, "FAILED")))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.REPORT_IDEMPOTENCY_CONFLICT"));
        var otherRun = newRun("trace-private-report-other-run");
        coordinator.open(otherRun);
        runtime.emit(event(otherRun, 1, "FINAL_RESULT", Map.of("terminal", true,
                "terminalStatus", "COMPLETED", "finalAnswer", "另一运行完成",
                "answerCompleteness", "COMPLETE")));
        assertThatThrownBy(() -> internal.report(otherRun.run().id(), report))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.REPORT_IDEMPOTENCY_CONFLICT"));
        String publicJson = conversations.events(OWNER, prepared.run().id(), 0).items().toString()
                + conversations.messages(OWNER, prepared.run().conversationId(), 1, 20).items();
        assertThat(publicJson).doesNotContain("memoryCandidates", "promptTokens", "phaseDurationsMs");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE result_report_id=?", Integer.class,
                reportId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record WHERE run_id=?", Integer.class,
                prepared.run().id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory WHERE owner_user_id=?", Integer.class,
                OWNER.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_index_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_normalization_status FROM ai_run WHERE id=?", String.class,
                prepared.run().id().toString())).isEqualTo("NORMALIZED");
    }

    private AgentInternalDtos.RunResultReportRequest report(UUID id, String reason) {
        return report(id, reason, List.of("明确偏好：分步讲解"));
    }

    private AgentInternalDtos.RunResultReportRequest report(UUID id, String reason, List<String> candidates) {
        return new AgentInternalDtos.RunResultReportRequest(id, "test-model", 2, 0, 1, 10, 5,
                Map.of("MODEL_MATH_TUTOR", 20L), reason, candidates);
    }

    @Test
    /** 聊天候选是弱证据；“我懂了”和单次“我不会”只记安全拒绝，不形成记忆或画像。 */
    void chatClaimsCannotCreateMasteryOrPermanentDeficit() {
        var prepared = terminalRun("trace-chat-weak");
        internal.report(prepared.run().id(), report(UUID.randomUUID(), "COMPLETED", List.of(
                "未解决卡点：我懂了", "用户自述的重复卡点：我不会")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_profile_topic", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_safety_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record", Integer.class)).isEqualTo(1);
    }

    @Test
    /** V2 存量私有报告解析失败时整次回滚，仅另事务留下脱敏 FAILED 状态和原报告供重试。 */
    void malformedHistoricalReportRollsBackAllDerivedFactsAndRemainsRetryable() {
        var prepared = terminalRun("trace-malformed-report");
        UUID reportId = UUID.randomUUID();
        jdbc.update("""
                UPDATE ai_run SET result_report_id=?,result_report_json='{',
                    result_reported_at=?,result_normalization_status='PENDING'
                WHERE id=?
                """, reportId.toString(), java.sql.Timestamp.from(Instant.now()),
                prepared.run().id().toString());
        RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> memoryFacts.normalize(prepared.run().id()));
        memoryFacts.recordNormalizationFailure(prepared.run().id(), failure);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_index_outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT result_normalization_status FROM ai_run WHERE id=?",
                String.class, prepared.run().id().toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT result_report_json FROM ai_run WHERE id=?",
                String.class, prepared.run().id().toString())).isEqualTo("{");
    }

    @Test
    /** 推断偏好可到期，后续明确偏好会修正当前值并为旧索引写 tombstone。 */
    void inferredPreferenceDecaysAndExplicitPreferenceCorrectsIt() {
        var inferred = terminalRun("trace-inferred-preference");
        internal.report(inferred.run().id(), report(UUID.randomUUID(), "COMPLETED",
                List.of("推断偏好：先提示再逐步引导")));
        assertThat(jdbc.queryForObject("SELECT source_type FROM ai_preference", String.class)).isEqualTo("INFERRED");
        assertThat(jdbc.queryForObject("SELECT expires_at IS NOT NULL FROM ai_preference", Boolean.class)).isTrue();

        var explicit = terminalRun("trace-explicit-preference");
        internal.report(explicit.run().id(), report(UUID.randomUUID(), "COMPLETED",
                List.of("明确偏好：先引导后讲解")));
        assertThat(jdbc.queryForObject("SELECT source_type FROM ai_preference", String.class)).isEqualTo("EXPLICIT");
        assertThat(jdbc.queryForObject("SELECT expires_at IS NULL FROM ai_preference", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory WHERE status='EXPIRED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_index_outbox WHERE payload_json LIKE '%\"operation\":\"DELETE\"%'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    /** 记忆到期产生更高 revision tombstone；画像久未验证只降低可信度并保留历史掌握。 */
    void expiryAndProfileConfidenceDecayPreserveHistory() {
        var prepared = terminalRun("trace-memory-expiry");
        internal.report(prepared.run().id(), report(UUID.randomUUID(), "COMPLETED",
                List.of("未解决卡点：一次方程移项仍需练习")));
        jdbc.update("UPDATE ai_memory SET expires_at=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        assertThat(memoryFacts.expireDue(Instant.now(), 10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_memory", String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT MAX(source_revision) FROM ai_agent_index_outbox", Long.class))
                .isEqualTo(2L);

        UUID source = UUID.randomUUID();
        memoryFacts.recordLearningEvidence(OWNER, "STUDENT_SUBMISSION", source, 3,
                "几何证明", "MASTERED", "trace-profile-decay");
        memoryFacts.recordLearningEvidence(OWNER, "STUDENT_SUBMISSION", source, 2,
                "几何证明", "NEEDS_SUPPORT", "trace-stale-evidence");
        jdbc.update("UPDATE ai_profile_topic SET last_verified_at=?,confidence_level='HIGH'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(100L * 86400)));
        assertThat(memoryFacts.decayStaleTopicConfidence(Instant.now())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_level FROM ai_profile_topic", String.class))
                .isEqualTo("MASTERED");
        assertThat(jdbc.queryForObject("SELECT confidence_level FROM ai_profile_topic", String.class))
                .isEqualTo("LOW");
        assertThat(jdbc.queryForObject("SELECT historical_mastery FROM ai_profile_topic", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_evidence WHERE source_id=?", Integer.class,
                source.toString())).isEqualTo(1);
    }

    @Test
    /** 正式作答/批改是强证据；同 revision 幂等，较新修正保留历史掌握但更新当前等级。 */
    void formalEvidenceCorrectsCurrentTopicWithoutDeletingMasteryHistory() {
        UUID source = UUID.randomUUID();
        memoryFacts.recordLearningEvidence(OWNER, "TEACHER_GRADING", source, 1,
                "一次方程", "MASTERED", "trace-formal-evidence");
        memoryFacts.recordLearningEvidence(OWNER, "TEACHER_GRADING", source, 1,
                "一次方程", "MASTERED", "trace-formal-evidence");
        memoryFacts.recordLearningEvidence(OWNER, "TEACHER_GRADING", source, 3,
                "一次方程", "NEEDS_SUPPORT", "trace-formal-correction");
        memoryFacts.recordLearningEvidence(OWNER, "TEACHER_GRADING", source, 2,
                "一次方程", "MASTERED", "trace-stale-correction");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_evidence", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_evidence WHERE effective=1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_level FROM ai_profile_topic WHERE owner_user_id=?",
                String.class, OWNER.toString())).isEqualTo("NEEDS_SUPPORT");
        assertThat(jdbc.queryForObject("SELECT historical_mastery FROM ai_profile_topic WHERE owner_user_id=?",
                Integer.class, OWNER.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_index_outbox", Integer.class)).isEqualTo(2);
        assertThat(memoryIndex.page(0, new UUID(0, 0), null, 100).items())
                .allMatch(item -> item.subjectUserId().equals(OWNER))
                .allMatch(item -> item.visibility().equals("USER_PRIVATE"));
    }

    @Test
    /** 内部事实源必须同时校验固定调用方和专用令牌，返回体不泄露证据、画像或会话原文。 */
    void memoryProjectionHttpEndpointIsAuthenticatedAndSafe() throws Exception {
        UUID source = UUID.randomUUID();
        memoryFacts.recordLearningEvidence(OWNER, "STUDENT_SUBMISSION", source, 1,
                "函数图像", "DEVELOPING", "trace-memory-projection");
        var client = java.net.http.HttpClient.newHttpClient();
        var uri = java.net.URI.create("http://127.0.0.1:" + port
                + "/internal/v1/agent-index/student-memories?size=10");
        var denied = client.send(java.net.http.HttpRequest.newBuilder(uri)
                        .header("X-Internal-Caller", "agent-index-worker")
                        .header("X-Internal-Service-Token", "wrong-token-which-is-long-enough-000000")
                        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);

        var allowed = client.send(java.net.http.HttpRequest.newBuilder(uri)
                        .header("X-Internal-Caller", "agent-index-worker")
                        .header("X-Internal-Service-Token", "test-only-agent-index-service-token-32-bytes")
                        .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.body()).contains(OWNER.toString(), "USER_PRIVATE", "学习主题：函数图像")
                .doesNotContain("evidence", "currentLevel", "historicalMastery", "promptTokens",
                        "completionTokens", "phaseDurationsMs");
    }

    @Test
    /** 全局单调游标能看到分页后新建的记忆；运行上下文始终按可信 owner 隔离。 */
    void lateMemoryIsVisibleAfterCursorAndContextIsOwnerIsolated() {
        memoryFacts.recordLearningEvidence(OWNER, "STUDENT_SUBMISSION", UUID.randomUUID(), 1,
                "一次方程", "DEVELOPING", "trace-cursor-first");
        memoryFacts.recordLearningEvidence(OWNER, "STUDENT_SUBMISSION", UUID.randomUUID(), 1,
                "函数图像", "DEVELOPING", "trace-cursor-second");
        var initial = memoryIndex.page(0, new UUID(0, 0), null, 100);
        assertThat(initial.items()).hasSize(2);

        memoryFacts.recordLearningEvidence(OTHER, "STUDENT_SUBMISSION", UUID.randomUUID(), 1,
                "几何证明", "DEVELOPING", "trace-cursor-late");
        var late = memoryIndex.page(initial.nextCursor().sourceRevision(),
                initial.nextCursor().entityId(), null, 100);
        assertThat(late.items()).singleElement().satisfies(item ->
                assertThat(item.subjectUserId()).isEqualTo(OTHER));
        assertThat(memoryContext.summaries(OWNER, null, null, null))
                .allMatch(item -> !item.get("text").toString().contains("几何证明"));
        assertThat(memoryContext.summaries(OTHER, null, null, null))
                .singleElement().satisfies(item -> assertThat(item.get("text")).isEqualTo("学习主题：几何证明"));
    }

    private AgentConversationService.PreparedRun newRun(String traceId) {
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        return conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(UUID.randomUUID(),
                "请给我一个提示", null), traceId);
    }

    private AgentConversationService.PreparedRun terminalRun(String traceId) {
        var prepared = newRun(traceId);
        coordinator.open(prepared);
        runtime.emit(event(prepared, 1, "FINAL_RESULT", Map.of("terminal", true,
                "terminalStatus", "COMPLETED", "finalAnswer", "完成", "answerCompleteness", "COMPLETE")));
        return prepared;
    }

    private JsonNode event(AgentConversationService.PreparedRun run, long sequence, String type,
            Map<String, Object> payload) {
        return mapper.valueToTree(Map.of("eventType", type, "runId", run.run().id().toString(),
                "sequence", sequence, "traceId", run.run().traceId(), "emittedAt", Instant.now().toString(),
                "payload", payload));
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        AuthorizedContextResolver contextResolver() {
            return (user, entry, input, trace) -> new AgentDtos.AuthorizedContext(
                    new AgentDtos.MessageContext(null, null, null, null, null, List.of()),
                    Map.of("userId", user.toString(), "educationStage", "JUNIOR", "entryPoint", entry.name(),
                            "authorizedAt", Instant.now().toString(), "toolGrants", List.of(Map.of(
                                    "toolName", "QUESTION_SEARCH", "resourceScopes",
                                    List.of(Map.of("scopeType", "PUBLIC_JUNIOR_MATH"))))));
        }

        @Bean
        @Primary
        FakeRuntime fakeRuntime() { return new FakeRuntime(); }

        @Bean
        @Primary
        FakeDomain fakeDomain() { return new FakeDomain(); }

        @Bean
        @Primary
        FakeGovernance fakeGovernance() { return new FakeGovernance(); }
    }

    static final class FakeRuntime implements AgentRuntimeClient {
        private Consumer<JsonNode> events;
        private Consumer<Throwable> errors;
        private int streamCalls;

        @Override
        public StreamHandle stream(JsonNode request, Consumer<JsonNode> onEvent,
                Consumer<Throwable> onError, Runnable onComplete) {
            this.events = onEvent;
            this.errors = onError;
            this.streamCalls++;
            return () -> { };
        }

        @Override
        public void cancel(UUID runId, UUID cancelRequestId, String traceId, String reason) { }

        void emit(JsonNode event) { events.accept(event); }
        void fail() { errors.accept(new IllegalStateException("fixture failure")); }
        void reset() { events = null; errors = null; streamCalls = 0; }
    }

    static final class FakeDomain implements AgentDomainClient {
        UUID lastOwner;
        Set<UUID> allowed = Set.of();
        boolean failure;

        @Override
        public Set<UUID> verifyCourseCandidates(UUID owner, List<AgentInternalDtos.CourseCandidate> candidates,
                String traceId) {
            this.lastOwner = owner;
            if (failure) throw unavailable();
            return allowed;
        }

        @Override
        public List<AgentInternalDtos.SafeFact> readStudentFacts(UUID owner,
                AgentInternalDtos.StudentFactsRequest request, String traceId) {
            this.lastOwner = owner;
            if (failure) throw unavailable();
            return List.of();
        }

        private AgentException unavailable() {
            return new AgentException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT.DEPENDENCY_UNAVAILABLE", "fixture unavailable");
        }

        void reset() { lastOwner = null; allowed = Set.of(); failure = false; }
    }

    static final class FakeGovernance implements RunGovernance {
        final Set<UUID> valid = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<UUID> cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet();
        boolean failure;

        public Reservation reserve(UUID owner, UUID conversation, UUID request) {
            if (failure) throw new AgentException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT.GOVERNANCE_UNAVAILABLE", "fixture unavailable");
            return new Reservation(UUID.randomUUID(), owner, conversation, request, "idem", "active", "minute",
                    "day", true);
        }
        public Lease bind(Reservation reservation, AgentRepository.RunRow run) {
            valid.add(run.id());
            return new Lease(run.id(), run.ownerUserId(), UUID.randomUUID().toString(), "lease", "active");
        }
        public boolean renew(Lease lease) { return valid.contains(lease.runId()) && !cancelled.contains(lease.runId()); }
        public boolean hasValidLease(UUID runId) { return valid.contains(runId); }
        public boolean cancellationRequested(UUID runId) { return cancelled.contains(runId); }
        public void recordProgress(Lease lease, String status, long lastSequence) { }
        public void requestCancellation(UUID runId) { cancelled.add(runId); }
        public void release(Lease lease) { valid.remove(lease.runId()); cancelled.remove(lease.runId()); }
        public void rollback(Reservation reservation) { }
        void reset() { valid.clear(); cancelled.clear(); failure = false; }
    }
}
