package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.course.seed.CourseSeedInitializer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:course_seed_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.course.seed.enabled=true"
})
@ActiveProfiles("test")
class CourseSeedIntegrationTest {
    @Autowired CourseSeedInitializer initializer;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void seedIsMinimalIdempotentAndCleanupRemovesOnlyCourseFixtures() throws Exception {
        initializer.run(new DefaultApplicationArguments(new String[0]));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chapter", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT publication_status FROM course", String.class))
                .isEqualTo("PUBLISHED");

        jdbc.update("""
                INSERT INTO course_version (
                    id, course_id, version_number, version_label, lifecycle_status,
                    title, summary, description, cover_url, instructor_display_name,
                    category_id, level, access_type, curriculum_hash, row_version
                )
                SELECT '00000000-0000-4000-8000-000000000212', course_id, 2, 'v2', 'DRAFT',
                       title, summary, description, cover_url, instructor_display_name,
                       category_id, level, access_type, curriculum_hash, row_version
                FROM course_version
                WHERE id = '00000000-0000-4000-8000-000000000211'
                """);
        jdbc.update("""
                UPDATE course
                SET current_draft_version_id = '00000000-0000-4000-8000-000000000212'
                WHERE id = '00000000-0000-4000-8000-000000000201'
                """);

        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/seed/cleanup-course-seed.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lesson", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chapter", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_version", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class)).isZero();
    }
}
