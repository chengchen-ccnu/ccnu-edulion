package com.eduplatform.operation.hot;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 独立热点队列/死信，复用领域Outbox交换机，不抢通知、Course或Learning原队列。 */
@Configuration
@ConditionalOnProperty(name="platform.operation.hot.messaging-enabled",havingValue="true")
public class HotCourseRabbit {
    /** 输入本任务独立队列名，声明durable绑定及DLQ；最多三次消费，无正文日志或无限重入队。 */
    @Bean
    Declarables hotTopology(@Value("${OPERATION_HOT_QUEUE:edu.operation.hot-courses.v1}") String name) {
        Queue queue=QueueBuilder.durable(name).deadLetterExchange(name+".dlx").deadLetterRoutingKey("dead").build();
        Queue dead=QueueBuilder.durable(name+".dlq").build();DirectExchange dx=new DirectExchange(name+".dlx",true,false);
        var declarations=new java.util.ArrayList<Declarable>();declarations.addAll(java.util.List.of(queue,dead,dx,BindingBuilder.bind(dead).to(dx).with("dead")));
        for(String[] signal:java.util.List.of(new String[]{"edu.course.portal.events.v1","course.portal.changed.v1"},
                new String[]{"edu.learning.hot-course.events.v1","learning.course.day-qualified.v1"},
                new String[]{"edu.course.availability.events.v1","course.availability.changed.v1"},
                new String[]{"edu.learning.enrollment.events.v1","learning.enrollment.access-changed.v1"})) {
            TopicExchange exchange=new TopicExchange(signal[0],true,false);declarations.add(exchange);declarations.add(BindingBuilder.bind(queue).to(exchange).with(signal[1]));
        }
        return new Declarables(declarations);
    }
    /** 固定单消费/有限预算，事务登记失败三次后安全拒绝，完整原消息仅留独立DLQ。 */
    @Bean
    SimpleRabbitListenerContainerFactory hotContainerFactory(ConnectionFactory connection) {
        var factory=new SimpleRabbitListenerContainerFactory();factory.setConnectionFactory(connection);
        factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);factory.setPrefetchCount(20);factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxRetries(2).backOffOptions(200,2,1000)
                .recoverer((message,cause)->{throw new org.springframework.amqp.AmqpRejectAndDontRequeueException("热点消费预算耗尽，进入独立死信");}).build());
        return factory;
    }
}
