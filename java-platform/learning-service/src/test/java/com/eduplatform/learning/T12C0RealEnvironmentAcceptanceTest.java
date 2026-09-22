package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.messaging.CourseLessonContentRabbitConfig;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.progress.ProgressBufferWorker;
import com.eduplatform.learning.progress.RedisProgressBufferStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T12C.0 真实长链验收：读取但不修改《线性代数》第 2 课时，使用随机学习者验证 MySQL/Redis；
 * RabbitMQ 使用完全独立的随机课程映射验证重复与乱序，finally 精确清理本轮事实和 Redis Key。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "platform.learning.import.messaging-enabled=false",
        "platform.learning.course-content-messaging.enabled=true",
        "platform.learning.progress-buffer.worker-delay=1h",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12C0_REAL_ACCEPTANCE_ENABLED", matches = "true")
class T12C0RealEnvironmentAcceptanceTest {
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String REDIS_DATABASE = "4";
    private static final String LINEAR_ALGEBRA_COURSE = "5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057";
    private static final String SECOND_LESSON = "70775426-c55f-4662-bbc7-14750ce183e6";
    private static final String DIRTY_KEY = "edu:learning:progress:dirty";

    @LocalServerPort int port;
    @Autowired CourseFactsClient courses;
    @Autowired LearningAssessmentRepository repository;
    @Autowired ProgressBufferWorker worker;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void mysqlRedisRabbitLongChainLeavesManualCourseUnchanged() throws Exception {
        assertThat(System.getenv("LEARNING_MYSQL_DATABASE")).isEqualTo(DATABASE);
        assertThat(System.getenv("LEARNING_REDIS_DATABASE")).isEqualTo(REDIS_DATABASE);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        JsonNode before = courseDetail();
        assertThat(before.path("title").asText()).isEqualTo("线性代数");
        assertThat(before.path("chapters").toString()).contains("\"id\":\"" + SECOND_LESSON + "\"");
        String protectedCourseFacts = before.path("publishedVersion").asText() + "|"
                + before.path("publicationRevision").asInt() + "|" + before.path("chapters").toString();

        CourseFactsClient.CourseFact fact = courses.requirePublished(LINEAR_ALGEBRA_COURSE, "t12c0-real-read");
        String contentRevisionId = fact.contentRevisionId(SECOND_LESSON);
        assertThat(contentRevisionId).isNotBlank();
        String learnerId = UUID.randomUUID().toString();
        String redisKey = RedisProgressBufferStore.key(learnerId, LINEAR_ALGEBRA_COURSE, contentRevisionId);
        String syntheticCourse = UUID.randomUUID().toString();
        String syntheticVersion = UUID.randomUUID().toString();
        String syntheticLesson = UUID.randomUUID().toString();
        String syntheticRevision = UUID.randomUUID().toString();
        String newestEvent = UUID.randomUUID().toString();
        String staleEvent = UUID.randomUUID().toString();
        try {
            HttpResponse<String> heartbeat = report(learnerId, contentRevisionId, "HEARTBEAT", 12, 4);
            assertThat(heartbeat.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(heartbeat.body()).path("persistenceStatus").asText()).isEqualTo("BUFFERED");
            assertThat(redis.hasKey(redisKey)).isTrue();
            assertThat(redis.opsForHash().get(redisKey, "lessonId")).isEqualTo(SECOND_LESSON);
            worker.flushDue();
            await(() -> repository.findProgress(learnerId, LINEAR_ALGEBRA_COURSE, contentRevisionId).isPresent());
            assertThat(repository.findProgress(learnerId, LINEAR_ALGEBRA_COURSE, contentRevisionId).orElseThrow()
                    .watchedSeconds()).isEqualTo(4);

            HttpResponse<String> pause = report(learnerId, contentRevisionId, "PAUSE", 20, 3);
            assertThat(pause.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(pause.body()).path("persistenceStatus").asText()).isEqualTo("PERSISTED");

            rabbit.convertAndSend(CourseLessonContentRabbitConfig.EXCHANGE,
                    CourseLessonContentRabbitConfig.ROUTING_KEY,
                    event(newestEvent, syntheticCourse, syntheticVersion, syntheticLesson, syntheticRevision, 2));
            await(() -> repository.publishedCourseContent(syntheticCourse)
                    .map(value -> value.publicationRevision() == 2).orElse(false));
            rabbit.convertAndSend(CourseLessonContentRabbitConfig.EXCHANGE,
                    CourseLessonContentRabbitConfig.ROUTING_KEY,
                    event(newestEvent, syntheticCourse, syntheticVersion, syntheticLesson, syntheticRevision, 2));
            rabbit.convertAndSend(CourseLessonContentRabbitConfig.EXCHANGE,
                    CourseLessonContentRabbitConfig.ROUTING_KEY,
                    event(staleEvent, syntheticCourse, UUID.randomUUID().toString(), syntheticLesson,
                            UUID.randomUUID().toString(), 1));
            await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM consumed_course_content_event WHERE event_id IN (?,?)",
                    Integer.class, newestEvent, staleEvent) == 2);
            assertThat(repository.publishedCourseContent(syntheticCourse).orElseThrow().publicationRevision()).isEqualTo(2);
            assertThat(repository.publishedLessonContent(syntheticCourse)).singleElement()
                    .extracting(LearningAssessmentRepository.PublishedLessonContentRow::contentRevisionId)
                    .isEqualTo(syntheticRevision);

