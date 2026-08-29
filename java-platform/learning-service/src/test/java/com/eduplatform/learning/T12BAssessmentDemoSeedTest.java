package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.domain.QuestionModels;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AnswerRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentQuestionRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AttemptRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.EnrollmentRow;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.RedoRow;
import com.eduplatform.learning.service.LearningAssessmentService;
import com.eduplatform.learning.service.QuestionService;
import com.eduplatform.learning.web.LearningDtos.AnswerPublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.AssessmentCloseStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentSettlementStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentType;
import com.eduplatform.learning.web.LearningDtos.AttemptStatus;
import com.eduplatform.learning.web.LearningDtos.EnrollmentStatus;
import com.eduplatform.learning.web.LearningDtos.GradePublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.GradingStatus;
import com.eduplatform.learning.web.LearningDtos.ResultPublicationStatus;
import com.eduplatform.learning.web.QuestionDtos;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * T12B 本地演示数据工具，只在显式开关与专用 learning 开发库同时满足时运行。
 *
 * <p>输入是 Initialize、Cleanup 或 Rebuild 动作；输出是固定 ID 的测评闭环数据或精确清理结果。
 * 题目通过稳定 SYSTEM 来源引用解析当前不可变版本，绝不把历史题库正文复制进仓库。所有写入位于单个事务，
 * 并在删除前校验固定 ID 的 owner 与标题标记，避免误删用户或其他任务的数据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "platform.learning.import.messaging-enabled=false")
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12B_DEMO_SEED_ENABLED", matches = "true")
class T12BAssessmentDemoSeedTest {
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String TEACHER = "00000000-0000-4000-8000-000000000102";
    private static final String STUDENT = "00000000-0000-4000-8000-000000000101";
    private static final String COURSE = "00000000-0000-4000-8000-000000000201";
    private static final String LESSON = "00000000-0000-4000-8000-000000000231";
    private static final String ENROLLMENT = id("enrollment");
    private static final String COURSE_VERSION = "t12b-demo-course-version";
    private static final String SOURCE_SYSTEM = "KG_MATH_T10B_V3";
    private static final String OBJECTIVE_SOURCE = "1ibldfJk31DjPp9tLRX";
    private static final String SUBJECTIVE_SOURCE = "11w24M4EsCHrZUgcByl";
    private static final String COMPOSITE_IMAGE_SOURCE = "0FdO1eUNsjnvldzZcy6";
    private static final String TITLE_PREFIX = "[T12B演示]";
    private static final List<String> ASSESSMENTS = List.of(
            id("assessment-practice"), id("assessment-exam"), id("assessment-hidden"),
            id("assessment-grade"), id("assessment-answer"), id("assessment-both"));

    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired LearningAssessmentService assessments;
    @Autowired QuestionService questions;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transactions;

