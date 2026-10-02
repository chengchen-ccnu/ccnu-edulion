package com.eduplatform.iam.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * IAM 资质引用复用 Media Exchange；最小审核决定和权限事实保存在持久队列供后续 T16.4 消费。
 * 本任务不投递站内消息。死信队列在后续消费者有限重试耗尽时保存失败原件。
 */
@Configuration
@ConditionalOnProperty(prefix = "platform.iam.review", name = "messaging-enabled", havingValue = "true")
public class TeacherReviewRabbitConfig {
    public static final String MEDIA_EXCHANGE = "edu.media.events.v1";
    public static final String IAM_EXCHANGE = "edu.iam.events.v1";
    public static final String IAM_DLX = "edu.iam.dlx.v1";
    public static final String DECISION_QUEUE = "edu.iam.teacher-review.decisions.v1";
    public static final String DECISION_DLQ = "edu.iam.teacher-review.decisions.dlq.v1";
    public static final String DECISION_KEY = "iam.teacher-qualification.decided.v1";
    public static final String PERMISSION_QUEUE = "edu.iam.account-permissions.v1";
    public static final String PERMISSION_DLQ = "edu.iam.account-permissions.dlq.v1";
    public static final String PERMISSION_KEY = "iam.account-permission.changed.v1";

    @Bean TopicExchange iamMediaExchange() { return new TopicExchange(MEDIA_EXCHANGE, true, false); }
    @Bean TopicExchange iamEventsExchange() { return new TopicExchange(IAM_EXCHANGE, true, false); }
    @Bean TopicExchange iamDeadExchange() { return new TopicExchange(IAM_DLX, true, false); }
    @Bean Queue teacherDecisionQueue() {
        return QueueBuilder.durable(DECISION_QUEUE).deadLetterExchange(IAM_DLX)
                .deadLetterRoutingKey("iam.teacher-review.dead.v1").build();
    }
    @Bean Queue teacherDecisionDeadQueue() { return QueueBuilder.durable(DECISION_DLQ).build(); }
    @Bean Binding teacherDecisionBinding(Queue teacherDecisionQueue, TopicExchange iamEventsExchange) {
        return BindingBuilder.bind(teacherDecisionQueue).to(iamEventsExchange).with(DECISION_KEY);
    }
    @Bean Binding teacherDecisionDeadBinding(Queue teacherDecisionDeadQueue, TopicExchange iamDeadExchange) {
        return BindingBuilder.bind(teacherDecisionDeadQueue).to(iamDeadExchange).with("iam.teacher-review.dead.v1");
    }
    @Bean Queue accountPermissionQueue() {
        return QueueBuilder.durable(PERMISSION_QUEUE).deadLetterExchange(IAM_DLX)
                .deadLetterRoutingKey("iam.account-permission.dead.v1").build();
    }
    @Bean Queue accountPermissionDeadQueue() { return QueueBuilder.durable(PERMISSION_DLQ).build(); }
    @Bean Binding accountPermissionBinding(Queue accountPermissionQueue,TopicExchange iamEventsExchange) {
        return BindingBuilder.bind(accountPermissionQueue).to(iamEventsExchange).with(PERMISSION_KEY);
    }
    @Bean Binding accountPermissionDeadBinding(Queue accountPermissionDeadQueue,TopicExchange iamDeadExchange) {
        return BindingBuilder.bind(accountPermissionDeadQueue).to(iamDeadExchange).with("iam.account-permission.dead.v1");
    }
}
