package com.eduplatform.media.messaging;

import com.eduplatform.media.processing.MediaProcessingCoordinator;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

/**
 * 消息进程内有限重试耗尽后的恢复器。处理事件会先记录 FAILED 事实，
 * 随后抛出不重新入队异常，让 Broker 按原队列 DLX 参数进入 DLQ。
 */
@Component
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class MediaMessageRecoverer implements MessageRecoverer {
    private final MediaEventParser parser;
    private final MediaProcessingCoordinator coordinator;

    public MediaMessageRecoverer(MediaEventParser parser, MediaProcessingCoordinator coordinator) {
        this.parser = parser;
        this.coordinator = coordinator;
    }

    @Override
    /** 尝试写入最终失败状态，无论该补偿是否成功都拒绝原消息进入 DLQ。 */
    public void recover(Message message, Throwable cause) {
        try {
            coordinator.exhausted(parser.parse(new String(message.getBody(), StandardCharsets.UTF_8)), cause);
        } catch (RuntimeException ignored) { }
        throw new AmqpRejectAndDontRequeueException("Media message exhausted finite retries", cause);
    }
}
