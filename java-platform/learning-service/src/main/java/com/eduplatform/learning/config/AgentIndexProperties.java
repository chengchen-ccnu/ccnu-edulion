package com.eduplatform.learning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent Index Worker 读取 Learning 安全投影的内部开关与共享令牌；令牌只可由本机秘密环境注入。 */
@ConfigurationProperties("platform.learning.agent-index")
public record AgentIndexProperties(boolean enabled, String serviceToken) {}
