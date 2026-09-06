package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AttemptRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.EnrollmentRow;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.LearningDtos.AnswerPublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.AssessmentCloseStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentSettlementStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentType;
import com.eduplatform.learning.web.LearningDtos.AttemptStatus;
import com.eduplatform.learning.web.LearningDtos.EnrollmentStatus;
import com.eduplatform.learning.web.LearningDtos.GradePublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.GradingStatus;
import com.eduplatform.learning.web.LearningDtos.HomeworkLatePolicy;
import com.eduplatform.learning.web.LearningDtos.ResultPublicationStatus;
import com.eduplatform.learning.web.QuestionDtos;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.ObjectMapper;

/**
 * T11C.1 专用 Learning 演示数据入口，只在 local profile、显式开关和指定开发 Schema 下运行。
 *
 * <p>Initialize 创建一门课程下的一场完整 HOMEWORK 和一场待人工批改 EXAM，覆盖准时/迟交/缺交/退课、
 * 1 次与 3 次提交上限、客观自动判分和主观待批状态。Cleanup 只按本类固定 UUID、标题前缀和关联行逆序清理，
 * Rebuild 等价于 Cleanup 后 Initialize；不会创建 IAM、Course、题库或媒体域数据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "platform.learning.import.messaging-enabled=false",
                "platform.learning.assessment-settlement-poll-delay=1h"
        })
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T11C1_DEMO_SEED_ENABLED", matches = "true")
class T11C1HomeworkGradebookDemoSeedTest {
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String TEACHER = "00000000-0000-4000-8000-000000000102";
    private static final List<String> LEARNERS = List.of(
            "00000000-0000-4000-8000-000000000101",
            "00000000-0000-4000-8000-000000000102",
            "00000000-0000-4000-8000-000000000103",
            "00000000-0000-4000-8000-000000000104");
    private static final String COURSE = "00000000-0000-4000-8000-000000000201";
    private static final String LESSON = "00000000-0000-4000-8000-000000000231";
    private static final String COURSE_VERSION = "t12b-demo-course-version";
    private static final String SOURCE_SYSTEM = "KG_MATH_T10B_V3";
    private static final String OBJECTIVE_SOURCE = "1ibldfJk31DjPp9tLRX";
    private static final String SUBJECTIVE_SOURCE = "11w24M4EsCHrZUgcByl";
    private static final String TITLE_PREFIX = "[T11C.1演示]";
    private static final List<String> ASSESSMENTS = List.of(id("homework"), id("exam"));

    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired QuestionService questions;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transactions;

    /** 在 Spring 建立连接前拒绝错误 Schema，避免演示动作误写到生产或普通开发库。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T11C.1 demo seed requires edu_platform_learning_dev");
        }
    }

    /** 根据脚本传入的动作执行单一事务；未知动作和半成品均失败而不会静默覆盖。 */
    @Test
    void applyRequestedDemoSeedAction() {
        String action = System.getenv().getOrDefault("T11C1_DEMO_SEED_ACTION", "").toUpperCase(Locale.ROOT);
        transactions.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
            switch (action) {
                case "INITIALIZE" -> initialize();
                case "CLEANUP" -> cleanup();
                case "REBUILD" -> {
                    cleanup();
                    initialize();
                }
                default -> throw new IllegalArgumentException(
                        "T11C1_DEMO_SEED_ACTION must be INITIALIZE, CLEANUP or REBUILD");
            }
        });
        System.out.printf("T11C.1 demo seed action %s completed; assessments=%d%n", action, ASSESSMENTS.size());
    }

    /** 初始化固定报名、不可回收课程编号、两场测评、冻结题目快照、最终 attempt 和参与者状态。 */
    private void initialize() {
        int existing = countAssessments();
        if (existing == ASSESSMENTS.size()) {
            validateSeedRows();
            return;
        }
        if (existing != 0) {
            throw new IllegalStateException("T11C.1 demo seed is partial; run Rebuild after checking local changes");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ensureEnrollments(now);
        ensureCourseStudentNumbers(now);
        Map<String, QuestionRef> questionsBySource = resolveQuestions();
        QuestionRef objective = questionsBySource.get(OBJECTIVE_SOURCE);
        QuestionRef subjective = questionsBySource.get(SUBJECTIVE_SOURCE);

        Instant publishedAt = now.minusSeconds(7200);
        Instant closesAt = now.minusSeconds(7200);
        Instant lateUntil = now.minusSeconds(3600);
        AssessmentRow homework = insertAssessment(0, "可导出作业（完整）", AssessmentType.HOMEWORK,
                HomeworkLatePolicy.ALLOW_UNTIL, 3, null, publishedAt, closesAt, lateUntil, now);
        String homeworkQuestion = addQuestion(homework, objective, 1, "2.00", now);
        addAttemptWithAnswer(homework, 0, LEARNERS.get(0), AttemptStatus.GRADED, new BigDecimal("2.00"),
                new BigDecimal("2.00"), false, closesAt.minusSeconds(60), homeworkQuestion, objective,
                objective.snapshot().answers(), BigDecimal.ZERO, true, GradingStatus.AUTO_GRADED, "SYSTEM", now);
        addAttemptWithAnswer(homework, 1, LEARNERS.get(1), AttemptStatus.GRADED, new BigDecimal("1.00"),
                new BigDecimal("2.00"), true, closesAt.plusSeconds(60), homeworkQuestion, objective,
                List.of(wrongChoice(objective.snapshot())), BigDecimal.ZERO, false, GradingStatus.AUTO_GRADED,
                "SYSTEM", now);
        insertParticipant(homework.id(), LEARNERS.get(2), "ACTIVE", "MISSING", "NOT_REQUIRED", 0, null,
                null, false, now);
        insertParticipant(homework.id(), LEARNERS.get(3), "WITHDRAWN", "WITHDRAWN", "NOT_REQUIRED", 0, null,
                null, false, now);

        AssessmentRow exam = insertAssessment(1, "主观题待批考试（隐藏）", AssessmentType.EXAM,
                HomeworkLatePolicy.DISALLOW, 1, 900, publishedAt, now.minusSeconds(3600), null, now);
        String examQuestion = addQuestion(exam, subjective, 1, "5.00", now);
        addAttemptWithAnswer(exam, 0, LEARNERS.get(0), AttemptStatus.PARTIALLY_GRADED, BigDecimal.ZERO,
                new BigDecimal("5.00"), false, closesAt.minusSeconds(30), examQuestion, subjective,
                List.of("演示主观作答：等待教师批改"), null, null, GradingStatus.PENDING_MANUAL, null, now);
        insertParticipant(exam.id(), LEARNERS.get(1), "ACTIVE", "MISSING", "NOT_REQUIRED", 0, null,
                null, false, now);
        insertParticipant(exam.id(), LEARNERS.get(2), "ACTIVE", "MISSING", "NOT_REQUIRED", 0, null,
                null, false, now);
        insertParticipant(exam.id(), LEARNERS.get(3), "WITHDRAWN", "WITHDRAWN", "NOT_REQUIRED", 0, null,
                null, false, now);

        validateSeedRows();
    }

    /** 创建已关闭的正式测评；HOMEWORK 使用三次上限，EXAM 使用既有单次限时规则。 */
    private AssessmentRow insertAssessment(int index, String title, AssessmentType type,
            HomeworkLatePolicy latePolicy, int maxAttempts, Integer timeLimitSeconds, Instant publishedAt,
            Instant closesAt, Instant lateUntil, Instant now) {
        String id = ASSESSMENTS.get(index);
        AssessmentRow assessment = new AssessmentRow(id, TEACHER, COURSE, LESSON, TITLE_PREFIX + title,
                "T11C.1 local/test 演示数据，不用于正式教学。", type, AssessmentStatus.PUBLISHED,
                GradePublicationPolicy.MANUAL, AnswerPublicationPolicy.NEVER, AssessmentCloseStatus.CLOSED,
                AssessmentSettlementStatus.COMPLETED, now.minusSeconds(1800), 0, 0, null, null,
                ResultPublicationStatus.UNPUBLISHED, ResultPublicationStatus.UNPUBLISHED, null, null,
                publishedAt.minusSeconds(60), closesAt, timeLimitSeconds, latePolicy, lateUntil, maxAttempts,
                0, publishedAt, null, publishedAt, now);
        repository.insertAssessment(assessment);
        repository.ensureCourseAssignment(id("assignment-" + index), id, COURSE, publishedAt);
        return assessment;
    }

    /**
     * 一次扫描解析两个既有 SYSTEM 题目版本并冻结到本轮测评。
     * 来源表是历史导入表，不能假设存在外部编号索引；合并查询避免为两个固定引用重复扫描大表。
     */
    private Map<String, QuestionRef> resolveQuestions() {
        List<QuestionRef> refs = jdbc.query("SELECT r.external_id,a.id question_id,v.id version_id "
                        + "FROM question_legacy_source_ref r JOIN question_aggregate a ON a.id=r.question_id "
                        + "JOIN question_version v ON v.id=a.current_version_id "
                        + "WHERE r.source_system=? AND r.external_id IN (?,?) AND r.dry_run=FALSE "
                        + "AND r.outcome='IMPORTED' AND a.scope='SYSTEM' AND a.status='ACTIVE' LIMIT 3",
                (rs, row) -> new QuestionRef(rs.getString("external_id"), rs.getString("question_id"),
                        rs.getString("version_id")),
                SOURCE_SYSTEM, OBJECTIVE_SOURCE, SUBJECTIVE_SOURCE);
        if (refs.size() != 2 || refs.stream().map(QuestionRef::externalId).distinct().count() != 2) {
            throw new IllegalStateException("Expected one active SYSTEM question for each T11C.1 demo source");
        }
        Map<String, QuestionRef> resolved = new HashMap<>();
        for (QuestionRef ref : refs) {
            QuestionDtos.VersionResponse snapshot = questions.version(TEACHER, ref.questionId(), ref.versionId());
            QuestionModels.Type expectedType = OBJECTIVE_SOURCE.equals(ref.externalId())
                    ? QuestionModels.Type.SINGLE_CHOICE : QuestionModels.Type.SHORT_ANSWER;
            if (snapshot.type() != expectedType) throw new IllegalStateException("Unexpected T11C.1 demo question type");
            resolved.put(ref.externalId(), ref.withSnapshot(snapshot));
        }
        return resolved;
    }

    /** 写入冻结题目版本并返回答案表使用的 assessment_question ID。 */
    private String addQuestion(AssessmentRow assessment, QuestionRef ref, int sequence, String points, Instant now) {
        String id = id(assessment.id() + "-question-" + sequence);
        repository.insertAssessmentQuestion(new AssessmentQuestionRow(id, assessment.id(), ref.questionId(),
                ref.versionId(), sequence, new BigDecimal(points), ref.snapshot(), now));
        return id;
    }

    /** 写入一份最终 attempt、其答案和对应最终参与者指针；历史 attempt 不在 Seed 中额外伪造。 */
    private void addAttemptWithAnswer(AssessmentRow assessment, int learnerIndex, String learnerId,
            AttemptStatus status, BigDecimal score, BigDecimal totalPoints, boolean late, Instant submittedAt,
            String assessmentQuestionId, QuestionRef question, List<String> answer, BigDecimal answerScore,
            Boolean correct, GradingStatus gradingStatus, String grader, Instant now) {
        String attemptId = id(assessment.id() + "-attempt-" + learnerIndex);
        Instant startedAt = submittedAt.minusSeconds(120);
        repository.insertAttempt(new AttemptRow(attemptId, assessment.id(), learnerId, status, 0,
                null, null, id(attemptId + "-submit"), "seed-fingerprint-" + learnerIndex, score, totalPoints,
                startedAt, assessment.timeLimitSeconds() == null ? null : startedAt.plusSeconds(assessment.timeLimitSeconds()),
                submittedAt, status == AttemptStatus.GRADED ? submittedAt.plusSeconds(30) : null,
                status == AttemptStatus.GRADED ? submittedAt.plusSeconds(30) : null, 1, late, startedAt, now));
        jdbc.update("INSERT INTO assessment_answer(id,attempt_id,assessment_question_id,answer_json,score,correct,"
                        + "grading_status,revision,graded_by,graded_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id(attemptId + "-answer"), attemptId, assessmentQuestionId, json(answer), answerScore, correct,
                gradingStatus.name(), status == AttemptStatus.GRADED ? 1 : 0, grader,
                status == AttemptStatus.GRADED ? timestamp(submittedAt.plusSeconds(30)) : null,
                timestamp(startedAt), timestamp(now));
        insertParticipant(assessment.id(), learnerId, "ACTIVE", "SUBMITTED",
                status == AttemptStatus.GRADED ? "GRADED" : "PENDING", 1, attemptId, submittedAt, late, now);
    }

    /** 写入空白/退课参与者，保留 expected、missing、withdrawn 三类成绩册主体状态。 */
    private void insertParticipant(String assessmentId, String learnerId, String enrollmentStatus,
            String submissionStatus, String gradingStatus, int attemptsUsed, String finalAttemptId,
            Instant lastSubmittedAt, boolean late, Instant now) {
        repository.insertParticipant(id(assessmentId + "-participant-" + learnerId), assessmentId, learnerId, now);
        jdbc.update("UPDATE assessment_participant SET enrollment_status=?,submission_status=?,grading_status=?,"
                        + "attempts_used=?,final_attempt_id=?,last_submitted_at=?,late_submission=?,updated_at=? "
                        + "WHERE assessment_id=? AND learner_id=?",
                enrollmentStatus, submissionStatus, gradingStatus, attemptsUsed, finalAttemptId,
                timestamp(lastSubmittedAt), late, timestamp(now), assessmentId, learnerId);
    }

    /** 为固定四名合成身份补齐课程报名；已有非本 Seed 报名只读复用，不覆盖其状态。 */
    private void ensureEnrollments(Instant now) {
        for (int index = 0; index < LEARNERS.size(); index++) {
            String learnerId = LEARNERS.get(index);
            var existing = repository.enrollment(learnerId, COURSE);
            EnrollmentStatus expected = index == 3 ? EnrollmentStatus.CANCELED : EnrollmentStatus.ACTIVE;
            if (existing.isPresent()) {
                if (existing.orElseThrow().status() != expected) {
                    throw new IllegalStateException("Existing T11C.1 demo learner enrollment has unexpected status");
                }
                continue;
            }
            Instant canceledAt = expected == EnrollmentStatus.CANCELED ? now.minusSeconds(600) : null;
            repository.insertEnrollment(new EnrollmentRow(id("enrollment-" + learnerId), learnerId, COURSE,
                    COURSE_VERSION, expected, now.minusSeconds(7200), canceledAt, now.minusSeconds(7200), now));
        }
    }

    /** 按课程内原子计数器为所有报名者分配稳定展示编号，退课者也保留编号。 */
    private void ensureCourseStudentNumbers(Instant now) {
        for (String learnerId : LEARNERS) {
            if (repository.courseStudentNumber(COURSE, learnerId).isEmpty()) {
                int number = repository.nextCourseStudentNumber(COURSE);
                repository.insertCourseStudentNumber(id("number-" + learnerId), COURSE, learnerId, number, now);
            }
        }
    }

    /** 精确检查 Seed 行、两场门禁状态、次数规则以及缺交/退课统计主体。 */
    private void validateSeedRows() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,owner_teacher_id,course_id,title,max_attempts,"
                + "late_policy,close_status,settlement_status FROM assessment WHERE id IN (?,?) ORDER BY id",
                ASSESSMENTS.toArray());
        assertThat(rows).hasSize(2);
        rows.forEach(row -> {
            assertThat(row.get("owner_teacher_id")).isEqualTo(TEACHER);
            assertThat(row.get("course_id")).isEqualTo(COURSE);
            assertThat(String.valueOf(row.get("title"))).startsWith(TITLE_PREFIX);
            assertThat(row.get("close_status")).isEqualTo("CLOSED");
            assertThat(row.get("settlement_status")).isEqualTo("COMPLETED");
        });
        assertThat(jdbc.queryForObject("SELECT max_attempts FROM assessment WHERE id=?", Integer.class,
                ASSESSMENTS.get(0))).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT max_attempts FROM assessment WHERE id=?", Integer.class,
                ASSESSMENTS.get(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE assessment_id=? "
                + "AND submission_status='MISSING'", Integer.class, ASSESSMENTS.get(0))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE assessment_id=? "
                + "AND enrollment_status='WITHDRAWN'", Integer.class, ASSESSMENTS.get(0))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_answer WHERE attempt_id IN "
                + "(SELECT id FROM assessment_attempt WHERE assessment_id IN (?,?)) AND grading_status='PENDING_MANUAL'",
                Integer.class, ASSESSMENTS.get(0), ASSESSMENTS.get(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_student_number WHERE course_id=?",
                Integer.class, COURSE)).isGreaterThanOrEqualTo(LEARNERS.size());
    }

    /** 只按固定 assessment ID 清理 V14/作答派生行、V9/11 派生行、课程编号和本类固定报名。 */
    private void cleanup() {
        if (countAssessments() == 0) {
            cleanupOwnedEnrollmentsAndNumbers();
            return;
        }
        if (countAssessments() != ASSESSMENTS.size()) {
            throw new IllegalStateException("T11C.1 demo seed is partial; refusing broad cleanup");
        }
        validateSeedRows();
        jdbc.update("DELETE FROM gradebook_export_audit WHERE course_id=? AND operator_id=?", COURSE, TEACHER);
        jdbc.update("DELETE FROM assessment_settlement_audit WHERE assessment_id IN (?,?)",
                ASSESSMENTS.toArray());
        jdbc.update("DELETE FROM assessment_deadline_audit WHERE assessment_id IN (?,?)", ASSESSMENTS.toArray());
        jdbc.update("DELETE FROM assessment_answer WHERE attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id IN (?,?))",
                ASSESSMENTS.toArray());
        jdbc.update("DELETE FROM assessment_participant WHERE assessment_id IN (?,?)", ASSESSMENTS.toArray());
        jdbc.update("DELETE FROM assessment_attempt WHERE assessment_id IN (?,?)", ASSESSMENTS.toArray());
        jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id IN (?,?)", ASSESSMENTS.toArray());
        jdbc.update("DELETE FROM assessment_question WHERE assessment_id IN (?,?)", ASSESSMENTS.toArray());
        jdbc.update("DELETE FROM assessment WHERE id IN (?,?) AND owner_teacher_id=?",
                ASSESSMENTS.get(0), ASSESSMENTS.get(1), TEACHER);
        cleanupOwnedEnrollmentsAndNumbers();
        assertThat(countAssessments()).isZero();
    }

    /** 清理前验证固定 ID 的课程/学习者绑定，只删除本类创建的报名和编号。 */
    private void cleanupOwnedEnrollmentsAndNumbers() {
        for (String learnerId : LEARNERS) {
            String enrollmentId = id("enrollment-" + learnerId);
            List<Map<String, Object>> enrollments = jdbc.queryForList("SELECT learner_id,course_id FROM enrollment WHERE id=?",
                    enrollmentId);
            if (!enrollments.isEmpty()) {
                assertThat(enrollments.get(0).get("learner_id")).isEqualTo(learnerId);
                assertThat(enrollments.get(0).get("course_id")).isEqualTo(COURSE);
                jdbc.update("DELETE FROM enrollment WHERE id=?", enrollmentId);
            }
            String numberId = id("number-" + learnerId);
            List<Map<String, Object>> numbers = jdbc.queryForList(
                    "SELECT learner_id,course_id FROM course_student_number WHERE id=?", numberId);
            if (!numbers.isEmpty()) {
                assertThat(numbers.get(0).get("learner_id")).isEqualTo(learnerId);
                assertThat(numbers.get(0).get("course_id")).isEqualTo(COURSE);
                jdbc.update("DELETE FROM course_student_number WHERE id=?", numberId);
            }
        }
    }

    private int countAssessments() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id IN (?,?)", Integer.class,
                ASSESSMENTS.toArray());
    }

    private static String wrongChoice(QuestionDtos.VersionResponse snapshot) {
        return snapshot.options().stream().map(QuestionDtos.OptionInput::key)
                .filter(key -> snapshot.answers().stream().noneMatch(answer -> answer.equalsIgnoreCase(key)))
                .findFirst().orElseThrow(() -> new IllegalStateException("T11C.1 objective question has no wrong option"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot serialize T11C.1 demo answer", error);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String id(String name) {
        return UUID.nameUUIDFromBytes(("edu-platform:t11c1-demo:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record QuestionRef(String externalId, String questionId, String versionId,
            QuestionDtos.VersionResponse snapshot) {
        private QuestionRef(String externalId, String questionId, String versionId) {
            this(externalId, questionId, versionId, null);
        }

        private QuestionRef withSnapshot(QuestionDtos.VersionResponse resolvedSnapshot) {
            return new QuestionRef(externalId, questionId, versionId, resolvedSnapshot);
        }
    }
}
