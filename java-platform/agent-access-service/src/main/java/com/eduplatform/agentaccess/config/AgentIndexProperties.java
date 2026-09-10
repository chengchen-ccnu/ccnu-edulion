package com.eduplatform.agentaccess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Access 记忆索引源与 Outbox 发布配置。
 *
 * @param enabled 是否开放仅服务间可见的记忆投影
 * @param publishEnabled 是否运行 Outbox 发布器，与只读投影开关分离便于无 Rabbit 测试
 * @param serviceToken Index Worker 专用令牌，只能由部署 Secret 或本地 DPAPI 注入
 * @param confirmTimeout RabbitMQ publisher confirm 最长等待时间
 * @param maxPublishAttempts Outbox 发布总尝试上限，耗尽后保留 FAILED 供运维重投
 */
@ConfigurationProperties("platform.agent-index")
public record AgentIndexProperties(boolean enabled, boolean publishEnabled, String serviceToken, Duration confirmTimeout,
        int maxPublishAttempts) {}
