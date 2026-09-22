package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.course.service.CourseAgentIndexProjectionService;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.course.web.CourseAgentIndexController;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 验证 Worker 只能读取当前发布版本的非空 ARTICLE，并且内部令牌失败时不返回正文。 */
@SpringBootTest(properties = "platform.course.agent-index.enabled=true")
@ActiveProfiles("test")
class CourseAgentIndexProjectionIntegrationTest {
    private static final String ZERO = "00000000-0000-0000-0000-000000000000";
    private static final String CATEGORY = "14020000-0000-4000-8000-000000000001";
    private static final String COURSE = "14020000-0000-4000-8000-000000000002";
    private static final String VERSION = "14020000-0000-4000-8000-000000000003";
    private static final String CHAPTER = "14020000-0000-4000-8000-000000000004";
    private static final String LESSON = "14020000-0000-4000-8000-000000000005";

    @Autowired JdbcTemplate jdbc;
    @Autowired CourseAgentIndexProjectionService projections;
    @Autowired CourseAgentIndexController controller;

    @BeforeEach
    void seedPublishedArticle() {
        cleanup();
        Timestamp now = Timestamp.from(Instant.parse("2026-09-09T06:00:00Z"));
        jdbc.update("INSERT INTO category(id,code,name,status,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                CATEGORY, "T14_AGENT_INDEX", "T14 索引测试", "ACTIVE", now, now);
        jdbc.update("""
                INSERT INTO course(id,create_request_id,owner_teacher_id,current_draft_version_id,
                    published_version_id,publication_status,published_at,created_at,updated_at)
                VALUES(?,?,?,NULL,NULL,'DRAFT',NULL,?,?)
                """, COURSE, "14020000-0000-4000-8000-000000000090",
                "14020000-0000-4000-8000-000000000099", now, now);
        jdbc.update("""
                INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,
                    description,cover_url,instructor_display_name,category_id,level,access_type,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, VERSION, COURSE, 1, "v1", "PUBLISHED", "初中数学", "安全摘要", "安全描述", "",
                "测试教师", CATEGORY, "BEGINNER", "ENROLLMENT_REQUIRED", now, now);
        jdbc.update("UPDATE course SET published_version_id=?,publication_status='PUBLISHED',published_at=? WHERE id=?",
                VERSION, now, COURSE);
        jdbc.update("INSERT INTO chapter(id,course_version_id,title,position,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                CHAPTER, VERSION, "方程", 1, now, now);
        jdbc.update("""
                INSERT INTO lesson(id,content_revision_id,chapter_id,title,lesson_type,position,previewable,article_markdown,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, LESSON, LESSON, CHAPTER, "二元一次方程", "ARTICLE", 1, false, "# 二元一次方程\n正文", now, now);
        jdbc.update("INSERT INTO course_text_index_revision(lesson_id,revision,updated_at) VALUES(?,?,?)",
                LESSON, 7, now);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM course_text_index_revision WHERE lesson_id=?", LESSON);
        jdbc.update("DELETE FROM lesson WHERE id=?", LESSON);
        jdbc.update("DELETE FROM chapter WHERE id=?", CHAPTER);
        jdbc.update("UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id=?", COURSE);
        jdbc.update("DELETE FROM course_version WHERE id=?", VERSION);
        jdbc.update("DELETE FROM course WHERE id=?", COURSE);
        jdbc.update("DELETE FROM category WHERE id=?", CATEGORY);
    }

    @Test
    void returnsOnlyCurrentPublishedArticleAndStopsAfterUnpublish() {
        var page = projections.page(0, ZERO, LESSON, 10);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceRevision()).isEqualTo(7);
            assertThat(item.articleMarkdown()).contains("二元一次方程");
            assertThat(item.accessType()).isEqualTo("PAID");
        });

        jdbc.update("UPDATE course SET publication_status='UNPUBLISHED' WHERE id=?", COURSE);
        assertThat(projections.page(0, ZERO, LESSON, 10).items()).isEmpty();
    }

    @Test
    void rejectsWrongWorkerTokenBeforeReturningCourseText() {
        assertThatThrownBy(() -> controller.page("browser", "wrong-token", 0,
                UUID.fromString(ZERO), null, 10))
                .isInstanceOfSatisfying(CourseException.class,
                        failure -> assertThat(failure.code()).isEqualTo("AGENT_INDEX.UNAUTHORIZED"));
    }
}
