package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.course.service.CourseAgentAccessService;
import com.eduplatform.course.web.CourseAgentAccessDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 验证课程索引命中必须再次通过当前发布版本、精确资源字段和当前报名投影。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "platform.course.agent-access.enabled=true")
@ActiveProfiles("test")
class CourseAgentAccessIntegrationTest {
    private static final UUID OWNER = UUID.fromString("14021000-0000-4000-8000-000000000001");
    private static final UUID CATEGORY = UUID.fromString("14021000-0000-4000-8000-000000000002");
    private static final UUID COURSE = UUID.fromString("14021000-0000-4000-8000-000000000003");
    private static final UUID VERSION = UUID.fromString("14021000-0000-4000-8000-000000000004");
    private static final UUID CHAPTER = UUID.fromString("14021000-0000-4000-8000-000000000005");
    private static final UUID LESSON = UUID.fromString("14021000-0000-4000-8000-000000000006");
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseAgentAccessService service;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @BeforeEach
    void seed() {
        cleanup();
        Timestamp now = Timestamp.from(Instant.parse("2026-09-10T01:00:00Z"));
        jdbc.update("INSERT INTO category(id,code,name,status,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                CATEGORY, "T1421", "Agent 复核", "ACTIVE", now, now);
        jdbc.update("""
                INSERT INTO course(id,create_request_id,owner_teacher_id,current_draft_version_id,published_version_id,
                    publication_status,published_at,created_at,updated_at)
                VALUES(?,?,?,NULL,NULL,'DRAFT',NULL,?,?)
                """, COURSE, UUID.randomUUID(), UUID.randomUUID(), now, now);
        jdbc.update("""
                INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,
                    description,cover_url,instructor_display_name,category_id,level,access_type,created_at,updated_at)
                VALUES(?, ?,1,'v1','PUBLISHED','方程课','摘要','描述','','教师',?,'BEGINNER','ENROLLMENT_REQUIRED',?,?)
                """, VERSION, COURSE, CATEGORY, now, now);
        jdbc.update("UPDATE course SET published_version_id=?,publication_status='PUBLISHED',published_at=? WHERE id=?",
                VERSION, now, COURSE);
        jdbc.update("INSERT INTO chapter(id,course_version_id,title,position,created_at,updated_at) VALUES(?,?,?,1,?,?)",
                CHAPTER, VERSION, "方程", now, now);
        jdbc.update("""
                INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,article_markdown,created_at,updated_at)
                VALUES(?,?,'移项','ARTICLE',1,FALSE,'# 正文',?,?)
                """, LESSON, CHAPTER, now, now);
        jdbc.update("INSERT INTO course_text_index_revision(lesson_id,revision,updated_at) VALUES(?,7,?)", LESSON, now);
        jdbc.update("""
                INSERT INTO course_enrollment_access(learner_id,course_id,enrollment_id,status,changed_at,updated_at)
                VALUES(?,?,?,'ACTIVE',?,?)
                """, OWNER, COURSE, UUID.randomUUID(), now, now);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM course_enrollment_access WHERE learner_id=? AND course_id=?", OWNER, COURSE);
        jdbc.update("DELETE FROM course_text_index_revision WHERE lesson_id=?", LESSON);
        jdbc.update("DELETE FROM lesson WHERE id=?", LESSON);
        jdbc.update("DELETE FROM chapter WHERE id=?", CHAPTER);
        jdbc.update("UPDATE course SET published_version_id=NULL,current_draft_version_id=NULL WHERE id=?", COURSE);
        jdbc.update("DELETE FROM course_version WHERE id=?", VERSION);
        jdbc.update("DELETE FROM course WHERE id=?", COURSE);
        jdbc.update("DELETE FROM category WHERE id=?", CATEGORY);
    }

    @Test
    void removesWithdrawnUnpublishedOldVersionAndMismatchedCandidates() {
        var exact = candidate(LESSON, COURSE, VERSION.toString(), LESSON, 7L);
        assertThat(service.verify(new CourseAgentAccessDtos.VerifyRequest(OWNER, List.of(exact))).allowedEntityIds())
                .containsExactly(LESSON);
        assertThat(service.verify(new CourseAgentAccessDtos.VerifyRequest(OWNER, List.of(
                candidate(LESSON, COURSE, UUID.randomUUID().toString(), LESSON, 7L),
                candidate(UUID.randomUUID(), COURSE, VERSION.toString(), LESSON, 7L),
                candidate(LESSON, COURSE, VERSION.toString(), LESSON, 8L)))).allowedEntityIds()).isEmpty();

        jdbc.update("UPDATE course_enrollment_access SET status='CANCELED' WHERE learner_id=? AND course_id=?",
                OWNER, COURSE);
        assertThat(service.verify(new CourseAgentAccessDtos.VerifyRequest(OWNER, List.of(exact))).allowedEntityIds())
                .isEmpty();
        jdbc.update("UPDATE course SET publication_status='UNPUBLISHED' WHERE id=?", COURSE);
        assertThat(service.verify(new CourseAgentAccessDtos.VerifyRequest(OWNER, List.of(exact))).allowedEntityIds())
                .isEmpty();
    }

    @Test
    /** 回环 HTTP 入口只接受 Agent Access 服务身份，且只返回逐项复核通过的实体。 */
    void internalHttpEndpointAuthenticatesCallerAndReturnsOnlyAllowedIds() throws Exception {
        String body = """
                {"ownerUserId":"%s","candidates":[{"entityId":"%s","courseId":"%s",
                "courseVersionId":"%s","lessonId":"%s","sourceRevision":7}]}
                """.formatted(OWNER, LESSON, COURSE, VERSION, LESSON);
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://127.0.0.1:" + port + "/internal/v1/agent-access/course-candidates:verify"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Caller", "agent-access-service")
                .header("X-Internal-Service-Token", "test-only-agent-domain-token-32-bytes")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var response = java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(LESSON.toString()).doesNotContain("ownerUserId");

        var rejected = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                        request.uri()).header("Content-Type", "application/json")
                .header("X-Internal-Caller", "agent-access-service")
                .header("X-Internal-Service-Token", "invalid")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(401);
    }

    private static CourseAgentAccessDtos.Candidate candidate(UUID entity, UUID course, String version,
            UUID lesson, long revision) {
        return new CourseAgentAccessDtos.Candidate(entity, course, version, lesson, revision);
    }
}
