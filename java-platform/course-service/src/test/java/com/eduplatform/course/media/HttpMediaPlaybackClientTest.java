package com.eduplatform.course.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.course.config.CourseMediaProperties;
import com.eduplatform.course.web.CourseDtos;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证课程域优先使用 fMP4 HLS，并且只为尚未补产 v2 的旧资产做一次兼容回退。 */
class HttpMediaPlaybackClientTest {
    @Test
    void fallsBackOnceWithFreshAuthorizationWhenV2DoesNotExist() throws Exception {
        List<String> bodies = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/media/media-test/access-grants", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (bodies.size() == 1) {
                respond(exchange, 404, "{}");
                return;
            }
            respond(exchange, 201, """
                    {"mediaId":"00000000-0000-0000-0000-000000000099",
                     "mimeType":"application/vnd.apple.mpegurl","sizeBytes":1024,
                     "method":"GET","url":"https://media.test/legacy.m3u8","headers":{},
                     "expiresAt":"%s"}
                    """.formatted(Instant.now().plusSeconds(60)));
        });
        server.start();
        try {
            CourseMediaProperties properties = properties(server.getAddress().getPort());
            HttpMediaPlaybackClient client = new HttpMediaPlaybackClient(
                    properties, new CourseMediaAuthorizationIssuer(properties));

            CourseDtos.CourseMediaPlaybackGrant grant = client.issuePlayback(
                    "media-test", "LESSON_VIDEO", "00000000-0000-0000-0000-000000000001", "trace-test");

            assertThat(grant.url()).isEqualTo("https://media.test/legacy.m3u8");
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(0)).contains("\"variant\":\"hls-fmp4-v2\"");
            assertThat(bodies.get(1)).contains("\"variant\":\"hls-v1\"");
            assertThat(authorizations).allSatisfy(value -> assertThat(value).startsWith("Bearer "));
            assertThat(authorizations.get(0)).isNotEqualTo(authorizations.get(1));
        } finally {
            server.stop(0);
        }
    }

    private static CourseMediaProperties properties(int port) {
        return new CourseMediaProperties("http://127.0.0.1:" + port, Duration.ofSeconds(2),
                new CourseMediaProperties.Messaging(false, Duration.ofSeconds(1), Duration.ofSeconds(1), 1),
                new CourseMediaProperties.ServiceAuthorization(
                        "course-test", "media-test", Duration.ofSeconds(30),
                        "test-only-course-media-secret-32-bytes-minimum"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
