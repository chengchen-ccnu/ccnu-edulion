package com.eduplatform.learning.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 集中启用 Learning 的业务、进度、索引和 Agent 私有读取配置；秘密只从部署环境注入。 */
@Configuration
@EnableConfigurationProperties({LearningProperties.class, ProgressBufferProperties.class, AgentIndexProperties.class,
        AgentAccessProperties.class})
public class LearningConfiguration {}
