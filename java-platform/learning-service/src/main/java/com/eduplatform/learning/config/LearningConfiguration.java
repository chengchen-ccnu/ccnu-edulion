package com.eduplatform.learning.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用 T09 类型安全配置绑定；秘密仍由 Spring/RabbitMQ 标准环境变量管理。 */
@Configuration
@EnableConfigurationProperties({LearningProperties.class, ProgressBufferProperties.class, AgentIndexProperties.class})
public class LearningConfiguration {}
