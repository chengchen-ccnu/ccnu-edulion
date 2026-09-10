package com.eduplatform.agentaccess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启动独立 Agent Access 服务；该服务只持有 AI 会话事实并通过 HTTP 调用领域服务和 Python。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AgentAccessServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentAccessServiceApplication.class, args);
    }
}
