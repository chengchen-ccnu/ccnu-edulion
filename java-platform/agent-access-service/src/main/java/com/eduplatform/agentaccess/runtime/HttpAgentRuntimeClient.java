package com.eduplatform.agentaccess.runtime;

import com.eduplatform.agentaccess.config.AgentProperties;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** WebClient 流式适配 T13.1 协议；只解析单个 SSE data，不聚合整条回答。 */
@Component
public class HttpAgentRuntimeClient implements AgentRuntimeClient {
    private final WebClient client;
    private final AgentProperties properties;
    private final ObjectMapper mapper;

    public HttpAgentRuntimeClient(AgentProperties properties, ObjectMapper mapper) {
        this.client = WebClient.builder().baseUrl(properties.runtimeBaseUrl()).build();
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public StreamHandle stream(JsonNode request, Consumer<JsonNode> onEvent,
            Consumer<Throwable> onError, Runnable onComplete) {
        Disposable subscription = client.post().uri("/internal/v1/agent/runs")
                .header("X-Agent-Internal-Protocol-Version", properties.protocolVersion())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request).retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(properties.runtimeRequestTimeout())
                .subscribe(event -> {
                    if (event.data() == null) return;
                    try { onEvent.accept(mapper.readTree(event.data())); }
                    catch (Exception invalid) { onError.accept(invalid); }
                }, onError, onComplete);
        return subscription::dispose;
    }

    @Override
    public void cancel(UUID runId, UUID cancelRequestId, String traceId, String reason) {
        client.post().uri("/internal/v1/agent/runs/{runId}/cancel", runId)
                .header("X-Agent-Internal-Protocol-Version", properties.protocolVersion())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("cancelRequestId", cancelRequestId.toString(), "traceId", traceId,
                        "requestedAt", Instant.now().toString(), "reason", reason))
                .retrieve().toBodilessEntity().timeout(properties.domainRequestTimeout()).block();
    }
}
