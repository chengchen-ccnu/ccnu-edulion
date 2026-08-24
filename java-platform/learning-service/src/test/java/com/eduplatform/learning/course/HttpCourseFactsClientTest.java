package com.eduplatform.learning.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.service.QuestionException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证课程事实适配器的成功、隐藏式 404 与可重试 503 语义。 */
class HttpCourseFactsClientTest {
    private HttpServer server;
    private HttpCourseFactsClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new HttpCourseFactsClient(new LearningProperties(
                new LearningProperties.Media("http://media", Duration.ofSeconds(1)),
                new LearningProperties.Course("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1)),
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), false,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3)));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void publishedFactContainsOnlyStableVersionOwnerAndLessonIds() {
        var fact = client.requirePublished("ok", "trace-course");
        assertThat(fact.publishedVersion()).isEqualTo("v8");
        assertThat(fact.accessType()).isEqualTo("ENROLLMENT_REQUIRED");
        assertThat(fact.lessonIds()).containsExactly("00000000-0000-4000-8000-000000000102");
    }

    @Test
    void missingIs404AndDependencyFailureIs503() {
        assertThatThrownBy(() -> client.requirePublished("missing", "trace-missing"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> client.requirePublished("failed", "trace-failed"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(503));
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/missing")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
        if (path.endsWith("/failed")) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
        byte[] body = ("{\"id\":\"00000000-0000-4000-8000-000000000100\",\"publishedVersion\":\"v8\","
                + "\"accessType\":\"ENROLLMENT_REQUIRED\",\"teacher\":{\"id\":\"00000000-0000-4000-8000-000000000101\"},"
                + "\"chapters\":[{\"lessons\":[{\"id\":\"00000000-0000-4000-8000-000000000102\"}]}]}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
