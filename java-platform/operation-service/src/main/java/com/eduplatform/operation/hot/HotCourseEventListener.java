package com.eduplatform.operation.hot;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 热点独立消费者与拓扑配置分离，避免容器工厂在监听器初始化时形成循环依赖。 */
@Component
@ConditionalOnProperty(name="platform.operation.hot.messaging-enabled",havingValue="true")
public class HotCourseEventListener {
    private final HotCourseEvents events;
    public HotCourseEventListener(HotCourseEvents events) {this.events=events;}
    /** 输入持久消息；inbox/dirty同事务成功后AUTO ACK，失败走三次总尝试/DLQ，重投不再次登记。 */
    @RabbitListener(queues="${OPERATION_HOT_QUEUE:edu.operation.hot-courses.v1}",containerFactory="hotContainerFactory")
    public void receive(Message message) {events.consume(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8));}
}
