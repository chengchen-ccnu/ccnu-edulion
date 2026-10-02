package com.eduplatform.learning.messaging;

import com.eduplatform.learning.importing.QuestionImportProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 把导入消息交给幂等处理器；异常抛回容器执行有限重试与 DLQ。 */
@Component
@ConditionalOnProperty(prefix = "platform.learning.import", name = "messaging-enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${platform.learning.import.consumer-enabled:true}")
public class QuestionImportListener {
    private final QuestionImportProcessor processor;
    public QuestionImportListener(QuestionImportProcessor processor) { this.processor = processor; }

    /** 接收冻结的 JSON 事件信封；异常交给容器有限重试和 DLQ recoverer。 */
    @RabbitListener(queues = LearningRabbitTopology.IMPORT_QUEUE, containerFactory = "learningImportContainerFactory")
    public void consume(String json) { processor.process(json); }
}
