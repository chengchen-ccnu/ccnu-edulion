package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.QuestionDtos;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * T10C.2 真实浏览器验收夹具：只在明确 Learning 开发 Schema 创建固定课程 PRACTICE 和未开放 HOMEWORK 探针。
 * 输入为 INITIALIZE/CLEANUP/REPORT；输出为固定任务及其精确清理结果，不创建账号、课程、报名、题库或媒体。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"platform.learning.import.messaging-enabled=false",
                "platform.learning.assessment-settlement-poll-delay=1h"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T10C2_FIXTURE_ENABLED", matches = "true")
class T10C2CoursePracticeBrowserFixtureTest {
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String COURSE = "00000000-0000-4000-8000-000000000201";
    private static final String OWNER = "00000000-0000-4000-8000-000000000102";
    private static final String LEARNER = "00000000-0000-4000-8000-000000000101";
    private static final String SOURCE_QUESTION = "03a34109-c7d7-475a-9a37-57eac1fec6e8";
    private static final String ASSESSMENT = id("assessment");
    private static final String ASSESSMENT_QUESTION = id("assessment-question");
    private static final String ASSIGNMENT = id("assignment");
    private static final String PARTICIPANT = id("participant");
    private static final String TITLE = "[T10C.2验收]课程练习";
    private static final String NOT_OPEN_ASSESSMENT = id("not-open-assessment");
    private static final String NOT_OPEN_QUESTION = id("not-open-question");
    private static final String NOT_OPEN_ASSIGNMENT = id("not-open-assignment");
    private static final String NOT_OPEN_PARTICIPANT = id("not-open-participant");
    private static final String NOT_OPEN_TITLE = "[T10C.2验收]未开放作业";

    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired QuestionService questions;
    @Autowired TransactionTemplate transactions;

