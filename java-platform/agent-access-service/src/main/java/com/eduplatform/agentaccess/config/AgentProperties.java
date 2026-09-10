package com.eduplatform.agentaccess.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Access 的连接、保留窗口与内部服务身份参数；令牌只允许由部署 Secret 或本地 DPAPI 包装器注入。
 *
 * @param persistenceEnabled 仅 local/test 启用；默认构建不连接数据库
 * @param runtimeBaseUrl Python 内部协议地址，不向 Gateway 暴露
 * @param runtimeRequestTimeout 单次流的客户端上限，应略大于内部总预算
 * @param learningBaseUrl Learning 领域 API 地址
 * @param courseBaseUrl Course 领域 API 地址
 * @param domainRequestTimeout 领域授权投影同步读取上限
 * @param protocolVersion T13.1 内部协议头版本
 * @param runtimeCallbackToken Python 回调 Agent Access 的服务身份秘密；不得写入日志或浏览器响应
 * @param domainServiceToken Agent Access 调用 Course/Learning 私有只读投影的服务身份秘密
 * @param recentMessageMaxTurns 最近消息窗口最多保留的完整轮数
 * @param recentMessageMaxChars 最近消息窗口全部正文的总字符上限
 */
@ConfigurationProperties("platform.agent")
public record AgentProperties(boolean persistenceEnabled, String runtimeBaseUrl, Duration runtimeRequestTimeout,
        String learningBaseUrl, String courseBaseUrl, Duration domainRequestTimeout,
        String protocolVersion, String runtimeCallbackToken, String domainServiceToken,
        int recentMessageMaxTurns, int recentMessageMaxChars) {
}
