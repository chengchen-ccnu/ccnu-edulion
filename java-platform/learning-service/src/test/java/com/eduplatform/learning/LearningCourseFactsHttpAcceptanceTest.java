package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.EnrollmentRow;
import com.eduplatform.learning.web.LearningDtos.AssessmentCloseStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentSettlementStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentType;
import com.eduplatform.learning.web.LearningDtos.AnswerPublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.EnrollmentStatus;
import com.eduplatform.learning.web.LearningDtos.GradePublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.HomeworkLatePolicy;
import com.eduplatform.learning.web.LearningDtos.ResultPublicationStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 通过真实 Learning HTTP 入口验收 Course 公开响应向前兼容。
 * Course 端只用本机 HTTP 端点复现当前公开详情 JSON 形状；Learning H2 只保存本用例的报名、任务和分配事实。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:learning_course_facts_http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.learning.progress-buffer.enabled=false",
        "platform.learning.import.messaging-enabled=false",
        "platform.learning.assessment-settlement-poll-delay=1h"
})
@ActiveProfiles("test")
class LearningCourseFactsHttpAcceptanceTest {
    private static final String LEARNER_ID = "00000000-0000-4000-8000-000000000201";
    private static final String TEACHER_ID = "00000000-0000-4000-8000-000000000202";
    private static final String COURSE_ID = "00000000-0000-4000-8000-000000000203";
    private static final String LESSON_ID = "00000000-0000-4000-8000-000000000204";
    private static final String ASSESSMENT_ID = "00000000-0000-4000-8000-000000000205";
    private static final AtomicInteger COURSE_REQUESTS = new AtomicInteger();
    private static final HttpServer COURSE_SERVER = startCourseServer();

    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired ObjectMapper objectMapper;
    @Value("${local.server.port}") int learningPort;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void courseServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.learning.course.base-url",
                () -> "http://127.0.0.1:" + COURSE_SERVER.getAddress().getPort());
        registry.add("platform.learning.course.request-timeout", () -> "1s");
        registry.add("platform.learning.course.fact-cache-ttl", () -> "0s");
    }

    @BeforeEach
    void seedSyntheticLearningFacts() {
        cleanupSyntheticRows();
        Instant now = Instant.now();
        repository.insertEnrollment(new EnrollmentRow("00000000-0000-4000-8000-000000000206", LEARNER_ID,
                COURSE_ID, "v8", EnrollmentStatus.ACTIVE, now, null, now, now));
        repository.insertAssessment(new AssessmentRow(ASSESSMENT_ID, TEACHER_ID, COURSE_ID, LESSON_ID,
                "HTTP 兼容任务", "任务说明", AssessmentType.HOMEWORK, AssessmentStatus.PUBLISHED,
                GradePublicationPolicy.MANUAL, AnswerPublicationPolicy.NEVER, AssessmentCloseStatus.OPEN,
                AssessmentSettlementStatus.NOT_STARTED, null, 0, 0, null, null,
                ResultPublicationStatus.UNPUBLISHED, ResultPublicationStatus.UNPUBLISHED, null, null,
                null, now.plusSeconds(3600), null, HomeworkLatePolicy.DISALLOW, null, 1, 0,
                now, null, now, now));
        repository.ensureCourseAssignment("00000000-0000-4000-8000-000000000207", ASSESSMENT_ID, COURSE_ID, now);
        repository.insertParticipant("00000000-0000-4000-8000-000000000208", ASSESSMENT_ID, LEARNER_ID, now);
        COURSE_REQUESTS.set(0);
    }

    @AfterEach
    void cleanup() {
        cleanupSyntheticRows();
    }

    @AfterAll
    static void stopCourseServer() {
        COURSE_SERVER.stop(0);
    }

    @Test
    void courseTaskListUsesRealCourseResponseShapeWithout503() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + learningPort + "/api/v1/learning/courses/" + COURSE_ID
                        + "/tasks?page=1&size=20"))
                .header("X-User-Id", LEARNER_ID)
                .header("X-User-Roles", "STUDENT")
                .header("X-User-Permissions", "learning:access")
                .header("X-Trace-Id", "t11c1-2-course-facts-http")
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("items")).hasSize(1);
        assertThat(body.path("items").get(0).path("id").asText()).isEqualTo(ASSESSMENT_ID);
        assertThat(body.path("items").get(0).path("courseId").asText()).isEqualTo(COURSE_ID);
        assertThat(body.path("items").get(0).path("lessonId").asText()).isEqualTo(LESSON_ID);
        assertThat(body.path("items").get(0).path("type").asText()).isEqualTo("HOMEWORK");
        assertThat(COURSE_REQUESTS).hasValue(1);
    }

    private void cleanupSyntheticRows() {
        jdbc.update("DELETE FROM assessment_participant WHERE assessment_id=?", ASSESSMENT_ID);
        jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id=?", ASSESSMENT_ID);
        jdbc.update("DELETE FROM assessment WHERE id=?", ASSESSMENT_ID);
        jdbc.update("DELETE FROM enrollment WHERE learner_id=? AND course_id=?", LEARNER_ID, COURSE_ID);
    }

    private static HttpServer startCourseServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/courses/", LearningCourseFactsHttpAcceptanceTest::courseResponse);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void courseResponse(HttpExchange exchange) throws IOException {
        COURSE_REQUESTS.incrementAndGet();
        byte[] body = ("{\"id\":\"" + COURSE_ID + "\",\"title\":\"代数入门\","
                + "\"summary\":\"公开课程摘要\",\"description\":\"课程说明\","
                + "\"coverMediaId\":null,\"coverUrl\":null,"
                + "\"teacher\":{\"id\":\"" + TEACHER_ID + "\",\"displayName\":\"教师测试\"},"
                + "\"category\":{\"id\":\"00000000-0000-4000-8000-000000000209\",\"name\":\"数学\"},"
                + "\"level\":\"BEGINNER\",\"accessType\":\"ENROLLMENT_REQUIRED\",\"publishedVersion\":\"v8\","
                + "\"chapters\":[{\"id\":\"00000000-0000-4000-8000-000000000210\",\"title\":\"第一章\","
                + "\"sequence\":1,\"lessons\":[{\"id\":\"" + LESSON_ID + "\",\"title\":\"第一课时\","
                + "\"type\":\"ARTICLE\",\"sequence\":1,\"previewable\":false,"
                + "\"articleMarkdown\":\"# 内容\",\"resources\":[]}],\"chapterExtra\":true}],"
                + "\"createdAt\":\"2026-09-06T00:00:00Z\",\"updatedAt\":\"2026-09-06T00:00:00Z\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