    /** 在 Spring 建立写连接前拒绝非固定开发 Schema。 */
    @DynamicPropertySource
    static void requireDevelopmentSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T10C.2 fixture requires edu_platform_learning_dev");
        }
    }

    /** 每次只执行一个事务动作；固定 ID 的 owner、课程或标题不匹配时整体回滚。 */
    @Test
    void applyFixtureAction() {
        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
            String action = System.getenv().getOrDefault("T10C2_FIXTURE_ACTION", "");
            switch (action) {
                case "INITIALIZE" -> initialize();
                case "CLEANUP" -> cleanup();
                case "REPORT" -> report();
                default -> throw new IllegalArgumentException("T10C2_FIXTURE_ACTION must be INITIALIZE, CLEANUP or REPORT");
            }
        });
    }

    /** 创建一场课程练习和一场未来开放作业；重复执行只校验归属，不重置已有作答。 */
    private void initialize() {
        int existing = countAssessment();
        if (existing == 1) {
            validateOwnedAssessment();
        }
        else {
            assertThat(existing).isZero();
        }
        assertThat(repository.enrollment(LEARNER, COURSE)).hasValueSatisfying(value ->
                assertThat(value.status()).isEqualTo(LearningDtos.EnrollmentStatus.ACTIVE));
        String versionId = jdbc.queryForObject("SELECT current_version_id FROM question_aggregate "
                + "WHERE id=? AND scope='SYSTEM' AND status='ACTIVE'", String.class, SOURCE_QUESTION);
        QuestionDtos.VersionResponse snapshot = questions.version(OWNER, SOURCE_QUESTION, versionId);
        assertThat(snapshot.media()).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question WHERE learner_id=? AND course_id=? "
                + "AND question_version_id=?", Integer.class, LEARNER, COURSE, versionId)).isZero();
        Instant now = Instant.now();
        if (existing == 0) {
            repository.insertAssessment(new AssessmentRow(ASSESSMENT, OWNER, COURSE, null, TITLE,
                    "local/test 最小课程练习验收数据", LearningDtos.AssessmentType.PRACTICE,
                    LearningDtos.AssessmentStatus.PUBLISHED, LearningDtos.GradePublicationPolicy.MANUAL,
                    LearningDtos.AnswerPublicationPolicy.AFTER_SUBMISSION, LearningDtos.AssessmentCloseStatus.OPEN,
                    LearningDtos.AssessmentSettlementStatus.NOT_STARTED, null, 0, 0, null, null,
                    LearningDtos.ResultPublicationStatus.UNPUBLISHED, LearningDtos.ResultPublicationStatus.UNPUBLISHED,
                    null, null, null, null, null, LearningDtos.HomeworkLatePolicy.DISALLOW,
                    null, 1, 1, now, null, now, now));
            repository.insertAssessmentQuestion(new AssessmentQuestionRow(ASSESSMENT_QUESTION, ASSESSMENT,
                    SOURCE_QUESTION, versionId, 1, BigDecimal.ONE, snapshot, now));
            repository.ensureCourseAssignment(ASSIGNMENT, ASSESSMENT, COURSE, now);
            repository.insertParticipant(PARTICIPANT, ASSESSMENT, LEARNER, now);
        }
        validateOwnedAssessment();
        initializeNotOpenProbe(versionId, snapshot, now);
        System.out.printf("T10C.2 fixture initialized: practice=%s, notOpen=%s, questions=2, participants=2%n",
                ASSESSMENT, NOT_OPEN_ASSESSMENT);
    }

    /** 创建未来一天才开放的 HOMEWORK，只供验证安全摘要和开始时 409；不修改已有正式作业。 */
    private void initializeNotOpenProbe(String versionId, QuestionDtos.VersionResponse snapshot, Instant now) {
        int existing = countAssessment(NOT_OPEN_ASSESSMENT);
        if (existing == 1) {
            validateNotOpenAssessment();
            return;
        }
        assertThat(existing).isZero();
        repository.insertAssessment(new AssessmentRow(NOT_OPEN_ASSESSMENT, OWNER, COURSE, null, NOT_OPEN_TITLE,
                "local/test 未开放门禁验收数据", LearningDtos.AssessmentType.HOMEWORK,
                LearningDtos.AssessmentStatus.PUBLISHED, LearningDtos.GradePublicationPolicy.MANUAL,
                LearningDtos.AnswerPublicationPolicy.NEVER, LearningDtos.AssessmentCloseStatus.OPEN,
                LearningDtos.AssessmentSettlementStatus.NOT_STARTED, null, 0, 0, null, null,
                LearningDtos.ResultPublicationStatus.UNPUBLISHED, LearningDtos.ResultPublicationStatus.UNPUBLISHED,
                null, null, now.plusSeconds(86_400), now.plusSeconds(172_800), null,
                LearningDtos.HomeworkLatePolicy.DISALLOW, null, 1, 1, now, null, now, now));
        repository.insertAssessmentQuestion(new AssessmentQuestionRow(NOT_OPEN_QUESTION, NOT_OPEN_ASSESSMENT,
                SOURCE_QUESTION, versionId, 1, BigDecimal.ONE, snapshot, now));
        repository.ensureCourseAssignment(NOT_OPEN_ASSIGNMENT, NOT_OPEN_ASSESSMENT, COURSE, now);
        repository.insertParticipant(NOT_OPEN_PARTICIPANT, NOT_OPEN_ASSESSMENT, LEARNER, now);
        validateNotOpenAssessment();
    }

    /** 删除固定任务及其作答派生事实；错题若混入非本夹具 occurrence 或已有重做则拒绝删除。 */
    private void cleanup() {
        if (countAssessment() != 0) {
            validateOwnedAssessment();
            cleanupPractice();
        }
        if (countAssessment(NOT_OPEN_ASSESSMENT) != 0) {
            validateNotOpenAssessment();
            cleanupSimpleAssessment(NOT_OPEN_ASSESSMENT);
        }
        assertThat(countAssessment()).isZero();
        assertThat(countAssessment(NOT_OPEN_ASSESSMENT)).isZero();
        System.out.println("T10C.2 fixture cleanup: assessments=0, attempts=0, answers=0, occurrences=0");
    }

    /** 课程练习可能产生作答和错题，删除前先证明聚合只由本夹具 occurrence 构成。 */
    private void cleanupPractice() {
        List<Map<String, Object>> wrongs = jdbc.queryForList("SELECT * FROM wrong_question WHERE learner_id=? "
                + "AND (assessment_id=? OR last_attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id=?)) FOR UPDATE",
                LEARNER, ASSESSMENT, ASSESSMENT);
        for (Map<String, Object> wrong : wrongs) {
            int occurrences = jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question_occurrence o "
                    + "JOIN assessment_attempt a ON a.id=o.attempt_id "
                    + "JOIN assessment_question q ON q.id=o.assessment_question_id "
                    + "WHERE a.assessment_id=? AND q.question_version_id=?", Integer.class,
                    ASSESSMENT, wrong.get("question_version_id"));
            assertThat(((Number) wrong.get("wrong_count")).intValue()).isEqualTo(occurrences);
            assertThat(((Number) wrong.get("redo_attempts_used")).intValue()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question_redo WHERE wrong_question_id=?",
                    Integer.class, wrong.get("id"))).isZero();
        }
        jdbc.update("DELETE FROM wrong_question_occurrence WHERE attempt_id IN "
                + "(SELECT id FROM assessment_attempt WHERE assessment_id=?)", ASSESSMENT);
        for (Map<String, Object> wrong : wrongs) {
            jdbc.update("DELETE FROM wrong_question WHERE id=?", wrong.get("id"));
        }
        jdbc.update("DELETE FROM learning_idempotency_request WHERE resource_id=? OR resource_id IN "
                + "(SELECT id FROM assessment_attempt WHERE assessment_id=?)", ASSESSMENT, ASSESSMENT);
        jdbc.update("DELETE FROM manual_grading_record WHERE attempt_id IN "
                + "(SELECT id FROM assessment_attempt WHERE assessment_id=?)", ASSESSMENT);
        jdbc.update("DELETE FROM assessment_answer WHERE attempt_id IN "
                + "(SELECT id FROM assessment_attempt WHERE assessment_id=?)", ASSESSMENT);
        cleanupSimpleAssessment(ASSESSMENT);
    }

    /** 删除一个已校验固定任务的全部 Learning 子记录；NOT_OPEN 探针正常情况下没有 attempt。 */
    private void cleanupSimpleAssessment(String assessmentId) {
        jdbc.update("DELETE FROM assessment_participant WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_settlement_audit WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_deadline_audit WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_attempt WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment_question WHERE assessment_id=?", assessmentId);
        jdbc.update("DELETE FROM assessment WHERE id=?", assessmentId);
    }

    /** 只输出数量与状态，供验收前后证明没有遗留临时数据。 */
    private void report() {
        System.out.printf("T10C.2 fixture report: practice=%d, notOpen=%d, attempts=%d, answers=%d%n",
                countAssessment(), countAssessment(NOT_OPEN_ASSESSMENT),
                count("assessment_attempt", "assessment_id"), countAnswers());
    }

    private void validateOwnedAssessment() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=? AND owner_teacher_id=? "
                + "AND course_id=? AND title=? AND assessment_type='PRACTICE'", Integer.class,
                ASSESSMENT, OWNER, COURSE, TITLE)).isEqualTo(1);
    }

    private void validateNotOpenAssessment() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=? AND owner_teacher_id=? "
                + "AND course_id=? AND title=? AND assessment_type='HOMEWORK' AND status='PUBLISHED' "
                + "AND opens_at>CURRENT_TIMESTAMP", Integer.class,
                NOT_OPEN_ASSESSMENT, OWNER, COURSE, NOT_OPEN_TITLE)).isEqualTo(1);
    }

    private int countAssessment() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=?", Integer.class, ASSESSMENT);
    }

    private int countAssessment(String assessmentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=?", Integer.class, assessmentId);
    }

    private int count(String table, String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, ASSESSMENT);
    }

    private int countAnswers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM assessment_answer WHERE attempt_id IN "
                + "(SELECT id FROM assessment_attempt WHERE assessment_id=?)", Integer.class, ASSESSMENT);
    }

    private static String id(String value) {
        return UUID.nameUUIDFromBytes(("edu-platform:t10c2-browser:" + value)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
