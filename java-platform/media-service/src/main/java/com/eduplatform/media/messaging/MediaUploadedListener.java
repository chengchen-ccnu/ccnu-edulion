package com.eduplatform.media.messaging;

import com.eduplatform.media.processing.MediaProcessingCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 消费 MediaUploaded v1 并把处理交给幂等协调器；方法成功返回后容器才 ACK。 */
@Component
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
public class MediaUploadedListener {
    private final MediaEventParser parser;
    private final MediaProcessingCoordinator coordinator;

    public MediaUploadedListener(MediaEventParser parser, MediaProcessingCoordinator coordinator) {
        this.parser = parser;
        this.coordinator = coordinator;
    }

    @RabbitListener(queues = MediaRabbitTopology.PROCESSING_QUEUE,
            containerFactory = "mediaRabbitListenerContainerFactory",
            autoStartup = "${platform.media.messaging.enabled:false}")
    /** 解析事件并执行处理；异常交给有限重试拦截器。 */
    public void onMessage(String body) {
        coordinator.process(parser.parse(body));
    }
}