    /** 在 Spring 建立连接前拒绝错误、测试意图不明确或可能属于生产的 Schema。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("LEARNING_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12B demo seed requires edu_platform_learning_dev");
        }
    }

    /**
     * 按环境变量执行一次原子动作；失败会整体回滚，调用方可修复前置条件后安全重试。
     */
    @Test
    void applyRequestedDemoSeedAction() {
        String action = System.getenv().getOrDefault("T12B_DEMO_SEED_ACTION", "").toUpperCase(Locale.ROOT);
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
                        "T12B_DEMO_SEED_ACTION must be INITIALIZE, CLEANUP or REBUILD");
            }
        });
        System.out.printf("T12B demo seed action %s completed; assessments=%d%n", action, ASSESSMENTS.size());
    }

    /** 创建六场固定测评，覆盖草稿恢复、限时考试、四种公布组合、待人工批改与错题重做。 */
    private void initialize() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ensureDemoEnrollment(now);
        int existing = countAssessments();
        if (existing == ASSESSMENTS.size()) {
            validateOwnedSeedRows();
            validateLearnerProjections();
            return;
        }
        if (existing != 0) {
            throw new IllegalStateException("T12B demo seed is partial; run Rebuild after checking local changes");
        }

        QuestionRef objective = resolveQuestion(OBJECTIVE_SOURCE, QuestionModels.Type.SINGLE_CHOICE, false);
        QuestionRef subjective = resolveQuestion(SUBJECTIVE_SOURCE, QuestionModels.Type.SHORT_ANSWER, false);
        QuestionRef composite = resolveQuestion(COMPOSITE_IMAGE_SOURCE, QuestionModels.Type.COMPOSITE, true);

        SeedAssessment practice = assessment(0, "可恢复练习", "含客观题、主观题、组合题与真实图片。",
                AssessmentType.PRACTICE, false, false, false, now, null, null, null);
        addQuestion(practice, objective, 1, "2.00");
        addQuestion(practice, subjective, 2, "5.00");
        addQuestion(practice, composite, 3, "8.00");
        addAttempt(practice, AttemptStatus.DRAFT, "15.00", null, now.minusSeconds(180), null);
        addAnswer(practice, objective, List.of(wrongChoice(objective.snapshot())), null, null,
                GradingStatus.PENDING_MANUAL, null, null, now.minusSeconds(60));

        SeedAssessment exam = assessment(1, "限时考试", "服务端截止时间会在刷新后继续生效。",
                AssessmentType.EXAM, false, false, false, now, now.minusSeconds(300), now.plusSeconds(600), 180);
        addQuestion(exam, objective, 1, "2.00");
        addQuestion(exam, subjective, 2, "5.00");
        addAttempt(exam, AttemptStatus.DRAFT, "7.00", null, now.minusSeconds(30), now.plusSeconds(150));

        SeedAssessment hidden = assessment(2, "成绩答案均未公布", "用于验证提交后不泄漏成绩、答案与解析。",
                AssessmentType.HOMEWORK, true, false, false, now, null, null, null);
        addQuestion(hidden, objective, 1, "2.00");
        addAttempt(hidden, AttemptStatus.GRADED, "2.00", BigDecimal.ZERO, now.minusSeconds(3600), null);
        AnswerRow hiddenWrong = addAnswer(hidden, objective, List.of(wrongChoice(objective.snapshot())),
                BigDecimal.ZERO, false, GradingStatus.AUTO_GRADED, "SYSTEM", now.minusSeconds(3500), now.minusSeconds(3500));
        addWrongQuestion("objective", hidden, objective, hiddenWrong, true, now.minusSeconds(3400));

        SeedAssessment gradeOnly = assessment(3, "仅公布成绩", "学生只能看到得分与判分状态。",
                AssessmentType.HOMEWORK, true, true, false, now, null, null, null);
        addQuestion(gradeOnly, objective, 1, "2.00");
        addAttempt(gradeOnly, AttemptStatus.GRADED, "2.00", new BigDecimal("2.00"),
                now.minusSeconds(3000), null);
        addAnswer(gradeOnly, objective, objective.snapshot().answers(), new BigDecimal("2.00"), true,
                GradingStatus.AUTO_GRADED, "SYSTEM", now.minusSeconds(2900), now.minusSeconds(2900));

        SeedAssessment answerOnly = assessment(4, "仅公布参考答案", "包含一份待人工批改的部分评分提交。",
                AssessmentType.HOMEWORK, true, false, true, now, null, null, null);
        addQuestion(answerOnly, subjective, 1, "5.00");
        addAttempt(answerOnly, AttemptStatus.PARTIALLY_GRADED, "5.00", BigDecimal.ZERO,
                now.minusSeconds(2400), null);
        addAnswer(answerOnly, subjective, List.of("演示主观作答：保留给教师批改"), null, null,
                GradingStatus.PENDING_MANUAL, null, null, now.minusSeconds(2300));

        SeedAssessment both = assessment(5, "成绩答案均已公布", "包含已人工批改答案与主观题错题重做记录。",
                AssessmentType.HOMEWORK, true, true, true, now, null, null, null);
        addQuestion(both, objective, 1, "2.00");
        addQuestion(both, subjective, 2, "5.00");
        addAttempt(both, AttemptStatus.GRADED, "7.00", new BigDecimal("5.00"),
                now.minusSeconds(1800), null);
        addAnswer(both, objective, objective.snapshot().answers(), new BigDecimal("2.00"), true,
                GradingStatus.AUTO_GRADED, "SYSTEM", now.minusSeconds(1700), now.minusSeconds(1700));
        AnswerRow manual = addAnswer(both, subjective, List.of("演示主观作答：步骤不完整"),
                new BigDecimal("3.00"), false, GradingStatus.MANUALLY_GRADED, TEACHER,
                now.minusSeconds(1600), now.minusSeconds(1600));
        repository.insertManualGrade(id("manual-grade-both"), id("manual-grade-request-both"), both.attemptId(),
                manual.id(), TEACHER, 0, new BigDecimal("3.00"), "演示批语：思路正确，步骤不完整。",
                now.minusSeconds(1600));
        addWrongQuestion("subjective", both, subjective, manual, false, now.minusSeconds(1500));

        validateOwnedSeedRows();
        assertThat(jdbc.queryForObject(sqlCount("assessment_question", "assessment_id"), Integer.class,
                ASSESSMENTS.toArray())).isEqualTo(10);
        assertThat(jdbc.queryForObject(sqlCount("assessment_attempt", "assessment_id"), Integer.class,
                ASSESSMENTS.toArray())).isEqualTo(6);
        validateLearnerProjections();
    }

    /** 验证真实服务投影能读取六场测评、四种公布组合和两条有限重做错题。 */
    private void validateLearnerProjections() {
        var page = assessments.learnerAssessments(STUDENT, null, 1, 12);
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.items()).extracting(item -> item.id()).containsExactlyInAnyOrderElementsOf(ASSESSMENTS);
        var practice = assessments.learnerAssessment(STUDENT, ASSESSMENTS.get(0));
        assertThat(practice.questions()).hasSize(3);
        assertThat(assessments.getDraft(STUDENT, id("attempt-0")).assessmentId()).isEqualTo(ASSESSMENTS.get(0));

        var hidden = assessments.learnerResult(STUDENT, id("attempt-2"));
        var gradeOnly = assessments.learnerResult(STUDENT, id("attempt-3"));
        var answerOnly = assessments.learnerResult(STUDENT, id("attempt-4"));
        var both = assessments.learnerResult(STUDENT, id("attempt-5"));
        assertThat(List.of(hidden.gradeVisible(), hidden.answerVisible(), gradeOnly.gradeVisible(),
                gradeOnly.answerVisible(), answerOnly.gradeVisible(), answerOnly.answerVisible(),
                both.gradeVisible(), both.answerVisible()))
                .containsExactly(false, false, true, false, false, true, true, true);
        assertThat(assessments.wrongQuestions(STUDENT, COURSE, 1, 20).total()).isEqualTo(2);
    }

    /** 只删除固定测评及其派生行；任何固定 ID 所属标记异常都会拒绝继续。 */
    private void cleanup() {
        if (countAssessments() == 0) {
            cleanupDemoEnrollment();
            return;
        }
        validateOwnedSeedRows();
        Object[] ids = ASSESSMENTS.toArray();
        jdbc.update("DELETE lir FROM learning_idempotency_request lir JOIN wrong_question w "
                + "ON w.id=lir.resource_id WHERE w.learner_id=? AND w.assessment_id IN (" + placeholders() + ")",
                prepend(STUDENT, ids));
        jdbc.update("DELETE lir FROM learning_idempotency_request lir JOIN assessment_attempt t "
                + "ON t.id=lir.resource_id WHERE t.learner_id=? AND t.assessment_id IN (" + placeholders() + ")",
                prepend(STUDENT, ids));
        jdbc.update("DELETE lir FROM learning_idempotency_request lir JOIN assessment a "
                + "ON a.id=lir.resource_id WHERE a.owner_teacher_id=? AND a.id IN (" + placeholders() + ")",
                prepend(TEACHER, ids));
        jdbc.update("DELETE r FROM wrong_question_redo r JOIN wrong_question w ON w.id=r.wrong_question_id "
                + "WHERE w.learner_id=? AND w.assessment_id IN (" + placeholders() + ")", prepend(STUDENT, ids));
        jdbc.update("DELETE o FROM wrong_question_occurrence o JOIN assessment_attempt t ON t.id=o.attempt_id "
                + "WHERE t.learner_id=? AND t.assessment_id IN (" + placeholders() + ")", prepend(STUDENT, ids));
        jdbc.update("DELETE FROM wrong_question WHERE learner_id=? AND assessment_id IN (" + placeholders() + ")",
                prepend(STUDENT, ids));
        jdbc.update("DELETE g FROM manual_grading_record g JOIN assessment_attempt t ON t.id=g.attempt_id "
                + "WHERE t.learner_id=? AND t.assessment_id IN (" + placeholders() + ")", prepend(STUDENT, ids));
        jdbc.update("DELETE a FROM assessment_answer a JOIN assessment_attempt t ON t.id=a.attempt_id "
                + "WHERE t.learner_id=? AND t.assessment_id IN (" + placeholders() + ")", prepend(STUDENT, ids));
        jdbc.update("DELETE FROM assessment_attempt WHERE learner_id=? AND assessment_id IN (" + placeholders() + ")",
                prepend(STUDENT, ids));
        jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id IN (" + placeholders() + ")", ids);
        jdbc.update("DELETE FROM assessment_question WHERE assessment_id IN (" + placeholders() + ")", ids);
        jdbc.update("DELETE FROM assessment WHERE owner_teacher_id=? AND id IN (" + placeholders() + ")",
                prepend(TEACHER, ids));
        assertThat(countAssessments()).isZero();
        cleanupDemoEnrollment();
    }

    /**
     * 测评详情必须经过学习域报名校验；只在没有现有报名时创建本夹具拥有的固定报名，绝不覆盖用户已有记录。
     */
    private void ensureDemoEnrollment(Instant now) {
        var existing = repository.enrollment(STUDENT, COURSE);
        if (existing.isPresent()) {
            assertThat(existing.orElseThrow().status()).isEqualTo(EnrollmentStatus.ACTIVE);
            return;
        }
        repository.insertEnrollment(new EnrollmentRow(ENROLLMENT, STUDENT, COURSE, COURSE_VERSION,
                EnrollmentStatus.ACTIVE, now, null, now, now));
    }

    /** 只清理由本夹具固定 ID 创建且 learner/course/version 全部匹配的报名。 */
    private void cleanupDemoEnrollment() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT learner_id,course_id,course_version_id FROM enrollment WHERE id=?", ENROLLMENT);
        if (rows.isEmpty()) return;
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("learner_id")).isEqualTo(STUDENT);
        assertThat(rows.get(0).get("course_id")).isEqualTo(COURSE);
        assertThat(rows.get(0).get("course_version_id")).isEqualTo(COURSE_VERSION);
        jdbc.update("DELETE FROM enrollment WHERE id=? AND learner_id=? AND course_id=?", ENROLLMENT, STUDENT, COURSE);
    }

    /** 解析稳定来源引用，并校验题型与图片要求，防止题库演进后静默换成错误演示题。 */
    private QuestionRef resolveQuestion(String externalId, QuestionModels.Type expectedType, boolean requireImage) {
        List<QuestionRef> refs = jdbc.query("SELECT a.id question_id,v.id version_id "
                        + "FROM question_legacy_source_ref r JOIN question_aggregate a ON a.id=r.question_id "
                        + "JOIN question_version v ON v.id=a.current_version_id "
                        + "WHERE r.source_system=? AND r.external_id=? AND r.dry_run=FALSE AND r.outcome='IMPORTED' "
                        + "AND a.scope='SYSTEM' AND a.status='ACTIVE'",
                (rs, row) -> new QuestionRef(rs.getString("question_id"), rs.getString("version_id"), null),
                SOURCE_SYSTEM, externalId);
        if (refs.size() != 1) {
            throw new IllegalStateException("Expected one active SYSTEM question for source " + externalId);
        }
        QuestionRef ref = refs.get(0);
        QuestionDtos.VersionResponse snapshot = questions.version(TEACHER, ref.questionId(), ref.versionId());
        if (snapshot.type() != expectedType) {
            throw new IllegalStateException("Unexpected question type for source " + externalId);
        }
        boolean hasImage = !snapshot.media().isEmpty()
                || snapshot.children().stream().anyMatch(child -> !child.media().isEmpty());
        if (requireImage && !hasImage) {
            throw new IllegalStateException("Composite demo source no longer contains question media");
        }
        return new QuestionRef(ref.questionId(), ref.versionId(), snapshot);
    }

    /** 创建一场测评与课程分配；结果类测评直接冻结为已关闭且结算完成。 */
    private SeedAssessment assessment(int index, String title, String description, AssessmentType type,
            boolean closed, boolean gradePublished, boolean answerPublished, Instant now,
            Instant opensAt, Instant closesAt, Integer timeLimitSeconds) {
        String assessmentId = ASSESSMENTS.get(index);
        Instant publishedAt = now.minusSeconds(7200 - index * 300L);
        repository.insertAssessment(new AssessmentRow(assessmentId, TEACHER, COURSE, LESSON,
                TITLE_PREFIX + title, description, type, AssessmentStatus.PUBLISHED, GradePublicationPolicy.MANUAL,
                AnswerPublicationPolicy.NEVER, closed ? AssessmentCloseStatus.CLOSED : AssessmentCloseStatus.OPEN,
                closed ? AssessmentSettlementStatus.COMPLETED : AssessmentSettlementStatus.NOT_STARTED,
                closed ? publishedAt.plusSeconds(600) : null, 0, 0, null, null,
                gradePublished ? ResultPublicationStatus.PUBLISHED : ResultPublicationStatus.UNPUBLISHED,
                answerPublished ? ResultPublicationStatus.PUBLISHED : ResultPublicationStatus.UNPUBLISHED,
                gradePublished ? publishedAt.plusSeconds(1200) : null,
                answerPublished ? publishedAt.plusSeconds(1200) : null,
                opensAt, closesAt, timeLimitSeconds, closed ? 4 : 1, publishedAt, null, publishedAt, now));
        repository.ensureCourseAssignment(id("assignment-" + index), assessmentId, COURSE, publishedAt);
        return new SeedAssessment(index, assessmentId, new LinkedHashMap<>(), null);
    }

    /** 向测评绑定精确不可变版本快照，并记住固定题目行 ID 供作答和错题引用。 */
    private void addQuestion(SeedAssessment assessment, QuestionRef ref, int sequence, String points) {
        String rowId = id("assessment-question-" + assessment.index() + "-" + sequence);
        repository.insertAssessmentQuestion(new AssessmentQuestionRow(rowId, assessment.id(), ref.questionId(),
                ref.versionId(), sequence, new BigDecimal(points), ref.snapshot(), Instant.now()));
        assessment.questions().put(ref.versionId(), rowId);
    }

    /** 为固定学生创建一份 attempt；DRAFT 支持恢复，结果 attempt 则保留提交与评分时间。 */
    private void addAttempt(SeedAssessment assessment, AttemptStatus status, String totalPoints, BigDecimal score,
            Instant startedAt, Instant deadlineAt) {
        String attemptId = id("attempt-" + assessment.index());
        boolean submitted = status != AttemptStatus.DRAFT;
        Instant submittedAt = submitted ? startedAt.plusSeconds(100) : null;
        Instant gradedAt = status == AttemptStatus.GRADED ? startedAt.plusSeconds(200) : null;
        repository.insertAttempt(new AttemptRow(attemptId, assessment.id(), STUDENT, status, 1,
                null, null, submitted ? id("submit-request-" + assessment.index()) : null,
                submitted ? "a".repeat(64) : null, score, new BigDecimal(totalPoints), startedAt, deadlineAt,
                submittedAt, gradedAt, gradedAt, startedAt, Instant.now()));
        assessment.attemptId(attemptId);
    }

    /** 写入固定答案行；主观待批改允许 score/correct/grader/gradedAt 为空。 */
    private AnswerRow addAnswer(SeedAssessment assessment, QuestionRef ref, List<String> answer,
            BigDecimal score, Boolean correct, GradingStatus status, String grader, Instant gradedAt, Instant updatedAt) {
        String rowId = assessment.questions().get(ref.versionId());
        String answerId = id("answer-" + assessment.index() + "-" + rowId);
        Instant createdAt = assessment.index() < 2 ? updatedAt.minusSeconds(30) : updatedAt;
        jdbc.update("INSERT INTO assessment_answer(id,attempt_id,assessment_question_id,answer_json,score,correct,"
                        + "grading_status,revision,graded_by,graded_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                answerId, assessment.attemptId(), rowId, json(answer), score, correct, status.name(),
                status == GradingStatus.MANUALLY_GRADED ? 1 : 0, grader, timestamp(gradedAt),
                timestamp(createdAt), timestamp(updatedAt));
        return repository.answer(assessment.attemptId(), rowId).orElseThrow();
    }

    /** 创建一条错题和一次不可变重做；客观题带判分，主观题显式保留 null 判分语义。 */
    private void addWrongQuestion(String suffix, SeedAssessment assessment, QuestionRef ref, AnswerRow answer,
            boolean objective, Instant now) {
        String wrongId = id("wrong-" + suffix);
        String assessmentQuestionId = assessment.questions().get(ref.versionId());
        repository.accumulateWrongQuestion(wrongId, STUDENT, COURSE, assessment.id(), ref.versionId(),
                assessment.attemptId(), now);
        repository.markWrongOccurrence(assessment.attemptId(), assessmentQuestionId, now);
        repository.insertWrongQuestionRedo(new RedoRow(id("redo-" + suffix), wrongId, STUDENT,
                id("redo-request-" + suffix), (objective ? "b" : "c").repeat(64), 1,
                objective ? ref.snapshot().answers() : List.of("演示主观重做：等待自我核对"),
                objective ? new BigDecimal("2.00") : null, objective ? true : null, now.plusSeconds(60)), 0,
                now.plusSeconds(60));
    }

    /** 固定 ID 存在时先校验 owner、课程和标题标记；失败时不执行覆盖或清理。 */
    private void validateOwnedSeedRows() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,owner_teacher_id,course_id,title FROM assessment "
                + "WHERE id IN (" + placeholders() + ")", ASSESSMENTS.toArray());
        assertThat(rows).hasSize(countAssessments());
        rows.forEach(row -> {
            assertThat(row.get("owner_teacher_id")).isEqualTo(TEACHER);
            assertThat(row.get("course_id")).isEqualTo(COURSE);
            assertThat(String.valueOf(row.get("title"))).startsWith(TITLE_PREFIX);
        });
    }

    private int countAssessments() {
        return jdbc.queryForObject(sqlCount("assessment", "id"), Integer.class, ASSESSMENTS.toArray());
    }

    private static String wrongChoice(QuestionDtos.VersionResponse snapshot) {
        return snapshot.options().stream().map(QuestionDtos.OptionInput::key)
                .filter(key -> snapshot.answers().stream().noneMatch(answer -> answer.equalsIgnoreCase(key)))
                .findFirst().orElseThrow(() -> new IllegalStateException("Objective demo question has no wrong option"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot serialize T12B demo answer", error);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String sqlCount(String table, String column) {
        return "SELECT COUNT(*) FROM " + table + " WHERE " + column + " IN (" + placeholders() + ")";
    }

    private static String placeholders() {
        return String.join(",", java.util.Collections.nCopies(ASSESSMENTS.size(), "?"));
    }

    private static Object[] prepend(Object first, Object[] remaining) {
        List<Object> values = new ArrayList<>(remaining.length + 1);
        values.add(first);
        values.addAll(List.of(remaining));
        return values.toArray();
    }

    private static String id(String name) {
        return UUID.nameUUIDFromBytes(("edu-platform:t12b-demo:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record QuestionRef(String questionId, String versionId, QuestionDtos.VersionResponse snapshot) {}

    private static final class SeedAssessment {
        private final int index;
        private final String id;
        private final Map<String, String> questions;
        private String attemptId;

        private SeedAssessment(int index, String id, Map<String, String> questions, String attemptId) {
            this.index = index;
            this.id = id;
            this.questions = questions;
            this.attemptId = attemptId;
        }

        private int index() { return index; }
        private String id() { return id; }
        private Map<String, String> questions() { return questions; }
        private String attemptId() { return attemptId; }
        private void attemptId(String value) { attemptId = value; }
    }
}
