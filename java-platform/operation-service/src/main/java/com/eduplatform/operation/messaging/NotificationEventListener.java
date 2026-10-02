package com.eduplatform.operation.messaging;

import com.eduplatform.operation.service.NotificationDelivery;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 只消费本域独立队列；事务成功后AUTO ACK，失败有限重试/DLQ，不打印包含业务事实的payload。 */
@Component
@ConditionalOnProperty(name="platform.operation.messaging-enabled",havingValue="true")
public class NotificationEventListener {
    private final NotificationDelivery delivery;
    public NotificationEventListener(NotificationDelivery delivery) { this.delivery=delivery; }
    /** 信封输入校验、eventId幂等和有界工作登记同事务；消费成功不表示全部学生已投递。 */
    @RabbitListener(queues="${OPERATION_NOTIFICATION_QUEUE:edu.operation.notifications.v1}",containerFactory="notificationContainerFactory")
    public void receive(Message message) { delivery.consume(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8)); }
}
