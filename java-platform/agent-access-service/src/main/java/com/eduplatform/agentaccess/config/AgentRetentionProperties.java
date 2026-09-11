package com.eduplatform.agentaccess.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 原始消息清理和媒体 Outbox 配置；清理默认关闭，部署确认保留规则后显式启用。
 *
 * @param enabled 是否运行定时正文清理
 * @param batchSize 每次事务最多处理的消息数
 * @param mediaPublishEnabled 是否发布媒体引用 Outbox
 * @param maxPublishAttempts 单条媒体事件有限发布次数，耗尽后保留 FAILED
 */
@ConfigurationProperties("platform.agent-retention")
public record AgentRetentionProperties(boolean enabled, int batchSize, boolean mediaPublishEnabled,
        int maxPublishAttempts) {}
