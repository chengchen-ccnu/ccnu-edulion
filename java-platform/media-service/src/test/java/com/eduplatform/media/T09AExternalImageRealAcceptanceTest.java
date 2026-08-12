package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.gateway.GatewayServiceApplication;
import com.eduplatform.gateway.config.GatewayProperties;
import com.eduplatform.learning.LearningServiceApplication;
import com.eduplatform.learning.importing.LegacyQuestionJsonParser;
import com.eduplatform.learning.messaging.LearningOutboxPublisher;
import com.eduplatform.learning.messaging.LearningRabbitTopology;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.media.messaging.MediaRabbitTopology;
import com.eduplatform.media.messaging.OutboxPublisher;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.storage.ObjectStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T09A 单文件真实图片验收：Gateway、learning-service、media-service、MySQL、RabbitMQ 与阿里云 OSS。
 *
 * <p>该用例默认跳过，只有显式提供专用 dev Schema、一个真实只读 JSON 和一个精确 HTTPS 主机时运行。
 * 测试不输出题干、答案、JSON、来源 URL、Bucket 或 Object Key；finally 仅按本轮捕获的任务、题目、媒体、
 * 上传、事件与精确 Object Key 清理，并用哈希后的 ID 输出聚合证据。</p>
 */
@EnabledIfEnvironmentVariable(named = "T09A_EXTERNAL_IMAGE_ACCEPTANCE", matches = "true")
class T09AExternalImageRealAcceptanceTest {
    private static final String JWT_SECRET = "t09a-real-acceptance-jwt-secret-32-bytes";
    private static final String ISSUER = "edu-platform-iam";
    private static final String AUDIENCE = "edu-platform-gateway";
    private static final String GATEWAY_AUTOCONFIG_EXCLUDES = String.join(",",
            "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayResilience4JCircuitBreakerAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayFunctionAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayStreamAutoConfiguration",
            "org.springframework.cloud.gateway.discovery.GatewayDiscoveryClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.SimpleUrlHandlerMappingGlobalCorsAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayReactiveLoadBalancerClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.LocalResponseCacheAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayTracingAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration");
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * 运行单个真实来源的受控 E2E，并在任何阶段失败时执行精确回收。
     *
     * @throws Exception 网络、数据库、OSS 或清理断言失败；异常消息不携带来源内容或永久存储位置
     */
    @Test
    void transfersOneRealImageThroughGatewayAndPreciselyCleansEverything() throws Exception {
        requireEnvironment();
        String runId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();
        String expectedHost = required("T09A_EXTERNAL_IMAGE_HOST").toLowerCase();
        SourceItem source = sourceItem(expectedHost);
        Set<String> temporaryBefore = externalImageTemporaryFiles();
        String prefix = "t09a-acceptance/" + runId;

        try (ConfigurableApplicationContext mediaRaw = startMedia(prefix, expectedHost)) {
            var mediaContext = (ServletWebServerApplicationContext) mediaRaw;
            int mediaPort = mediaContext.getWebServer().getPort();
            try (ConfigurableApplicationContext learningRaw = startLearning(mediaPort)) {
                var learningContext = (ServletWebServerApplicationContext) learningRaw;
                int learningPort = learningContext.getWebServer().getPort();
                assertParserAcceptsExactlyOneImage(learningContext, source);
                try (ConfigurableApplicationContext gatewayRaw = startGateway(mediaPort, learningPort)) {
                    var gatewayContext = (ReactiveWebServerApplicationContext) gatewayRaw;
                    GatewayProperties jwt = gatewayContext.getBean(GatewayProperties.class);
                    assertThat(jwt.jwt().secret()).isEqualTo(JWT_SECRET);
                    assertThat(jwt.jwt().issuer()).isEqualTo(ISSUER);
                    assertThat(jwt.jwt().audience()).isEqualTo(AUDIENCE);
                    String token = teacherToken(ownerId);
                    assertThat(gatewayContext.getBean(ReactiveJwtDecoder.class).decode(token).block().getSubject())
                            .isEqualTo(ownerId);
                    runAcceptance(gatewayContext.getWebServer().getPort(), ownerId, token, runId, source,
                            learningContext, mediaContext, temporaryBefore);
                }
            }
        }
    }

    /** 强制使用专用 Schema 和单一真实文件，避免误连其他数据库或扩大来源范围。 */
    private static void requireEnvironment() {
        assertThat(System.getenv("LEARNING_MYSQL_DATABASE")).isEqualTo("edu_platform_learning_dev");
        assertThat(System.getenv("MEDIA_MYSQL_DATABASE")).isEqualTo("edu_platform_media_dev");
        assertThat(required("LEGACY_QUESTION_ROOT")).isNotBlank();
        assertThat(required("T09A_EXTERNAL_IMAGE_FILE")).isNotBlank();
        assertThat(required("T09A_EXTERNAL_IMAGE_HOST")).doesNotContain(",", "*", "://", "/");
    }

