package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T11C.2 真实浏览器验收 Fixture：在指定开发库中准备两个独立开放任务，复用已有题目快照和报名。
 * 输入 T11C2_FIXTURE_ACTION=INITIALIZE/REPORT/CLEANUP；事务内按固定 ID 和 owner/标题校验，
 * 不修改 T11C.1 演示数据，不创建数据库、账号、课程或题库数据，重复初始化不重置已有作答。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"platform.learning.import.messaging-enabled=false",
                "platform.learning.assessment-settlement-poll-delay=1h"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T11C2_FIXTURE_ENABLED", matches = "true")
class T11C2BrowserFixtureTest {
    private static final String COURSE = "00000000-0000-4000-8000-000000000201";
    private static final String OWNER = "00000000-0000-4000-8000-000000000102";
    private static final String PREFIX = "[T11C.2验收]";
    private static final List<String> IDS = List.of(id("homework"), id("exam"));
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired TransactionTemplate transactions;

    /** 连接前校验明确的开发 Schema，禁止凭共享配置推断生产数据可写。 */
    @DynamicPropertySource
    static void requireDevelopmentSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_learning_dev".equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T11C.2 fixture requires edu_platform_learning_dev");
        }
    }

    /** 初始化或精确清理当前两个任务及派生行；任意校验失败整体回滚。 */
    @Test
    void applyFixture() {
        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_learning_dev");
            String action = System.getenv("T11C2_FIXTURE_ACTION");
            if (!List.of("INITIALIZE", "CLEANUP", "REPORT").contains(action)) throw new IllegalArgumentException("Unknown action");
            if ("REPORT".equals(action)) {
                System.out.println("T11C.2 assessment count=" + jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id IN (?,?)", Integer.class, IDS.get(0), IDS.get(1)));
                System.out.println("T11C.1 preserved demo count=" + jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE owner_teacher_id=? AND course_id=? AND title LIKE '[T11C.1演示]%'", Integer.class, OWNER, COURSE));
                for (String assessmentId : IDS) {
                    System.out.println(jdbc.queryForList("SELECT id,assessment_id,attempt_sequence,status FROM assessment_attempt WHERE assessment_id=? ORDER BY attempt_sequence", assessmentId));
                    System.out.println(jdbc.queryForList("SELECT id,assessment_id,wrong_count,revision,redo_attempts_used,first_wrong_at,last_wrong_at,last_attempt_id FROM wrong_question WHERE assessment_id=? OR last_attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id=?)", assessmentId, assessmentId));
                    System.out.println(jdbc.queryForList("SELECT * FROM wrong_question_occurrence WHERE attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id=?)", assessmentId));
                }
                System.out.println(jdbc.queryForList("SELECT id,client_request_id,filter_json,row_count,assessment_count,occurred_at FROM gradebook_export_audit WHERE operator_id=? AND course_id=? AND occurred_at >= '2026-09-07 09:30:00'", OWNER, COURSE));
                return;
            }
            if ("CLEANUP".equals(action)) cleanupWrongQuestions();
            for (int i = 0; i < IDS.size(); i++) {
                String assessmentId = IDS.get(i);
                int existing = jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=?", Integer.class, assessmentId);
                if (existing != 0) {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=? AND owner_teacher_id=? "
                            + "AND course_id=? AND title=?", Integer.class, assessmentId, OWNER, COURSE,
                            PREFIX + (i == 0 ? "多次提交作业" : "限时考试"))).isEqualTo(1);
                }
                if ("CLEANUP".equals(action)) {
                    if (existing != 0) cleanup(assessmentId);
                } else if (existing == 0) {
                    initialize(assessmentId, i == 0);
                }
            }
            if ("CLEANUP".equals(action)) cleanupExportAudits();
        });
        System.out.println("T11C.2 browser fixture action completed: " + System.getenv("T11C2_FIXTURE_ACTION"));
    }

    /** 复制已有演示题目的不可变快照，只创建 Learning 任务与应交名单；窗口两小时，考试限时一小时。 */
    private void initialize(String assessmentId, boolean homework) {
        // 来源通过明确的 owner、课程和演示标题校验；作业复制客观题、考试复制主观题，
        // 这样真实验收可以同时覆盖最后结果和待人工批改，而不改动 T11C.1 固定演示数据。
        String sourceTitle = homework ? "[T11C.1演示]可导出作业（完整）" : "[T11C.1演示]主观题待批考试（隐藏）";
        List<String> sources = jdbc.queryForList("SELECT id FROM assessment WHERE owner_teacher_id=? AND course_id=? "
                + "AND title=?", String.class, OWNER, COURSE, sourceTitle);
        assertThat(sources).hasSize(1);
        String source = sources.get(0);
        assertThat(jdbc.update("INSERT INTO assessment(id,owner_teacher_id,course_id,lesson_id,title,description,"
                + "assessment_type,status,answer_publication_policy,answer_release_policy,grade_publication_policy,"
                + "opens_at,closes_at,time_limit_seconds,max_attempts,published_at,created_at,updated_at) "
                + "SELECT ?,owner_teacher_id,course_id,NULL,?,'T11C.2 local/test 临时验收',?,'PUBLISHED','NEVER','NEVER','MANUAL',"
                + "TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP),TIMESTAMPADD(HOUR,2,CURRENT_TIMESTAMP),?,?,"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM assessment WHERE id=?",
                assessmentId, PREFIX + (homework ? "多次提交作业" : "限时考试"), homework ? "HOMEWORK" : "EXAM",
                homework ? null : 3600, homework ? 3 : 1, source)).isEqualTo(1);
        assertThat(jdbc.update("INSERT INTO assessment_question(id,assessment_id,question_id,question_version_id,sequence_no,points,snapshot_json,created_at) "
                + "SELECT ?,?,question_id,question_version_id,sequence_no,points,snapshot_json,CURRENT_TIMESTAMP "
                + "FROM assessment_question WHERE assessment_id=?", id(assessmentId + "question"), assessmentId, source)).isEqualTo(1);
        repository.ensureCourseAssignment(id(assessmentId + "assignment"), assessmentId, COURSE, Instant.now());
        repository.ensureParticipantsForAssessment(COURSE, assessmentId, Instant.now());
        System.out.println("T11C.2 task " + assessmentId);
    }

    /** 只按已校验任务 ID 删除派生行，保留既有报名、编号、题目及其他任务审计。 */
    private void cleanup(String assessmentId) {
        // 错题聚合已先验证并清理；任何剩余引用都会停止事务，不能删除其他任务的累计数据。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question WHERE assessment_id=? OR last_attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id=?)", Integer.class, assessmentId, assessmentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question_occurrence WHERE attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id=?)", Integer.class, assessmentId)).isZero();
        jdbc.update("DELETE FROM learning_idempotency_request WHERE resource_id=? OR resource_id IN "
                + "(SELECT id FROM assessment_attempt WHERE assessment_id=?)", assessmentId, assessmentId);
        jdbc.update("DELETE FROM manual_grading_record WHERE attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id=?)", assessmentId);
        jdbc.update("DELETE FROM assessment_answer WHERE attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id=?)", assessmentId);
        jdbc.update("DELETE FROM assessment_participant WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_settlement_audit WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_deadline_audit WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_attempt WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_question WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment WHERE id=?", assessmentId);
    }

    /** 仅当累计次数完全由本轮两个任务的 occurrence 解释且没有重做时删除，混有既有累计则停止。 */
    private void cleanupWrongQuestions() {
        var rows = jdbc.queryForList("SELECT * FROM wrong_question WHERE assessment_id IN (?,?) OR last_attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id IN (?,?)) FOR UPDATE", IDS.get(0), IDS.get(1), IDS.get(0), IDS.get(1));
        for (var row : rows) {
            Integer ownCount = jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question_occurrence o JOIN assessment_attempt a ON a.id=o.attempt_id JOIN assessment_question q ON q.id=o.assessment_question_id WHERE a.assessment_id IN (?,?) AND a.learner_id=? AND q.question_version_id=?", Integer.class, IDS.get(0), IDS.get(1), row.get("learner_id"), row.get("question_version_id"));
            assertThat(((Number) row.get("wrong_count")).intValue()).isEqualTo(ownCount);
            assertThat(((Number) row.get("redo_attempts_used")).intValue()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question_redo WHERE wrong_question_id=?", Integer.class, row.get("id"))).isZero();
            jdbc.update("DELETE FROM wrong_question WHERE id=?", row.get("id"));
        }
        jdbc.update("DELETE FROM wrong_question_occurrence WHERE attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id IN (?,?))", IDS.get(0), IDS.get(1));
    }

    /** 仅清理验收者明确记录的导出审计 UUID，并再次限定测试教师和课程；不按课程批量删除。 */
    private void cleanupExportAudits() {
        String ids = System.getenv("T11C2_EXPORT_AUDIT_IDS");
        if (ids == null || ids.isBlank()) return;
        for (String value : ids.split(",")) {
            String auditId = UUID.fromString(value.trim()).toString();
            jdbc.update("DELETE FROM gradebook_export_audit WHERE id=? AND operator_id=? AND course_id=?", auditId, OWNER, COURSE);
        }
    }

    private static String id(String value) {
        return UUID.nameUUIDFromBytes(("T11C2-browser-" + value).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
