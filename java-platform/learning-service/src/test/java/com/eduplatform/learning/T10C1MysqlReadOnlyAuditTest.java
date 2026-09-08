package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 显式只读审计既有 Learning 开发库存。仅由安全环境包装脚本启用，连接前严格限定 Schema；
 * 不启动 Spring/Flyway/Worker，不创建或清理数据，不读取历史源 JSON，不输出题干、答案或凭据。
 */
@EnabledIfEnvironmentVariable(named = "T10C1_MYSQL_AUDIT", matches = "true")
class T10C1MysqlReadOnlyAuditTest {
    @Test
    void inventoryExistingQuestionsAndCoursePracticesWithoutMutation() throws Exception {
        String host = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("MYSQL_PORT", "3306");
        assertThat(host).matches("[a-zA-Z0-9.:-]+");
        assertThat(port).matches("[0-9]{1,5}");
        auditLearning(host, port);
        auditMedia(host, port);
    }

    /** 审计 Learning 自有表，限定聚合统计和一个公共根题样本，不跨 Schema Join。 */
    private static void auditLearning(String host, String port) throws Exception {
        String schema = System.getenv("LEARNING_MYSQL_DATABASE");
        assertThat(schema).isEqualTo("edu_platform_learning_dev");
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + schema,
                System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement query = connection.createStatement()) {
                query.setQueryTimeout(30);
                counts(query, "questions", "SELECT scope,status,COUNT(*) FROM question_aggregate GROUP BY scope,status", 3);
                counts(query, "root_questions", "SELECT a.scope,a.status,COUNT(*) FROM question_aggregate a WHERE NOT EXISTS (SELECT 1 FROM question_version_child c WHERE c.child_question_id=a.id) GROUP BY a.scope,a.status", 3);
                counts(query, "question_media", "SELECT a.scope,a.status,m.purpose,COUNT(*) FROM question_aggregate a JOIN question_version v ON v.question_id=a.id JOIN question_version_media m ON m.question_version_id=v.id GROUP BY a.scope,a.status,m.purpose", 4);
                counts(query, "assessments", "SELECT assessment_type,status,COUNT(*),SUM(course_id IS NULL) FROM assessment GROUP BY assessment_type,status", 4);
                counts(query, "practice_assignment", "SELECT COUNT(*),COALESCE(SUM(EXISTS (SELECT 1 FROM assessment_assignment aa WHERE aa.assessment_id=a.id AND aa.assignment_type='COURSE' AND aa.target_id=a.course_id)),0) FROM assessment a WHERE assessment_type='PRACTICE'", 2);
                counts(query, "question_sample", "SELECT a.id,v.question_type,(SELECT COUNT(*) FROM question_version_child c WHERE c.parent_question_version_id=v.id),(SELECT COUNT(*) FROM question_version_media m WHERE m.question_version_id=v.id) FROM question_aggregate a JOIN question_version v ON v.id=a.current_version_id WHERE a.scope='SYSTEM' AND a.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM question_version_child c WHERE c.child_question_id=a.id) ORDER BY a.id LIMIT 1", 4);
            } finally {
                connection.rollback();
            }
        }
    }

    /** 审计 Media 自有表中 SYSTEM 资产状态和引用状态，不读取对象键、文件名或签名地址。 */
    private static void auditMedia(String host, String port) throws Exception {
        String schema = System.getenv("MEDIA_MYSQL_DATABASE");
        assertThat(schema).isEqualTo("edu_platform_media_dev");
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + schema,
                System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD"))) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement query = connection.createStatement()) {
                query.setQueryTimeout(30);
                counts(query, "system_media", "SELECT purpose,status,COUNT(*) FROM media_asset WHERE scope='SYSTEM' GROUP BY purpose,status", 3);
                counts(query, "system_media_references", "SELECT r.reference_type,r.status,COUNT(*) FROM media_reference r JOIN media_asset m ON m.media_id=r.media_id WHERE m.scope='SYSTEM' GROUP BY r.reference_type,r.status", 3);
                counts(query, "system_media_sample", "SELECT media_id,purpose,status FROM media_asset WHERE scope='SYSTEM' ORDER BY media_id LIMIT 1", 3);
            } finally {
                connection.rollback();
            }
        }
    }

    /** 只输出固定白名单 SQL 的枚举、数量和一个稳定样本 ID；ResultSet 与 Statement 都有界关闭。 */
    private static void counts(Statement query, String label, String sql, int columns) throws Exception {
        try (ResultSet result = query.executeQuery(sql)) {
            while (result.next()) {
                StringBuilder summary = new StringBuilder("T10C1_AUDIT ").append(label);
                for (int column = 1; column <= columns; column++) summary.append(' ').append(result.getString(column));
                System.out.println(summary);
            }
        }
    }
}
