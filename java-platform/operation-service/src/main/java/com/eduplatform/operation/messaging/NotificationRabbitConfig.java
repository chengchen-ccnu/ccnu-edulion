package com.eduplatform.operation.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 独立Operation消费拓扑；既有Course/Learning/IAM队列不抢占不改属性，三次总尝试后独立DLQ。 */
@Configuration
@ConditionalOnProperty(name="platform.operation.messaging-enabled",havingValue="true")
public class NotificationRabbitConfig {
    public static final String EXCHANGE="edu.operation.events.v1";
    @Bean
    Declarables notificationTopology() {
        String queue=System.getenv().getOrDefault("OPERATION_NOTIFICATION_QUEUE","edu.operation.notifications.v1");
        String dlx=queue+".dlx",dlq=queue+".dlq";
        Queue q=QueueBuilder.durable(queue).deadLetterExchange(dlx).deadLetterRoutingKey("dead").build();
        Queue dead=QueueBuilder.durable(dlq).build(); DirectExchange dx=new DirectExchange(dlx,true,false);
        TopicExchange own=new TopicExchange(EXCHANGE,true,false);
        TopicExchange task=new TopicExchange("edu.learning.task-notifications.events.v1",true,false);
        TopicExchange course=new TopicExchange("edu.course.availability.events.v1",true,false);
        TopicExchange iam=new TopicExchange("edu.iam.events.v1",true,false);
        return new Declarables(q,dead,dx,own,task,course,iam,
                BindingBuilder.bind(q).to(own).with("operation.announcement.changed.v1"),
                BindingBuilder.bind(q).to(task).with("learning.task.notification.changed.v1"),
                BindingBuilder.bind(q).to(course).with("course.availability.changed.v1"),
                BindingBuilder.bind(q).to(iam).with("iam.teacher-qualification.decided.v1"),
                BindingBuilder.bind(dead).to(dx).with("dead"));
    }
    /** 单队列消费者仍靠eventId/版本防重放乱序；有限3次失败后拒绝且不重入队。 */
    @Bean
    SimpleRabbitListenerContainerFactory notificationContainerFactory(ConnectionFactory connection) {
        var factory=new SimpleRabbitListenerContainerFactory(); factory.setConnectionFactory(connection);
        factory.setConcurrentConsumers(1); factory.setMaxConcurrentConsumers(1); factory.setPrefetchCount(20);
        factory.setDefaultRequeueRejected(false);
        // 默认recoverer会打印AMQP正文；未知/恶意事件可能含隐私，所以仅安全拒绝，完整原消息留独立DLQ。
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxRetries(2).backOffOptions(200,2,1000)
                .recoverer((message,cause)->{throw new org.springframework.amqp.AmqpRejectAndDontRequeueException("通知消费预算耗尽，进入独立死信");}).build());
        return factory;
    }
}
