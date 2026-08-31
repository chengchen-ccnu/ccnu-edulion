package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** T12F.1 课程演示 Seed，只在专用 course 开发库写入固定课程版本、正文和媒体引用快照。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "platform.course.messaging.enabled=false", "platform.course.seed.enabled=false"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12F1_DEMO_SEED_ENABLED", matches = "true")
class T12F1CourseContentDemoSeedTest {
    private static final String DATABASE = "edu_platform_course_dev";
    static final String COURSE = "00000000-0000-4000-8000-00000000f201";
    static final String VERSION = "00000000-0000-4000-8000-00000000f211";
    static final String VIDEO_LESSON = "00000000-0000-4000-8000-00000000f231";
    static final String ARTICLE_LESSON = "00000000-0000-4000-8000-00000000f232";
    private static final String CATEGORY = "00000000-0000-4000-8000-00000000f501";
    private static final String CHAPTER = "00000000-0000-4000-8000-00000000f221";
    private static final String VIDEO = "00000000-0000-4000-8000-00000000f101";
    private static final String CUSTOM_POSTER = "00000000-0000-4000-8000-00000000f102";
    private static final String ARTICLE_IMAGE = "00000000-0000-4000-8000-00000000f103";
    private static final String PDF = "00000000-0000-4000-8000-00000000f104";
    private static final String TXT = "00000000-0000-4000-8000-00000000f105";
    private static final String PPTX = "00000000-0000-4000-8000-00000000f106";
    private static final String MARKDOWN = """
            # 图文课时：一元二次方程

            公式：$x=\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}$

            | 符号 | 含义 |
            | --- | --- |
            | $a$ | 二次项系数 |

            ```java
            double delta = b * b - 4 * a * c;
            ```

            ![判别式示意图](media:00000000-0000-4000-8000-00000000f103)
            """;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("COURSE_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12F.1 course demo seed requires edu_platform_course_dev");
        }
    }

    @Test
    void applyRequestedDemoSeedAction() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        String action = System.getenv().getOrDefault("T12F1_DEMO_SEED_ACTION", "").toUpperCase(Locale.ROOT);
        transactions.executeWithoutResult(status -> {
            switch (action) {
                case "INITIALIZE" -> initialize();
                case "CLEANUP" -> cleanup();
                case "REBUILD" -> { cleanup(); initialize(); }
                default -> throw new IllegalArgumentException("T12F1_DEMO_SEED_ACTION must be INITIALIZE, CLEANUP or REBUILD");
            }
        });
        System.out.printf("T12F.1 course demo action %s completed; course=%s%n", action, COURSE);
    }

    /** 固定课程完整存在时只校验；所有资源均引用 media-service 固定 ID，不跨库查询。 */
    private void initialize() {
        int existing = count("course", "id", COURSE);
        if (existing == 1) { validateSeed(); return; }
        jdbc.update("INSERT INTO category(id,code,name,status,row_version) VALUES(?,?,?,'ACTIVE',0)",
                CATEGORY, "T12F1_DEMO_MATH", "T12F.1演示分类");
        jdbc.update("INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status,row_version) "
                        + "VALUES(?,?,?,'DRAFT',0)", COURSE, "00000000-0000-4000-8000-00000000f291",
                "00000000-0000-4000-8000-000000000102");
        jdbc.update("""
                INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,
                    description,cover_url,instructor_display_name,category_id,level,access_type,curriculum_hash,row_version)
                VALUES(?,?,1,'v1','PUBLISHED','[T12F.1演示]课程内容与资源','视频、图文与资料授权演示',
                    '固定伪数据，只用于本地 T12F.1 验收',NULL,'测试教师',?,'BEGINNER','PUBLIC',?,0)
                """, VERSION, COURSE, CATEGORY, "f".repeat(64));
        jdbc.update("INSERT INTO chapter(id,course_version_id,title,position,row_version) VALUES(?,?,?,1,0)",
                CHAPTER, VERSION, "内容与资源");
        jdbc.update("INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,article_markdown,row_version) "
                + "VALUES(?,?,?,'VIDEO',1,TRUE,NULL,0)", VIDEO_LESSON, CHAPTER, "短视频与自动海报");
        jdbc.update("INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,article_markdown,row_version) "
                + "VALUES(?,?,?,'ARTICLE',2,TRUE,?,0)", ARTICLE_LESSON, CHAPTER, "公式、表格、代码与正文图片", MARKDOWN);
        bind("00000000-0000-4000-8000-00000000f301", VIDEO_LESSON, VIDEO, "LESSON_VIDEO", 1,
                "演示短视频", "INLINE", "t12f1-short-video.mp4", "video/mp4", 1, "hls-fmp4-v2,poster-v1", "LESSON_VIDEO_V5");
        bind("00000000-0000-4000-8000-00000000f302", VIDEO_LESSON, CUSTOM_POSTER, "VIDEO_POSTER", 1,
                "教师自定义海报", "INLINE", "t12f1-custom-poster.webp", "image/webp", 1, "", null);
        bind("00000000-0000-4000-8000-00000000f303", ARTICLE_LESSON, ARTICLE_IMAGE, "ARTICLE_IMAGE", 1,
                "判别式示意图", "INLINE", "t12f1-formula.png", "image/png", 1, "", null);
        bind("00000000-0000-4000-8000-00000000f304", ARTICLE_LESSON, PDF, "ARTICLE_ATTACHMENT", 1,
                "阅读材料 PDF", "INLINE", "t12f1-reading.pdf", "application/pdf", 1, "", null);
        bind("00000000-0000-4000-8000-00000000f305", ARTICLE_LESSON, TXT, "ARTICLE_ATTACHMENT", 2,
                "纯文本笔记", "DOWNLOAD", "t12f1-notes.txt", "text/plain", 1, "", null);
        bind("00000000-0000-4000-8000-00000000f306", ARTICLE_LESSON, PPTX, "COURSEWARE", 1,
                "演示课件 PPTX", "DOWNLOAD", "t12f1-slides.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", 1, "", null);
        jdbc.update("UPDATE course SET published_version_id=?,publication_status='PUBLISHED',published_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                VERSION, COURSE);
        validateSeed();
    }

    private void bind(String id, String lesson, String media, String purpose, int position, String display,
            String presentation, String filename, String mime, long size, String variants, String contract) {
        jdbc.update("""
                INSERT INTO lesson_media_resource(id,lesson_id,media_id,purpose,position,display_name,presentation,
                    original_file_name,mime_type,size_bytes,variant_names,processing_contract_version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, lesson, media, purpose, position, display, presentation, filename, mime, size, variants, contract);
    }

    /** 只删除固定课程的本域子表与固定分类；不删除 media 或 learning 数据。 */
    private void cleanup() {
        if (count("course", "id", COURSE) == 0) return;
        assertThat(jdbc.queryForObject("SELECT title FROM course_version WHERE id=?", String.class, VERSION))
                .startsWith("[T12F.1演示]");
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (?,?) OR payload_json LIKE ?",
                VIDEO_LESSON, ARTICLE_LESSON, "%" + COURSE + "%");
        jdbc.update("DELETE FROM course_enrollment_access WHERE course_id=?", COURSE);
        jdbc.update("DELETE FROM lesson_media_resource WHERE lesson_id IN (?,?)", VIDEO_LESSON, ARTICLE_LESSON);
        jdbc.update("DELETE FROM lesson WHERE id IN (?,?)", VIDEO_LESSON, ARTICLE_LESSON);
        jdbc.update("DELETE FROM chapter WHERE id=?", CHAPTER);
        jdbc.update("UPDATE course SET published_version_id=NULL,current_draft_version_id=NULL WHERE id=?", COURSE);
        jdbc.update("DELETE FROM course_version WHERE id=?", VERSION);
        jdbc.update("DELETE FROM course WHERE id=?", COURSE);
        jdbc.update("DELETE FROM category WHERE id=?", CATEGORY);
        assertThat(count("course", "id", COURSE)).isZero();
    }

    private void validateSeed() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson WHERE chapter_id=?", Integer.class, CHAPTER)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson_media_resource WHERE lesson_id IN (?,?)",
                Integer.class, VIDEO_LESSON, ARTICLE_LESSON)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT article_markdown FROM lesson WHERE id=?", String.class, ARTICLE_LESSON))
                .contains("\\frac", "| 符号 |", "```java", "media:" + ARTICLE_IMAGE);
    }

    private int count(String table, String column, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, id);
    }
}
