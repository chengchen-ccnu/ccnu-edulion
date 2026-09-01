package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * T12F.1.1 真实 owner 验收：经 Gateway 保存临时课程正文，再由 course-service 调用真实 media-service/OSS。
 *
 * <p>测试只在显式门控下连接三个既有开发服务。媒体使用 T12F.1 固定 READY 原件但不修改或删除它；
 * 课程数据使用随机 courseId，并在 finally 中按该 ID 精确逆序清理。JWT 只在内存中使用本机加密环境
 * 注入的密钥生成，不写文件、不输出 Token。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "platform.course.messaging.enabled=false",
        "platform.course.enrollment-messaging.enabled=false",
        "platform.course.seed.enabled=false"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12F11_REAL_ACCEPTANCE", matches = "true")
class T12F11TeacherArticleImageRealAcceptanceTest {
    private static final String DATABASE = "edu_platform_course_dev";
    private static final String OWNER = "00000000-0000-4000-8000-000000000102";
    private static final String OTHER_TEACHER = "00000000-0000-4000-8000-000000000103";
    private static final String CATEGORY = "00000000-0000-4000-8000-00000000f501";
    private static final String ARTICLE_IMAGE = "00000000-0000-4000-8000-00000000f103";
    private static final String ARTICLE_ATTACHMENT = "00000000-0000-4000-8000-00000000f104";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("COURSE_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12F.1.1 real acceptance requires edu_platform_course_dev");
        }
    }

    /** 保存、刷新重读、历史隔离、未引用忽略、错用途拒绝和缺失绑定冲突均走真实服务链路。 */
    @Test
    void savedArticleImagesAreReauthorizedThroughGatewayAndLoadedFromOss() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE id=?", Integer.class, CATEGORY)).isOne();
        String tracePrefix = "t12f11-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String ownerToken = token(OWNER);
        String otherToken = token(OTHER_TEACHER);
        String courseId = null;
        try {
            JsonNode created = request("POST", "/api/v1/teacher/courses", """
                    {"clientRequestId":"%s","title":"[T12F.1.1临时]教师正文图片","summary":"临时验收",
                     "description":"只用于 T12F.1.1 本地验收","coverMediaId":null,"instructorDisplayName":"测试教师",
                     "categoryId":"%s","level":"BEGINNER","accessType":"PUBLIC"}
                    """.formatted(UUID.randomUUID(), CATEGORY), ownerToken, tracePrefix + "-create", 201);
            courseId = created.get("id").asString();
            long revision = created.get("revision").asLong();
            String markdown = "# 已保存正文\n\n![判别式](media:" + ARTICLE_IMAGE + ")";
            String curriculum = curriculum(revision, "已保存正文", markdown);
            JsonNode saved = request("PUT", "/api/v1/teacher/courses/" + courseId + "/curriculum",
                    curriculum, ownerToken, tracePrefix + "-save", 200);
            JsonNode savedLesson = saved.get("chapters").get(0).get("lessons").get(0);
            String publishedLessonId = savedLesson.get("id").asString();

            JsonNode first = articleContent(courseId, publishedLessonId, ownerToken,
                    tracePrefix + "-first", ARTICLE_ATTACHMENT, 200);
            assertMinimalProjection(first, courseId, publishedLessonId, ARTICLE_IMAGE);
            loadAuthorizedImage(first.get("inlineImages").get(0).get("grant"));
            assertGrantExpiresWithoutRenewal(first.get("inlineImages").get(0).get("grant"));

            JsonNode refreshed = articleContent(courseId, publishedLessonId, ownerToken,
                    tracePrefix + "-refresh", ARTICLE_ATTACHMENT, 200);
            assertMinimalProjection(refreshed, courseId, publishedLessonId, ARTICLE_IMAGE);
            loadAuthorizedImage(refreshed.get("inlineImages").get(0).get("grant"));

            assertError(articlePath(courseId, publishedLessonId), otherToken,
                    tracePrefix + "-other-owner", 404, "COURSE.NOT_FOUND");
            assertError(articlePath(UUID.randomUUID().toString(), publishedLessonId), ownerToken,
                    tracePrefix + "-wrong-course", 404, "COURSE.NOT_FOUND");
            assertError(articlePath(courseId, UUID.randomUUID().toString()), ownerToken,
                    tracePrefix + "-wrong-lesson", 404, "COURSE.NOT_FOUND");

            request("PUT", "/api/v1/teacher/courses/" + courseId + "/publication",
                    "{\"status\":\"PUBLISHED\",\"expectedRevision\":" + saved.get("revision").asLong() + "}",
                    ownerToken, tracePrefix + "-publish", 200);
            JsonNode editor = request("GET", "/api/v1/teacher/courses/" + courseId, null,
                    ownerToken, tracePrefix + "-editor", 200);
            JsonNode draft = request("PUT", "/api/v1/teacher/courses/" + courseId + "/curriculum",
                    curriculum(editor.get("revision").asLong(), "当前草稿正文", markdown), ownerToken,
                    tracePrefix + "-new-draft", 200);
            JsonNode currentLesson = draft.get("chapters").get(0).get("lessons").get(0);
            String currentLessonId = currentLesson.get("id").asString();
            assertError(articlePath(courseId, publishedLessonId), ownerToken,
                    tracePrefix + "-historical", 404, "COURSE.NOT_FOUND");

            String attachmentResourceId = resourceId(currentLesson, "ARTICLE_ATTACHMENT");
            jdbc.update("UPDATE lesson_media_resource SET purpose='ARTICLE_IMAGE', position=2 WHERE id=?",
                    attachmentResourceId);
            JsonNode ignoresUnreferenced = articleContent(courseId, currentLessonId, ownerToken,
                    tracePrefix + "-unreferenced", ARTICLE_ATTACHMENT, 200);
            assertMinimalProjection(ignoresUnreferenced, courseId, currentLessonId, ARTICLE_IMAGE);

            jdbc.update("UPDATE lesson SET article_markdown=? WHERE id=?",
                    "![错用途](media:" + ARTICLE_ATTACHMENT + ")", currentLessonId);
            assertError(articlePath(courseId, currentLessonId), ownerToken,
                    tracePrefix + "-wrong-purpose", 404, "COURSE.NOT_FOUND");

            jdbc.update("UPDATE lesson SET article_markdown=? WHERE id=?", markdown, currentLessonId);
            String imageResourceId = resourceId(currentLesson, "ARTICLE_IMAGE");
            jdbc.update("DELETE FROM lesson_media_resource WHERE id=?", imageResourceId);
            assertError(articlePath(courseId, currentLessonId), ownerToken,
                    tracePrefix + "-missing", 409, "COURSE.CONTENT_INCONSISTENT");
        } finally {
            if (courseId != null) cleanupCourse(courseId, tracePrefix);
        }
    }

    private JsonNode articleContent(String courseId, String lessonId, String token,
            String traceId, String arbitraryMediaId, int status) throws Exception {
        return request("GET", articlePath(courseId, lessonId) + "?mediaId=" + arbitraryMediaId,
                null, token, traceId, status);
    }

    private static String articlePath(String courseId, String lessonId) {
        return "/api/v1/teacher/courses/" + courseId + "/lessons/" + lessonId + "/article-content";
    }

    private void assertMinimalProjection(JsonNode body, String courseId, String lessonId, String mediaId) {
        assertThat(body.get("courseId").asString()).isEqualTo(courseId);
        assertThat(body.get("lessonId").asString()).isEqualTo(lessonId);
        assertThat(body.get("inlineImages")).singleElement().satisfies(image ->
                assertThat(image.get("mediaId").asString()).isEqualTo(mediaId));
        assertThat(body.toString()).doesNotContain(ARTICLE_ATTACHMENT, "resourceId", "resources",
                "originalFileName", "objectKey", "bucket", "Bucket", "serviceToken", "token");
    }

    private void loadAuthorizedImage(JsonNode grant) throws Exception {
        HttpResponse<byte[]> response = load(grant);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("image/png");
        assertThat(response.body()).isNotEmpty();
    }

    /** 等待本地验收专用短 TTL 到期并复用同一签名，证明图片地址本身不会自动续签。 */
    private void assertGrantExpiresWithoutRenewal(JsonNode grant) throws Exception {
        long waitMillis = Instant.parse(grant.get("expiresAt").asString()).toEpochMilli()
                - Instant.now().toEpochMilli() + 1_500;
        if (waitMillis <= 0 || waitMillis > 15_000) {
            throw new IllegalStateException("T12F.1.1 acceptance requires a 2s-10s media access grant TTL");
        }
        Thread.sleep(waitMillis);
        assertThat(load(grant).statusCode()).isEqualTo(403);
    }

    private HttpResponse<byte[]> load(JsonNode grant) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(grant.get("url").asString())).GET();
        grant.get("headers").properties().forEach(entry ->
                builder.header(entry.getKey(), entry.getValue().asString()));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void assertError(String path, String token, String traceId, int status, String code) throws Exception {
        JsonNode error = request("GET", path, null, token, traceId, status);
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.toString()).doesNotContain("objectKey", "bucket", "Bucket", "serviceToken", "Bearer");
    }

    private JsonNode request(String method, String path, String body, String token,
            String traceId, int expectedStatus) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gatewayUrl() + path))
                .header("Authorization", "Bearer " + token)
                .header("X-Trace-Id", traceId);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return mapper.readTree(response.body());
    }

    private String curriculum(long revision, String lessonTitle, String markdown) {
        return """
                {"expectedRevision":%d,"chapters":[{"title":"正文","sequence":1,"lessons":[
                  {"title":"%s","type":"ARTICLE","sequence":1,"previewable":false,
                   "articleMarkdown":%s,"resources":[
                     {"mediaId":"%s","purpose":"ARTICLE_IMAGE","sequence":1,"presentation":"INLINE"},
                     {"mediaId":"%s","purpose":"ARTICLE_ATTACHMENT","sequence":1,"presentation":"INLINE"}
                   ]}
                ]}]}
                """.formatted(revision, lessonTitle, jsonString(markdown), ARTICLE_IMAGE, ARTICLE_ATTACHMENT);
    }

    private String jsonString(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize acceptance Markdown", exception);
        }
    }

    private static String resourceId(JsonNode lesson, String purpose) {
        for (JsonNode resource : lesson.get("resources")) {
            if (purpose.equals(resource.get("purpose").asString())) return resource.get("id").asString();
        }
        throw new IllegalStateException("Missing acceptance resource " + purpose);
    }

    /** 按随机 courseId 逆序清理本测试课程及 Outbox；固定分类与 T12F.1 媒体 Seed 均保留。 */
    private void cleanupCourse(String courseId, String tracePrefix) {
        List<String> versionIds = jdbc.queryForList(
                "SELECT id FROM course_version WHERE course_id=?", String.class, courseId);
        for (String versionId : versionIds) {
            List<String> chapterIds = jdbc.queryForList(
                    "SELECT id FROM chapter WHERE course_version_id=?", String.class, versionId);
            for (String chapterId : chapterIds) {
                List<String> lessonIds = jdbc.queryForList(
                        "SELECT id FROM lesson WHERE chapter_id=?", String.class, chapterId);
                for (String lessonId : lessonIds) {
                    jdbc.update("DELETE FROM lesson_media_resource WHERE lesson_id=?", lessonId);
                    jdbc.update("DELETE FROM lesson WHERE id=?", lessonId);
                }
                jdbc.update("DELETE FROM chapter WHERE id=?", chapterId);
            }
        }
        jdbc.update("DELETE FROM course_enrollment_access WHERE course_id=?", courseId);
        jdbc.update("DELETE FROM outbox_event WHERE payload_json LIKE ? OR trace_id LIKE ?",
                "%" + courseId + "%", tracePrefix + "%");
        jdbc.update("UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id=?", courseId);
        for (String versionId : versionIds) jdbc.update("DELETE FROM course_version WHERE id=?", versionId);
        jdbc.update("DELETE FROM course WHERE id=?", courseId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE id=?", Integer.class, courseId)).isZero();
    }

    private static String gatewayUrl() {
        return System.getenv().getOrDefault("T12F11_GATEWAY_URL", "http://127.0.0.1:8080");
    }

    private static String token(String subject) {
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
                .subject(subject).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("roles", List.of("TEACHER"))
                .claim("permissions", List.of("teaching:access"))
                .claim("sessionVersion", 0L)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
