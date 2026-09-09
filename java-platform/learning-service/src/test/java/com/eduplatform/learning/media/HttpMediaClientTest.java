package com.eduplatform.learning.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.service.QuestionException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证媒体错用途、他人资产、非 READY 与短期源读取的 HTTP 适配语义。 */
class HttpMediaClientTest {
    private HttpServer server;
    private HttpMediaClient client;
    private String observedRoles;
    private String observedPermissions;
    private String observedTransferBody;
    private String observedAuthorization;
    private String observedInternalBody;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle); server.start();
        LearningProperties properties = new LearningProperties(new LearningProperties.Media(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
                "edu-learning-service", "edu-media-service", Duration.ofSeconds(30),
                "test-assessment-media-secret-32-bytes-minimum"),
                new LearningProperties.Course("http://127.0.0.1:8082", Duration.ofSeconds(2), Duration.ZERO),
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), false, Duration.ofSeconds(1), Duration.ofSeconds(1), 3));
        client = new HttpMediaClient(properties, new AssessmentMediaAuthorizationIssuer(properties));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void distinguishesOwnerNotFoundFromWrongPurposeAndNonReadyWithoutWritingAnything() {
        assertNotBindable("00000000-0000-4000-8000-000000000401", "媒体不存在或不属于当前教师");
        assertNotBindable("00000000-0000-4000-8000-000000000402", "用途不匹配或尚未 READY");
        assertNotBindable("00000000-0000-4000-8000-000000000403", "用途不匹配或尚未 READY");
    }

    @Test
    void downloadsImportSourceOnlyThroughReturnedShortLivedGrant() throws Exception {
        try (var stream = client.openOriginal("00000000-0000-4000-8000-000000000404",
                "00000000-0000-4000-8000-000000009101", "trace-grant")) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("type,difficulty");
        }
    }

    @Test
    void systemImageTransferForwardsAdministratorCapabilityAndScope() {
        String mediaId = client.transferExternalImage("00000000-0000-4000-8000-000000000405",
                "https://images.fixture.test/system.png", "00000000-0000-4000-8000-000000009103",
                "SYSTEM", "trace-system-image");
        assertThat(mediaId).isEqualTo("00000000-0000-4000-8000-000000000405");
        assertThat(observedRoles).isEqualTo("ADMIN");
        assertThat(observedPermissions).isEqualTo("administration:access");
        assertThat(observedTransferBody).contains("\"scope\":\"SYSTEM\"");
    }

    @Test
    void assessmentAccessUsesLearningIssuerAndBindsTheInternalRequest() {
        var grant = client.issueAssessmentAccess("00000000-0000-4000-8000-000000000406",
                "00000000-0000-4000-8000-000000009102", "QUESTION_IMAGE",
                com.eduplatform.learning.web.LearningDtos.MediaDisposition.INLINE, "trace-assessment-media");
        assertThat(grant.method()).isEqualTo("GET");
        assertThat(observedAuthorization).startsWith("Bearer ");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(
                "test-assessment-media-secret-32-bytes-minimum".getBytes(StandardCharsets.UTF_8), "HmacSHA256")).build();
        var jwt = decoder.decode(observedAuthorization.substring(7));
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("edu-learning-service");
        assertThat(jwt.getClaimAsString("accessType")).isEqualTo("ASSESSMENT_QUESTION");
        assertThat(jwt.getClaimAsString("mediaId")).isEqualTo("00000000-0000-4000-8000-000000000406");
        assertThat(observedInternalBody).contains("\"visitorId\":\"00000000-0000-4000-8000-000000009102\"",
                "\"purpose\":\"QUESTION_IMAGE\"", "\"variant\":\"ORIGINAL\"", "\"disposition\":\"INLINE\"");
    }

    private void assertNotBindable(String id, String reason) {
        assertThatThrownBy(() -> client.requireBindable(id, "QUESTION_IMAGE",
                "00000000-0000-4000-8000-000000009101", "trace-bind"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.code()).isEqualTo("QUESTION.MEDIA_NOT_BINDABLE");
                    assertThat(error.fieldErrors().get(0).reason()).contains(reason);
                });
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes(); String path = exchange.getRequestURI().getPath();
        if (path.equals("/download")) { respond(exchange, 200, "type,difficulty"); return; }
        if (path.startsWith("/internal/v1/media/")) {
            observedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            observedInternalBody = new String(requestBody, StandardCharsets.UTF_8);
            respond(exchange, 201, "{\"mediaId\":\"00000000-0000-4000-8000-000000000406\","
                    + "\"mimeType\":\"image/png\",\"sizeBytes\":1024,"
                    + "\"method\":\"GET\",\"url\":\"https://fixture.invalid/private\","
                    + "\"headers\":{},\"expiresAt\":\"2026-08-27T10:00:00Z\"}"); return;
        }
        if (path.equals("/api/v1/media/external-image-transfers")) {
            observedRoles = exchange.getRequestHeaders().getFirst("X-User-Roles");
            observedPermissions = exchange.getRequestHeaders().getFirst("X-User-Permissions");
            observedTransferBody = new String(requestBody, StandardCharsets.UTF_8);
            respond(exchange, 201, "{\"mediaId\":\"00000000-0000-4000-8000-000000000405\","
                    + "\"purpose\":\"QUESTION_IMAGE\",\"status\":\"READY\"}"); return;
        }
        if (path.endsWith("/access-grants")) {
            respond(exchange, 201, "{\"mediaId\":\"00000000-0000-4000-8000-000000000404\","
                    + "\"mimeType\":\"text/csv\",\"sizeBytes\":1024,"
                    + "\"method\":\"GET\",\"url\":\"http://127.0.0.1:" + server.getAddress().getPort()
                    + "/download\",\"headers\":{},\"expiresAt\":\"2026-08-09T22:00:00Z\"}"); return;
        }
        if (path.contains("000000000401")) { respond(exchange, 404, "{}"); return; }
        if (path.contains("000000000402") || path.contains("000000000403")) { respond(exchange, 409, "{}"); return; }
        respond(exchange, 200, "{\"mediaId\":\"00000000-0000-4000-8000-000000000404\",\"purpose\":\"QUESTION_IMAGE\",\"status\":\"READY\",\"bindable\":true}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
