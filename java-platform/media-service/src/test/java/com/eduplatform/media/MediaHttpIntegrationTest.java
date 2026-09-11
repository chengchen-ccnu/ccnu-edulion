package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.messaging.MediaEventFactory;
import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.messaging.MediaEventParser;
import com.eduplatform.media.messaging.MediaReferenceListener;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.processing.MediaProcessingCoordinator;
import com.eduplatform.media.service.MediaReclaimer;
import com.eduplatform.media.messaging.MediaMessageRecoverer;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MediaTestConfiguration.class)
/**
 * 使用 H2 MySQL 模式和 Fake OSS 验证媒体 HTTP 契约、幂等、状态机、引用台账与回收。
 * <p>每个用例前清空仅属于内存测试库的表，不连接本机开发 Schema 或真实 OSS。</p>
 */
class MediaHttpIntegrationTest {
    private static final String TEACHER = "00000000-0000-4000-8000-000000007101";
    private static final String STUDENT = "00000000-0000-4000-8000-000000007102";
    private static final String SERVICE_SECRET = "test-only-course-media-auth-secret-32-bytes";
    private static final String HLS_V2 = "hls-fmp4-v2";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired FakeObjectStorage storage;
    @Autowired MediaRepository repository;
    @Autowired MediaEventParser parser;
    @Autowired MediaEventFactory events;
    @Autowired MediaReferenceListener references;
    @Autowired TransactionTemplate transactions;
    @Autowired MediaProcessingCoordinator coordinator;
    @Autowired MediaMessageRecoverer recoverer;
    @Autowired FakeFfmpegCommandRunner ffmpeg;
    @Autowired FakeExternalImageDownloader externalImages;
    @Autowired MediaProperties mediaProperties;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM consumed_service_authorization");
        jdbc.update("DELETE FROM temporary_access_object");
        jdbc.update("DELETE FROM access_grant_audit");
        jdbc.update("DELETE FROM media_audit_record");
        jdbc.update("DELETE FROM consumed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM media_reference");
        jdbc.update("DELETE FROM processing_job");
        jdbc.update("DELETE FROM media_variant_component");
        jdbc.update("DELETE FROM media_variant");
        jdbc.update("DELETE FROM media_asset");
        jdbc.update("DELETE FROM upload_part");
        jdbc.update("DELETE FROM upload_session");
    }

    @Test
    /** Given READY 封面，When 校验 owner/用途/状态，Then 仅精确匹配成功且不暴露存储字段。 */
    void bindingValidationRejectsOtherOwnerWrongPurposeMissingAndNotReady() throws Exception {
        CompletedTestAsset ready = createReadyCover("00000000-0000-4000-8000-000000007207", "bind-cover");
        HttpResponse<String> valid = send("POST", "/api/v1/media/" + ready.mediaId() + "/binding-validations",
                "{\"purpose\":\"COURSE_COVER\"}", "TEACHER");
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(valid.body()).contains("\"bindable\":true", "\"status\":\"READY\"")
                .doesNotContain("objectKey", "url");
        JsonNode validation = mapper.readTree(valid.body());
        assertThat(validation.get("originalFileName").asText()).isEqualTo("cover.png");
        assertThat(validation.get("mimeType").asText()).isEqualTo("image/png");
        assertThat(validation.get("size").asLong()).isPositive();
        assertThat(validation.get("variants").isArray()).isTrue();
        assertThat(send("POST", "/api/v1/media/" + ready.mediaId() + "/binding-validations",
                "{\"purpose\":\"LESSON_VIDEO\"}", "TEACHER").statusCode()).isEqualTo(422);
        assertThat(sendAs("POST", "/api/v1/media/" + ready.mediaId() + "/binding-validations",
                "{\"purpose\":\"COURSE_COVER\"}", "TEACHER",
                "00000000-0000-4000-8000-000000007199").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/v1/media/00000000-0000-4000-8000-000000007999/binding-validations",
                "{\"purpose\":\"COURSE_COVER\"}", "TEACHER").statusCode()).isEqualTo(404);

        byte[] bytes = "pending".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        JsonNode session = mapper.readTree(send("POST", "/api/v1/media/uploads", """
                {"clientRequestId":"00000000-0000-4000-8000-000000007208","purpose":"COURSE_COVER",
                 "fileName":"pending.png","size":%d,"mimeType":"image/png","sha256":"%s"}
                """.formatted(bytes.length, sha), "TEACHER").body());
        storage.upload(session.get("uploadGrant").get("url").asString(), bytes, "image/png", sha);
        JsonNode pending = mapper.readTree(send("POST", "/api/v1/media/uploads/" + session.get("uploadId").asString()
                + "/complete", "{\"sha256\":\"" + sha + "\",\"parts\":[]}", "TEACHER").body());
        assertThat(send("POST", "/api/v1/media/" + pending.get("mediaId").asString() + "/binding-validations",
                "{\"purpose\":\"COURSE_COVER\"}", "TEACHER").statusCode()).isEqualTo(409);
    }

    @Test
    /** AI 题图按 learning:access 开放并保存为 USER_PRIVATE；角色不能替代能力，非 owner 不可读写。 */
    void aiQuestionImageUsesLearningPermissionAndUserPrivateOwnership() throws Exception {
        byte[] bytes = "small-ai-question-image".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        String body = """
                {"clientRequestId":"00000000-0000-4000-8000-000000007401","purpose":"AI_QUESTION_IMAGE",
                 "fileName":"question.webp","size":%d,"mimeType":"image/webp","sha256":"%s"}
                """.formatted(bytes.length, sha);
        assertThat(sendAs("POST", "/api/v1/media/uploads", body, "STUDENT", STUDENT).statusCode()).isEqualTo(403);

        HttpResponse<String> created = sendAsWithPermissions("POST", "/api/v1/media/uploads", body,
                "TEACHER", "teaching:access,learning:access", STUDENT);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode session = mapper.readTree(created.body());
        storage.upload(session.get("uploadGrant").get("url").asString(), bytes, "image/webp", sha);
        HttpResponse<String> completed = sendAsWithPermissions("POST", "/api/v1/media/uploads/"
                + session.get("uploadId").asString() + "/complete", "{\"sha256\":\"" + sha
                + "\",\"parts\":[]}", "TEACHER", "learning:access", STUDENT);
        assertThat(completed.statusCode()).isEqualTo(202);
        JsonNode asset = mapper.readTree(completed.body());
        assertThat(asset.get("scope").asString()).isEqualTo("USER_PRIVATE");
        assertThat(asset.get("purpose").asString()).isEqualTo("AI_QUESTION_IMAGE");
        String mediaId = asset.get("mediaId").asString();
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(mediaId) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        coordinator.process(parser.parse(uploaded.payloadJson()));
        assertThat(sendAsWithPermissions("GET", "/api/v1/media/" + mediaId, null,
                "STUDENT", "learning:access", STUDENT).statusCode()).isEqualTo(200);
        assertThat(sendAsWithPermissions("GET", "/api/v1/media/" + mediaId, null,
                "STUDENT", "learning:access", TEACHER).statusCode()).isEqualTo(404);
        assertThat(sendAsWithPermissions("POST", "/api/v1/media/" + mediaId + "/binding-validations",
                "{\"purpose\":\"AI_QUESTION_IMAGE\"}", "STUDENT", "learning:access", TEACHER)
                .statusCode()).isEqualTo(404);

        for (String invalid : List.of(
                "{\"clientRequestId\":\"00000000-0000-4000-8000-000000007402\",\"purpose\":\"AI_QUESTION_IMAGE\",\"fileName\":\"x.svg\",\"size\":1,\"mimeType\":\"image/svg+xml\",\"sha256\":\"" + sha + "\"}",
                "{\"clientRequestId\":\"00000000-0000-4000-8000-000000007403\",\"purpose\":\"AI_QUESTION_IMAGE\",\"fileName\":\"x.gif\",\"size\":1,\"mimeType\":\"image/gif\",\"sha256\":\"" + sha + "\"}",
                "{\"clientRequestId\":\"00000000-0000-4000-8000-000000007404\",\"purpose\":\"AI_QUESTION_IMAGE\",\"fileName\":\"x.png\",\"size\":10485761,\"mimeType\":\"image/png\",\"sha256\":\"" + sha + "\"}")) {
            assertThat(sendAsWithPermissions("POST", "/api/v1/media/uploads", invalid,
                    "STUDENT", "learning:access", STUDENT).statusCode()).isEqualTo(422);
        }
    }

    @Test
    /** Given 虚构 HTTPS 图片，When 转存并重放，Then 只建一个 READY 资产且数据库/事件不保存 URL。 */
    void externalQuestionImageTransferIsIdempotentAndPersistsNoSourceUrl() throws Exception {
        String request = """
                {"clientRequestId":"00000000-0000-4000-8000-000000007301",
                 "sourceUrl":"https://images.fixture.test/question.png","scope":"TEACHER"}
                """;
        HttpResponse<String> first = send("POST", "/api/v1/media/external-image-transfers", request, "TEACHER");
        HttpResponse<String> replay = send("POST", "/api/v1/media/external-image-transfers", request, "TEACHER");
        assertThat(first.statusCode()).isEqualTo(201); assertThat(replay.statusCode()).isEqualTo(200);
        JsonNode asset = mapper.readTree(first.body());
        assertThat(asset.get("purpose").asString()).isEqualTo("QUESTION_IMAGE");
        assertThat(asset.get("status").asString()).isEqualTo("READY");
        assertThat(externalImages.calls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_asset", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE payload_json LIKE '%images.fixture.test%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_audit_record WHERE details LIKE '%images.fixture.test%'", Integer.class)).isZero();
        String malformed = """
                {"clientRequestId":"00000000-0000-4000-8000-000000007302","sourceUrl":"https://[","scope":"TEACHER"}
                """;
        assertThat(send("POST", "/api/v1/media/external-image-transfers", malformed, "TEACHER").statusCode()).isEqualTo(422);
        assertThat(externalImages.calls()).isEqualTo(1);
    }

    @Test
    /** SYSTEM 元数据可认证读取；非 owner 的内容授权必须经过 Learning 正文裁决。 */
    void systemQuestionImageRequiresAdminAndDomainAuthorizedLearnerAccess() throws Exception {
        String request = """
                {"clientRequestId":"00000000-0000-4000-8000-000000007303",
                 "sourceUrl":"https://images.fixture.test/system-question.png","scope":"SYSTEM"}
                """;
        assertThat(send("POST", "/api/v1/media/external-image-transfers", request, "TEACHER").statusCode())
                .isEqualTo(403);
        assertThat(sendAs("POST", "/api/v1/media/external-image-transfers", request, "ADMIN", TEACHER).statusCode())
                .isEqualTo(403);
        HttpResponse<String> created = sendAsWithPermissions("POST", "/api/v1/media/external-image-transfers",
                request, "ADMIN", "administration:access", TEACHER);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode asset = mapper.readTree(created.body());
        assertThat(asset.get("scope").asString()).isEqualTo("SYSTEM");
        String mediaId = asset.get("mediaId").asString();

        assertThat(sendAs("GET", "/api/v1/media/" + mediaId, null, "STUDENT", STUDENT).statusCode())
                .isEqualTo(200);
        assertThat(sendAs("GET", "/api/v1/media/" + mediaId, null, "TEACHER",
                "00000000-0000-4000-8000-000000007199").statusCode()).isEqualTo(200);
        assertThat(sendAs("POST", "/api/v1/media/" + mediaId + "/access-grants",
                "{\"variant\":\"ORIGINAL\",\"disposition\":\"INLINE\"}", "STUDENT", STUDENT).statusCode())
                .isEqualTo(404);
        assertThat(sendAsWithPermissions("POST", "/api/v1/media/" + mediaId + "/access-grants",
                "{\"variant\":\"ORIGINAL\",\"disposition\":\"INLINE\"}", "TEACHER",
                "teaching:access", "00000000-0000-4000-8000-000000007199").statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT scope FROM media_asset WHERE media_id=?", String.class, mediaId))
                .isEqualTo("SYSTEM");
    }

    @Test
    /** Given 最小视频，When 处理并授权 HLS，Then 清单/切片就绪、URL 短期签发且临时对象到期清理。 */
    void videoProducesAuthorizedHlsAndCleansTemporaryManifest() throws Exception {
        CompletedTestAsset video = createUploadedAsset("00000000-0000-4000-8000-000000007209",
                "LESSON_VIDEO", "lesson.mp4", "video/mp4", "video123".getBytes(StandardCharsets.UTF_8));
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(video.mediaId()) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        coordinator.process(parser.parse(uploaded.payloadJson()));

        JsonNode ready = mapper.readTree(send("GET", "/api/v1/media/" + video.mediaId(), null, "TEACHER").body());
        assertThat(ready.get("status").asString()).isEqualTo("READY");
        assertThat(ready.get("processingContractVersion").asText()).isEqualTo("LESSON_VIDEO_V5");
        assertThat(ready.get("variants")).anySatisfy(value ->
                assertThat(value.get("variant").asString()).isEqualTo(HLS_V2));
        assertThat(ready.get("variants")).anySatisfy(value -> {
            assertThat(value.get("variant").asString()).isEqualTo("poster-v1");
            assertThat(value.get("mimeType").asString()).isEqualTo("image/webp");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_variant", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_variant_component", Integer.class)).isEqualTo(2);
        assertThat(ffmpeg.lastScratch()).isNotNull();
        assertThat(Files.exists(ffmpeg.lastScratch())).isFalse();

        JsonNode grant = mapper.readTree(send("POST", "/api/v1/media/" + video.mediaId() + "/access-grants",
                "{\"variant\":\"" + HLS_V2 + "\",\"disposition\":\"INLINE\"}", "TEACHER").body());
        assertThat(sendAs("POST", "/api/v1/media/" + video.mediaId() + "/access-grants",
                "{\"variant\":\"" + HLS_V2 + "\",\"disposition\":\"INLINE\"}", "STUDENT", STUDENT).statusCode())
                .isEqualTo(404);
        String authorizedPlaylist = storage.readSigned(grant.get("url").asString());
        assertThat(authorizedPlaylist).contains("#EXTM3U", "https://oss.test/read/")
                .doesNotContain("URI=\"init.mp4\"", "segment-00000.m4s\n");
        String temporaryKey = jdbc.queryForObject("SELECT object_key FROM temporary_access_object", String.class);
        jdbc.update("UPDATE temporary_access_object SET expires_at = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        new MediaReclaimer(repository, storage, events, transactions).reclaim();
        assertThat(storage.hasObject(temporaryKey)).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM temporary_access_object", Boolean.class))
                .isTrue();
    }

    @Test
    /** Given READY 视频和短时服务令牌；Then 仅完整匹配的首次调用成功，伪造/过期/错绑定/重放全部拒绝。 */
    void internalPlaybackAuthorizationBindsAllClaimsRejectsReplayAndAuditsVisitor() throws Exception {
        CompletedTestAsset video = createUploadedAsset("00000000-0000-4000-8000-000000007220",
                "LESSON_VIDEO", "authorized.mp4", "video/mp4", "authvid".getBytes(StandardCharsets.UTF_8));
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(video.mediaId()) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        coordinator.process(parser.parse(uploaded.payloadJson()));
        String body = internalPlaybackBody(STUDENT, "LESSON_VIDEO", HLS_V2);
        Instant now = Instant.now();
        String token = serviceToken(UUID.randomUUID().toString(), STUDENT, video.mediaId(),
                "LESSON_VIDEO", HLS_V2, "edu-platform-media-service-test", now, now.plusSeconds(30));

        HttpResponse<String> granted = sendInternal(token, video.mediaId(), body);
        assertThat(granted.statusCode()).isEqualTo(201);
        JsonNode serviceGrant = mapper.readTree(granted.body());
        assertThat(storage.readSigned(serviceGrant.get("url").asString())).contains("#EXTM3U", "https://oss.test/read/");
        assertThat(granted.body()).doesNotContain("objectKey", "bucket");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM access_grant_audit", String.class)).isEqualTo(STUDENT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_service_authorization", Integer.class))
                .isEqualTo(1);

        String legacyBody = internalPlaybackBody(STUDENT, "LESSON_VIDEO", "hls-v1");
        String legacyToken = serviceToken(UUID.randomUUID().toString(), STUDENT, video.mediaId(),
                "LESSON_VIDEO", "hls-v1", "edu-platform-media-service-test", now, now.plusSeconds(30));
        // 该测试资产只生成 v2；404 而非 403 证明旧变体仍可通过服务授权并进入变体查找。
        assertThat(sendInternal(legacyToken, video.mediaId(), legacyBody).statusCode()).isEqualTo(404);

        assertThat(sendInternal(token, video.mediaId(), body).statusCode()).isEqualTo(403);
        assertThat(sendInternal(token.substring(0, token.length() - 1) + "x", video.mediaId(), body).statusCode())
                .isEqualTo(403);
        assertThat(sendInternal(serviceToken(UUID.randomUUID().toString(), STUDENT, video.mediaId(),
                "LESSON_VIDEO", HLS_V2, "wrong-audience", now, now.plusSeconds(30)),
                video.mediaId(), body).statusCode()).isEqualTo(403);
        assertThat(sendInternal(serviceToken(UUID.randomUUID().toString(), STUDENT, video.mediaId(),
                "LESSON_VIDEO", HLS_V2, "edu-platform-media-service-test",
                now.minusSeconds(60), now.minusSeconds(30)), video.mediaId(), body).statusCode()).isEqualTo(403);
        assertThat(sendInternal(serviceToken(UUID.randomUUID().toString(), STUDENT,
                "00000000-0000-4000-8000-000000007999", "LESSON_VIDEO", HLS_V2,
                "edu-platform-media-service-test", now, now.plusSeconds(30)),
                video.mediaId(), body).statusCode()).isEqualTo(403);
        assertThat(sendInternal(serviceToken(UUID.randomUUID().toString(), STUDENT, video.mediaId(),
                "LESSON_VIDEO", HLS_V2, "edu-platform-media-service-test", now, now.plusSeconds(30)),
                video.mediaId(), internalPlaybackBody(TEACHER, "LESSON_VIDEO", HLS_V2)).statusCode())
                .isEqualTo(403);
        assertThat(sendInternal(serviceToken(UUID.randomUUID().toString(), STUDENT, video.mediaId(),
                "COURSE_COVER", HLS_V2, "edu-platform-media-service-test", now, now.plusSeconds(30)),
                video.mediaId(), internalPlaybackBody(STUDENT, "COURSE_COVER", HLS_V2)).statusCode())
                .isEqualTo(403);
        assertThat(sendInternal(serviceToken(UUID.randomUUID().toString(), STUDENT, video.mediaId(),
                "LESSON_VIDEO", "ORIGINAL", "edu-platform-media-service-test", now, now.plusSeconds(30)),
                video.mediaId(), internalPlaybackBody(STUDENT, "LESSON_VIDEO", "ORIGINAL")).statusCode())
                .isEqualTo(403);
        assertThat(sendInternal(null, video.mediaId(), body).statusCode()).isEqualTo(403);

        CompletedTestAsset pending = createUploadedAsset("00000000-0000-4000-8000-000000007221",
                "LESSON_VIDEO", "pending.mp4", "video/mp4", "pending".getBytes(StandardCharsets.UTF_8));
        String pendingToken = serviceToken(UUID.randomUUID().toString(), STUDENT, pending.mediaId(),
                "LESSON_VIDEO", HLS_V2, "edu-platform-media-service-test", now, now.plusSeconds(30));
        assertThat(sendInternal(pendingToken, pending.mediaId(), body).statusCode()).isEqualTo(409);
    }

    @Test
    /** Given READY 课程封面；When 课程域以精确服务令牌申请 ORIGINAL；Then 返回短期原件且视频访问类型不能复用。 */
    void internalCourseCoverDisplayRequiresExactPurposeAccessTypeAndOriginalVariant() throws Exception {
        CompletedTestAsset cover = createUploadedAsset("00000000-0000-4000-8000-000000007222",
                "COURSE_COVER", "course-cover.png", "image/png", "cover-image".getBytes(StandardCharsets.UTF_8));
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(cover.mediaId()) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        coordinator.process(parser.parse(uploaded.payloadJson()));
        String body = internalAccessBody(TEACHER, "COURSE_COVER", "COURSE_COVER_DISPLAY", "ORIGINAL");
        Instant now = Instant.now();
        String token = serviceTokenForAccess(UUID.randomUUID().toString(), TEACHER, cover.mediaId(),
                "COURSE_COVER", "COURSE_COVER_DISPLAY", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30));

        HttpResponse<String> granted = sendInternal(token, cover.mediaId(), body);

        assertThat(granted.statusCode()).isEqualTo(201);
        JsonNode grant = mapper.readTree(granted.body());
        assertThat(storage.readSigned(grant.get("url").asString())).isEqualTo("cover-image");
        assertThat(granted.body()).doesNotContain("objectKey", "bucket");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM access_grant_audit", String.class)).isEqualTo(TEACHER);
        String playbackToken = serviceToken(UUID.randomUUID().toString(), TEACHER, cover.mediaId(),
                "COURSE_COVER", "ORIGINAL", "edu-platform-media-service-test", now, now.plusSeconds(30));
        assertThat(sendInternal(playbackToken, cover.mediaId(), body).statusCode()).isEqualTo(403);
    }

    @Test
    /** 课程正文图只接受精确 COURSE_ARTICLE_IMAGE/ORIGINAL/INLINE，并继续复核 READY 与一次性 jti。 */
    void internalCourseArticleImageRequiresExactAuthorizationAndReadyAsset() throws Exception {
        CompletedTestAsset image = createUploadedAsset("00000000-0000-4000-8000-000000007223",
                "ARTICLE_IMAGE", "article-image.png", "image/png",
                "article-image".getBytes(StandardCharsets.UTF_8));
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(image.mediaId()) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        coordinator.process(parser.parse(uploaded.payloadJson()));
        String body = internalAccessBody(TEACHER, "ARTICLE_IMAGE", "COURSE_ARTICLE_IMAGE", "ORIGINAL");
        Instant now = Instant.now();
        String token = serviceTokenForAccess(UUID.randomUUID().toString(), TEACHER, image.mediaId(),
                "ARTICLE_IMAGE", "COURSE_ARTICLE_IMAGE", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30));

        HttpResponse<String> granted = sendInternal(token, image.mediaId(), body);

        assertThat(granted.statusCode()).withFailMessage(granted.body()).isEqualTo(201);
        assertThat(storage.readSigned(mapper.readTree(granted.body()).get("url").asString()))
                .isEqualTo("article-image");
        assertThat(granted.body()).doesNotContain("objectKey", "bucket", "token");
        assertThat(sendInternal(token, image.mediaId(), body).statusCode()).isEqualTo(403);

        String wrongAction = serviceTokenForAccess(UUID.randomUUID().toString(), TEACHER, image.mediaId(),
                "ARTICLE_IMAGE", "COURSE_RESOURCE_PREVIEW", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30));
        assertThat(sendInternal(wrongAction, image.mediaId(),
                internalAccessBody(TEACHER, "ARTICLE_IMAGE", "COURSE_RESOURCE_PREVIEW", "ORIGINAL"))
                .statusCode()).isEqualTo(403);
        String wrongVariant = serviceTokenForAccess(UUID.randomUUID().toString(), TEACHER, image.mediaId(),
                "ARTICLE_IMAGE", "COURSE_ARTICLE_IMAGE", "poster-v1", "edu-platform-media-service-test",
                now, now.plusSeconds(30));
        assertThat(sendInternal(wrongVariant, image.mediaId(),
                internalAccessBody(TEACHER, "ARTICLE_IMAGE", "COURSE_ARTICLE_IMAGE", "poster-v1"))
                .statusCode()).isEqualTo(403);

        CompletedTestAsset pending = createUploadedAsset("00000000-0000-4000-8000-000000007224",
                "ARTICLE_IMAGE", "pending-article.png", "image/png",
                "pending-article".getBytes(StandardCharsets.UTF_8));
        String pendingToken = serviceTokenForAccess(UUID.randomUUID().toString(), TEACHER, pending.mediaId(),
                "ARTICLE_IMAGE", "COURSE_ARTICLE_IMAGE", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30));
        assertThat(sendInternal(pendingToken, pending.mediaId(),
                internalAccessBody(TEACHER, "ARTICLE_IMAGE", "COURSE_ARTICLE_IMAGE", "ORIGINAL"))
                .statusCode()).isEqualTo(409);
    }

    @Test
    /** 学习域签发的题目媒体令牌只能访问精确 ORIGINAL，并由 jti 唯一消费；课程域 issuer 不能越权代签。 */
    void internalAssessmentMediaAuthorizationRequiresLearningIssuerAndRejectsReplay() throws Exception {
        CompletedTestAsset image = createUploadedAsset("00000000-0000-4000-8000-000000007304",
                "QUESTION_IMAGE", "private-assessment.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1});
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(image.mediaId()) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        coordinator.process(parser.parse(uploaded.payloadJson()));
        String mediaId = image.mediaId();
        String body = internalAccessBody(STUDENT, "QUESTION_IMAGE", "ASSESSMENT_QUESTION", "ORIGINAL");
        Instant now = Instant.now();
        String token = serviceTokenForAccess(UUID.randomUUID().toString(), STUDENT, mediaId,
                "QUESTION_IMAGE", "ASSESSMENT_QUESTION", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30), "edu-platform-learning-service-test", "INLINE");
        SecretKey diagnosticKey = new SecretKeySpec(SERVICE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoded = NimbusJwtDecoder.withSecretKey(diagnosticKey).build().decode(token);
        assertThat(mediaProperties.serviceAuthorization().assessmentIssuer())
                .isEqualTo(decoded.getClaimAsString("iss"));
        assertThat(decoded.getClaimAsString("accessType")).isEqualTo("ASSESSMENT_QUESTION");

        HttpResponse<String> granted = sendInternal(token, mediaId, body);
        assertThat(granted.statusCode()).withFailMessage(granted.body()).isEqualTo(201);
        assertThat(granted.body()).doesNotContain("objectKey", "bucket");
        assertThat(sendInternal(token, mediaId, body).statusCode()).isEqualTo(403);

        String courseIssuerToken = serviceTokenForAccess(UUID.randomUUID().toString(), STUDENT, mediaId,
                "QUESTION_IMAGE", "ASSESSMENT_QUESTION", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30));
        assertThat(sendInternal(courseIssuerToken, mediaId, body).statusCode()).isEqualTo(403);
        String wrongDisposition = serviceTokenForAccess(UUID.randomUUID().toString(), STUDENT, mediaId,
                "QUESTION_IMAGE", "ASSESSMENT_QUESTION", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30), "edu-platform-learning-service-test", "ATTACHMENT");
        assertThat(sendInternal(wrongDisposition, mediaId, body).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_service_authorization", Integer.class))
                .isEqualTo(1);

        String publicContent = internalAccessBody(STUDENT, "QUESTION_IMAGE", "SYSTEM_QUESTION_CONTENT", "ORIGINAL");
        String publicToken = serviceTokenForAccess(UUID.randomUUID().toString(), STUDENT, mediaId,
                "QUESTION_IMAGE", "SYSTEM_QUESTION_CONTENT", "ORIGINAL", "edu-platform-media-service-test",
                now, now.plusSeconds(30), "edu-platform-learning-service-test", "INLINE");
        assertThat(sendInternal(publicToken, mediaId, publicContent).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_service_authorization", Integer.class))
                .isEqualTo(2);
    }

    @Test
    /** Given Worker 首次失败，When 同事件重试后成功及另一消息耗尽，Then 状态可重试且 Recoverer 拒绝进 DLQ。 */
    void videoFailureRetriesAndExhaustionCreatesFailedEventForDlq() throws Exception {
        CompletedTestAsset retry = createUploadedAsset("00000000-0000-4000-8000-000000007210",
                "LESSON_VIDEO", "retry.mp4", "video/mp4", "retry123".getBytes(StandardCharsets.UTF_8));
        MediaRepository.OutboxEvent retryEvent = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(retry.mediaId()) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        ffmpeg.failNext(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.process(parser.parse(retryEvent.payloadJson())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(ffmpeg.lastScratch())).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM processing_job WHERE media_id = ?", String.class,
                retry.mediaId())).isEqualTo("FAILED");
        coordinator.process(parser.parse(retryEvent.payloadJson()));
        assertThat(jdbc.queryForObject("SELECT status FROM media_asset WHERE media_id = ?", String.class,
                retry.mediaId())).isEqualTo("READY");

        CompletedTestAsset dead = createUploadedAsset("00000000-0000-4000-8000-000000007211",
                "LESSON_VIDEO", "dead.mp4", "video/mp4", "dead1234".getBytes(StandardCharsets.UTF_8));
        MediaRepository.OutboxEvent deadEvent = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(dead.mediaId()) && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> recoverer.recover(
                        new Message(deadEvent.payloadJson().getBytes(StandardCharsets.UTF_8)),
                        new IllegalStateException("exhausted")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM media_asset WHERE media_id = ?", String.class,
                dead.mediaId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'MediaFailed'",
                Integer.class, dead.mediaId())).isEqualTo(1);
    }

    @Test
    /** Given 封面声明，When 直传/完成/处理/重放，Then 只生成一个 READY 资产且可签发私有读授权。 */
    void singleUploadIsIdempotentVerifiedProcessedAndPrivatelyGranted() throws Exception {
        byte[] bytes = "minimal-cover".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        String createBody = """
                {"clientRequestId":"00000000-0000-4000-8000-000000007201","purpose":"COURSE_COVER",
                 "fileName":"cover.png","size":%d,"mimeType":"image/png","sha256":"%s"}
                """.formatted(bytes.length, sha);
        HttpResponse<String> created = send("POST", "/api/v1/media/uploads", createBody, "TEACHER");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode session = mapper.readTree(created.body());
        assertThat(session.get("mode").asString()).isEqualTo("SINGLE_PUT");
        assertThat(created.body()).doesNotContain("objectKey", "bucket", "accessKey");
        String signedUrl = session.get("uploadGrant").get("url").asString();
        storage.upload(signedUrl, bytes, "image/png", sha);

        HttpResponse<String> replay = send("POST", "/api/v1/media/uploads", createBody, "TEACHER");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(replay.body()).get("uploadId").asString())
                .isEqualTo(session.get("uploadId").asString());

        HttpResponse<String> accepted = send("POST", "/api/v1/media/uploads/"
                + session.get("uploadId").asString() + "/complete", "{\"sha256\":\"" + sha + "\",\"parts\":[]}", "TEACHER");
        assertThat(accepted.statusCode()).isEqualTo(202);
        JsonNode asset = mapper.readTree(accepted.body());
        assertThat(asset.get("status").asString()).isEqualTo("PROCESSING");
        assertThat(accepted.body()).doesNotContain("objectKey", "url");

        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(java.time.Instant.now(), 10).get(0);
        coordinator.process(parser.parse(uploaded.payloadJson()));
        coordinator.process(parser.parse(uploaded.payloadJson()));

        HttpResponse<String> ready = send("GET", "/api/v1/media/" + asset.get("mediaId").asString(), null, "TEACHER");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(ready.body()).get("status").asString()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Integer.class,
                uploaded.eventId())).isEqualTo(1);

        HttpResponse<String> grant = send("POST", "/api/v1/media/" + asset.get("mediaId").asString()
                + "/access-grants", "{\"variant\":\"ORIGINAL\",\"disposition\":\"INLINE\"}", "TEACHER");
        assertThat(grant.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(grant.body()).get("url").asString()).startsWith("https://oss.test/read/");

        HttpResponse<String> completeReplay = send("POST", "/api/v1/media/uploads/"
                + session.get("uploadId").asString() + "/complete", "{\"sha256\":\"" + sha + "\",\"parts\":[]}", "TEACHER");
        assertThat(completeReplay.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_asset", Integer.class)).isEqualTo(1);
    }

    @Test
    /** Given 视频大于测试阈值，When 分片授权与有序完成，Then 分片 ETag 入库且任务进入 PENDING。 */
    void multipartAuthorizationChecksPartsAndCompletesExactObject() throws Exception {
        byte[] bytes = "0123456789ab".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        String body = """
                {"clientRequestId":"00000000-0000-4000-8000-000000007202","purpose":"LESSON_VIDEO",
                 "fileName":"lesson.mp4","size":12,"mimeType":"video/mp4","sha256":"%s"}
                """.formatted(sha);
        JsonNode session = mapper.readTree(send("POST", "/api/v1/media/uploads", body, "TEACHER").body());
        assertThat(session.get("mode").asString()).isEqualTo("MULTIPART");
        String uploadId = session.get("uploadId").asString();
        StringBuilder completed = new StringBuilder("[");
        int[] sizes = {5, 5, 2};
        int offset = 0;
        for (int index = 0; index < sizes.length; index++) {
            int part = index + 1;
            JsonNode grant = mapper.readTree(send("POST", "/api/v1/media/uploads/" + uploadId + "/parts/authorize",
                    "{\"partNumber\":" + part + ",\"contentLength\":" + sizes[index] + "}", "TEACHER").body());
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, offset, offset + sizes[index]);
            offset += sizes[index];
            String etag = storage.upload(grant.get("url").asString(), chunk, null, null);
            if (index > 0) completed.append(',');
            completed.append("{\"partNumber\":").append(part).append(",\"etag\":\"").append(etag).append("\"}");
        }
        completed.append(']');
        HttpResponse<String> response = send("POST", "/api/v1/media/uploads/" + uploadId + "/complete",
                "{\"sha256\":\"" + sha + "\",\"parts\":" + completed + "}", "TEACHER");
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(mapper.readTree(response.body()).get("processingStatus").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_part WHERE completed_at IS NOT NULL", Integer.class))
                .isEqualTo(3);
    }

    @Test
    /** 验证学生角色被拒绝、同幂等键改变文件声明冲突，且错误信封完整。 */
    void rejectsRolePurposeAndIdempotencyConflictsWithUnifiedEnvelope() throws Exception {
        byte[] bytes = {1};
        String sha = sha256(bytes);
        String body = "{\"clientRequestId\":\"00000000-0000-4000-8000-000000007203\","
                + "\"purpose\":\"COURSE_COVER\",\"fileName\":\"x.png\",\"size\":1,"
                + "\"mimeType\":\"image/png\",\"sha256\":\"" + sha + "\"}";
        HttpResponse<String> forbidden = send("POST", "/api/v1/media/uploads", body, "STUDENT");
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(forbidden.body()).get("code").asString()).isEqualTo("MEDIA.PURPOSE_FORBIDDEN");
        assertThat(send("POST", "/api/v1/media/uploads", body, "TEACHER").statusCode()).isEqualTo(201);
        String conflictBody = body.replace("x.png", "other.png");
        HttpResponse<String> conflict = send("POST", "/api/v1/media/uploads", conflictBody, "TEACHER");
        assertThat(conflict.statusCode()).isEqualTo(409);
        JsonNode error = mapper.readTree(conflict.body());
        assertThat(error.get("code").asString()).isEqualTo("MEDIA.IDEMPOTENCY_CONFLICT");
        assertThat(error.has("traceId") && error.has("timestamp") && error.has("path") && error.has("fieldErrors")).isTrue();
    }

    @Test
    /** Given READY 封面，When 重放绑定后解除，Then 台账不重复且最后引用解除重启保留期。 */
    void referenceLedgerIsIdempotentAndControlsUnboundRetention() throws Exception {
        CompletedTestAsset completed = createReadyCover("00000000-0000-4000-8000-000000007204", "reference-cover");
        String referenceId = "00000000-0000-4000-8000-000000007304";
        MediaRepository.OutboxEvent bound = events.create("MediaReferenceBound", completed.mediaId(),
                "media.reference.bound.v1", "trace-t07-reference", Map.of("mediaId", completed.mediaId(),
                        "ownerService", "course-service", "referenceType", "COURSE", "referenceId", referenceId),
                Instant.now());
        references.onMessage(bound.payloadJson());
        references.onMessage(bound.payloadJson());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_reference WHERE media_id = ? AND status = 'BOUND'",
                Integer.class, completed.mediaId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Integer.class,
                bound.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unbound_expires_at IS NULL FROM media_asset WHERE media_id = ?",
                Boolean.class, completed.mediaId())).isTrue();

        MediaRepository.OutboxEvent released = events.create("MediaReferenceReleased", completed.mediaId(),
                "media.reference.released.v1", "trace-t07-reference", Map.of("mediaId", completed.mediaId(),
                        "ownerService", "course-service", "referenceType", "COURSE", "referenceId", referenceId),
                Instant.now());
        references.onMessage(released.payloadJson());
        assertThat(jdbc.queryForObject("SELECT status FROM media_reference WHERE media_id = ?", String.class,
                completed.mediaId())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT unbound_expires_at IS NOT NULL FROM media_asset WHERE media_id = ?",
                Boolean.class, completed.mediaId())).isTrue();
    }

    @Test
    /** Given 过期会话和过保留期的无引用资产，When 回收两次，Then 精确对象删除且 MediaDeleted 只有一条。 */
    void reclaimerExpiresSessionsAndDeletesOnlyUnboundAssetsIdempotently() throws Exception {
        JsonNode abandoned = mapper.readTree(send("POST", "/api/v1/media/uploads", """
                {"clientRequestId":"00000000-0000-4000-8000-000000007205","purpose":"COURSE_COVER",
                 "fileName":"abandoned.png","size":1,"mimeType":"image/png",
                 "sha256":"%s"}
                """.formatted(sha256(new byte[] {1})), "TEACHER").body());
        jdbc.update("UPDATE upload_session SET expires_at = ? WHERE upload_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), abandoned.get("uploadId").asString());

        CompletedTestAsset completed = createReadyCover("00000000-0000-4000-8000-000000007206", "reclaim-cover");
        String objectKey = jdbc.queryForObject("SELECT object_key FROM media_asset WHERE media_id = ?", String.class,
                completed.mediaId());
        jdbc.update("UPDATE media_asset SET unbound_expires_at = ? WHERE media_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), completed.mediaId());
        MediaReclaimer reclaimer = new MediaReclaimer(repository, storage, events, transactions);
        reclaimer.reclaim();
        reclaimer.reclaim();

        assertThat(jdbc.queryForObject("SELECT status FROM upload_session WHERE upload_id = ?", String.class,
                abandoned.get("uploadId").asString())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM media_asset WHERE media_id = ?", String.class,
                completed.mediaId())).isEqualTo("DELETED");
        assertThat(storage.hasObject(objectKey)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'MediaDeleted'",
                Integer.class, completed.mediaId())).isEqualTo(1);
    }

    private CompletedTestAsset createReadyCover(String clientRequestId, String content) throws Exception {
        CompletedTestAsset completed = createUploadedAsset(clientRequestId, "COURSE_COVER",
                "cover.png", "image/png", content.getBytes(StandardCharsets.UTF_8));
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now(), 20).stream()
                .filter(event -> event.aggregateId().equals(completed.mediaId())
                        && event.eventType().equals("MediaUploaded"))
                .findFirst().orElseThrow();
        coordinator.process(parser.parse(uploaded.payloadJson()));
        return completed;
    }

    private CompletedTestAsset createUploadedAsset(String clientRequestId, String purpose,
            String fileName, String mimeType, byte[] bytes) throws Exception {
        String sha = sha256(bytes);
        JsonNode session = mapper.readTree(send("POST", "/api/v1/media/uploads", """
                {"clientRequestId":"%s","purpose":"%s","fileName":"%s",
                 "size":%d,"mimeType":"%s","sha256":"%s"}
                """.formatted(clientRequestId, purpose, fileName, bytes.length, mimeType, sha), "TEACHER").body());
        storage.upload(session.get("uploadGrant").get("url").asString(), bytes, mimeType, sha);
        JsonNode asset = mapper.readTree(send("POST", "/api/v1/media/uploads/" + session.get("uploadId").asString()
                + "/complete", "{\"sha256\":\"" + sha + "\",\"parts\":[]}", "TEACHER").body());
        return new CompletedTestAsset(session.get("uploadId").asString(), asset.get("mediaId").asString());
    }

    private HttpResponse<String> send(String method, String path, String body, String role) throws Exception {
        return sendAs(method, path, body, role, TEACHER);
    }

    private HttpResponse<String> sendAs(String method, String path, String body, String role, String userId)
            throws Exception {
        return sendAsWithPermissions(method, path, body, role, null, userId);
    }

    private HttpResponse<String> sendAsWithPermissions(String method, String path, String body, String role,
            String permissions, String userId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-User-Id", userId).header("X-User-Roles", role).header("X-Trace-Id", "trace-t08a-test");
        if (permissions != null) request.header("X-User-Permissions", permissions);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendInternal(String token, String mediaId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/internal/v1/media/" + mediaId + "/access-grants"))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String internalPlaybackBody(String visitorId, String purpose, String variant) {
        return internalAccessBody(visitorId, purpose, "COURSE_PLAYBACK", variant);
    }

    private static String internalAccessBody(String visitorId, String purpose, String accessType, String variant) {
        return """
                {"visitorId":"%s","purpose":"%s","accessType":"%s",
                 "variant":"%s","disposition":"INLINE"}
                """.formatted(visitorId, purpose, accessType, variant);
    }

    private static String serviceToken(String tokenId, String visitorId, String mediaId, String purpose,
            String variant, String audience, Instant issuedAt, Instant expiresAt) {
        return serviceTokenForAccess(tokenId, visitorId, mediaId, purpose, "COURSE_PLAYBACK",
                variant, audience, issuedAt, expiresAt);
    }

    private static String serviceTokenForAccess(String tokenId, String visitorId, String mediaId, String purpose,
            String accessType, String variant, String audience, Instant issuedAt, Instant expiresAt) {
        return serviceTokenForAccess(tokenId, visitorId, mediaId, purpose, accessType, variant, audience,
                issuedAt, expiresAt, "edu-platform-course-service-test", "INLINE");
    }

    private static String serviceTokenForAccess(String tokenId, String visitorId, String mediaId, String purpose,
            String accessType, String variant, String audience, Instant issuedAt, Instant expiresAt,
            String issuer, String disposition) {
        SecretKey key = new SecretKeySpec(SERVICE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(visitorId)
                .id(tokenId)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("mediaId", mediaId)
                .claim("purpose", purpose)
                .claim("accessType", accessType)
                .claim("variant", variant)
                .claim("disposition", disposition)
                .claim("traceId", "trace-service-auth-test")
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record CompletedTestAsset(String uploadId, String mediaId) {}
}
