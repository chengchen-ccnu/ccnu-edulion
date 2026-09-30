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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证课程事实适配器的向前兼容读取、响应校验和 HTTP 失败语义。 */
class HttpCourseFactsClientTest {
    private static final String COURSE_ID = "00000000-0000-4000-8000-000000000100";
    private static final String TEACHER_ID = "00000000-0000-4000-8000-000000000101";
    private static final String LESSON_ID = "00000000-0000-4000-8000-000000000102";
    private HttpServer server;
    private HttpCourseFactsClient client;
    private final AtomicInteger publishedRequests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new HttpCourseFactsClient(new LearningProperties(
                new LearningProperties.Media("http://media", Duration.ofSeconds(1), "edu-learning-service",
                        "edu-media-service", Duration.ofSeconds(30), "test-assessment-media-secret-32-bytes-minimum"),
                new LearningProperties.Course("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1),
                        Duration.ZERO),
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), false,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3)));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void realCourseResponseShapeWithExtraFieldsPreservesLearningFacts() {
        var fact = client.requirePublished("ok", "trace-course");
        assertThat(fact.courseId()).isEqualTo(COURSE_ID);
        assertThat(fact.publishedVersion()).isEqualTo("v8");
        assertThat(fact.accessType()).isEqualTo("ENROLLMENT_REQUIRED");
        assertThat(fact.ownerTeacherId()).isEqualTo(TEACHER_ID);
        assertThat(fact.lessonIds()).containsExactly(LESSON_ID);
        assertThat(fact.lessonTypes()).containsEntry(LESSON_ID, "ARTICLE");
    }

    @Test
    void publishedAuthorizationNeverUsesStaleCache() {
        client = new HttpCourseFactsClient(new LearningProperties(
                new LearningProperties.Media("http://media", Duration.ofSeconds(1), "edu-learning-service",
                        "edu-media-service", Duration.ofSeconds(30), "test-assessment-media-secret-32-bytes-minimum"),
                new LearningProperties.Course("http://127.0.0.1:" + server.getAddress().getPort(),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), false,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3)));

        client.requirePublished("ok", "trace-cache-1");
        client.requirePublished("ok", "trace-cache-2");

        assertThat(publishedRequests).hasValue(2);
    }

    @Test
    void course404AndDependencyFailuresKeepTheirExistingSemantics() {
        assertThatThrownBy(() -> client.requirePublished("missing", "trace-missing"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("LEARNING.NOT_FOUND");
                });
        assertThatThrownBy(() -> client.requirePublished("forbidden", "trace-forbidden"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(503));
        assertThatThrownBy(() -> client.requirePublished("failed", "trace-failed"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(503));
        assertThatThrownBy(() -> client.requireOwnedPublished("forbidden", TEACHER_ID, "trace-owner-forbidden"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(404);
                    assertThat(error.code()).isEqualTo("LEARNING.NOT_FOUND");
                });
    }

    @Test
    /** 教师本人草稿无发布版本为合法空态；Course 失败仍 503，其他 owner 仍 404。 */
    void ownerDraftIsEmptyButFailureAndForeignOwnerAreHidden() {
        assertThat(client.ownedPublishedOrEmpty("draft", TEACHER_ID, "trace-draft")).isNull();
        assertThatThrownBy(() -> client.ownedPublishedOrEmpty("failed", TEACHER_ID, "trace-failed"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(503));
        assertThatThrownBy(() -> client.ownedPublishedOrEmpty("forbidden", TEACHER_ID, "trace-foreign"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(404));
    }

    @Test
    void missingRequiredFieldsAndWrongFieldTypesRemain503() {
        assertThatThrownBy(() -> client.requirePublished("invalid-missing", "trace-invalid-missing"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(503));
        assertThatThrownBy(() -> client.requirePublished("invalid-type", "trace-invalid-type"))
                .isInstanceOfSatisfying(QuestionException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("LEARNING.COURSE_DEPENDENCY_UNAVAILABLE");
                });
    }

    @Test
    void courseTimeoutRemains503() {
        client = newClient(Duration.ofMillis(50), Duration.ZERO);
        assertThatThrownBy(() -> client.requirePublished("timeout", "trace-timeout"))
                .isInstanceOfSatisfying(QuestionException.class, error -> assertThat(error.status().value()).isEqualTo(503));
    }

    /** 暂停标量不能因 Jackson 强制转换变为 ACTIVE；空分页、区间与布尔缺失均失败关闭。 */
    @Test
    void availabilityRequiresExplicitBooleanAndCompleteIntervals() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var response = new java.util.concurrent.atomic.AtomicReference<>("{}");
        server.createContext("/internal/v1/courses/" + COURSE_ID + "/availability", exchange -> write(exchange, response.get()));
        var value = mapper.createObjectNode();
        value.put("courseId", COURSE_ID).put("ownerTeacherId", TEACHER_ID).put("availabilityRevision", 0)
                .put("supportEmail", "support@example.invalid").put("hasMore", false).putArray("intervals");
        response.set(value.toString());
        assertUnavailable(() -> client.availability(COURSE_ID, 0, "t161-missing-flag"));
        value.put("paused", "false"); response.set(value.toString());
        assertUnavailable(() -> client.availability(COURSE_ID, 0, "t161-wrong-flag"));
        value.put("paused", false); response.set(value.toString());
        assertThat(client.availability(COURSE_ID, 0, "t161-active").paused()).isFalse();
        value.put("hasMore", true); response.set(value.toString());
        assertUnavailable(() -> client.availability(COURSE_ID, 0, "t161-empty-page"));
        value.put("hasMore", false).put("paused", true).put("availabilityRevision", 1);
        var interval=value.withArray("intervals").addObject();
        interval.put("sequence", 1).put("suspensionId", "00000000-0000-4000-8000-000000016199")
                .put("suspendedAt", "2026-09-30T00:00:00Z");
        response.set(value.toString());
        assertUnavailable(() -> client.availability(COURSE_ID, 0, "t161-missing-end"));
        interval.putNull("resumedAt"); response.set(value.toString());
        assertThat(client.availability(COURSE_ID, 0, "t161-paused").paused()).isTrue();
    }

    private void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(QuestionException.class,
                error -> assertThat(error.status().value()).isEqualTo(503));
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/draft")) {
            if (path.contains("/teacher/")) { exchange.sendResponseHeaders(200, -1); exchange.close(); }
            else { exchange.sendResponseHeaders(404, -1); exchange.close(); }
            return;
        }
        if (path.endsWith("/missing")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
        if (path.endsWith("/forbidden")) { exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
        if (path.endsWith("/failed")) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
        if (path.endsWith("/timeout")) {
            try { Thread.sleep(300); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
            return;
        }
        if (path.endsWith("/invalid-missing")) {
            write(exchange, "{\"id\":\"" + COURSE_ID + "\",\"publishedVersion\":\"v8\","
                    + "\"accessType\":\"ENROLLMENT_REQUIRED\",\"teacher\":{},\"chapters\":[]}");
            return;
        }
        if (path.endsWith("/invalid-type")) {
            write(exchange, "{\"id\":{},\"publishedVersion\":\"v8\","
                    + "\"accessType\":\"ENROLLMENT_REQUIRED\",\"teacher\":{\"id\":\"" + TEACHER_ID
                    + "\"},\"chapters\":[]}");
            return;
        }
        publishedRequests.incrementAndGet();
        write(exchange, "{\"id\":\"" + COURSE_ID + "\",\"title\":\"代数入门\","
                + "\"summary\":\"公开课程摘要\",\"description\":\"课程说明\","
                + "\"coverMediaId\":null,\"coverUrl\":null,"
                + "\"teacher\":{\"id\":\"" + TEACHER_ID + "\",\"displayName\":\"教师测试\"},"
                + "\"category\":{\"id\":\"00000000-0000-4000-8000-000000000103\",\"name\":\"数学\"},"
                + "\"level\":\"BEGINNER\",\"accessType\":\"ENROLLMENT_REQUIRED\",\"publishedVersion\":\"v8\",\"publicationRevision\":8,"
                + "\"chapters\":[{\"id\":\"00000000-0000-4000-8000-000000000104\",\"title\":\"第一章\","
                + "\"sequence\":1,\"lessons\":[{\"id\":\"" + LESSON_ID + "\",\"title\":\"第一课时\","
                + "\"type\":\"ARTICLE\",\"sequence\":1,\"previewable\":false,\"contentRevisionId\":\"" + LESSON_ID + "\","
                + "\"articleMarkdown\":\"# 内容\",\"resources\":[]}],\"chapterExtra\":true}],"
                + "\"createdAt\":\"2026-09-06T00:00:00Z\",\"updatedAt\":\"2026-09-06T00:00:00Z\"}");
    }

    private HttpCourseFactsClient newClient(Duration timeout, Duration cacheTtl) {
        return new HttpCourseFactsClient(new LearningProperties(
                new LearningProperties.Media("http://media", Duration.ofSeconds(1), "edu-learning-service",
                        "edu-media-service", Duration.ofSeconds(30), "test-assessment-media-secret-32-bytes-minimum"),
                new LearningProperties.Course("http://127.0.0.1:" + server.getAddress().getPort(), timeout, cacheTtl),
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), false,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 3)));
    }

    private static void write(HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
