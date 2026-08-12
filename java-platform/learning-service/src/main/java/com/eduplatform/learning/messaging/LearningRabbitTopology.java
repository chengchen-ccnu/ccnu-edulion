package com.eduplatform.learning.messaging;

/** T09 学习域导入 RabbitMQ 资源名，与 docs/events/README.md 完全一致。 */
public final class LearningRabbitTopology {
    public static final String EVENTS_EXCHANGE = "edu.learning.events.v1";
    public static final String EVENTS_EXCHANGE_V2 = "edu.learning.events.v2";
    public static final String DEAD_EXCHANGE = "edu.learning.dlx.v1";
    public static final String IMPORT_QUEUE = "edu.learning.question-import.v1";
    public static final String IMPORT_KEY = "question.import.requested.v1";
    public static final String IMPORT_DEAD_KEY = "question.import.dead.v1";
    public static final String IMPORT_DEAD_QUEUE = "edu.learning.question-import.dlq.v1";
    private LearningRabbitTopology() {}
}
