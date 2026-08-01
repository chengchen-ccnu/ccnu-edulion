package com.eduplatform.media.messaging;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明媒体域 durable Exchange/Queue/DLX/DLQ 与消费容器语义。
 *
 * <p>消费者最多执行 3 次（首次 + 2 次指数退避重试）；业务方法成功返回后才 AUTO ACK，
 * 超限由 Recoverer 拒绝且不重新入队，由 Broker 路由到统一 DLQ。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class RabbitMessagingConfig {
    @Bean
    SimpleRabbitListenerContainerFactory mediaRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageRecoverer mediaMessageRecoverer) {
        Advice retry = RetryInterceptorBuilder.stateless()
                .maxRetries(2)
                .backOffOptions(500, 2.0, 4000)
                .recoverer(mediaMessageRecoverer)
                .build();
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retry);
        factory.setPrefetchCount(1);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    TopicExchange mediaEventsExchange() {
        return new TopicExchange(MediaRabbitTopology.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    DirectExchange mediaDeadExchange() {
        return new DirectExchange(MediaRabbitTopology.DEAD_EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Queue mediaProcessingQueue() {
        return QueueBuilder.durable(MediaRabbitTopology.PROCESSING_QUEUE)
                .deadLetterExchange(MediaRabbitTopology.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MediaRabbitTopology.PROCESSING_DEAD_KEY)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Queue mediaReferencesQueue() {
        return QueueBuilder.durable(MediaRabbitTopology.REFERENCES_QUEUE)
                .deadLetterExchange(MediaRabbitTopology.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MediaRabbitTopology.REFERENCES_DEAD_KEY)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Queue mediaLifecycleQueue() {
        return QueueBuilder.durable(MediaRabbitTopology.LIFECYCLE_QUEUE)
                .deadLetterExchange(MediaRabbitTopology.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MediaRabbitTopology.LIFECYCLE_DEAD_KEY)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Queue mediaDeadQueue() {
        return QueueBuilder.durable(MediaRabbitTopology.DEAD_QUEUE).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaUploadedBinding(Queue mediaProcessingQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaProcessingQueue).to(mediaEventsExchange).with("media.uploaded.v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaReferenceBoundBinding(Queue mediaReferencesQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaReferencesQueue).to(mediaEventsExchange).with("media.reference.bound.v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaReferenceReleasedBinding(Queue mediaReferencesQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaReferencesQueue).to(mediaEventsExchange).with("media.reference.released.v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaReadyBinding(Queue mediaLifecycleQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaLifecycleQueue).to(mediaEventsExchange).with("media.ready.v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaRejectedBinding(Queue mediaLifecycleQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaLifecycleQueue).to(mediaEventsExchange).with("media.rejected.v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaFailedBinding(Queue mediaLifecycleQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaLifecycleQueue).to(mediaEventsExchange).with("media.failed.v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaDeletedBinding(Queue mediaLifecycleQueue, TopicExchange mediaEventsExchange) {
        return BindingBuilder.bind(mediaLifecycleQueue).to(mediaEventsExchange).with("media.deleted.v1");
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaProcessingDeadBinding(Queue mediaDeadQueue, DirectExchange mediaDeadExchange) {
        return BindingBuilder.bind(mediaDeadQueue).to(mediaDeadExchange).with(MediaRabbitTopology.PROCESSING_DEAD_KEY);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaReferencesDeadBinding(Queue mediaDeadQueue, DirectExchange mediaDeadExchange) {
        return BindingBuilder.bind(mediaDeadQueue).to(mediaDeadExchange).with(MediaRabbitTopology.REFERENCES_DEAD_KEY);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.media.messaging", name = "enabled", havingValue = "true")
    Binding mediaLifecycleDeadBinding(Queue mediaDeadQueue, DirectExchange mediaDeadExchange) {
        return BindingBuilder.bind(mediaDeadQueue).to(mediaDeadExchange).with(MediaRabbitTopology.LIFECYCLE_DEAD_KEY);
    }
}
