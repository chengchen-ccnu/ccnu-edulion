package com.eduplatform.media.messaging;

/** RabbitMQ 媒体域 v1 拓扑的唯一名称集合，与 {@code docs/events/README.md} 保持一致。 */
public final class MediaRabbitTopology {
    public static final String EVENTS_EXCHANGE = "edu.media.events.v1";
    public static final String DEAD_EXCHANGE = "edu.media.dlx.v1";
    public static final String PROCESSING_QUEUE = "edu.media.processing.v1";
    public static final String REFERENCES_QUEUE = "edu.media.references.v1";
    public static final String LIFECYCLE_QUEUE = "edu.media.lifecycle.v1";
    public static final String DEAD_QUEUE = "edu.media.dlq.v1";
    public static final String PROCESSING_DEAD_KEY = "media.processing.dead.v1";
    public static final String REFERENCES_DEAD_KEY = "media.references.dead.v1";
    public static final String LIFECYCLE_DEAD_KEY = "media.lifecycle.dead.v1";

    private MediaRabbitTopology() {}
}
