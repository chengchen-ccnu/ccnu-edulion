package com.eduplatform.agentaccess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Access 的非敏感连接与保留参数。
 *
 * @param persistenceEnabled 仅 local/test 启用；默认构建不连接数据库
 * @param runtimeBaseUrl Python 内部协议地址，不向 Gateway 暴露
 * @param runtimeRequestTimeout 单次流的客户端上限，应略大于内部总预算
 * @param learningBaseUrl Learning 领域 API 地址
 * @param courseBaseUrl Course 领域 API 地址
 * @param domainRequestTimeout 领域授权投影同步读取上限
 * @param protocolVersion T13.1 内部协议头版本
 */
@ConfigurationProperties("platform.agent")
public record AgentProperties(boolean persistenceEnabled, String runtimeBaseUrl, Duration runtimeRequestTimeout,
        String learningBaseUrl, String courseBaseUrl, Duration domainRequestTimeout,
        String protocolVersion) {
}
