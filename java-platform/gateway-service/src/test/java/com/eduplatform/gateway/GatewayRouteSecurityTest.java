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

    @DynamicPropertySource
    static void iamServiceUrl(DynamicPropertyRegistry registry) {
        try {
            iamStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            iamStub.createContext("/", GatewayRouteSecurityTest::handleIamRequest);
            iamStub.start();
            registry.add("IAM_SERVICE_URL", () -> "http://127.0.0.1:" + iamStub.getAddress().getPort());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopIamStub() {
        if (iamStub != null) iamStub.stop(0);
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

    private String token(Instant issuedAt, Instant expiresAt) {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("edu-platform-iam-test")
                .audience(List.of("edu-platform-gateway-test")).subject("00000000-0000-4000-8000-000000000001")
                .issuedAt(issuedAt).expiresAt(expiresAt).claim("roles", List.of("STUDENT"))
                .claim("permissions", List.of("learning:access")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
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
}
