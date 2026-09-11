package com.eduplatform.agentaccess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 短期运行治理参数。所有阈值都是部署可调默认值，不代表已确认的生产配额。
 *
 * @param enabled 是否启用 Redis 治理；local 必须启用，非持久化基础启动可关闭
 * @param keyPrefix Agent 独占且版本化的 Redis Key 前缀
 * @param instanceId 当前 Java 实例标识，只用于安全运行元数据
 * @param leaseTtl 执行租约存活时间
 * @param renewInterval 持有实例续租和观察取消标记的间隔，必须小于租约时间
 * @param cancellationTtl 跨实例取消标记的最长保留时间
 * @param recentRunTtl 最近运行安全元数据和幂等预留的保留时间
 * @param maxConcurrentRunsPerUser 单用户跨会话最大活动运行数
 * @param maxRunsPerMinute 单用户分钟窗口最多新运行数
 * @param maxReservedTokensPerDay 单用户每天可预留的 Token 上限
 * @param reservedTokensPerRun 新运行开始前的保守 Token 预留量
 * @param orphanScanInterval 孤儿 run 低频复核间隔；每次仍须满足租约宽限和 Redis 明确无租约
 * @param followerPollInterval 跨实例恢复轮询 MySQL 已保存事件的间隔
 * @param followerMaxDuration 单个恢复连接有界跟随的最长时间
 */
@ConfigurationProperties("platform.agent-governance")
public record AgentGovernanceProperties(boolean enabled, String keyPrefix, String instanceId,
        Duration leaseTtl, Duration renewInterval, Duration cancellationTtl, Duration recentRunTtl,
        int maxConcurrentRunsPerUser, int maxRunsPerMinute, long maxReservedTokensPerDay,
        long reservedTokensPerRun, Duration orphanScanInterval,
        Duration followerPollInterval, Duration followerMaxDuration) {
}
