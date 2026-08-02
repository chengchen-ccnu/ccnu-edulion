package com.eduplatform.course.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** course-service 只声明并发布到媒体事件 Exchange，不创建媒体消费者队列。 */
@Configuration
public class CourseRabbitConfig {
    public static final String MEDIA_EVENTS_EXCHANGE = "edu.media.events.v1";

    @Bean
    @ConditionalOnProperty(prefix = "platform.course.media.messaging", name = "enabled", havingValue = "true")
    TopicExchange courseMediaEventsExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }
}
