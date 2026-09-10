package com.eduplatform.course.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent Access 调用课程二次授权投影的开关和服务身份秘密。 */
@ConfigurationProperties("platform.course.agent-access")
public record AgentAccessProperties(boolean enabled, String serviceToken) {}
