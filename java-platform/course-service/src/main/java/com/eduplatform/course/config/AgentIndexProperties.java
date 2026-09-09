package com.eduplatform.course.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent Index Worker 读取课程文字投影的内部开关与共享令牌；令牌禁止进入仓库和日志。 */
@ConfigurationProperties("platform.course.agent-index")
public record AgentIndexProperties(boolean enabled, String serviceToken) {}
