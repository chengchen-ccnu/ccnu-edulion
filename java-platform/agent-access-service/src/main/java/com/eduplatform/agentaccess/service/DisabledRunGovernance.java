package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 仅供不连接外部依赖的非 local 基础启动；持久化 local 配置始终启用 Redis 实现。 */
@Component
@ConditionalOnProperty(prefix = "platform.agent-governance", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledRunGovernance implements RunGovernance {
    public Reservation reserve(UUID owner, UUID conversation, UUID request) {
        return new Reservation(UUID.randomUUID(), owner, conversation, request, "", "", "", "", true);
    }
    public Lease bind(Reservation reservation, RunRow run) {
        return new Lease(run.id(), run.ownerUserId(), UUID.randomUUID().toString(), "", "");
    }
    public boolean renew(Lease lease) { return true; }
    public boolean hasValidLease(UUID runId) { return false; }
    public boolean cancellationRequested(UUID runId) { return false; }
    public void recordProgress(Lease lease, String status, long lastSequence) { }
    public void requestCancellation(UUID runId) { }
    public void release(Lease lease) { }
    public void rollback(Reservation reservation) { }
}
