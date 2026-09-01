package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.persistence.MediaRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T12F.1.1 真实非 READY 验收，只在专用 media 开发库短暂切换固定正文图状态。
 *
 * <p>输入是 T12F.1 固定 ARTICLE_IMAGE 与固定课程投影；输出应为安全的 MEDIA.NOT_READY。
 * finally 会恢复原状态、删除本轮精确服务授权 nonce，并清理 trace 前缀属于本任务的短期授权审计，
 * 不删除固定媒体记录或 OSS 对象。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "platform.media.messaging.enabled=false", "platform.media.maintenance.enabled=false",
        "platform.media.worker.enabled=false", "platform.media.oss.enabled=true",
        "spring.flyway.locations=filesystem:src/main/resources/db/migration",
        "spring.autoconfigure.exclude="
                + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayResilience4JCircuitBreakerAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayFunctionAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayStreamAutoConfiguration,"
                + "org.springframework.cloud.gateway.discovery.GatewayDiscoveryClientAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.SimpleUrlHandlerMappingGlobalCorsAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayReactiveLoadBalancerClientAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.LocalResponseCacheAutoConfiguration,"
                + "org.springframework.cloud.gateway.config.GatewayTracingAutoConfiguration"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12F11_REAL_ACCEPTANCE", matches = "true")
class T12F11ArticleImageStateRealAcceptanceTest {
    private static final String DATABASE = "edu_platform_media_dev";
    private static final String OWNER = "00000000-0000-4000-8000-000000000102";
    private static final String COURSE = "00000000-0000-4000-8000-00000000f201";
    private static final String LESSON = "00000000-0000-4000-8000-00000000f232";
    private static final String ARTICLE_IMAGE = "00000000-0000-4000-8000-00000000f103";

    @Autowired JdbcTemplate jdbc;
    @Autowired MediaRepository repository;
    @Autowired ObjectMapper mapper;

    private final HttpClient client = HttpClient.newHttpClient();

    /** 在创建 Spring 连接前确认目标是明确的 media 开发 Schema。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("MEDIA_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12F.1.1 real acceptance requires edu_platform_media_dev");
        }
    }

    /** 暂时改为 UPLOADED 后走 Gateway 请求，验证媒体域最终 READY 校验，并无条件恢复 Seed。 */
    @Test
    void nonReadyArticleImageIsRejectedAndFixedSeedIsRestored() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        String originalStatus = jdbc.queryForObject(
                "SELECT status FROM media_asset WHERE media_id=? AND purpose='ARTICLE_IMAGE'",
                String.class, ARTICLE_IMAGE);
        assertThat(originalStatus).isEqualTo("READY");
        Set<String> tokenIdsBefore = new HashSet<>(jdbc.queryForList(
                "SELECT token_id FROM consumed_service_authorization", String.class));
        try {
            assertThat(jdbc.update("UPDATE media_asset SET status='UPLOADED' WHERE media_id=? AND status='READY'",
                    ARTICLE_IMAGE)).isOne();
            HttpRequest request = HttpRequest.newBuilder(URI.create(gatewayUrl()
                            + "/api/v1/teacher/courses/" + COURSE + "/lessons/" + LESSON + "/article-content"))
                    .header("Authorization", "Bearer " + token())
                    .header("X-Trace-Id", "t12f11-real-nonready")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            JsonNode error = mapper.readTree(response.body());
            assertThat(error.get("code").asString()).isEqualTo("MEDIA.NOT_READY");
            assertThat(error.toString()).doesNotContain(
                    "objectKey", "bucket", "Bucket", "serviceToken", "Bearer", "ARTICLE_IMAGE");
        } finally {
            jdbc.update("UPDATE media_asset SET status=? WHERE media_id=?", originalStatus, ARTICLE_IMAGE);
            cleanupNewServiceAuthorizations(tokenIdsBefore);
            cleanupTaskAccessAudits();
            repository.deleteExpiredServiceAuthorizations(Instant.now());
            assertThat(jdbc.queryForObject("SELECT status FROM media_asset WHERE media_id=?",
                    String.class, ARTICLE_IMAGE)).isEqualTo("READY");
        }
    }

    /** 删除与测试前快照相比新增的随机 jti；调用已经结束，JWT 未暴露且不会再被重放。 */
    private void cleanupNewServiceAuthorizations(Set<String> tokenIdsBefore) {
        List<String> tokenIdsAfter = jdbc.queryForList(
                "SELECT token_id FROM consumed_service_authorization", String.class);
        tokenIdsAfter.stream().filter(tokenId -> !tokenIdsBefore.contains(tokenId))
                .forEach(tokenId -> jdbc.update(
                        "DELETE FROM consumed_service_authorization WHERE token_id=?", tokenId));
    }

    /** ORIGINAL 图片没有临时 OSS 对象；这里只按测试 trace 精确清理授权审计行。 */
    private void cleanupTaskAccessAudits() {
        List<String> grantIds = jdbc.queryForList(
                "SELECT grant_id FROM access_grant_audit WHERE trace_id LIKE 't12f11-%'", String.class);
        grantIds.forEach(grantId -> jdbc.update("DELETE FROM access_grant_audit WHERE grant_id=?", grantId));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM access_grant_audit WHERE trace_id LIKE 't12f11-%'", Integer.class)).isZero();
    }

    private static String gatewayUrl() {
        return System.getenv().getOrDefault("T12F11_GATEWAY_URL", "http://127.0.0.1:8080");
    }

    private static String token() {
        String secret = System.getenv("IAM_JWT_SECRET");
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("T12F.1.1 acceptance requires IAM_JWT_SECRET");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(System.getenv().getOrDefault("IAM_JWT_ISSUER", "edu-platform-iam"))
                .audience(List.of(System.getenv().getOrDefault("IAM_JWT_AUDIENCE", "edu-platform-gateway")))
                .subject(OWNER).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("TEACHER"))
                .claim("permissions", List.of("teaching:access"))
                .claim("sessionVersion", 0L)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
