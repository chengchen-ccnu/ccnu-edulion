package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import com.eduplatform.agentaccess.runtime.AgentRuntimeClient;
import com.eduplatform.agentaccess.web.AgentDtos;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    private final RunGovernance governance;
    private final com.eduplatform.agentaccess.config.AgentGovernanceProperties properties;
    private final ConcurrentHashMap<UUID, Execution> executions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "agent-run-governance");
        thread.setDaemon(true);
        return thread;
    });

    public AgentRunCoordinator(AgentConversationService conversations, AgentRuntimeClient runtime,
            ObjectMapper mapper, RunGovernance governance,
            com.eduplatform.agentaccess.config.AgentGovernanceProperties properties) {
        this.conversations = conversations;
        this.runtime = runtime;
        this.mapper = mapper;
        this.governance = governance;
        this.properties = properties;
    }

    /** 实例关闭时终止续租/跟随线程；未终态 run 由租约到期后的启动恢复收口。 */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 打开 SSE：先重放已保存事件，新 run 再且只再启动一个 Python 执行。 */
    public SseEmitter open(AgentConversationService.PreparedRun prepared) {
        SseEmitter emitter = new SseEmitter(100_000L);
        RunRow run = prepared.run();
        long replayedThrough = 0;
        for (AgentDtos.StreamEvent event : conversations.events(run.ownerUserId(), run.id(), replayedThrough).items()) {
            if (!send(emitter, event)) return emitter;
            replayedThrough = event.sequence();
        }
        RunRow current = conversations.requireRun(run.ownerUserId(), run.id());
        if (current.terminal()) {
            // run 状态可能在首次事件查询后才转终态；结束前补读实际未发送的尾部事件。
            for (AgentDtos.StreamEvent event : conversations.events(
                    run.ownerUserId(), run.id(), replayedThrough).items()) {
                if (!send(emitter, event)) return emitter;
            }
            emitter.complete();
            return emitter;
        }
        if (prepared.replayed() || executions.containsKey(run.id())) {
            // 重连统一跟随 MySQL，以实际已发送 sequence 为游标，避免注册进程内广播时出现查询缝隙。
            followSavedEvents(run, emitter, replayedThrough);
            return emitter;
        }
        Execution created = new Execution(run, prepared, emitter);
        Execution raced = executions.putIfAbsent(run.id(), created);
        if (raced != null) {
            followSavedEvents(run, emitter, replayedThrough);
            return emitter;
        }
        try {
            created.handle = runtime.stream(internalRequest(prepared),
                    event -> accept(created, event), failure -> fail(created), () -> complete(created));
            if (!created.terminal.get()) {
                created.renewal = scheduler.scheduleWithFixedDelay(() -> maintainLease(created),
                        properties.renewInterval().toMillis(), properties.renewInterval().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            registerDisconnect(created, emitter);
        } catch (RuntimeException unavailable) {
            fail(created);
        }
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
            if (execution == null) {
                // 请求可能落到非 owner 实例；Redis 标记由真正持有者在事件/续租边界观察。
                governance.requestCancellation(runId);
            }
            try {
                runtime.cancel(runId, request.cancelRequestId(), latest.traceId(), internalReason(request.reason()));
            } catch (RuntimeException ignored) {
                // 本地取消事实优先；依赖故障不得让模型继续占用活动槽，也不得把内部异常暴露给用户。
            }
            if (execution == null) {
                AgentDtos.CancelRunResponse response = new AgentDtos.CancelRunResponse(runId, latest.status(), true,
                        false, latest.terminalEventSequence());
                conversations.saveCancellation(runId, request, response);
                return response;
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
            release(execution);
        }
        return response;
    }

    private void accept(Execution execution, JsonNode internal) {
        if (execution.terminal.get()) return;
        try {
            if (governance.cancellationRequested(execution.run.id())) {
                stop(execution, AgentDtos.RunStatus.CANCELLED, "AGENT.CANCELLED");
                return;
            }
            if (!governance.renew(execution.prepared.lease())) {
                stop(execution, AgentDtos.RunStatus.FAILED, "AGENT.LEASE_LOST");
                return;
            }
            AgentDtos.StreamEvent event = conversations.appendRuntimeEvent(execution.run.id(), internal);
            governance.recordProgress(execution.prepared.lease(),
                    "FINAL_RESULT".equals(event.eventType()) ? "TERMINAL" : "RUNNING", event.sequence());
            execution.emitters.removeIf(emitter -> !send(emitter, event));
            if ("FINAL_RESULT".equals(event.eventType())) {
                execution.terminal.set(true);
                executions.remove(execution.run.id(), execution);
                release(execution);
                finishEmitters(execution);
            } else if (execution.emitters.isEmpty()) {
                // 写事件后才发现唯一客户端不可写：保留刚落库片段并有限取消，不继续后台生成。
                stop(execution, AgentDtos.RunStatus.CANCELLED, "AGENT.CLIENT_DISCONNECTED");
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
        release(execution);
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
        release(execution);
    }

    /** 定时续租并观察跨实例取消；任一失败都先停止上游，再保存唯一安全终态。 */
    private void maintainLease(Execution execution) {
        if (execution.terminal.get()) return;
        try {
            if (governance.cancellationRequested(execution.run.id())) {
                stop(execution, AgentDtos.RunStatus.CANCELLED, "AGENT.CANCELLED");
            } else if (!governance.renew(execution.prepared.lease())) {
                stop(execution, AgentDtos.RunStatus.FAILED, "AGENT.LEASE_LOST");
            }
        } catch (RuntimeException unavailable) {
            stop(execution, AgentDtos.RunStatus.FAILED, "AGENT.GOVERNANCE_UNAVAILABLE");
        }
    }

    /** 停止模型/工具上游并仅尝试写一个中断终态；已发送供应商的请求仍可能计费。 */
    private void stop(Execution execution, AgentDtos.RunStatus status, String code) {
        if (!execution.terminal.compareAndSet(false, true)) return;
        executions.remove(execution.run.id(), execution);
        if (execution.handle != null) execution.handle.cancel();
        try {
            runtime.cancel(execution.run.id(), UUID.randomUUID(), execution.run.traceId(),
                    status == AgentDtos.RunStatus.CANCELLED ? "USER_STOPPED" : "CLIENT_DISCONNECTED");
        } catch (RuntimeException ignored) { }
        long previous = conversations.requireRun(execution.run.ownerUserId(), execution.run.id()).lastEventSequence();
        conversations.terminate(execution.run.id(), status, code, "回答已中断");
        conversations.events(execution.run.ownerUserId(), execution.run.id(), previous).items()
                .forEach(event -> execution.emitters.removeIf(emitter -> !send(emitter, event)));
        release(execution);
        finishEmitters(execution);
    }

    /** 跨实例恢复以固定最小间隔轮询 MySQL，达到终态、断连或时长上限即释放线程。 */
    private void followSavedEvents(RunRow run, SseEmitter emitter, long afterSequence) {
        scheduler.execute(() -> {
            long cursor = afterSequence;
            Instant deadline = Instant.now().plus(properties.followerMaxDuration());
            try {
                while (Instant.now().isBefore(deadline)) {
                    var page = conversations.events(run.ownerUserId(), run.id(), cursor);
                    for (AgentDtos.StreamEvent event : page.items()) {
                        if (!send(emitter, event)) return;
                        cursor = event.sequence();
                    }
                    if (conversations.requireRun(run.ownerUserId(), run.id()).terminal()) {
                        // 终态与事件在同一 MySQL 事务提交；观察到终态后补读一次可消除查询竞态。
                        for (AgentDtos.StreamEvent event : conversations.events(
                                run.ownerUserId(), run.id(), cursor).items()) {
                            if (!send(emitter, event)) return;
                            cursor = event.sequence();
                        }
                        return;
                    }
                    Thread.sleep(properties.followerPollInterval().toMillis());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // 跟随失败只结束当前浏览器连接，不改变 owner 实例的运行事实。
            } finally {
                emitter.complete();
            }
        });
    }

    /** token 匹配释放租约；Redis 故障不覆盖已经写入的 MySQL 终态。 */
    private void release(Execution execution) {
        if (execution.renewal != null) execution.renewal.cancel(false);
        if (execution.prepared.lease() != null) {
            try {
                governance.release(execution.prepared.lease());
            } catch (RuntimeException ignored) { }
        }
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
                Map.entry("recentMessages", prepared.recentMessages()),
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
        private volatile ScheduledFuture<?> renewal;

        private Execution(RunRow run, AgentConversationService.PreparedRun prepared, SseEmitter emitter) {
            this.run = run;
            this.prepared = prepared;
            this.emitters.add(emitter);
        }
    }
}
