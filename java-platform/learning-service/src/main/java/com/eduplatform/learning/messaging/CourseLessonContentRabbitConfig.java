package com.eduplatform.learning.messaging;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 声明 Course→Learning 当前内容投影队列；三次总尝试后进入独立 DLQ，不自动回流。 */
@Configuration
@ConditionalOnProperty(prefix = "platform.learning.course-content-messaging", name = "enabled", havingValue = "true")
public class CourseLessonContentRabbitConfig {
    public static final String EXCHANGE = "edu.course.lesson-content.events.v1";
    public static final String QUEUE = "edu.learning.lesson-content.v1";
    public static final String ROUTING_KEY = "course.lesson-content.published.v1";
    public static final String DLX = "edu.learning.lesson-content.dlx.v1";
    public static final String DLQ = "edu.learning.lesson-content.dlq.v1";
    public static final String DEAD_KEY = "learning.lesson-content.dead.v1";

    @Bean TopicExchange courseLessonContentExchange() { return new TopicExchange(EXCHANGE, true, false); }
    @Bean DirectExchange courseLessonContentDlx() { return new DirectExchange(DLX, true, false); }
    @Bean Queue courseLessonContentQueue() { return QueueBuilder.durable(QUEUE)
            .deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_KEY).build(); }
    @Bean Queue courseLessonContentDlq() { return QueueBuilder.durable(DLQ).build(); }
    @Bean Binding courseLessonContentBinding(Queue courseLessonContentQueue,
            TopicExchange courseLessonContentExchange) {
        return BindingBuilder.bind(courseLessonContentQueue).to(courseLessonContentExchange).with(ROUTING_KEY);
    }
    @Bean Binding courseLessonContentDeadBinding(Queue courseLessonContentDlq,
            DirectExchange courseLessonContentDlx) {
        return BindingBuilder.bind(courseLessonContentDlq).to(courseLessonContentDlx).with(DEAD_KEY);
    }

    /** 单队列串行消费正常顺序；eventId 和 publicationRevision 仍承担重复与乱序保护。 */
    @Bean SimpleRabbitListenerContainerFactory courseLessonContentContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        Advice retry = RetryInterceptorBuilder.stateless().maxRetries(2).backOffOptions(200, 2.0, 1000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build();
        factory.setAdviceChain(retry);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