            JsonNode after = courseDetail();
            String afterProtectedFacts = after.path("publishedVersion").asText() + "|"
                    + after.path("publicationRevision").asInt() + "|" + after.path("chapters").toString();
            assertThat(after.path("title").asText()).isEqualTo("线性代数");
            assertThat(afterProtectedFacts).isEqualTo(protectedCourseFacts);
        } finally {
            jdbc.update("DELETE FROM lesson_progress_event WHERE learner_id=? AND course_id=?",
                    learnerId, LINEAR_ALGEBRA_COURSE);
            jdbc.update("DELETE FROM lesson_progress WHERE learner_id=? AND course_id=?",
                    learnerId, LINEAR_ALGEBRA_COURSE);
            redis.delete(redisKey);
            redis.opsForZSet().remove(DIRTY_KEY, redisKey);
            jdbc.update("DELETE FROM consumed_course_content_event WHERE event_id IN (?,?)", newestEvent, staleEvent);
            jdbc.update("DELETE FROM published_lesson_content_projection WHERE course_id=?", syntheticCourse);
            jdbc.update("DELETE FROM published_course_content_projection WHERE course_id=?", syntheticCourse);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_progress WHERE learner_id=?",
                    Integer.class, learnerId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_course_content_event WHERE event_id IN (?,?)",
                    Integer.class, newestEvent, staleEvent)).isZero();
            assertThat(redis.hasKey(redisKey)).isFalse();
        }
    }

    private HttpResponse<String> report(String learnerId, String contentRevisionId, String eventType,
            int position, int watched) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "clientEventId", UUID.randomUUID().toString(), "contentRevisionId", contentRevisionId,
                "occurredAt", Instant.now().toString(), "positionSeconds", position,
                "watchedSeconds", watched, "completed", false, "eventType", eventType));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/learning/courses/" + LINEAR_ALGEBRA_COURSE + "/lessons/" + SECOND_LESSON
                        + "/progress-events"))
                .header("Content-Type", "application/json").header("X-User-Id", learnerId)
                .header("X-User-Roles", "STUDENT").header("X-User-Permissions", "learning:access")
                .header("X-Trace-Id", "t12c0-real-" + learnerId).POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String event(String eventId, String courseId, String versionId, String lessonId,
            String revisionId, int publicationRevision) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId", eventId, "eventType", "PublishedLessonContentChanged", "eventVersion", 1,
                "aggregateId", courseId, "occurredAt", Instant.now().toString(), "traceId", "t12c0-real-rabbit",
                "payload", Map.of("courseId", courseId, "courseVersionId", versionId,
                        "publicationRevision", publicationRevision,
                        "lessons", java.util.List.of(Map.of("lessonId", lessonId,
                                "contentRevisionId", revisionId, "lessonType", "VIDEO")))));
    }

    private JsonNode courseDetail() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:8082/api/v1/courses/" + LINEAR_ALGEBRA_COURSE)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        assertThat(condition.getAsBoolean()).as("condition did not become true before timeout").isTrue();
    }
}
