package com.eduplatform.agentaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.agentaccess.runtime.AgentRuntimeClient;
import com.eduplatform.agentaccess.service.AgentConversationService;
import com.eduplatform.agentaccess.service.AgentException;
import com.eduplatform.agentaccess.service.AgentRunCoordinator;
import com.eduplatform.agentaccess.service.AuthorizedContextResolver;
import com.eduplatform.agentaccess.web.AgentDtos;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@SpringBootTest
@ActiveProfiles("test")
class AgentAccessIntegrationTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000102");

    @Autowired AgentConversationService conversations;
    @Autowired AgentRunCoordinator coordinator;
    @Autowired FakeRuntime runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_tool_call");
        jdbc.update("DELETE FROM ai_run_cancellation");
        jdbc.update("DELETE FROM ai_run_event");
        jdbc.update("DELETE FROM ai_run");
        jdbc.update("DELETE FROM ai_message");
        jdbc.update("DELETE FROM ai_conversation");
        runtime.reset();
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
    /** 无本地执行句柄的幂等恢复只返回保存快照，不会把其他实例可能仍执行的 run 误标失败。 */
    void replayWithoutLocalHandleDoesNotTerminateActiveRun() {
        var prepared = newRun("trace-agent-replay-snapshot");
        var replayed = conversations.prepareRun(OWNER, prepared.run().conversationId(),
                new AgentDtos.SendMessageRequest(prepared.userMessage().clientRequestId(),
                        prepared.userMessage().content(), null), "trace-agent-retry");
        assertThat(replayed.replayed()).isTrue();

        coordinator.open(replayed);

        assertThat(conversations.requireRun(OWNER, prepared.run().id()).status())
                .isEqualTo(AgentDtos.RunStatus.ACCEPTED);
        assertThat(conversations.events(OWNER, prepared.run().id(), 0).items()).isEmpty();
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

    private AgentConversationService.PreparedRun newRun(String traceId) {
        var conversation = conversations.create(OWNER, new AgentDtos.CreateConversationRequest(UUID.randomUUID(),
                AgentDtos.EntryPoint.INDEPENDENT_CHAT, null));
        return conversations.prepareRun(OWNER, conversation.id(), new AgentDtos.SendMessageRequest(UUID.randomUUID(),
                "请给我一个提示", null), traceId);
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
    }

    static final class FakeRuntime implements AgentRuntimeClient {
        private Consumer<JsonNode> events;
        private Consumer<Throwable> errors;

        @Override
        public StreamHandle stream(JsonNode request, Consumer<JsonNode> onEvent,
                Consumer<Throwable> onError, Runnable onComplete) {
            this.events = onEvent;
            this.errors = onError;
            return () -> { };
        }

        @Override
        public void cancel(UUID runId, UUID cancelRequestId, String traceId, String reason) { }

        void emit(JsonNode event) { events.accept(event); }
        void fail() { errors.accept(new IllegalStateException("fixture failure")); }
        void reset() { events = null; errors = null; }
    }
}
