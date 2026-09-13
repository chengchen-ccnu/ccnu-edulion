package com.eduplatform.agentaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.agentaccess.service.MemoryFactService;
import com.eduplatform.agentaccess.service.MemoryIndexProjectionService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 显式开关下在 Agent Access 专用开发 Schema 验证完整迁移链和幂等强证据。
 *
 * <p>用例只创建本轮随机 owner/source 的最小记忆事实，finally 按精确 owner 逆序清理；
 * 不连接 RabbitMQ、Learning/Course 数据库或 Python 状态。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "platform.agent.persistence-enabled=true",
        "platform.agent-index.enabled=true",
        "platform.agent-index.publish-enabled=false"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "AGENT_MEMORY_MYSQL_ACCEPTANCE", matches = "true")
class AgentMemoryMysqlAcceptanceTest {
    private static final Set<String> V3_TABLES = Set.of("ai_memory_revision_sequence",
            "ai_conversation_summary", "ai_memory", "ai_memory_evidence", "ai_profile_topic",
            "ai_preference", "ai_usage_record", "ai_safety_event", "ai_agent_index_outbox");

    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryFactService facts;
    @Autowired MemoryIndexProjectionService projections;

    /** 在 Spring 建立连接前拒绝非专用开发 Schema。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_agent_dev".equals(System.getenv("AGENT_MYSQL_DATABASE"))) {
            throw new IllegalStateException("Agent memory MySQL acceptance requires edu_platform_agent_dev");
        }
    }

    /** V1 至 V7 必须按顺序在真实 MySQL 成功，Skill 私有审计表不得缺失。 */
    @Test
    void migrationsThroughV7AreCompleteAndRestartSafe() {
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class))
                .containsAll(V3_TABLES).contains("ai_skill_execution");
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='ai_run'
                """, String.class)).contains("result_normalization_status", "result_normalization_attempts",
                        "result_normalization_error", "result_normalized_at");
    }

    /** 在真实 MySQL 验证重复/乱序 revision 为 no-op、较新修正生效，并精确清理本轮事实。 */
    @Test
    void formalEvidenceIsIdempotentCorrectableAndPreciselyCleaned() {
        UUID owner = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        try {
            facts.recordLearningEvidence(owner, "TEACHER_GRADING", source, 1,
                    "T14.2.2 验收主题", "MASTERED", "trace-t1422-mysql-one");
            facts.recordLearningEvidence(owner, "TEACHER_GRADING", source, 1,
                    "T14.2.2 验收主题", "MASTERED", "trace-t1422-mysql-one");
            facts.recordLearningEvidence(owner, "TEACHER_GRADING", source, 3,
                    "T14.2.2 验收主题", "NEEDS_SUPPORT", "trace-t1422-mysql-three");
            facts.recordLearningEvidence(owner, "TEACHER_GRADING", source, 2,
                    "T14.2.2 验收主题", "MASTERED", "trace-t1422-mysql-stale");

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory_evidence WHERE owner_user_id=?",
                    Integer.class, owner.toString())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT current_level FROM ai_profile_topic WHERE owner_user_id=?",
                    String.class, owner.toString())).isEqualTo("NEEDS_SUPPORT");
            assertThat(jdbc.queryForObject("SELECT historical_mastery FROM ai_profile_topic WHERE owner_user_id=?",
                    Integer.class, owner.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_index_outbox WHERE aggregate_id IN "
                    + "(SELECT id FROM ai_memory WHERE owner_user_id=?)", Integer.class, owner.toString()))
                    .isEqualTo(2);
            assertThat(projections.page(0, new UUID(0, 0), null, 200).items())
                    .filteredOn(item -> item.subjectUserId().equals(owner)).singleElement()
                    .satisfies(item -> assertThat(item.visibility()).isEqualTo("USER_PRIVATE"));
        } finally {
            List<String> ids = jdbc.queryForList("SELECT id FROM ai_memory WHERE owner_user_id=?",
                    String.class, owner.toString());
            for (String id : ids) {
                jdbc.update("DELETE FROM ai_agent_index_outbox WHERE aggregate_id=?", id);
                jdbc.update("DELETE FROM ai_memory_evidence WHERE memory_id=?", id);
                jdbc.update("DELETE FROM ai_memory WHERE id=?", id);
            }
            jdbc.update("DELETE FROM ai_profile_topic WHERE owner_user_id=?", owner.toString());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_memory WHERE owner_user_id=?",
                    Integer.class, owner.toString())).isZero();
        }
    }
}
