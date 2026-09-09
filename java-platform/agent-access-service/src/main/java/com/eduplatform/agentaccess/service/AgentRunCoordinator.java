package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import com.eduplatform.agentaccess.runtime.AgentRuntimeClient;
import com.eduplatform.agentaccess.web.AgentDtos;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 将一个数据库 run 绑定到一个 Python 流并向当前进程内的重连客户端广播。
 *
 * <p>每个事件先持久化再发送；断开最后一个浏览器连接会停止上游并落唯一 CANCELLED 终态，
 * 依赖错误或无终态完成会落 FAILED。所有恢复读取都来自数据库。</p>
 */
@Service
@ConditionalOnProperty(prefix = "platform.agent", name = "persistence-enabled", havingValue = "true")
public class AgentRunCoordinator {
    private final AgentConversationService conversations;
    private final AgentRuntimeClient runtime;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<UUID, Execution> executions = new ConcurrentHashMap<>();

    public AgentRunCoordinator(AgentConversationService conversations, AgentRuntimeClient runtime,
            ObjectMapper mapper) {
        this.conversations = conversations;
        this.runtime = runtime;
        this.mapper = mapper;
    }

    /** 打开 SSE：先重放已保存事件，新 run 再且只再启动一个 Python 执行。 */
    public SseEmitter open(AgentConversationService.PreparedRun prepared) {
        SseEmitter emitter = new SseEmitter(100_000L);
        RunRow run = prepared.run();
        for (AgentDtos.StreamEvent event : conversations.events(run.ownerUserId(), run.id(), 0).items()) {
            if (!send(emitter, event)) return emitter;
        }
        RunRow current = conversations.requireRun(run.ownerUserId(), run.id());
        if (current.terminal()) {
            emitter.complete();
            return emitter;
        }
        Execution execution = executions.compute(run.id(), (id, present) -> {
            if (present != null) {
                present.emitters.add(emitter);
                return present;
            }
            if (prepared.replayed()) {
                // 另一个实例可能仍持有真实上游句柄；这里只返回已保存快照，不能误杀仍在运行的 run。
                emitter.complete();
                return null;
            }
            Execution created = new Execution(run, prepared, emitter);
            created.handle = runtime.stream(internalRequest(prepared),
                    event -> accept(created, event), failure -> fail(created), () -> complete(created));
            return created;
        });
        if (execution != null) registerDisconnect(execution, emitter);
        return emitter;
    }

    /** owner 校验后幂等取消；Python 取消失败也不阻止 Java 释放活动槽并保存安全终态。 */
    public AgentDtos.CancelRunResponse cancel(UUID owner, UUID runId, AgentDtos.CancelRunRequest request) {
        RunRow run = conversations.requireRun(owner, runId);
        AgentDtos.CancelRunResponse saved = conversations.savedCancellation(runId, request.cancelRequestId());
        if (saved != null) return saved;
        Execution execution = executions.remove(runId);
        RunRow latest = conversations.requireRun(owner, runId);
        boolean accepted = !latest.terminal();
        long previousSequence = latest.lastEventSequence();
        if (execution != null) {
            execution.terminal.set(true);
            execution.handle.cancel();
        }
        if (accepted) {
            try {
                runtime.cancel(runId, request.cancelRequestId(), latest.traceId(), internalReason(request.reason()));
            } catch (RuntimeException ignored) {
                // 本地取消事实优先；依赖故障不得让模型继续占用活动槽，也不得把内部异常暴露给用户。
            }
            conversations.terminate(runId, AgentDtos.RunStatus.CANCELLED, "AGENT.CANCELLED", "回答已中断");
        }
        RunRow terminal = conversations.requireRun(owner, runId);
        AgentDtos.CancelRunResponse response = new AgentDtos.CancelRunResponse(runId, terminal.status(), accepted,
                terminal.terminal(), terminal.terminalEventSequence());
        conversations.saveCancellation(runId, request, response);
        if (execution != null) {
            conversations.events(owner, runId, previousSequence).items()
                    .forEach(event -> execution.emitters.removeIf(emitter -> !send(emitter, event)));
            finishEmitters(execution);
        }
        return response;
    }

    private void accept(Execution execution, JsonNode internal) {
        if (execution.terminal.get()) return;
        try {
            AgentDtos.StreamEvent event = conversations.appendRuntimeEvent(execution.run.id(), internal);
            execution.emitters.removeIf(emitter -> !send(emitter, event));
            if ("FINAL_RESULT".equals(event.eventType())) {
                execution.terminal.set(true);
                executions.remove(execution.run.id(), execution);
                finishEmitters(execution);
            }
        } catch (RuntimeException invalid) {
            fail(execution);
        }
    }

