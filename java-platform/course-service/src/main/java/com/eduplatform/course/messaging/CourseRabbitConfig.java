package com.eduplatform.course.messaging;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 声明课程域所需 RabbitMQ 资源：媒体事件只发布，报名事件由本地投影队列消费。
 *
 * <p>报名消费单并发保持同一 learner/course 的正常顺序，失败总计最多尝试三次，
 * 最终拒绝并通过死信交换机进入 DLQ，不会无限重回队列。</p>
 */
@Configuration
public class CourseRabbitConfig {
    public static final String MEDIA_EVENTS_EXCHANGE = "edu.media.events.v1";
    public static final String ENROLLMENT_EVENTS_EXCHANGE = "edu.learning.enrollment.events.v1";
    public static final String ENROLLMENT_DLX = "edu.course.enrollment.dlx.v1";
    public static final String ENROLLMENT_QUEUE = "edu.course.enrollment-access.v1";
    public static final String ENROLLMENT_DLQ = "edu.course.enrollment-access.dlq.v1";
    public static final String ENROLLMENT_ROUTING_KEY = "learning.enrollment.access-changed.v1";
    public static final String ENROLLMENT_DEAD_ROUTING_KEY = "course.enrollment-access.dead.v1";
    public static final String AGENT_INDEX_EVENTS_EXCHANGE = "edu.agent.index.events.v1";
    public static final String LESSON_CONTENT_EVENTS_EXCHANGE = "edu.course.lesson-content.events.v1";

    /** 公开卡片变更只发布ID失效信号，复用Course Outbox。 */
    @Bean
    @ConditionalOnProperty(prefix = "platform.course.media.messaging", name = "enabled", havingValue = "true")
    TopicExchange coursePortalExchange() { return new TopicExchange("edu.course.portal.events.v1",true,false); }

    /** 暂停事实复用已有 Outbox 的有限重试和发布确认。 */
    @Bean
    @ConditionalOnProperty(prefix = "platform.course.media.messaging", name = "enabled", havingValue = "true")
    TopicExchange courseAvailabilityExchange() {
        return new TopicExchange(com.eduplatform.course.service.CourseSuspensionService.EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.media.messaging", name = "enabled", havingValue = "true")
    TopicExchange courseMediaEventsExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.media.messaging", name = "enabled", havingValue = "true")
    TopicExchange courseAgentIndexEventsExchange() {
        return new TopicExchange(AGENT_INDEX_EVENTS_EXCHANGE, true, false);
    }

    /** Course 发布 Outbox 与媒体引用共用可靠发布器，但使用独立版本化 Exchange。 */
    @Bean
    @ConditionalOnProperty(prefix = "platform.course.media.messaging", name = "enabled", havingValue = "true")
    TopicExchange courseLessonContentEventsExchange() {
        return new TopicExchange(LESSON_CONTENT_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
    TopicExchange enrollmentEventsExchange() { return new TopicExchange(ENROLLMENT_EVENTS_EXCHANGE, true, false); }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
    TopicExchange enrollmentDeadLetterExchange() { return new TopicExchange(ENROLLMENT_DLX, true, false); }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
    Queue enrollmentQueue() {
        return QueueBuilder.durable(ENROLLMENT_QUEUE).deadLetterExchange(ENROLLMENT_DLX)
                .deadLetterRoutingKey(ENROLLMENT_DEAD_ROUTING_KEY).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
    Queue enrollmentDeadLetterQueue() { return QueueBuilder.durable(ENROLLMENT_DLQ).build(); }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
    Binding enrollmentBinding(@Qualifier("enrollmentQueue") Queue enrollmentQueue,
            @Qualifier("enrollmentEventsExchange") TopicExchange enrollmentEventsExchange) {
        return BindingBuilder.bind(enrollmentQueue).to(enrollmentEventsExchange).with(ENROLLMENT_ROUTING_KEY);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
    Binding enrollmentDeadBinding(@Qualifier("enrollmentDeadLetterQueue") Queue enrollmentDeadLetterQueue,
            @Qualifier("enrollmentDeadLetterExchange") TopicExchange enrollmentDeadLetterExchange) {
        return BindingBuilder.bind(enrollmentDeadLetterQueue).to(enrollmentDeadLetterExchange)
                .with(ENROLLMENT_DEAD_ROUTING_KEY);
    }

    /** 单消费者串行保持 changedAt 投影顺序；单条失败最多尝试三次，随后 reject 进入 DLQ。 */
    @Bean
    @ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
    SimpleRabbitListenerContainerFactory courseEnrollmentRabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
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