    /** 只读取显式指定的一份 JSON，并验证它位于只读题库根目录下。 */
    private SourceItem sourceItem(String expectedHost) throws Exception {
        Path root = Path.of(required("LEGACY_QUESTION_ROOT")).toRealPath();
        Path file = Path.of(required("T09A_EXTERNAL_IMAGE_FILE")).toRealPath();
        assertThat(file).startsWith(root);
        assertThat(file.getFileName().toString()).endsWith(".json");
        byte[] raw = Files.readAllBytes(file);
        assertThat(raw.length).isLessThanOrEqualTo(256 * 1024);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(raw);
        Path relative = root.relativize(file);
        assertThat(relative.getNameCount()).isGreaterThanOrEqualTo(3);
        String externalId = json.path("exerciseID").asString();
        assertThat(externalId).isNotBlank();
        return new SourceItem(root, relative.toString().replace('\\', '/'), relative.getName(0).toString(),
                relative.getName(1).toString(), externalId, sha256(raw), json, expectedHost);
    }

    /** 使用生产解析器确认候选无需复核且只有一张精确白名单主机图片。 */
    private static void assertParserAcceptsExactlyOneImage(ServletWebServerApplicationContext context,
            SourceItem source) {
        LegacyQuestionJsonParser parser = context.getBean(LegacyQuestionJsonParser.class);
        var parsed = parser.parse(source.json(), source.pathEdition(), source.pathGrade());
        assertThat(parsed.review()).isFalse();
        assertThat(parsed.candidate().children()).isEmpty();
        assertThat(parsed.candidate().imageUrls()).hasSize(1);
        URI image = URI.create(parsed.candidate().imageUrls().get(0));
        assertThat(image.getScheme()).isEqualTo("https");
        assertThat(image.getHost()).isEqualToIgnoringCase(source.expectedHost());
        assertThat(image.getPort()).isEqualTo(-1);
        assertThat(image.getUserInfo()).isNull();
    }

    /** 启动真实 media-service；白名单只有本轮单个主机，OSS 前缀也只属于本轮。 */
    private static ConfigurableApplicationContext startMedia(String prefix, String host) {
        return new SpringApplicationBuilder(MediaServiceApplication.class).profiles("local").run(
                "--server.port=0",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration",
                "--spring.cloud.gateway.enabled=false",
                "--spring.cloud.gateway.server.webflux.enabled=false",
                "--spring.autoconfigure.exclude=" + GATEWAY_AUTOCONFIG_EXCLUDES,
                "--platform.media.messaging.enabled=true",
                "--platform.media.messaging.outbox-poll-delay=1h",
                "--platform.media.oss.enabled=true",
                "--platform.media.oss.key-prefix=" + prefix,
                "--platform.media.worker.enabled=false",
                "--platform.media.maintenance.enabled=false",
                "--platform.media.external-image-import.enabled=true",
                "--platform.media.external-image-import.allowed-hosts=" + host,
                "--platform.media.external-image-import.max-bytes=10485760",
                "--platform.media.external-image-import.max-redirects=0");
    }

    /** 启动真实 learning-service，并把唯一媒体下游指向本轮 media-service 随机端口。 */
    private static ConfigurableApplicationContext startLearning(int mediaPort) {
        return new SpringApplicationBuilder(LearningServiceApplication.class).profiles("local").run(
                "--server.port=0",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=filesystem:../learning-service/src/main/resources/db/migration",
                "--spring.cloud.gateway.enabled=false",
                "--spring.cloud.gateway.server.webflux.enabled=false",
                "--spring.autoconfigure.exclude=" + GATEWAY_AUTOCONFIG_EXCLUDES,
                "--spring.datasource.url=jdbc:mysql://" + envOr("MYSQL_HOST", "127.0.0.1") + ":"
                        + envOr("MYSQL_PORT", "3306") + "/edu_platform_learning_dev?useUnicode=true&characterEncoding=UTF-8&serverTimezone="
                        + envOr("MYSQL_SERVER_TIMEZONE", "Asia/Shanghai") + "&useSSL="
                        + envOr("MYSQL_SSL_ENABLED", "false") + "&allowPublicKeyRetrieval="
                        + envOr("MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL", "true"),
                "--platform.learning.media.base-url=http://127.0.0.1:" + mediaPort,
                "--platform.learning.media.request-timeout=15s",
                "--platform.learning.import.messaging-enabled=true",
                "--platform.learning.import.outbox-poll-delay=1h",
                "--platform.learning.import.max-source-bytes=104857600",
                "--platform.learning.import.retention=7d",
                "--platform.learning.import.confirm-timeout=10s",
                "--platform.learning.import.max-publish-attempts=20");
    }

