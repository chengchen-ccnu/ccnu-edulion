package com.eduplatform.learning.messaging;

import com.eduplatform.learning.importing.QuestionImportProcessor;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 重试耗尽时把任务标为 FAILED，再拒绝原消息，由 Broker 精确路由到导入 DLQ。 */
@Component("learningImportRecoverer")
@ConditionalOnProperty(prefix = "platform.learning.import", name = "messaging-enabled", havingValue = "true")
public class LearningImportRecoverer implements MessageRecoverer {
    private final QuestionImportProcessor processor;
    public LearningImportRecoverer(QuestionImportProcessor processor) { this.processor = processor; }

    @Override
    /** 重试耗尽时先保存任务安全失败状态，再拒绝原消息使 Broker 路由到 DLQ。 */
    public void recover(Message message, Throwable cause) {
        try { processor.exhausted(new String(message.getBody(), StandardCharsets.UTF_8), cause); }
        catch (RuntimeException ignored) { }
        throw new AmqpRejectAndDontRequeueException("Question import exhausted finite retries", cause);
    }
}
