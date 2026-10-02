package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.iam.seed.IamSeedInitializer;
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
        "spring.datasource.url=jdbc:h2:mem:iam_seed_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.iam.seed.enabled=true",
        "platform.iam.seed.password=test-seed-password"
})
@ActiveProfiles("test")
class IamSeedIntegrationTest {
    @Autowired IamSeedInitializer initializer;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void seedIsIdempotentAndCleanupScriptRemovesOnlySeedFixtures() throws Exception {
        initializer.run(new DefaultApplicationArguments(new String[0]));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE account LIKE 'seed_%'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `role`", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM permission", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN `role` r ON r.id=ur.role_id WHERE r.code='TEACHER'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identity", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT normalized_value FROM user_identity ORDER BY normalized_value", String.class))
                .containsExactly("admin1@example.invalid", "student1@example.invalid",
                        "teacher1@example.invalid", "teacher2@example.invalid");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_agreement", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_document WHERE is_current=TRUE", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT body FROM protocol_document", String.class))
                .allMatch(body -> body.contains("不可用于正式发布"));

        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/seed/cleanup-iam-seed.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `role`", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM permission", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identity", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_agreement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_document", Integer.class)).isZero();
    }
}
