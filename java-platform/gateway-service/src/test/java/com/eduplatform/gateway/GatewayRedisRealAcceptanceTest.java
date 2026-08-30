package com.eduplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 显式开关下启动真实 Gateway HTTP 服务器，使用本地 Redis Lua 验证分布式 429。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "platform.gateway.jwt.secret=test-only-real-gateway-secret-with-at-least-32-bytes",
        "platform.gateway.jwt.issuer=edu-platform-iam-test",
        "platform.gateway.jwt.audience=edu-platform-gateway-test",
        "platform.gateway.rate-limit.enabled=true",
        "platform.gateway.session-validation.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "GATEWAY_REDIS_ACCEPTANCE", matches = "true")
class GatewayRedisRealAcceptanceTest {
    private static final String PREFIX = "edu:test:t12d:gateway:" + UUID.randomUUID();
    private static HttpServer iamStub;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        try {
            iamStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            iamStub.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            iamStub.start();
            registry.add("IAM_SERVICE_URL", () -> "http://127.0.0.1:" + iamStub.getAddress().getPort());
            registry.add("platform.gateway.rate-limit.key-prefix", () -> PREFIX);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopStub() { if (iamStub != null) iamStub.stop(0); }

    @Value("${local.server.port}") int port;
    @Autowired ReactiveStringRedisTemplate redis;
    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        redis.delete(rateKey()).block();
    }

    @Test
    /** 前十次登录转发成功，第十一次由真实 Redis 共享窗口拒绝并返回 Retry-After。 */
    void realGatewayAndRedisReturnUnified429() {
        try {
            for (int request = 0; request < 10; request++) {
                client.post().uri("/api/v1/auth/login").bodyValue("{\"account\":\"x\",\"password\":\"x\"}")
                        .header("Content-Type", "application/json").exchange().expectStatus().isOk();
            }
            client.post().uri("/api/v1/auth/login").bodyValue("{\"account\":\"x\",\"password\":\"x\"}")
                    .header("Content-Type", "application/json").exchange()
                    .expectStatus().isEqualTo(429)
                    .expectHeader().exists("Retry-After")
                    .expectBody().jsonPath("$.code").isEqualTo("GATEWAY.RATE_LIMITED");
            assertThat(redis.hasKey(rateKey()).block()).isTrue();
        } finally {
            redis.delete(rateKey()).block();
        }
        assertThat(redis.hasKey(rateKey()).block()).isFalse();
    }

    private static String rateKey() {
        return PREFIX + ":login:" + sha256("ip:127.0.0.1");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
