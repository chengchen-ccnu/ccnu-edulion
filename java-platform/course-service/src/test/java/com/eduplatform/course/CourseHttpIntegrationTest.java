package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CourseHttpIntegrationTest {
    private static final String CATEGORY_ID = "00000000-0000-4000-8000-000000009601";
    private static final String TEACHER_ID = "00000000-0000-4000-8000-000000009101";

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetData() {
        jdbc.update("UPDATE course SET current_draft_version_id = NULL, published_version_id = NULL");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM lesson_media_resource");
        jdbc.update("DELETE FROM lesson");
        jdbc.update("DELETE FROM chapter");
        jdbc.update("DELETE FROM course_version");
        jdbc.update("DELETE FROM course");
        jdbc.update("DELETE FROM category");
        jdbc.update("INSERT INTO category (id, code, name, status) VALUES (?, 'HTTP_TEST', 'HTTP测试分类', 'ACTIVE')",
                CATEGORY_ID);
    }

    @Test
    void teacherHttpApiReturnsContractResponseAndUnifiedRoleError() throws Exception {
        HttpResponse<String> categories = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/courses/categories"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(categories.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(categories.body())).singleElement()
                .satisfies(category -> assertThat(category.get("id").asString()).isEqualTo(CATEGORY_ID));

        String body = """
                {
                  "clientRequestId": "00000000-0000-4000-8000-000000009701",
                  "title": "HTTP 课程",
                  "summary": "契约集成测试",
                  "description": "## HTTP",
                  "coverMediaId": null,
                  "instructorDisplayName": "测试教师",
                  "categoryId": "%s",
                  "level": "BEGINNER",
                  "accessType": "PUBLIC"
                }
                """.formatted(CATEGORY_ID);
        HttpResponse<String> created = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/teacher/courses"))
                        .header("Content-Type", "application/json")
                        .header("X-User-Id", TEACHER_ID)
                        .header("X-User-Roles", "TEACHER")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("X-Trace-Id")).isPresent();
        assertThat(created.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).startsWith("/api/v1/teacher/courses/"));
        assertThat(objectMapper.readTree(created.body()).get("publicationStatus").asString()).isEqualTo("DRAFT");

        HttpResponse<String> listed = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/teacher/courses?page=1&size=10"))
                        .header("X-User-Id", TEACHER_ID)
                        .header("X-User-Roles", "TEACHER")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(listed.body()).get("items")).hasSize(1);
        assertThat(objectMapper.readTree(listed.body()).get("total").asLong()).isEqualTo(1);

        HttpResponse<String> forbidden = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/teacher/courses"))
                        .header("Content-Type", "application/json")
                        .header("X-User-Id", TEACHER_ID)
                        .header("X-User-Roles", "STUDENT")
                        .POST(HttpRequest.BodyPublishers.ofString(body.replace("009701", "009702")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(forbidden.body()).get("code").asString()).isEqualTo("IAM.FORBIDDEN");
    }
}
