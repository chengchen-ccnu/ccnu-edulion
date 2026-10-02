package com.eduplatform.learning.messaging;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 声明导入 durable Queue/DLX/DLQ；首次处理加两次退避重试，成功后 AUTO ACK。 */
@Configuration
@ConditionalOnProperty(prefix = "platform.learning.import", name = "messaging-enabled", havingValue = "true")
public class LearningRabbitConfig {
    /** 任务通知生产者自己声明版本化交换机，Operation独立队列后续绑定；不依赖消费者先启动。 */
    /** 热点日资格复用现有有限Outbox发布，生产者独立声明交换机。 */
    @Bean TopicExchange hotLearningExchange() { return new TopicExchange("edu.learning.hot-course.events.v1",true,false); }
    @Bean TopicExchange taskNotificationExchange() { return new TopicExchange("edu.learning.task-notifications.events.v1",true,false); }
    @Bean
    SimpleRabbitListenerContainerFactory learningImportContainerFactory(ConnectionFactory connectionFactory,
            MessageRecoverer learningImportRecoverer) {
        Advice retry = RetryInterceptorBuilder.stateless().maxRetries(2).backOffOptions(500, 2.0, 4000)
                .recoverer(learningImportRecoverer).build();
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory); factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false); factory.setAdviceChain(retry); factory.setPrefetchCount(1);
        return factory;
    }

    @Bean TopicExchange learningEventsExchange() { return new TopicExchange(LearningRabbitTopology.EVENTS_EXCHANGE, true, false); }
    @Bean TopicExchange learningEventsExchangeV2() { return new TopicExchange(LearningRabbitTopology.EVENTS_EXCHANGE_V2, true, false); }
    /** T13.2 Worker 后续声明队列；生产者只声明统一 durable topic exchange。 */
    @Bean TopicExchange agentIndexEventsExchange() { return new TopicExchange(LearningEvents.AGENT_INDEX_EXCHANGE, true, false); }
    @Bean DirectExchange learningDeadExchange() { return new DirectExchange(LearningRabbitTopology.DEAD_EXCHANGE, true, false); }
    @Bean Queue learningImportQueue() { return QueueBuilder.durable(LearningRabbitTopology.IMPORT_QUEUE)
            .deadLetterExchange(LearningRabbitTopology.DEAD_EXCHANGE).deadLetterRoutingKey(LearningRabbitTopology.IMPORT_DEAD_KEY).build(); }
    @Bean Queue learningImportDeadQueue() { return QueueBuilder.durable(LearningRabbitTopology.IMPORT_DEAD_QUEUE).build(); }
    @Bean Binding learningImportBinding(Queue learningImportQueue, TopicExchange learningEventsExchange) {
        return BindingBuilder.bind(learningImportQueue).to(learningEventsExchange).with(LearningRabbitTopology.IMPORT_KEY); }
    @Bean Binding learningImportDeadBinding(Queue learningImportDeadQueue, DirectExchange learningDeadExchange) {
        return BindingBuilder.bind(learningImportDeadQueue).to(learningDeadExchange).with(LearningRabbitTopology.IMPORT_DEAD_KEY); }
}