    private void fail(Execution execution) {
        if (!execution.terminal.compareAndSet(false, true)) return;
        if (execution.handle != null) execution.handle.cancel();
        long previousSequence = conversations.requireRun(execution.run.ownerUserId(), execution.run.id()).lastEventSequence();
        conversations.terminate(execution.run.id(), AgentDtos.RunStatus.FAILED,
                "AGENT.RUNTIME_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试");
        conversations.events(execution.run.ownerUserId(), execution.run.id(), previousSequence).items()
                .forEach(event -> execution.emitters.removeIf(emitter -> !send(emitter, event)));
        executions.remove(execution.run.id(), execution);
        finishEmitters(execution);
    }

    private void complete(Execution execution) {
        if (!execution.terminal.get()) fail(execution);
    }

    private void registerDisconnect(Execution execution, SseEmitter emitter) {
        emitter.onTimeout(() -> disconnect(execution, emitter));
        emitter.onError(ignored -> disconnect(execution, emitter));
        emitter.onCompletion(() -> {
            if (!execution.terminal.get()) disconnect(execution, emitter);
        });
    }

    private void disconnect(Execution execution, SseEmitter emitter) {
        execution.emitters.remove(emitter);
        if (!execution.emitters.isEmpty() || !execution.terminal.compareAndSet(false, true)) return;
        executions.remove(execution.run.id(), execution);
        if (execution.handle != null) execution.handle.cancel();
        try {
            runtime.cancel(execution.run.id(), UUID.randomUUID(), execution.run.traceId(), "CLIENT_DISCONNECTED");
        } catch (RuntimeException ignored) { }
        conversations.terminate(execution.run.id(), AgentDtos.RunStatus.CANCELLED,
                "AGENT.CLIENT_DISCONNECTED", "回答已中断");
    }

    private JsonNode internalRequest(AgentConversationService.PreparedRun prepared) {
        Map<String, Object> budget = Map.of("maxOrchestrationSteps", 12, "maxLlmCalls", 8,
                "reservedFinalLlmCalls", 1, "maxToolAttempts", 10, "maxExpertTransfers", 2,
                "maxSameToolArgumentsAttempts", 2, "maxTeachingRemediations", 3,
                "teachingRemediationOrder", List.of("REPHRASE", "SIMPLE_EXAMPLE", "PREREQUISITE_FALLBACK"),
                "totalTimeMs", 90_000);
        Map<String, Object> timeout = Map.of("firstEventTimeoutMs", 5_000, "heartbeatIntervalMs", 10_000,
                "idleTimeoutMs", 20_000, "slowClientBufferMaxEvents", 64);
        Map<String, Object> request = Map.ofEntries(
                Map.entry("protocolVersion", 1),
                Map.entry("conversationId", prepared.run().conversationId().toString()),
                Map.entry("messageId", prepared.userMessage().id().toString()),
                Map.entry("runId", prepared.run().id().toString()),
                Map.entry("clientRequestId", prepared.userMessage().clientRequestId().toString()),
                Map.entry("traceId", prepared.run().traceId()),
                Map.entry("requestedAt", prepared.run().createdAt().toString()),
                Map.entry("userMessage", Map.of("text", prepared.userMessage().content(), "locale", "zh-CN")),
                Map.entry("trustedContext", prepared.context().trustedContext()),
                Map.entry("budget", budget), Map.entry("timeoutPolicy", timeout));
        return mapper.valueToTree(request);
    }

    private static boolean send(SseEmitter emitter, AgentDtos.StreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.eventType()).id(Long.toString(event.sequence())).data(event));
            return true;
        } catch (IOException | IllegalStateException failure) {
            return false;
        }
    }

    private static void finishEmitters(Execution execution) {
        execution.emitters.forEach(SseEmitter::complete);
        execution.emitters.clear();
    }

    private static String internalReason(AgentDtos.CancelReason reason) {
        return switch (reason) {
            case USER_STOP -> "USER_STOPPED";
            case CLIENT_DISCONNECTED -> "CLIENT_DISCONNECTED";
            case PAGE_CLOSED -> "PAGE_CLOSED";
        };
    }

    private static final class Execution {
        private final RunRow run;
        private final AgentConversationService.PreparedRun prepared;
        private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private volatile AgentRuntimeClient.StreamHandle handle;

        private Execution(RunRow run, AgentConversationService.PreparedRun prepared, SseEmitter emitter) {
            this.run = run;
            this.prepared = prepared;
            this.emitters.add(emitter);
        }
    }
}
