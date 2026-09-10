package com.eduplatform.agentaccess.messaging;

import com.eduplatform.agentaccess.service.MemoryFactService;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Agent Access 只声明统一索引事件 exchange；消费队列、有限重试和 DLQ 继续由现有 Worker 拥有。 */
@Configuration
@ConditionalOnProperty(prefix = "platform.agent-index", name = "publish-enabled", havingValue = "true")
public class AgentIndexRabbitConfig {
    @Bean
    TopicExchange agentMemoryIndexExchange() {
        return new TopicExchange(MemoryFactService.INDEX_EXCHANGE, true, false);
    }
}
