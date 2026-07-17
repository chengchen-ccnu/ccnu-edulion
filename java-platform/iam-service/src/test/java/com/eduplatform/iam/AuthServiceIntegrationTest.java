package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.TokenPair;
import java.sql.Timestamp;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuthServiceIntegrationTest {
    private static final String USER_ID = "00000000-0000-4000-8000-000000009001";

    @Autowired AuthService authService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Flyway flyway;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM login_audit");
        jdbc.update("DELETE FROM refresh_session");
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM role_permission");
        jdbc.update("DELETE FROM `user`");
        jdbc.update("DELETE FROM `role`");
        jdbc.update("DELETE FROM permission");
        jdbc.update("INSERT INTO `role` (id, code, name) VALUES (1, 'STUDENT', 'STUDENT')");
        jdbc.update("INSERT INTO permission (id, code, name) VALUES (1, 'learning:access', 'learning:access')");
        jdbc.update("INSERT INTO role_permission (role_id, permission_id) VALUES (1, 1)");
        insertUser("ACTIVE");
        jdbc.update("INSERT INTO user_role (user_id, role_id) VALUES (?, 1)", USER_ID);
    }

    @Test
    void flywayAndMyBatisSupportSuccessfulLoginWithoutPersistingRawRefreshToken() {
        flyway.validate();
        TokenPair tokens = authService.login("student_test_01", "correct-password", "127.0.0.1", "trace1234");

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(authService.currentUser(USER_ID).roles()).containsExactly("STUDENT");
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM refresh_session", String.class);
        assertThat(storedHash).hasSize(64).isNotEqualTo(tokens.refreshToken());
        assertThat(jdbc.queryForObject("SELECT outcome FROM login_audit", String.class)).isEqualTo("SUCCESS");
    }

    @Test
    void rejectsWrongPasswordAndAuditsFailure() {
        assertThatThrownBy(() -> authService.login("student_test_01", "wrong-password", "127.0.0.1", "trace1234"))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(401);
                    assertThat(error.code()).isEqualTo("IAM.BAD_CREDENTIALS");
                });
        assertThat(jdbc.queryForObject("SELECT outcome FROM login_audit", String.class)).isEqualTo("BAD_CREDENTIALS");
    }

    @Test
    void rejectsDisabledAccount() {
        jdbc.update("UPDATE `user` SET status = 'DISABLED' WHERE id = ?", USER_ID);
        assertThatThrownBy(() -> authService.login("student_test_01", "correct-password", "127.0.0.1", "trace1234"))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(403);
                    assertThat(error.code()).isEqualTo("IAM.ACCOUNT_DISABLED");
                });
    }

    @Test
    void rotatesRefreshTokenAndRejectsReuse() {
        TokenPair login = authService.login("student_test_01", "correct-password", "127.0.0.1", "trace1234");
        TokenPair rotated = authService.refresh(login.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(login.refreshToken());

        assertThatThrownBy(() -> authService.refresh(login.refreshToken()))
                .isInstanceOfSatisfying(AuthException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code()).isEqualTo("IAM.REFRESH_REUSED");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_session WHERE status = 'ACTIVE'", Integer.class)).isZero();
    }

    @Test
    void rejectsExpiredRefreshTokenAndLogoutRevokesActiveSession() {
        TokenPair expired = authService.login("student_test_01", "correct-password", "127.0.0.1", "trace1234");
        jdbc.update("UPDATE refresh_session SET expires_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), AuthService.hash(expired.refreshToken()));
        assertThatThrownBy(() -> authService.refresh(expired.refreshToken()))
                .isInstanceOfSatisfying(AuthException.class, error -> assertThat(error.status().value()).isEqualTo(401));

        TokenPair active = authService.login("student_test_01", "correct-password", "127.0.0.2", "trace5678");
        authService.logout(active.refreshToken());
        assertThat(jdbc.queryForObject("SELECT status FROM refresh_session WHERE token_hash = ?", String.class,
                AuthService.hash(active.refreshToken()))).isEqualTo("REVOKED");
    }

    @Test
    void rateLimitsRepeatedFailures() {
        for (int attempt = 0; attempt < 5; attempt++) {
            try { authService.login("student_test_01", "wrong-password", "127.0.0.3", "trace1234"); }
            catch (AuthException ignored) { }
        }
        assertThatThrownBy(() -> authService.login("student_test_01", "correct-password", "127.0.0.3", "trace1234"))
                .isInstanceOfSatisfying(AuthException.class, error -> assertThat(error.status().value()).isEqualTo(429));
    }

    private void insertUser(String status) {
        jdbc.update("INSERT INTO `user` (id, account, display_name, password_hash, status) VALUES (?, ?, ?, ?, ?)",
                USER_ID, "student_test_01", "测试学生", passwordEncoder.encode("correct-password"), status);
    }
}
