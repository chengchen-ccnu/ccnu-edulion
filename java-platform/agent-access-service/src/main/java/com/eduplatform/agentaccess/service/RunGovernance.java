package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.persistence.AgentRepository.RunRow;
import java.util.UUID;

/** Redis 短期治理端口；MySQL 的消息、运行、事件和最终用量仍是唯一事实源。 */
public interface RunGovernance {
    record Reservation(UUID id, UUID ownerUserId, UUID conversationId, UUID clientRequestId,
            String idempotencyKey, String activeKey, String minuteKey, String dayKey, boolean acquired) {}
    record Lease(UUID runId, UUID ownerUserId, String ownerToken, String leaseKey, String activeKey) {}

    /** 为新逻辑请求原子预留并发位、分钟次数和当日 Token；超限为 429，Redis 故障为 503。 */
    Reservation reserve(UUID ownerUserId, UUID conversationId, UUID clientRequestId);
    /** 将已提交的 MySQL run 原子绑定到不可猜测 owner token；失败由调用方收口 run。 */
    Lease bind(Reservation reservation, RunRow run);
    /** 匹配 owner token 后续租，并同时观察跨实例取消；false 表示必须停止后续工作。 */
    boolean renew(Lease lease);
    /** 只判断是否存在有效租约；Redis 不可用时抛 503，恢复任务据此保持安全不误杀。 */
    boolean hasValidLease(UUID runId);
    /** 读取有界取消标记；仅含布尔状态，不读取或保存取消正文。 */
    boolean cancellationRequested(UUID runId);
    /** 更新最近 run 的安全状态和已持久化序号；不得传入事件正文或回答。 */
    void recordProgress(Lease lease, String status, long lastSequence);
    /** 写有界取消标记，让真正持有实例在事件或续租边界停止上游。 */
    void requestCancellation(UUID runId);
    /** owner token 匹配才释放租约和用户活动成员，错误 token 不产生副作用。 */
    void release(Lease lease);
    /** 创建 run 前后失败时原子回退尚未绑定的预留；不会回退已经消费的成功运行。 */
    void rollback(Reservation reservation);
}
