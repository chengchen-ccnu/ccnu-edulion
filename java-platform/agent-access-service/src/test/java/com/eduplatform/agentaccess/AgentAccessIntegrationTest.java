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
import com.eduplatform.agentaccess.service.ExplanationPreferenceService;
import com.eduplatform.agentaccess.service.MemoryFactService;
import com.eduplatform.agentaccess.service.MemoryContextProvider;
import com.eduplatform.agentaccess.service.MemoryIndexProjectionService;
import com.eduplatform.agentaccess.service.PendingPracticeService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    @Autowired ExplanationPreferenceService explanationPreferences;
    @Autowired FakeDomain domains;
    @Autowired FakeGovernance governance;
    @Autowired PendingPracticeService pendingPractices;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @Test
    /** 浏览器消息 JSON 的数组与 UUID 必须能由 Spring 同一 ObjectMapper 精确绑定。 */
    void browserMessageJsonShapeBinds() throws Exception {
        String json = "{\"clientRequestId\":\"00000000-0000-4000-8000-000000000999\","
                + "\"content\":\"请识别图片中的数学内容并简要说明\",\"context\":{"
                + "\"aiQuestionMediaIds\":[\"00000000-0000-4000-8000-000000000998\"],"
                + "\"questionMediaIds\":[]}}";
        assertThat(mapper.readValue(json, AgentDtos.SendMessageRequest.class).context().aiQuestionMediaIds())
                .containsExactly(UUID.fromString("00000000-0000-4000-8000-000000000998"));
        assertThat(mapper.readValue("{\"clientRequestId\":\"00000000-0000-4000-8000-000000000999\","
                + "\"content\":\"请计算一加一\"}", AgentDtos.SendMessageRequest.class).context()).isNull();
        assertThat(mapper.readValue("{\"clientRequestId\":\"00000000-0000-4000-8000-000000000999\","
                + "\"entryPoint\":\"INDEPENDENT_CHAT\"}", AgentDtos.CreateConversationRequest.class).title())
                .isNull();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_pending_action_audit");
        jdbc.update("DELETE FROM ai_pending_action");
        jdbc.update("DELETE FROM ai_media_outbox");
        jdbc.update("DELETE FROM ai_message_media_reference");
        jdbc.update("DELETE FROM ai_agent_index_outbox");
        jdbc.update("DELETE FROM ai_memory_evidence");
        jdbc.update("DELETE FROM ai_memory");
        jdbc.update("DELETE FROM ai_conversation_summary");
        jdbc.update("DELETE FROM ai_preference_command");
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
    /** 新条件替换旧待确认动作；确认使用 revision CAS，并始终恢复同一 Learning 业务结果。 */
    void pendingPracticeSupersedesAndConfirmationIsIdempotent() {
        var first = practiceRun("一元一次方程");
        var oldAction = pendingPractices.acceptProposal(first.run(), practiceProposal("一元一次方程"));
        complete(first, "方案一");
        var second = conversations.prepareRun(OWNER, first.run().conversationId(),
                new AgentDtos.SendMessageRequest(UUID.randomUUID(), "推荐函数练习题", null), "trace-practice-new");
        var action = pendingPractices.acceptProposal(second.run(), practiceProposal("函数"));
        assertThat(pendingPractices.get(OWNER, oldAction.actionId()).status())
                .isEqualTo(AgentDtos.PendingPracticeActionStatus.CANCELLED);
        assertThatThrownBy(() -> pendingPractices.get(OTHER, action.actionId()))
                .isInstanceOfSatisfying(AgentException.class, error -> assertThat(error.status().value()).isEqualTo(404));

        UUID requestId = UUID.randomUUID();
        var succeeded = pendingPractices.confirm(OWNER, action.actionId(),
                new AgentDtos.ConfirmPendingPracticeActionRequest(requestId, action.revision()), "trace-confirm");
        var replay = pendingPractices.confirm(OWNER, action.actionId(),
                new AgentDtos.ConfirmPendingPracticeActionRequest(requestId, action.revision()), "trace-confirm-replay");
        assertThat(succeeded.status()).isEqualTo(AgentDtos.PendingPracticeActionStatus.SUCCEEDED);
        assertThat(replay.executionResult().assessmentId()).isEqualTo(succeeded.executionResult().assessmentId());
        assertThat(domains.createPracticeCalls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_pending_action WHERE status='PENDING'", Integer.class))
                .isZero();
    }

    @Test
    /** 过期和取消均终止可确认性；取消 requestId 重放不会产生第二次状态迁移。 */
    void pendingPracticeExpiryCancellationAndRevisionConflictsAreExplicit() {
        var expired = pendingPractices.acceptProposal(practiceRun("几何").run(), practiceProposal("几何"));
        jdbc.update("UPDATE ai_pending_action SET expires_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), expired.actionId().toString());
        assertThatThrownBy(() -> pendingPractices.confirm(OWNER, expired.actionId(),
                new AgentDtos.ConfirmPendingPracticeActionRequest(UUID.randomUUID(), expired.revision()), "trace-expired"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.PENDING_ACTION_NOT_CONFIRMABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_pending_action_audit WHERE action_id=? AND event_type='EXPIRED'",
                Integer.class, expired.actionId().toString())).isEqualTo(1);

        var cancellable = pendingPractices.acceptProposal(practiceRun("概率").run(), practiceProposal("概率"));
        assertThatThrownBy(() -> pendingPractices.cancel(OWNER, cancellable.actionId(),
                new AgentDtos.CancelPendingPracticeActionRequest(UUID.randomUUID(), 99L), "trace-stale"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.PENDING_ACTION_REVISION_CONFLICT"));
        UUID requestId = UUID.randomUUID();
        var cancelled = pendingPractices.cancel(OWNER, cancellable.actionId(),
                new AgentDtos.CancelPendingPracticeActionRequest(requestId, cancellable.revision()), "trace-cancel");
        var replay = pendingPractices.cancel(OWNER, cancellable.actionId(),
                new AgentDtos.CancelPendingPracticeActionRequest(requestId, cancellable.revision()), "trace-cancel-replay");
        assertThat(replay.revision()).isEqualTo(cancelled.revision());
    }

    @Test
    /** 创建响应超时但 Learning 已落事实时，按 actionId 查询并恢复同一结果，不把动作开放为可重建。 */
    void pendingPracticeRecoversUnknownCreateResult() {
        var action = pendingPractices.acceptProposal(practiceRun("整式").run(), practiceProposal("整式"));
        domains.unknownAfterPracticeCreate = true;

        var recovered = pendingPractices.confirm(OWNER, action.actionId(),
                new AgentDtos.ConfirmPendingPracticeActionRequest(UUID.randomUUID(), action.revision()),
                "trace-timeout-recovery");

        assertThat(recovered.status()).isEqualTo(AgentDtos.PendingPracticeActionStatus.SUCCEEDED);
        assertThat(recovered.executionResult()).isNotNull();
        assertThat(domains.createPracticeCalls).hasValue(1);
    }

    @Test
    /** 两个线程同时确认时 revision CAS 只允许一个线程调用 Learning，最终至多存在一个稳定结果。 */
    void concurrentPendingPracticeConfirmationCreatesAtMostOnce() throws Exception {
        var action = pendingPractices.acceptProposal(practiceRun("概率").run(), practiceProposal("概率"));
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentConfirm(action, start));
            var second = executor.submit(() -> concurrentConfirm(action, start));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(domains.createPracticeCalls).hasValue(1);
        assertThat(pendingPractices.get(OWNER, action.actionId()).status())
                .isEqualTo(AgentDtos.PendingPracticeActionStatus.SUCCEEDED);
    }

    private void concurrentConfirm(AgentDtos.PendingPracticeAction action, CountDownLatch start) {
        try {
            start.await(5, TimeUnit.SECONDS);
            pendingPractices.confirm(OWNER, action.actionId(),
                    new AgentDtos.ConfirmPendingPracticeActionRequest(UUID.randomUUID(), action.revision()),
                    "trace-concurrent-confirm");
        } catch (AgentException expectedRace) {
            assertThat(expectedRace.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
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
    /** 同页重复历史版本只批量解析一次；失效依赖显式降级，owner 检查先于领域调用。 */
    void practiceMessagePageBatchesExactQuestionProjectionAndFailsClosed() {
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.nameUUIDFromBytes((questionId + ":version").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID mediaId = UUID.randomUUID();
        domains.projections = List.of(new AgentInternalDtos.QuestionVersionProjection(questionId, versionId,
                "SINGLE_CHOICE", "EASY", "JUNIOR", "SEVEN", "MATHEMATICS", "历史题干",
                List.of(new AgentInternalDtos.QuestionOption("A", "历史选项")),
                List.of(new AgentInternalDtos.QuestionMedia(UUID.randomUUID(), mediaId, "QUESTION_IMAGE", 1)),
                List.of()));
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.PRACTICE_FLOATING, null));
        var context = new AgentDtos.MessageContextRequest(questionId, null, null, List.of(mediaId), List.of());
        var first = conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "第一问", context), "trace-projection-first");
        complete(first, "第一答");
        runtime.reset();
        var second = conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "第二问", context), "trace-projection-second");
        complete(second, "第二答");

        var page = conversations.messages(OWNER, conversation.id(), 1, 20, "trace-projection-page");
        assertThat(domains.projectionCalls).isEqualTo(1);
        assertThat(domains.lastVersions).containsExactly(new AgentInternalDtos.QuestionVersionKey(questionId, versionId));
        assertThat(page.items()).filteredOn(item -> item.role() == AgentDtos.MessageRole.USER)
                .allSatisfy(item -> assertThat(item.questionContextProjection()).satisfies(projection -> {
                    assertThat(projection.status()).isEqualTo(AgentDtos.QuestionProjectionStatus.AVAILABLE);
                    assertThat(projection.question().questionVersionId()).isEqualTo(versionId);
                    assertThat(projection.question().stem()).isEqualTo("历史题干");
                    assertThat(projection.question().media()).extracting(AgentDtos.QuestionContextMedia::mediaId)
                            .containsExactly(mediaId);
                }));
        assertThatThrownBy(() -> conversations.messages(OTHER, conversation.id(), 1, 20, "trace-foreign"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThat(domains.projectionCalls).isEqualTo(1);

        domains.failure = true;
        assertThat(conversations.messages(OWNER, conversation.id(), 1, 20, "trace-unavailable").items())
                .filteredOn(item -> item.role() == AgentDtos.MessageRole.USER)
                .allSatisfy(item -> assertThat(item.questionContextProjection()).satisfies(projection -> {
                    assertThat(projection.status()).isEqualTo(AgentDtos.QuestionProjectionStatus.UNAVAILABLE);
                    assertThat(projection.question()).isNull();
                }));
    }

    @Test
    /** 历史题图授权只使用本人悬浮消息保存的版本；非 owner 与非悬浮会话统一 404。 */
    void messageBoundQuestionMediaAuthorizationUsesPersistedVersion() {
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.nameUUIDFromBytes((questionId + ":version").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID mediaId = UUID.randomUUID();
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.PRACTICE_FLOATING, null));
        var run = conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "看图", new AgentDtos.MessageContextRequest(questionId, null, null,
                        List.of(mediaId), List.of())), "trace-media-message");
        var grant = conversations.authorizeQuestionMedia(OWNER, conversation.id(), run.userMessage().id(), mediaId,
                new AgentDtos.QuestionMediaAccessRequest("ORIGINAL", AgentDtos.MediaDisposition.INLINE),
                "trace-message-grant");
        assertThat(grant.mediaId()).isEqualTo(mediaId);
        assertThat(domains.lastQuestionVersionId).isEqualTo(versionId);
        assertThatThrownBy(() -> conversations.authorizeQuestionMedia(OTHER, conversation.id(),
                run.userMessage().id(), mediaId,
                new AgentDtos.QuestionMediaAccessRequest("ORIGINAL", AgentDtos.MediaDisposition.INLINE), "trace-other"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
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
    /** 明确讲解偏好只公开本人主动值；默认、CAS、稳定重试和下一轮可信上下文保持一致。 */
    void explicitExplanationPreferenceIsPrivateIdempotentAndEffectiveOnNextRun() {
        assertThat(explanationPreferences.get(OWNER)).satisfies(view -> {
            assertThat(view.value()).isEqualTo(AgentDtos.ExplanationPreference.GUIDED_STEP_BY_STEP);
            assertThat(view.revision()).isZero();
            assertThat(view.explicitlySet()).isFalse();
        });
        jdbc.update("""
                INSERT INTO ai_preference(id,owner_user_id,preference_kind,preference_value,source_type,status,
                    revision,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',1,?,?,?)
                """, UUID.randomUUID().toString(), OWNER.toString(), "EXPLANATION_STYLE", "INFERRED_GUIDANCE",
                "INFERRED", java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        assertThat(explanationPreferences.get(OWNER).explicitlySet()).isFalse();

        UUID requestId = UUID.randomUUID();
        var request = new AgentDtos.UpdateExplanationPreferenceRequest(requestId,
                AgentDtos.ExplanationPreference.COMPLETE_SOLUTION, 0L);
        var first = explanationPreferences.update(OWNER, request);
        var replay = explanationPreferences.update(OWNER, request);
        assertThat(replay).isEqualTo(first);
        assertThat(first.revision()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_preference_command", Integer.class)).isEqualTo(1);
        assertThat(explanationPreferences.get(OTHER).explicitlySet()).isFalse();
        assertThatThrownBy(() -> explanationPreferences.update(OWNER,
                new AgentDtos.UpdateExplanationPreferenceRequest(UUID.randomUUID(),
                        AgentDtos.ExplanationPreference.GUIDED_STEP_BY_STEP, 0L)))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.PREFERENCE_REVISION_CONFLICT"));

        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        var next = conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "验证明确偏好", null), "trace-preference-next-run");
        assertThat(next.context().trustedContext().get("preferences").toString())
                .contains("COMPLETE_SOLUTION", "EXPLICIT")
                .doesNotContain("evidence", "profile");
    }

    @Test
    /** 偏好 HTTP 端点必须有 learning:access，只返回 value/revision/explicitlySet 的本人投影。 */
    void explanationPreferenceHttpRequiresLearningAccessAndHidesPrivateFacts() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var uri = java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/ai/preferences/explanation");
        var denied = java.net.http.HttpRequest.newBuilder(uri).header("X-User-Id", OWNER.toString()).GET().build();
        assertThat(client.send(denied, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);

        UUID requestId = UUID.randomUUID();
        String body = """
                {"clientRequestId":"%s","value":"COMPLETE_SOLUTION","expectedRevision":0}
                """.formatted(requestId);
        var put = java.net.http.HttpRequest.newBuilder(uri).header("X-User-Id", OWNER.toString())
                .header("X-User-Permissions", "learning:access").header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var updated = client.send(put, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("COMPLETE_SOLUTION", "\"revision\":1", "\"explicitlySet\":true")
                .doesNotContain("source", "profile", "evidence", "memory", "ownerUserId");

        var other = java.net.http.HttpRequest.newBuilder(uri).header("X-User-Id", OTHER.toString())
                .header("X-User-Permissions", "learning:access").GET().build();
        var otherResponse = client.send(other, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(otherResponse.statusCode()).isEqualTo(200);
        assertThat(otherResponse.body()).contains("GUIDED_STEP_BY_STEP", "\"revision\":0")
                .doesNotContain("COMPLETE_SOLUTION");
    }

    @Test
    /** 完整回答可基于最后一问追加新回答；同 key 只恢复，换 key 才创建另一个 run。 */
    void regenerationPreservesOriginalHistoryAndIsIdempotent() {
        var original = newRun("trace-regeneration-original");
        complete(original, "原回答");
        String originalContext = jdbc.queryForObject("SELECT context_json FROM ai_message WHERE id=?", String.class,
                original.userMessage().id().toString());
        UUID requestId = UUID.randomUUID();
        var regenerated = conversations.prepareRegeneration(OWNER, original.run().conversationId(),
                original.userMessage().id(), new AgentDtos.RegenerateMessageRequest(requestId),
                "trace-regeneration-first");
        coordinator.open(regenerated);
        assertThat(runtime.streamCalls).isEqualTo(2);
        var replay = conversations.prepareRegeneration(OWNER, original.run().conversationId(),
                original.userMessage().id(), new AgentDtos.RegenerateMessageRequest(requestId),
                "trace-regeneration-retry");
        coordinator.open(replay);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().id()).isEqualTo(regenerated.run().id());
        assertThat(runtime.streamCalls).isEqualTo(2);
        assertThatThrownBy(() -> conversations.prepareRegeneration(OWNER, original.run().conversationId(),
                UUID.randomUUID(), new AgentDtos.RegenerateMessageRequest(requestId),
                "trace-regeneration-key-reuse"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT context_json FROM ai_message WHERE id=?", String.class,
                original.userMessage().id().toString())).isEqualTo(originalContext);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE user_message_id=?", Integer.class,
                original.userMessage().id().toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=? AND role='USER'",
                Integer.class, original.run().conversationId().toString())).isEqualTo(1);

        complete(regenerated, "重新回答一");
        var another = conversations.prepareRegeneration(OWNER, original.run().conversationId(),
                original.userMessage().id(), new AgentDtos.RegenerateMessageRequest(UUID.randomUUID()),
                "trace-regeneration-second");
        assertThat(another.run().id()).isNotEqualTo(regenerated.run().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE user_message_id=?", Integer.class,
                original.userMessage().id().toString())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=? AND role='ASSISTANT'",
                Integer.class, original.run().conversationId().toString())).isEqualTo(3);
    }

    @Test
    /** 中断回答仍可重生成；活动 run、非最后问题和其他 owner 均在创建新事实前安全拒绝。 */
    void regenerationAcceptsInterruptedAnswerAndRejectsUnsafeTargets() {
        var firstConversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        var first = conversations.prepareRun(OWNER, firstConversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "较早问题", null), "trace-regeneration-old");
        coordinator.open(first);
        conversations.terminate(first.run().id(), AgentDtos.RunStatus.CANCELLED,
                "AGENT.CANCELLED", "回答已取消");
        var second = conversations.prepareRun(OWNER, firstConversation.id(), new AgentDtos.SendMessageRequest(
                UUID.randomUUID(), "最后问题", null), "trace-regeneration-interrupted");
        coordinator.open(second);
        conversations.terminate(second.run().id(), AgentDtos.RunStatus.CANCELLED,
                "AGENT.CANCELLED", "回答已取消");

        var regeneration = conversations.prepareRegeneration(OWNER, firstConversation.id(), second.userMessage().id(),
                new AgentDtos.RegenerateMessageRequest(UUID.randomUUID()), "trace-regeneration-after-cancel");
        assertThat(regeneration.userMessage().content()).isEqualTo("最后问题");
        assertThatThrownBy(() -> conversations.prepareRegeneration(OWNER, firstConversation.id(),
                first.userMessage().id(), new AgentDtos.RegenerateMessageRequest(UUID.randomUUID()),
                "trace-regeneration-old-message"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.NOT_FOUND"));
        assertThatThrownBy(() -> conversations.prepareRegeneration(OTHER, firstConversation.id(),
                second.userMessage().id(), new AgentDtos.RegenerateMessageRequest(UUID.randomUUID()),
                "trace-regeneration-other-owner"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.NOT_FOUND"));

        coordinator.open(regeneration);
        assertThatThrownBy(() -> conversations.prepareRegeneration(OWNER, firstConversation.id(),
                second.userMessage().id(), new AgentDtos.RegenerateMessageRequest(UUID.randomUUID()),
                "trace-regeneration-active"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT.RUN_CONFLICT"));
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
    /** 合法外壳中的提示词、推理、Token、工具原始结果或临时 URL 都不会被保存或回显。 */
    void publicStreamRejectsSecretsHiddenInTextFields() {
        for (String unsafe : List.of("system prompt: private", "chainOfThought", "token=secret-value",
                "API Key: secret-value", "rawToolResult", "https://temporary.invalid/signed")) {
            var prepared = newRun("trace-secret-" + Math.abs(unsafe.hashCode()));
            coordinator.open(prepared);
            runtime.emit(event(prepared, 1, "RUN_ACCEPTED", Map.of(
                    "conversationId", prepared.run().conversationId().toString(),
                    "messageId", prepared.userMessage().id().toString(), "replayed", false)));
            runtime.emit(event(prepared, 2, "TEXT_DELTA", Map.of("delta", unsafe)));
            var saved = conversations.events(OWNER, prepared.run().id(), 0);
            assertThat(saved.items()).extracting(AgentDtos.StreamEvent::eventType)
                    .containsExactly("RUN_ACCEPTED", "FINAL_RESULT");
            assertThat(saved.items().toString()).doesNotContain(unsafe);
        }
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
    /** 帮助 HTTP 只接受固定 Runtime 身份与活动真实 run；未知字段、未知 run 和终态都安全失败。 */
    void userHelpHttpUsesCurrentVersionAndRejectsIdentityOrBoundaryEscalation() throws Exception {
        var prepared = newRun("trace-user-help-http");
        var base = "http://127.0.0.1:" + port + "/internal/v1/agent/runs/";
        var uri = java.net.URI.create(base + prepared.run().id() + "/user-help:search");
        var client = java.net.http.HttpClient.newHttpClient();
        var valid = helpRequest(uri, "edu-agent-runtime", "test-only-runtime-callback-token-32-bytes",
                "{\"query\":\"怎么登录\",\"limit\":5}");
        var response = client.send(valid, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("2026.09.1", "help_login_password", "\"directAnswer\":true",
                "\"appRoute\":\"/login\"")
                .doesNotContain("owner", "keywords", "highPrecisionRules", "currentFile");

        assertThat(client.send(helpRequest(uri, "wrong-runtime",
                        "test-only-runtime-callback-token-32-bytes", "{\"query\":\"怎么登录\",\"limit\":1}"),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(helpRequest(uri, "edu-agent-runtime", "wrong-token-value-that-is-long-enough-123",
                        "{\"query\":\"怎么登录\",\"limit\":1}"),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(helpRequest(uri, "edu-agent-runtime",
                        "test-only-runtime-callback-token-32-bytes",
                        "{\"query\":\"怎么登录\",\"limit\":1,\"userId\":\"" + OTHER + "\"}"),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(422);
        var unknown = java.net.URI.create(base + UUID.randomUUID() + "/user-help:search");
        assertThat(client.send(helpRequest(unknown, "edu-agent-runtime",
                        "test-only-runtime-callback-token-32-bytes", "{\"query\":\"怎么登录\",\"limit\":1}"),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);

        var terminal = terminalRun("trace-user-help-terminal");
        var terminalUri = java.net.URI.create(base + terminal.run().id() + "/user-help:search");
        assertThat(client.send(helpRequest(terminalUri, "edu-agent-runtime",
                        "test-only-runtime-callback-token-32-bytes", "{\"query\":\"怎么登录\",\"limit\":1}"),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
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

    private AgentConversationService.PreparedRun practiceRun(String topic) {
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        return conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(UUID.randomUUID(),
                "推荐" + topic + "练习题", null), "trace-practice-" + topic.hashCode());
    }

    private JsonNode practiceProposal(String topic) {
        return mapper.valueToTree(Map.of("skillName", "public-practice-generation", "skillVersion", "1.0.0",
                "topic", topic, "difficulty", "MEDIUM", "requestedCount", 5, "candidates", List.of(Map.of(
                        "questionId", UUID.randomUUID(), "questionVersionId", UUID.randomUUID()))));
    }

    private static java.net.http.HttpRequest helpRequest(java.net.URI uri, String caller, String token,
            String body) {
        return java.net.http.HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("X-Internal-Caller", caller).header("X-Internal-Service-Token", token)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private AgentConversationService.PreparedRun terminalRun(String traceId) {
        var prepared = newRun(traceId);
        coordinator.open(prepared);
        runtime.emit(event(prepared, 1, "FINAL_RESULT", Map.of("terminal", true,
                "terminalStatus", "COMPLETED", "finalAnswer", "完成", "answerCompleteness", "COMPLETE")));
        return prepared;
    }

    /** 让 Fake Runtime 产生真实的首事件与唯一完成终态，供重新生成用例结束活动槽。 */
    private void complete(AgentConversationService.PreparedRun prepared, String answer) {
        if (runtime.events == null) coordinator.open(prepared);
        runtime.emit(event(prepared, 1, "RUN_ACCEPTED", Map.of(
                "conversationId", prepared.run().conversationId().toString(),
                "messageId", prepared.userMessage().id().toString(), "replayed", false)));
        runtime.emit(event(prepared, 2, "FINAL_RESULT", Map.of("terminal", true,
                "terminalStatus", "COMPLETED", "finalAnswer", answer, "answerCompleteness", "COMPLETE")));
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
        AuthorizedContextResolver contextResolver(MemoryContextProvider memories) {
            return (user, entry, input, trace) -> {
                List<UUID> media = input == null ? List.of() : java.util.stream.Stream.concat(
                        input.questionMediaIds() == null ? java.util.stream.Stream.empty()
                                : input.questionMediaIds().stream(),
                        input.aiQuestionMediaIds() == null ? java.util.stream.Stream.empty()
                                : input.aiQuestionMediaIds().stream()).distinct().toList();
                Map<String, Object> trusted = new java.util.LinkedHashMap<>();
                trusted.put("userId", user.toString());
                trusted.put("educationStage", "JUNIOR");
                trusted.put("entryPoint", entry.name());
                trusted.put("authorizedAt", Instant.now().toString());
                List<Map<String, Object>> preferences = memories.preferences(user);
                if (!preferences.isEmpty()) trusted.put("preferences", preferences);
                trusted.put("toolGrants", List.of(Map.of("toolName", "QUESTION_SEARCH", "resourceScopes",
                        List.of(Map.of("scopeType", "PUBLIC_JUNIOR_MATH")))));
                UUID questionId = input == null ? null : input.questionId();
                UUID versionId = questionId == null ? null : UUID.nameUUIDFromBytes(
                        (questionId + ":version").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return new AgentDtos.AuthorizedContext(
                        new AgentDtos.MessageContext(questionId, versionId, null, null, null, media), trusted);
            };
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
        int projectionCalls;
        List<AgentInternalDtos.QuestionVersionKey> lastVersions = List.of();
        List<AgentInternalDtos.QuestionVersionProjection> projections = List.of();
        UUID lastQuestionVersionId;
        final AtomicInteger createPracticeCalls = new AtomicInteger();
        boolean unknownAfterPracticeCreate;
        final Map<UUID, AgentInternalDtos.AutonomousPracticeResult> practices = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public AgentInternalDtos.ValidatePracticeCandidatesResponse validatePracticeCandidates(UUID ownerUserId,
                AgentInternalDtos.PracticeCriteria criteria,
                List<AgentInternalDtos.PracticeCandidateKey> candidates, String traceId) {
            this.lastOwner = ownerUserId;
            return new AgentInternalDtos.ValidatePracticeCandidatesResponse(candidates.stream().map(candidate ->
                    new AgentInternalDtos.ValidatedPracticeCandidate(candidate.questionId(),
                            candidate.questionVersionId(), AgentDtos.PracticeQuestionType.SINGLE_CHOICE,
                            criteria.difficulty())).toList());
        }

        @Override
        public AgentInternalDtos.AutonomousPracticeResult createAutonomousPractice(UUID actionId, UUID ownerUserId,
                AgentInternalDtos.PracticeCriteria criteria,
                List<AgentInternalDtos.PracticeCandidateKey> candidates, String traceId) {
            createPracticeCalls.incrementAndGet();
            var result = practices.computeIfAbsent(actionId, ignored -> {
                UUID assessmentId = UUID.randomUUID();
                return new AgentInternalDtos.AutonomousPracticeResult(UUID.randomUUID(), assessmentId,
                        "/practice/" + assessmentId, false);
            });
            if (unknownAfterPracticeCreate) throw unavailable();
            return result;
        }

        @Override
        public AgentInternalDtos.AutonomousPracticeResult findAutonomousPractice(UUID actionId, UUID ownerUserId,
                String traceId) {
            return practices.get(actionId);
        }

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

        @Override
        public List<AgentInternalDtos.QuestionVersionProjection> projectQuestionVersions(UUID owner,
                List<AgentInternalDtos.QuestionVersionKey> versions, String traceId) {
            this.lastOwner = owner;
            this.projectionCalls++;
            this.lastVersions = List.copyOf(versions);
            if (failure) throw unavailable();
            return projections;
        }

        @Override
        public AgentInternalDtos.MediaAccessGrant authorizeQuestionVersionMedia(UUID owner, UUID questionId,
                UUID questionVersionId, UUID mediaId, String variant, String disposition, String traceId) {
            this.lastOwner = owner;
            this.lastQuestionVersionId = questionVersionId;
            if (failure) throw unavailable();
            return new AgentInternalDtos.MediaAccessGrant(mediaId, "image/png", 128, "GET",
                    "https://temporary.invalid/object", Map.of(), Instant.now().plusSeconds(60));
        }

        private AgentException unavailable() {
            return new AgentException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT.DEPENDENCY_UNAVAILABLE", "fixture unavailable");
        }

        void reset() { lastOwner = null; allowed = Set.of(); failure = false; projectionCalls = 0;
            lastVersions = List.of(); projections = List.of(); lastQuestionVersionId = null;
            createPracticeCalls.set(0); unknownAfterPracticeCreate = false; practices.clear(); }
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
