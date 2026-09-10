package com.eduplatform.agentaccess;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.agentaccess.config.AgentProperties;
import com.eduplatform.agentaccess.service.AgentException;
import com.eduplatform.agentaccess.service.HttpAgentDomainClient;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证 Course/Learning 私有依赖超时统一 fail-closed，调用方不会收到候选或缓存事实。 */
class HttpAgentDomainClientTest {

    @Test
    void courseTimeoutBecomesSafeDependencyUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/agent-access/course-candidates:verify", exchange -> {
            try {
                Thread.sleep(300);
                byte[] body = "{\"allowedEntityIds\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AgentProperties properties = new AgentProperties(true, "http://127.0.0.1:8000",
                    Duration.ofSeconds(1), baseUrl, baseUrl, Duration.ofMillis(50), "1",
                    "test-only-runtime-callback-token-32-bytes",
                    "test-only-agent-domain-token-32-bytes", 6, 16_000);
            HttpAgentDomainClient client = new HttpAgentDomainClient(properties);
            UUID entity = UUID.randomUUID();
            var candidate = new AgentInternalDtos.CourseCandidate(entity, UUID.randomUUID(), "v1", entity, 1L);

            assertThatThrownBy(() -> client.verifyCourseCandidates(UUID.randomUUID(), List.of(candidate),
                    "trace-domain-timeout"))
                    .isInstanceOfSatisfying(AgentException.class,
                            error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                    .isEqualTo("AGENT.DEPENDENCY_UNAVAILABLE"));
        } finally {
            server.stop(0);
        }
    }
}
