package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

import com.eduplatform.learning.media.MediaClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 从真实随机端口验证题目写请求的 Jackson、Bean Validation、Controller 与数据库完整 HTTP 边界。
 * 每项只使用固定合成 owner/知识点，并按该 owner 和知识点 ID 精确清理，不读取历史题库或外部系统。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:learning_http_binding_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class QuestionHttpBindingIntegrationTest {
    private static final String TEACHER = "00000000-0000-4000-8000-000000009301";
    private static final String KNOWLEDGE_POINT = "00000000-0000-4000-8000-000000009302";
    private static final String REQUEST_PREFIX = "00000000-0000-4000-8000-0000000093";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Value("${local.server.port}") int port;
    @MockitoBean MediaClient media;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedMinimalKnowledgePoint() {
        cleanupSyntheticRows();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                KNOWLEDGE_POINT, "JUNIOR_MATH_HTTP_BINDING", "HTTP 绑定测试知识点", null, "JUNIOR", "SEVEN",
                "MATHEMATICS", 9302, true, 0, Timestamp.from(now), Timestamp.from(now));
        doNothing().when(media).requireBindable(anyString(), anyString(), anyString(), anyString());
    }

    @AfterEach
    void cleanup() {
        cleanupSyntheticRows();
    }

    @Test
    void postOrdinaryQuestionMayOmitChildren() throws Exception {
        String requestId = REQUEST_PREFIX + "01";
        HttpResponse<String> response = post(body(ordinaryVersion(), requestId));

        assertCreatedWithNoChildren(response);
        Map<String, Object> explicitEmpty = ordinaryVersion();
        explicitEmpty.put("children", List.of());
        HttpResponse<String> replay = post(body(explicitEmpty, requestId));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(replay.body()).get("id").asString())
                .isEqualTo(objectMapper.readTree(response.body()).get("id").asString());
    }

    @Test
    void postOrdinaryQuestionAcceptsExplicitEmptyChildren() throws Exception {
        Map<String, Object> version = ordinaryVersion();
        version.put("children", List.of());

        HttpResponse<String> response = post(body(version, REQUEST_PREFIX + "02"));

        assertCreatedWithNoChildren(response);
    }

    @Test
    void postCompositeQuestionAcceptsNonEmptyChildren() throws Exception {
        Map<String, Object> version = baseVersion("COMPOSITE", "组合父题", List.of(), List.of("综合作答"));
        version.put("children", List.of(baseVersion("SINGLE_CHOICE", "组合子题",
                List.of(option("A", "1"), option("B", "2")), List.of("A"))));

        HttpResponse<String> response = post(body(version, REQUEST_PREFIX + "03"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(response.body()).get("currentVersion").get("children").size()).isEqualTo(1);
    }

    @Test
    void invalidEnumReturnsSafeVersionTypePath() throws Exception {
        Map<String, Object> version = ordinaryVersion();
        version.put("type", "NOT_A_QUESTION_TYPE");

        HttpResponse<String> response = post(body(version, REQUEST_PREFIX + "04"));

        assertValidationField(response, "version.type");
        assertThat(response.body()).doesNotContain("NOT_A_QUESTION_TYPE", "InvalidFormatException", "QuestionDtos");
    }

    @Test
    void unknownFieldRemainsRejectedAtItsSafePath() throws Exception {
        Map<String, Object> version = ordinaryVersion();
        version.put("unexpected", true);

        HttpResponse<String> response = post(body(version, REQUEST_PREFIX + "05"));

        assertValidationField(response, "version.unexpected");
    }

    @Test
    void invalidUuidRemainsRejectedAtItsSafeArrayPath() throws Exception {
        Map<String, Object> version = ordinaryVersion();
        version.put("knowledgePointIds", List.of("not-a-uuid"));

        HttpResponse<String> response = post(body(version, REQUEST_PREFIX + "07"));

        assertValidationField(response, "version.knowledgePointIds[0]");
        assertThat(response.body()).doesNotContain("not-a-uuid", "InvalidFormatException", "UUID");
    }

    @Test
    void missingRequiredFieldRemainsRejectedAtItsSafePath() throws Exception {
        Map<String, Object> version = ordinaryVersion();
        version.remove("stem");

        HttpResponse<String> response = post(body(version, REQUEST_PREFIX + "06"));

        assertValidationField(response, "version.stem");
    }

    private void assertCreatedWithNoChildren(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(response.body()).get("currentVersion").get("children").isEmpty()).isTrue();
    }

    private void assertValidationField(HttpResponse<String> response, String field) throws Exception {
        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo("COMMON.VALIDATION_FAILED");
        assertThat(error.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/teacher/questions"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", TEACHER)
                .header("X-User-Roles", "TEACHER")
                .header("X-User-Permissions", "teaching:access")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String body(Map<String, Object> version, String clientRequestId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("clientRequestId", clientRequestId, "version", version));
    }

    private Map<String, Object> ordinaryVersion() {
        return baseVersion("SINGLE_CHOICE", "普通单选题",
                List.of(option("A", "2"), option("B", "3")), List.of("A"));
    }

    private Map<String, Object> baseVersion(String type, String stem, List<Map<String, Object>> options,
            List<String> answers) {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("type", type);
        version.put("difficulty", "EASY");
        version.put("stage", "JUNIOR");
        version.put("grade", "SEVEN");
        version.put("subject", "MATHEMATICS");
        version.put("stem", stem);
        version.put("options", options);
        version.put("answers", answers);
        version.put("analysis", "合成解析");
        version.put("knowledgePointIds", List.of(KNOWLEDGE_POINT));
        version.put("media", List.of());
        return version;
    }

    private static Map<String, Object> option(String key, String content) {
        return Map.of("key", key, "content", content);
    }

    /** 只清理本测试固定 owner 产生的题目图和固定知识点，不清空或遍历其他题库数据。 */
    private void cleanupSyntheticRows() {
        jdbc.update("DELETE FROM question_version_child WHERE parent_question_version_id IN "
                + "(SELECT id FROM question_version WHERE created_by=?) OR child_question_id IN "
                + "(SELECT id FROM question_aggregate WHERE owner_id=?) OR child_question_version_id IN "
                + "(SELECT id FROM question_version WHERE created_by=?)", TEACHER, TEACHER, TEACHER);
        jdbc.update("DELETE FROM question_version_media WHERE question_version_id IN "
                + "(SELECT id FROM question_version WHERE created_by=?)", TEACHER);
        jdbc.update("DELETE FROM question_version_knowledge_point WHERE question_version_id IN "
                + "(SELECT id FROM question_version WHERE created_by=?)", TEACHER);
        jdbc.update("DELETE FROM question_index_revision WHERE question_id IN "
                + "(SELECT id FROM question_aggregate WHERE owner_id=?)", TEACHER);
        jdbc.update("DELETE FROM question_idempotency_request WHERE owner_id=?", TEACHER);
        jdbc.update("UPDATE question_aggregate SET current_version_id=NULL,parent_question_id=NULL WHERE owner_id=?", TEACHER);
        jdbc.update("DELETE FROM question_version WHERE created_by=?", TEACHER);
        jdbc.update("DELETE FROM question_aggregate WHERE owner_id=?", TEACHER);
        jdbc.update("DELETE FROM knowledge_point WHERE id=?", KNOWLEDGE_POINT);
    }
}
