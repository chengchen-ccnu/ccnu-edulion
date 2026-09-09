package com.eduplatform.agentaccess.runtime;

import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** T13.1 Python 内部 HTTP/SSE 协议端口；实现必须逐事件转发，不得缓冲完整回答。 */
public interface AgentRuntimeClient {
    interface StreamHandle { void cancel(); }

    /** 启动一次稳定 run；回调接收原始协议事件，错误和完成只能各收口一次。 */
    StreamHandle stream(JsonNode request, Consumer<JsonNode> onEvent,
            Consumer<Throwable> onError, Runnable onComplete);

    /** 向 Python 发送幂等取消；本地 Java 终态不依赖该调用一定成功。 */
    void cancel(UUID runId, UUID cancelRequestId, String traceId, String reason);
}
