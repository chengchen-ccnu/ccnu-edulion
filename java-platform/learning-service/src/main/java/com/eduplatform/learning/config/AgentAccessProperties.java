package com.eduplatform.learning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent Access 读取当前用户学习事实的开关和服务身份秘密。 */
@ConfigurationProperties("platform.learning.agent-access")
public record AgentAccessProperties(boolean enabled, String serviceToken) {}
