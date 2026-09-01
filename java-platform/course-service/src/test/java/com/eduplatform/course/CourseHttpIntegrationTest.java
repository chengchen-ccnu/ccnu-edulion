package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.course.service.CourseCatalogService;
import com.eduplatform.course.web.CourseDtos;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(CourseMediaTestConfiguration.class)
class CourseHttpIntegrationTest {
    private static final String CATEGORY_ID = "00000000-0000-4000-8000-000000009601";
    private static final String TEACHER_ID = "00000000-0000-4000-8000-000000009101";

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CourseCatalogService courseService;
    @Autowired CourseMediaTestConfiguration.FakeMediaBindingClient mediaClient;
    @Autowired CourseMediaTestConfiguration.FakeMediaAccessClient accessClient;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetData() {
        mediaClient.clear();
        accessClient.clear();
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

    @Test
    /** HTTP 契约不接受任意 mediaId；owner 得到最小投影，非 owner 与普通角色不获知资源细节。 */
    void teacherArticleContentHttpProjectionIsOwnerScopedAndStorageSafe() throws Exception {
        UUID image = UUID.randomUUID();
        UUID arbitraryMedia = UUID.randomUUID();
        mediaClient.register(image.toString(), TEACHER_ID, "ARTICLE_IMAGE", "READY",
                "teacher-article.png", "image/png", 512);
        mediaClient.register(arbitraryMedia.toString(), TEACHER_ID, "ARTICLE_IMAGE", "READY",
                "other-course.png", "image/png", 512);
        CourseDtos.TeacherCourse draft = courseService.create(TEACHER_ID,
                new CourseDtos.CreateCourseRequest(UUID.randomUUID(), "HTTP 正文", "摘要", "说明", null,
                        "测试教师", UUID.fromString(CATEGORY_ID), CourseDtos.CourseLevel.BEGINNER,
                        CourseDtos.CourseAccessType.PUBLIC)).course();
        CourseDtos.LessonInput article = new CourseDtos.LessonInput("图文课", CourseDtos.LessonType.ARTICLE,
                1, false, "![正文图](media:" + image + ")",
                List.of(new CourseDtos.LessonMediaResourceInput(image,
                        CourseDtos.LessonMediaPurpose.ARTICLE_IMAGE, 1)));
        CourseDtos.TeacherCourse saved = courseService.replaceCurriculum(draft.id(), TEACHER_ID,
                new CourseDtos.ReplaceCurriculumRequest(draft.revision(),
                        List.of(new CourseDtos.ChapterInput("正文", 1, List.of(article)))));
        CourseDtos.TeacherCourse otherDraft = courseService.create(TEACHER_ID,
                new CourseDtos.CreateCourseRequest(UUID.randomUUID(), "其他课程", "摘要", "说明", null,
                        "测试教师", UUID.fromString(CATEGORY_ID), CourseDtos.CourseLevel.BEGINNER,
                        CourseDtos.CourseAccessType.PUBLIC)).course();
        CourseDtos.LessonInput otherArticle = new CourseDtos.LessonInput("其他正文", CourseDtos.LessonType.ARTICLE,
                1, false, "![其他课程图](media:" + arbitraryMedia + ")",
                List.of(new CourseDtos.LessonMediaResourceInput(arbitraryMedia,
                        CourseDtos.LessonMediaPurpose.ARTICLE_IMAGE, 1)));
        courseService.replaceCurriculum(otherDraft.id(), TEACHER_ID,
                new CourseDtos.ReplaceCurriculumRequest(otherDraft.revision(),
                        List.of(new CourseDtos.ChapterInput("其他正文", 1, List.of(otherArticle)))));
        String lessonId = saved.chapters().get(0).lessons().get(0).id();
        String path = "/api/v1/teacher/courses/" + saved.id() + "/lessons/" + lessonId
                + "/article-content?mediaId=" + arbitraryMedia;

        HttpResponse<String> response = sendArticleContent(path, TEACHER_ID, "TEACHER");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("courseId").asString()).isEqualTo(saved.id());
        assertThat(body.get("lessonId").asString()).isEqualTo(lessonId);
        assertThat(body.get("inlineImages")).singleElement().satisfies(value ->
                assertThat(value.get("mediaId").asString()).isEqualTo(image.toString()));
        assertThat(response.body()).doesNotContain(arbitraryMedia.toString(), "resourceId", "resources",
                "originalFileName", "objectKey", "bucket", "Bucket", "serviceToken", "token");
        assertThat(accessClient.requests()).singleElement().satisfies(request ->
                assertThat(request.mediaId()).isEqualTo(image.toString()));

        HttpResponse<String> nonOwner = sendArticleContent(path,
                "00000000-0000-4000-8000-000000009102", "TEACHER");
        assertThat(nonOwner.statusCode()).isEqualTo(404);
        assertThat(nonOwner.body()).doesNotContain(image.toString(), arbitraryMedia.toString(),
                "ARTICLE_IMAGE", "objectKey", "bucket", "serviceToken", "question", "answer");
        assertThat(sendArticleContent(path, TEACHER_ID, "STUDENT").statusCode()).isEqualTo(403);
        assertThat(client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> sendArticleContent(String path, String userId, String roles) throws Exception {
        return client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", roles)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
