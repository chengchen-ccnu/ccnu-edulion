package com.eduplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "platform.gateway.jwt.secret=test-only-secret-with-at-least-32-bytes",
        "platform.gateway.jwt.issuer=edu-platform-iam-test",
        "platform.gateway.jwt.audience=edu-platform-gateway-test"
})
class GatewayRouteSecurityTest {
    private static final String SECRET = "test-only-secret-with-at-least-32-bytes";
    private static HttpServer iamStub;
    private static HttpServer courseStub;
    private static HttpServer mediaStub;
    private static HttpServer learningStub;

    @DynamicPropertySource
    static void iamServiceUrl(DynamicPropertyRegistry registry) {
        try {
            iamStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            iamStub.createContext("/", GatewayRouteSecurityTest::handleIamRequest);
            iamStub.start();
            registry.add("IAM_SERVICE_URL", () -> "http://127.0.0.1:" + iamStub.getAddress().getPort());
            courseStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            courseStub.createContext("/", GatewayRouteSecurityTest::handleCourseRequest);
            courseStub.start();
            registry.add("COURSE_SERVICE_URL", () -> "http://127.0.0.1:" + courseStub.getAddress().getPort());
            mediaStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mediaStub.createContext("/", GatewayRouteSecurityTest::handleMediaRequest);
            mediaStub.start();
            registry.add("MEDIA_SERVICE_URL", () -> "http://127.0.0.1:" + mediaStub.getAddress().getPort());
            learningStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            learningStub.createContext("/", GatewayRouteSecurityTest::handleLearningRequest);
            learningStub.start();
            registry.add("LEARNING_SERVICE_URL", () -> "http://127.0.0.1:" + learningStub.getAddress().getPort());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopIamStub() {
        if (iamStub != null) iamStub.stop(0);
        if (courseStub != null) courseStub.stop(0);
        if (mediaStub != null) mediaStub.stop(0);
        if (learningStub != null) learningStub.stop(0);
    }

    @Value("${local.server.port}") int port;
    WebTestClient client;

    @BeforeEach
    void createClient() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void protectedRouteRejectsMissingTokenWithUnifiedError() {
        client.get().uri("/api/v1/users/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo("IAM.UNAUTHORIZED")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void authenticationRoutesArePublicAndReachIam() {
        client.post().uri("/api/v1/auth/login").bodyValue("{\"account\":\"x\",\"password\":\"password\"}")
                .header("Content-Type", "application/json").exchange()
                .expectStatus().isOk().expectHeader().valueMatches("Set-Cookie", "EDU_REFRESH=.*");
        client.post().uri("/api/v1/auth/refresh").cookie("EDU_REFRESH", "opaque")
                .exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/logout").cookie("EDU_REFRESH", "opaque")
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void browserPreflightIsPublicAndReturnsCredentialedCorsHeaders() {
        client.options().uri("/api/v1/auth/login")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectHeader().value("Access-Control-Allow-Methods", value -> assertThat(value).contains("POST"));

        client.options().uri("/api/v1/teacher/courses/00000000-0000-4000-8000-000000000201")
                .header("Origin", "http://localhost:5174")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5174")
                .expectHeader().value("Access-Control-Allow-Methods", value -> assertThat(value).contains("PUT"));

        client.options().uri("/api/v1/media/uploads/00000000-0000-4000-8000-000000000701")
                .header("Origin", "http://localhost:5174")
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("Access-Control-Allow-Methods", value -> assertThat(value).contains("DELETE"));
    }

    @Test
    void validTokenReachesCurrentUserRouteWithTrustedIdentity() {
        Instant now = Instant.now();
        client.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300))))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo("00000000-0000-4000-8000-000000000001")
                .jsonPath("$.roles[0]").isEqualTo("STUDENT")
                .jsonPath("$.permissions[0]").isEqualTo("learning:access");
    }

    @Test
    void expiredTokenIsRejectedBeforeRouting() {
        client.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(token(Instant.now().minusSeconds(600), Instant.now().minusSeconds(300))))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void publishedCourseRoutesArePublic() {
        client.get().uri("/api/v1/courses?page=1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.route").isEqualTo("course");
    }