    /** 启动真实 Gateway；测试 JWT 密钥只存在于当前 JVM，路由只指向本轮两个服务。 */
    private static ConfigurableApplicationContext startGateway(int mediaPort, int learningPort) {
        return new SpringApplicationBuilder(GatewayServiceApplication.class).web(WebApplicationType.REACTIVE).profiles("local")
                .properties(Map.ofEntries(
                        Map.entry("spring.config.name", "t09a-no-external-config"),
                        Map.entry("spring.application.name", "gateway-service"),
                        Map.entry("spring.flyway.enabled", "false"),
                        Map.entry("spring.autoconfigure.exclude", String.join(",",
                                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")),
                        Map.entry("spring.cloud.gateway.server.webflux.routes[0].id", "media-service"),
                        Map.entry("spring.cloud.gateway.server.webflux.routes[0].uri", "http://127.0.0.1:" + mediaPort),
                        Map.entry("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", "Path=/api/v1/media/**"),
                        Map.entry("spring.cloud.gateway.server.webflux.routes[1].id", "learning-service"),
                        Map.entry("spring.cloud.gateway.server.webflux.routes[1].uri", "http://127.0.0.1:" + learningPort),
                        Map.entry("spring.cloud.gateway.server.webflux.routes[1].predicates[0]",
                                "Path=/api/v1/teacher/questions/**,/api/v1/teacher/legacy-question-imports,/api/v1/teacher/legacy-question-imports/**"),
                        Map.entry("platform.gateway.jwt.secret", JWT_SECRET),
                        Map.entry("platform.gateway.jwt.issuer", ISSUER),
                        Map.entry("platform.gateway.jwt.audience", AUDIENCE),
                        Map.entry("platform.gateway.allowed-origins", "http://127.0.0.1")))
                .run("--server.port=0",
                        "--MEDIA_SERVICE_URL=http://127.0.0.1:" + mediaPort,
                        "--LEARNING_SERVICE_URL=http://127.0.0.1:" + learningPort,
                        "--platform.gateway.jwt.secret=" + JWT_SECRET,
                        "--platform.gateway.jwt.issuer=" + ISSUER,
                        "--platform.gateway.jwt.audience=" + AUDIENCE);
    }

    /** 执行 dry-run、真实导入、幂等重放、非法主机拒绝、OSS 读取和精确清理断言。 */
    private void runAcceptance(int gatewayPort, String ownerId, String token, String runId, SourceItem source,
            ServletWebServerApplicationContext learningContext,
            ServletWebServerApplicationContext mediaContext, Set<String> temporaryBefore) throws Exception {
        ObjectMapper mapper = learningContext.getBean(ObjectMapper.class);
        JdbcTemplate learningJdbc = learningContext.getBean(JdbcTemplate.class);
        JdbcTemplate mediaJdbc = mediaContext.getBean(JdbcTemplate.class);
        ObjectStorage storage = mediaContext.getBean(ObjectStorage.class);
        QuestionRepository learningRepository = learningContext.getBean(QuestionRepository.class);
        MediaRepository mediaRepository = mediaContext.getBean(MediaRepository.class);
        LearningOutboxPublisher learningPublisher = learningContext.getBean(LearningOutboxPublisher.class);
        OutboxPublisher mediaPublisher = mediaContext.getBean(OutboxPublisher.class);
        RabbitAdmin rabbitAdmin = new RabbitAdmin(mediaContext.getBean(RabbitTemplate.class));
        assertQueuesEmpty(rabbitAdmin);

        AcceptanceIds ids = new AcceptanceIds();
        try {
            ids.dryTaskId = createTask(gatewayPort, token, mapper, source, true, runId + "-dry");
            JsonNode dry = submit(gatewayPort, token, mapper, source, ids.dryTaskId, UUID.randomUUID(), true);
            assertThat(dry.path("validCount").asInt()).isEqualTo(1);
            assertThat(dry.path("importedCount").asInt()).isZero();
            assertThat(dry.path("reviewCount").asInt()).isZero();
            assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id=?",
                    Integer.class, ownerId)).isZero();
            assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE owner_id=?",
                    Integer.class, ownerId)).isZero();
            assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM media_audit_record WHERE actor_id=?",
                    Integer.class, ownerId)).isZero();
            assertThat(externalImageTemporaryFiles()).isEqualTo(temporaryBefore);

            ids.importTaskId = createTask(gatewayPort, token, mapper, source, false, runId + "-import");
            ids.batchId = UUID.randomUUID();
            JsonNode imported = submit(gatewayPort, token, mapper, source, ids.importTaskId, ids.batchId, true);
            assertThat(imported.path("importedCount").asInt()).isEqualTo(1);
            assertThat(imported.path("reviewCount").asInt()).isZero();
            ids.questionId = imported.path("items").get(0).path("questionId").asString();
            assertThat(ids.questionId).isNotBlank();

            JsonNode replay = submit(gatewayPort, token, mapper, source, ids.importTaskId, ids.batchId, true);
            assertThat(replay.path("replayed").asBoolean()).isTrue();
            assertThat(replay.path("items").get(0).path("questionId").asString()).isEqualTo(ids.questionId);

            Map<String, Object> mediaRow = mediaJdbc.queryForMap(
                    "SELECT media_id,upload_id,object_key,purpose,status,mime_type,size_bytes,sha256 FROM media_asset WHERE owner_id=?",
                    ownerId);
            ids.mediaId = mediaRow.get("media_id").toString();
            ids.uploadId = mediaRow.get("upload_id").toString();
            ids.objectKey = mediaRow.get("object_key").toString();
            assertThat(mediaRow.get("purpose")).isEqualTo("QUESTION_IMAGE");
            assertThat(mediaRow.get("status")).isEqualTo("READY");
            assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id=?",
                    Integer.class, ownerId)).isEqualTo(1);
            assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE owner_id=?",
                    Integer.class, ownerId)).isEqualTo(1);

            assertLearningStoresOnlyMediaId(learningJdbc, ids, source, mediaRow);
            assertGatewayProjectionsAndOss(gatewayPort, token, mapper, ids, source, mediaRow, storage);
            assertMediaIdempotency(gatewayPort, token, mapper, ids, source, mediaJdbc);
            assertIllegalHostRejected(gatewayPort, token, mapper, ownerId, mediaJdbc);
            assertThat(externalImageTemporaryFiles()).isEqualTo(temporaryBefore);

            ids.mediaEventIds.addAll(mediaJdbc.queryForList(
                    "SELECT event_id FROM outbox_event WHERE aggregate_id=?", String.class, ids.mediaId));
            mediaRepository.pendingOutbox(Instant.now().plusSeconds(1), 100).stream()
                    .filter(event -> ids.mediaId.equals(event.aggregateId())).forEach(mediaPublisher::publish);
            publishLearningMediaEvents(ids, learningRepository, learningPublisher, "MediaReferenceBound");
            awaitReferenceAndConsumption(mediaJdbc, ids, "BOUND");

            JsonNode cleanup = request(gatewayPort, token, "DELETE",
                    "/api/v1/teacher/legacy-question-imports/" + ids.importTaskId + "/created-resources", null, 200, mapper);
            assertThat(cleanup.path("questionsDeleted").asInt()).isEqualTo(1);
            assertThat(cleanup.path("mediaReferencesReleased").asInt()).isEqualTo(1);
            request(gatewayPort, token, "DELETE",
                    "/api/v1/teacher/legacy-question-imports/" + ids.dryTaskId + "/created-resources", null, 200, mapper);
            publishLearningMediaEvents(ids, learningRepository, learningPublisher, "MediaReferenceReleased");
            awaitReferenceAndConsumption(mediaJdbc, ids, "RELEASED");
            assertQueuesEmpty(rabbitAdmin);

            storage.delete(ids.objectKey);
            assertThatThrownBy(() -> storage.head(ids.objectKey)).isInstanceOf(RuntimeException.class);
        } finally {
            stopRabbitListeners(learningContext, mediaContext);
            cleanupExact(ids, ownerId, learningJdbc, mediaJdbc, storage);
            assertQueuesEmpty(rabbitAdmin);
            assertThat(externalImageTemporaryFiles()).isEqualTo(temporaryBefore);
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("realFiles", 1);
        evidence.put("allowedHost", source.expectedHost());
        evidence.put("dryRunCreatedMedia", 0);
        evidence.put("importedQuestions", 1);
        evidence.put("questionImageReady", true);
        evidence.put("idempotentMediaRows", 1);
        evidence.put("illegalHostRejected", true);
        evidence.put("mysqlRemainingRows", 0);
        evidence.put("ossObjectDeleted", true);
        evidence.put("rabbitQueuesEmpty", true);
        evidence.put("temporaryFilesRemaining", 0);
        evidence.put("importTaskIdSha256", shortHash(ids.importTaskId));
        evidence.put("questionIdSha256", shortHash(ids.questionId));
        evidence.put("mediaIdSha256", shortHash(ids.mediaId));
        evidence.put("uploadIdSha256", shortHash(ids.uploadId));
        evidence.put("objectKeySha256", shortHash(ids.objectKey));
        System.out.println("T09A_REAL_EXTERNAL_IMAGE_E2E " + mapper.writeValueAsString(evidence));
    }

    private String createTask(int port, String token, ObjectMapper mapper, SourceItem source,
            boolean dryRun, String requestSeed) throws Exception {
        String rootFingerprint = sha256(source.root().toString().replace('\\', '/').toLowerCase());
        JsonNode response = request(port, token, "POST", "/api/v1/teacher/legacy-question-imports",
                mapper.writeValueAsString(Map.of("clientRequestId", UUID.nameUUIDFromBytes(requestSeed.getBytes(StandardCharsets.UTF_8)),
                        "sourceSystem", "LEGACY_T09A_REAL_E2E", "sourceRootFingerprint", rootFingerprint,
                        "dryRun", dryRun, "maxItems", 1)), 201, mapper);
        return response.path("id").asString();
    }

    private JsonNode submit(int port, String token, ObjectMapper mapper, SourceItem source,
            String taskId, UUID batchId, boolean finalBatch) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("externalId", source.externalId());
        item.put("relativePath", source.relativePath());
        item.put("pathEdition", source.pathEdition());
        item.put("pathGrade", source.pathGrade());
        item.put("contentSha256", source.contentSha256());
        item.put("json", source.json());
        String body = mapper.writeValueAsString(Map.of("batchId", batchId, "finalBatch", finalBatch,
                "items", List.of(item)));
        return request(port, token, "POST", "/api/v1/teacher/legacy-question-imports/" + taskId + "/batches",
                body, 200, mapper);
    }

    /** 断言题目域行与教师响应不保存来源 URL、Bucket 或 Object Key。 */
    private void assertLearningStoresOnlyMediaId(JdbcTemplate jdbc, AcceptanceIds ids, SourceItem source,
            Map<String, Object> mediaRow) {
        Map<String, Object> version = jdbc.queryForMap("""
                SELECT v.stem_md,v.options_json,v.answers_json,v.analysis_md,m.media_id,m.purpose
                FROM question_version v JOIN question_version_media m ON m.question_version_id=v.id
                WHERE v.question_id=?
                """, ids.questionId);
        assertThat(version.get("media_id")).isEqualTo(ids.mediaId);
        assertThat(version.get("purpose")).isEqualTo("QUESTION_IMAGE");
        String persisted = version.toString().toLowerCase();
        assertNoStorageLocator(persisted, source.expectedHost(), ids.objectKey, "learning persistence");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name IN ('question_version','question_version_media')
                  AND LOWER(column_name) IN ('bucket','object_key','url','signed_url','external_url')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_version_media WHERE media_id=?",
                Integer.class, ids.mediaId)).isEqualTo(1);
        assertThat(mediaRow.get("object_key")).isEqualTo(ids.objectKey);
    }

    /** 通过 Gateway 读取题目与媒体投影，再使用短期授权读取真实 OSS 对象。 */
    private void assertGatewayProjectionsAndOss(int port, String token, ObjectMapper mapper, AcceptanceIds ids,
            SourceItem source, Map<String, Object> mediaRow, ObjectStorage storage) throws Exception {
        JsonNode question = request(port, token, "GET", "/api/v1/teacher/questions/" + ids.questionId,
                null, 200, mapper);
        String questionProjection = mapper.writeValueAsString(question).toLowerCase();
        assertNoStorageLocator(questionProjection, source.expectedHost(), ids.objectKey, "question projection");
        assertThat(question.path("currentVersion").path("media").get(0).path("mediaId").asString())
                .isEqualTo(ids.mediaId);

        JsonNode media = request(port, token, "GET", "/api/v1/media/" + ids.mediaId, null, 200, mapper);
        assertThat(media.path("purpose").asString()).isEqualTo("QUESTION_IMAGE");
        assertThat(media.path("status").asString()).isEqualTo("READY");
        assertNoStorageLocator(mapper.writeValueAsString(media), source.expectedHost(), ids.objectKey,
                "media projection");

        var metadata = storage.head(ids.objectKey);
        assertThat(metadata.size()).isEqualTo(((Number) mediaRow.get("size_bytes")).longValue());
        assertThat(metadata.mimeType()).startsWith("image/");
        JsonNode grant = request(port, token, "POST", "/api/v1/media/" + ids.mediaId + "/access-grants",
                "{\"variant\":\"ORIGINAL\",\"disposition\":\"INLINE\"}", 201, mapper);
        HttpResponse<byte[]> downloaded = http.send(HttpRequest.newBuilder(URI.create(grant.path("url").asString()))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(sha256(downloaded.body())).isEqualTo(mediaRow.get("sha256"));
    }

    /** 使用 learning-service 的确定性 requestId 直接重放媒体转存，证明不会产生第二行或第二对象。 */
    private void assertMediaIdempotency(int port, String token, ObjectMapper mapper, AcceptanceIds ids,
            SourceItem source, JdbcTemplate mediaJdbc) throws Exception {
        var parser = new LegacyQuestionJsonParser();
        String sourceUrl = parser.parse(source.json(), source.pathEdition(), source.pathGrade())
                .candidate().imageUrls().get(0);
        UUID clientRequestId = UUID.nameUUIDFromBytes((ids.importTaskId + "\0" + source.externalId()
                + ":parent:image:1").getBytes(StandardCharsets.UTF_8));
        JsonNode replay = request(port, token, "POST", "/api/v1/media/external-image-transfers",
                mapper.writeValueAsString(Map.of("clientRequestId", clientRequestId, "sourceUrl", sourceUrl)),
                200, mapper);
        assertThat(replay.path("mediaId").asString()).isEqualTo(ids.mediaId);
        assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE media_id=?",
                Integer.class, ids.mediaId)).isEqualTo(1);
        assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE upload_id=?",
                Integer.class, ids.uploadId)).isEqualTo(1);
    }

    /** 非白名单主机必须在下载和持久化前返回 422。 */
    private void assertIllegalHostRejected(int port, String token, ObjectMapper mapper, String ownerId,
            JdbcTemplate mediaJdbc) throws Exception {
        UUID requestId = UUID.randomUUID();
        JsonNode rejected = request(port, token, "POST", "/api/v1/media/external-image-transfers",
                mapper.writeValueAsString(Map.of("clientRequestId", requestId,
                        "sourceUrl", "https://example.com/t09a-rejected.png")), 422, mapper);
        assertThat(rejected.path("code").asString()).isEqualTo("MEDIA.EXTERNAL_IMAGE_INVALID");
        assertThat(mediaJdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_session WHERE owner_id=? AND client_request_id=?",
                Integer.class, ownerId, requestId.toString())).isZero();
    }

    /** 只发布本轮指定类型的媒体引用事件，并记录精确 eventId 供清理。 */
    private static void publishLearningMediaEvents(AcceptanceIds ids, QuestionRepository repository,
            LearningOutboxPublisher publisher, String type) {
        List<QuestionRepository.Outbox> events = repository.pendingOutbox(Instant.now().plusSeconds(1), 100).stream()
                .filter(event -> ids.mediaId.equals(event.aggregateId()) && type.equals(event.eventType())).toList();
        assertThat(events).hasSize(1);
        ids.learningEventIds.add(events.get(0).eventId());
        publisher.publish(events.get(0));
    }

    /** 等待 RabbitMQ ACK 后的引用状态与 eventId 消费幂等证据。 */
    private static void awaitReferenceAndConsumption(JdbcTemplate mediaJdbc, AcceptanceIds ids, String status)
            throws InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            Integer references = mediaJdbc.queryForObject(
                    "SELECT COUNT(*) FROM media_reference WHERE media_id=? AND status=?",
                    Integer.class, ids.mediaId, status);
            int consumed = 0;
            for (String eventId : ids.learningEventIds) consumed += mediaJdbc.queryForObject(
                    "SELECT COUNT(*) FROM consumed_event WHERE event_id=?", Integer.class, eventId);
            if (references != null && references == 1 && consumed == ids.learningEventIds.size()) return;
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("media reference event was not consumed in time");
    }

    /** 按本轮精确 ID 逆序清理 MySQL 与 OSS，不使用 owner 批量删除或 Bucket 列举。 */
    private static void cleanupExact(AcceptanceIds ids, String ownerId, JdbcTemplate learningJdbc,
            JdbcTemplate mediaJdbc, ObjectStorage storage) {
        // 异常可能发生在 HTTP 已落库、测试尚未保存响应 ID 的窗口；随机 owner 仅属于本轮，用它解析后仍逐 ID 清理。
        List<Map<String, Object>> ownedMedia = mediaJdbc.queryForList(
                "SELECT media_id,upload_id,object_key FROM media_asset WHERE owner_id=?", ownerId);
        assertThat(ownedMedia).hasSizeLessThanOrEqualTo(1);
        if (!ownedMedia.isEmpty()) {
            Map<String, Object> row = ownedMedia.get(0);
            if (ids.mediaId == null) ids.mediaId = row.get("media_id").toString();
            if (ids.uploadId == null) ids.uploadId = row.get("upload_id").toString();
            if (ids.objectKey == null) ids.objectKey = row.get("object_key").toString();
        }
        if (ids.questionId == null && ids.importTaskId != null) {
            List<String> questionIds = learningJdbc.queryForList(
                    "SELECT question_id FROM question_legacy_source_ref WHERE import_task_id=? AND question_id IS NOT NULL",
                    String.class, ids.importTaskId);
            assertThat(questionIds).hasSizeLessThanOrEqualTo(1);
            if (!questionIds.isEmpty()) ids.questionId = questionIds.get(0);
        }
        if (ids.mediaId != null) {
            addMissing(ids.learningEventIds, learningJdbc.queryForList(
                    "SELECT event_id FROM learning_outbox_event WHERE aggregate_id=?", String.class, ids.mediaId));
            addMissing(ids.mediaEventIds, mediaJdbc.queryForList(
                    "SELECT event_id FROM outbox_event WHERE aggregate_id=?", String.class, ids.mediaId));
        }
        if (ids.objectKey != null) {
            try { storage.delete(ids.objectKey); } catch (RuntimeException ignored) { }
        }
        for (String eventId : ids.learningEventIds) {
            mediaJdbc.update("DELETE FROM consumed_event WHERE event_id=?", eventId);
            learningJdbc.update("DELETE FROM learning_consumed_event WHERE event_id=?", eventId);
            learningJdbc.update("DELETE FROM learning_outbox_event WHERE event_id=?", eventId);
        }
        for (String eventId : ids.mediaEventIds) {
            mediaJdbc.update("DELETE FROM consumed_event WHERE event_id=?", eventId);
            mediaJdbc.update("DELETE FROM outbox_event WHERE event_id=?", eventId);
        }
        if (ids.mediaId != null) {
            mediaJdbc.update("DELETE FROM temporary_access_object WHERE media_id=?", ids.mediaId);
            mediaJdbc.update("DELETE FROM access_grant_audit WHERE media_id=?", ids.mediaId);
            mediaJdbc.update("DELETE FROM media_audit_record WHERE media_id=? OR upload_id=?", ids.mediaId, ids.uploadId);
            mediaJdbc.update("DELETE FROM media_reference WHERE media_id=?", ids.mediaId);
            mediaJdbc.update("DELETE FROM processing_job WHERE media_id=?", ids.mediaId);
            mediaJdbc.update("DELETE FROM media_variant_component WHERE media_id=?", ids.mediaId);
            mediaJdbc.update("DELETE FROM media_variant WHERE media_id=?", ids.mediaId);
            mediaJdbc.update("DELETE FROM media_asset WHERE media_id=?", ids.mediaId);
        }
        if (ids.uploadId != null) {
            mediaJdbc.update("DELETE FROM upload_part WHERE upload_id=?", ids.uploadId);
            mediaJdbc.update("DELETE FROM upload_session WHERE upload_id=?", ids.uploadId);
        }
        for (String taskId : List.of(ids.dryTaskId == null ? "" : ids.dryTaskId,
                ids.importTaskId == null ? "" : ids.importTaskId)) {
            if (taskId.isBlank()) continue;
            learningJdbc.update("DELETE FROM question_legacy_import_issue WHERE import_task_id=?", taskId);
            learningJdbc.update("DELETE FROM question_legacy_source_ref WHERE import_task_id=?", taskId);
            learningJdbc.update("DELETE FROM question_legacy_import_batch WHERE import_task_id=?", taskId);
            learningJdbc.update("DELETE FROM question_import_row_error WHERE import_task_id=?", taskId);
            List<String> remainingEvents = learningJdbc.queryForList(
                    "SELECT event_id FROM learning_outbox_event WHERE aggregate_id=?", String.class, taskId);
            for (String eventId : remainingEvents) {
                learningJdbc.update("DELETE FROM learning_consumed_event WHERE event_id=?", eventId);
                learningJdbc.update("DELETE FROM learning_outbox_event WHERE event_id=?", eventId);
            }
            learningJdbc.update("DELETE FROM question_import_task WHERE id=?", taskId);
        }
        if (ids.questionId != null) {
            List<String> versionIds = learningJdbc.queryForList(
                    "SELECT id FROM question_version WHERE question_id=?", String.class, ids.questionId);
            for (String versionId : versionIds) {
                learningJdbc.update("DELETE FROM question_version_child WHERE parent_question_version_id=?", versionId);
                learningJdbc.update("DELETE FROM question_version_media WHERE question_version_id=?", versionId);
                learningJdbc.update("DELETE FROM question_version_knowledge_point WHERE question_version_id=?", versionId);
            }
            learningJdbc.update("DELETE FROM question_version_child WHERE child_question_id=?", ids.questionId);
            learningJdbc.update("DELETE FROM question_idempotency_request WHERE resource_id=?", ids.questionId);
            learningJdbc.update("DELETE FROM question_version WHERE question_id=?", ids.questionId);
            learningJdbc.update("DELETE FROM question_aggregate WHERE id=?", ids.questionId);
        }
        assertThat(learningJdbc.queryForObject("SELECT COUNT(*) FROM question_import_task WHERE owner_id=?",
                Integer.class, ownerId)).isZero();
        assertThat(learningJdbc.queryForObject("SELECT COUNT(*) FROM question_aggregate WHERE owner_id=?",
                Integer.class, ownerId)).isZero();
        assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id=?",
                Integer.class, ownerId)).isZero();
        assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE owner_id=?",
                Integer.class, ownerId)).isZero();
        if (ids.mediaId != null) {
            for (String table : List.of("media_reference", "processing_job", "media_variant",
                    "media_variant_component", "temporary_access_object", "access_grant_audit")) {
                assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE media_id=?",
                        Integer.class, ids.mediaId)).isZero();
            }
            assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM media_audit_record WHERE media_id=? OR upload_id=?",
                    Integer.class, ids.mediaId, ids.uploadId)).isZero();
        }
        if (ids.uploadId != null) {
            assertThat(mediaJdbc.queryForObject("SELECT COUNT(*) FROM upload_part WHERE upload_id=?",
                    Integer.class, ids.uploadId)).isZero();
        }
    }

    /** 将数据库兜底发现的事件 ID 合并到本轮集合，避免正常路径已捕获时重复删除。 */
    private static void addMissing(List<String> target, List<String> discovered) {
        discovered.stream().filter(eventId -> !target.contains(eventId)).forEach(target::add);
    }

    /** 仅输出投影类别与布尔结果，断言失败时也不泄露来源 URL、Bucket 或精确 Object Key。 */
    private static void assertNoStorageLocator(String projection, String sourceHost, String objectKey,
            String projectionKind) {
        String normalized = projection.toLowerCase();
        boolean safe = !normalized.contains("https://")
                && !normalized.contains(sourceHost.toLowerCase())
                && !normalized.contains(required("OSS_BUCKET").toLowerCase())
                && !normalized.contains(objectKey.toLowerCase());
        assertThat(safe).as("%s contains no external or storage locator", projectionKind).isTrue();
    }

    /** 停止两个服务的监听容器，避免精确清理与异步 ACK 竞态。 */
    private static void stopRabbitListeners(ServletWebServerApplicationContext learning,
            ServletWebServerApplicationContext media) {
        learning.getBean(RabbitListenerEndpointRegistry.class).stop();
        media.getBean(RabbitListenerEndpointRegistry.class).stop();
    }

    /** 本机六个持久队列在验收前后都必须没有消息。 */
    private static void assertQueuesEmpty(RabbitAdmin admin) {
        for (String queue : List.of(MediaRabbitTopology.PROCESSING_QUEUE, MediaRabbitTopology.REFERENCES_QUEUE,
                MediaRabbitTopology.LIFECYCLE_QUEUE, MediaRabbitTopology.DEAD_QUEUE,
                LearningRabbitTopology.IMPORT_QUEUE, LearningRabbitTopology.IMPORT_DEAD_QUEUE)) {
            var info = admin.getQueueInfo(queue);
            assertThat(info).as("RabbitMQ queue %s", queue).isNotNull();
            assertThat(info.getMessageCount()).as("RabbitMQ queue %s", queue).isZero();
        }
    }

    private JsonNode request(int port, String token, String method, String path, String body,
            int expectedStatus, ObjectMapper mapper) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).header("X-Trace-Id", "t09a-real-e2e");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String errorCode = "none";
        if (response.statusCode() != expectedStatus && !response.body().isBlank()) {
            try { errorCode = mapper.readTree(response.body()).path("code").asString("unknown"); }
            catch (Exception ignored) { errorCode = "unparseable"; }
        }
        String bearerChallenge = response.headers().firstValue("WWW-Authenticate").orElse("none");
        assertThat(response.statusCode()).as("%s %s status, safeErrorCode=%s, bearerChallenge=%s",
                        method, path, errorCode, bearerChallenge)
                .isEqualTo(expectedStatus);
        return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    /** 生成仅供本 JVM Gateway 验证的 5 分钟教师令牌。 */
    private static String teacherToken(String ownerId) {
        Instant now = Instant.now();
        SecretKey key = new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE)).subject(ownerId)
                .id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("TEACHER")).claim("permissions", List.of("teaching:access")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** 返回默认临时目录内转存专用前缀文件名；测试只比较集合，不读取其他临时文件内容。 */
    private static Set<String> externalImageTemporaryFiles() throws Exception {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        try (var files = Files.list(root)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("edu-external-question-image-")).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String shortHash(String value) { return value == null ? "none" : sha256(value).substring(0, 16); }

    private record SourceItem(Path root, String relativePath, String pathEdition, String pathGrade,
            String externalId, String contentSha256, JsonNode json, String expectedHost) {}

    private static final class AcceptanceIds {
        String dryTaskId;
        String importTaskId;
        UUID batchId;
        String questionId;
        String mediaId;
        String uploadId;
        String objectKey;
        final List<String> learningEventIds = new ArrayList<>();
        final List<String> mediaEventIds = new ArrayList<>();
    }
}
