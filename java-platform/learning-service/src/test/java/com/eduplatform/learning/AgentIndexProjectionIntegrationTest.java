package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.learning.service.AgentIndexProjectionService;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.web.AgentIndexController;
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
import tools.jackson.databind.ObjectMapper;

/** 验证 Worker 只能分页读取 ACTIVE SYSTEM 安全题面和有界初中知识关系。 */
@SpringBootTest(properties = "platform.learning.agent-index.enabled=true")
@ActiveProfiles("test")
class AgentIndexProjectionIntegrationTest {
    private static final String ZERO = "00000000-0000-0000-0000-000000000000";
    private static final String QUESTION = "14010000-0000-4000-8000-000000000001";
    private static final String VERSION = "14010000-0000-4000-8000-000000000002";
    private static final String PREREQUISITE = "14010000-0000-4000-8000-000000000003";
    private static final String KNOWLEDGE = "14010000-0000-4000-8000-000000000004";
    private static final String RELATION = "14010000-0000-4000-8000-000000000005";

    @Autowired JdbcTemplate jdbc;
    @Autowired AgentIndexProjectionService projections;
    @Autowired AgentIndexController controller;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void seedSafeAndSecretFacts() {
        cleanup();
        Instant now = Instant.parse("2026-09-09T06:00:00Z");
        insertKnowledge(PREREQUISITE, "T14_KP_PRE", "一次方程", 2, now);
        insertKnowledge(KNOWLEDGE, "T14_KP_TARGET", "二元一次方程", 4, now);
        jdbc.update("""
                INSERT INTO knowledge_point_prerequisite(id,prerequisite_id,dependent_id,relation_type,
                    source_system,source_relation_type,revision,created_at) VALUES(?,?,?,?,?,?,?,?)
                """, RELATION, PREREQUISITE, KNOWLEDGE, "PREREQUISITE", "t14-test", "PREREQUISITE", 0,
                Timestamp.from(now));
        jdbc.update("""
                INSERT INTO question_aggregate(id,owner_id,scope,status,current_version_id,parent_question_id,
                    revision,created_at,updated_at) VALUES(?,?,?,?,NULL,NULL,?,?,?)
                """, QUESTION, "14010000-0000-4000-8000-000000000099", "SYSTEM", "ACTIVE", 4,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO question_version(id,question_id,version_number,question_type,difficulty,stage,grade,
                    subject,stem_md,options_json,answers_json,analysis_md,created_by,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, VERSION, QUESTION, 1, "SINGLE_CHOICE", "MEDIUM", "JUNIOR", "EIGHT", "MATHEMATICS",
                "若 x+y=3，求一个可能的解。", "[{\"key\":\"A\",\"content\":\"x=1,y=2\"}]",
                "[\"禁止进入索引的答案\"]", "禁止进入索引的解析",
                "14010000-0000-4000-8000-000000000099", Timestamp.from(now));
        jdbc.update("UPDATE question_aggregate SET current_version_id=? WHERE id=?", VERSION, QUESTION);
        jdbc.update("""
                INSERT INTO question_version_knowledge_point(question_version_id,knowledge_point_id,sequence_no)
                VALUES(?,?,?)
                """, VERSION, KNOWLEDGE, 1);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM question_version_knowledge_point WHERE question_version_id=?", VERSION);
        jdbc.update("DELETE FROM question_index_revision WHERE question_id=?", QUESTION);
        jdbc.update("UPDATE question_aggregate SET current_version_id=NULL WHERE id=?", QUESTION);
        jdbc.update("DELETE FROM question_version WHERE id=?", VERSION);
        jdbc.update("DELETE FROM question_aggregate WHERE id=?", QUESTION);
        jdbc.update("DELETE FROM knowledge_point_prerequisite WHERE id=?", RELATION);
        jdbc.update("DELETE FROM knowledge_point WHERE id IN (?,?)", KNOWLEDGE, PREREQUISITE);
    }

    @Test
    void returnsSafeQuestionAndBoundedPrerequisiteWithoutHiddenAnswerFields() throws Exception {
        var questionPage = projections.questions(0, ZERO, QUESTION, 10);
        assertThat(questionPage.items()).hasSize(1);
        assertThat(questionPage.items().get(0).sourceRevision()).isEqualTo(5);
        assertThat(questionPage.items().get(0).options()).containsExactly("A. x=1,y=2");
        String json = mapper.writeValueAsString(questionPage);
        assertThat(json).doesNotContain("禁止进入索引的答案", "禁止进入索引的解析", "answers", "analysis");

        var knowledgePage = projections.knowledge(0, ZERO, KNOWLEDGE, 10);
        assertThat(knowledgePage.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceRevision()).isEqualTo(5);
            assertThat(item.prerequisiteIds()).containsExactly(PREREQUISITE);
        });
    }

    @Test
    void rejectsWrongWorkerTokenBeforeReadingProjection() {
        assertThatThrownBy(() -> controller.questions("agent-index-worker", "wrong-token", 0,
                UUID.fromString(ZERO), null, 10))
                .isInstanceOfSatisfying(QuestionException.class,
                        failure -> assertThat(failure.code()).isEqualTo("AGENT_INDEX.UNAUTHORIZED"));
    }

    private void insertKnowledge(String id, String code, String name, long revision, Instant now) {
        jdbc.update("""
                INSERT INTO knowledge_point(id,code,name,parent_id,stage,grade,subject,sort_order,enabled,revision,
                    created_at,updated_at,normalized_stage) VALUES(?,?,?,NULL,?,?,?,?,?,?,?,?,?)
                """, id, code, name, "JUNIOR", "EIGHT", "MATHEMATICS", 1, true, revision,
                Timestamp.from(now), Timestamp.from(now), "JUNIOR");
    }
}