    @Test
    /** 播放授权 POST 必须登录并只路由到课程域；media-service 的 internal 路径不会被 Gateway 暴露。 */
    void coursePlaybackGrantRequiresLoginAndInternalMediaRouteIsNotExposed() {
        String path = "/api/v1/courses/00000000-0000-4000-8000-000000000201"
                + "/lessons/00000000-0000-4000-8000-000000000202"
                + "/resources/00000000-0000-4000-8000-000000000203/playback-grants";
        client.post().uri(path).exchange().expectStatus().isUnauthorized();

        Instant now = Instant.now();
        client.post().uri(path)
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300))))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.route").isEqualTo("course")
                .jsonPath("$.userId").isEqualTo("00000000-0000-4000-8000-000000000001");

        client.post().uri("/internal/v1/media/00000000-0000-4000-8000-000000000701/access-grants")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300))))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    /** Given 普通或教师 JWT；Then 教师路由分别拒绝或转发令牌中重建的可信身份。 */
    void teacherCourseRoutesRequireTeacherRoleAndForwardTrustedIdentity() {
        Instant now = Instant.now();
        client.get().uri("/api/v1/teacher/courses/00000000-0000-4000-8000-000000000201")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300))))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("IAM.FORBIDDEN");

        client.get().uri("/api/v1/teacher/courses/00000000-0000-4000-8000-000000000201")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300),
                        "TEACHER", "teaching:access")))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("00000000-0000-4000-8000-000000000001")
                .jsonPath("$.roles").isEqualTo("TEACHER");
    }

    @Test
    /** 验证媒体 API 必须登录，且 Gateway 会移除客户端伪造身份头后注入 Token 中的可信身份。 */
    void mediaRoutesRequireAuthenticationAndForwardOnlyTrustedIdentity() {
        client.post().uri("/api/v1/media/uploads").bodyValue("{}")
                .header("Content-Type", "application/json")
                .exchange().expectStatus().isUnauthorized();

        Instant now = Instant.now();
        client.post().uri("/api/v1/media/uploads").bodyValue("{}")
                .header("Content-Type", "application/json")
                .header("X-User-Id", "00000000-0000-4000-8000-000000009999")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300),
                        "TEACHER", "teaching:access")))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.route").isEqualTo("media")
                .jsonPath("$.userId").isEqualTo("00000000-0000-4000-8000-000000000001")
                .jsonPath("$.roles").isEqualTo("TEACHER");
    }

    @Test
    /** 题库教师路由要求 TEACHER，知识点写路由额外要求 administration:access，并转发可信权限头。 */
    void questionBankRoutesEnforceTeacherAndAdministrationCapabilities() {
        Instant now = Instant.now();
        client.get().uri("/api/v1/teacher/questions")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300))))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/teacher/questions")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300), "TEACHER", "teaching:access")))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.route").isEqualTo("learning").jsonPath("$.roles").isEqualTo("TEACHER");

        client.post().uri("/api/v1/admin/knowledge-points").bodyValue("{}")
                .header("Content-Type", "application/json")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300), "ADMIN", "operation:read")))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/admin/knowledge-points").bodyValue("{}")
                .header("Content-Type", "application/json")
                .headers(headers -> headers.setBearerAuth(token(now, now.plusSeconds(300), "ADMIN", "administration:access")))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.route").isEqualTo("learning").jsonPath("$.permissions").isEqualTo("administration:access");
    }

    private String token(Instant issuedAt, Instant expiresAt) {
        return token(issuedAt, expiresAt, "STUDENT", "learning:access");
    }

    private String token(Instant issuedAt, Instant expiresAt, String role, String permission) {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("edu-platform-iam-test")
                .audience(List.of("edu-platform-gateway-test")).subject("00000000-0000-4000-8000-000000000001")
                .issuedAt(issuedAt).expiresAt(expiresAt).claim("roles", List.of(role))
                .claim("permissions", List.of(permission)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static void handleCourseRequest(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
        String roles = exchange.getRequestHeaders().getFirst("X-User-Roles");
        String body = userId == null
                ? "{\"route\":\"course\"}"
                : "{\"route\":\"course\",\"userId\":\"" + userId + "\",\"roles\":\"" + roles + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void handleIamRequest(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/auth/logout".equals(path)) {
            exchange.getResponseHeaders().add("Set-Cookie", "EDU_REFRESH=; Max-Age=0; Path=/api/v1/auth; HttpOnly; SameSite=Strict");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        String body;
        if ("/api/v1/users/me".equals(path)) {
            String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
            String roles = exchange.getRequestHeaders().getFirst("X-User-Roles");
            String permissions = exchange.getRequestHeaders().getFirst("X-User-Permissions");
            body = "{\"id\":\"" + userId + "\",\"displayName\":\"测试学生\",\"avatarUrl\":null,"
                    + "\"roles\":[\"" + roles + "\"],\"permissions\":[\"" + permissions + "\"]}";
        } else if (path.startsWith("/api/v1/auth/")) {
            exchange.getResponseHeaders().add("Set-Cookie", "EDU_REFRESH=opaque; Path=/api/v1/auth; HttpOnly; SameSite=Strict");
            body = "{\"tokenType\":\"Bearer\",\"accessToken\":\"downstream-token\",\"expiresIn\":900}";
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 本地媒体下游替身，只回显 Gateway 注入的身份头，不处理文件字节。 */
    private static void handleMediaRequest(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
        String roles = exchange.getRequestHeaders().getFirst("X-User-Roles");
        String body = "{\"route\":\"media\",\"userId\":\"" + userId + "\",\"roles\":\"" + roles + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** 学习域替身仅回显 Gateway 根据 JWT 重建的身份与权限，不持有题库数据。 */
    private static void handleLearningRequest(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
        String roles = exchange.getRequestHeaders().getFirst("X-User-Roles");
        String permissions = exchange.getRequestHeaders().getFirst("X-User-Permissions");
        String body = "{\"route\":\"learning\",\"userId\":\"" + userId + "\",\"roles\":\"" + roles
                + "\",\"permissions\":\"" + permissions + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
}
