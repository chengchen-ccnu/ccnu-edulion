package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.identity.LearnerDirectoryClient;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.service.LearningAssessmentService;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.learning.web.LearningDtos.AnswerPublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.AssessmentCloseStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentSettlementStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentStatus;
import com.eduplatform.learning.web.LearningDtos.AssessmentType;
import com.eduplatform.learning.web.LearningDtos.EnrollmentStatus;
import com.eduplatform.learning.web.LearningDtos.GradePublicationPolicy;
import com.eduplatform.learning.web.LearningDtos.HomeworkLatePolicy;
import com.eduplatform.learning.web.LearningDtos.ResultPublicationStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * T11C.1 合并后的 Learning 成绩册上限真实验收。
 *
 * <p>只在明确的 local/test Learning Schema 上创建本类固定前缀的 5,000 名学习者、50 场
 * HOMEWORK 和 50 场 EXAM，以及已完成但无提交明细的成绩册边界事实。初始化可重跑且不会
 * 重复，finally 按固定课程/测评/报名事实逆序清理，绝不触碰现有演示 Seed。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "platform.learning.import.messaging-enabled=false",
        "platform.learning.progress-buffer.enabled=false",
        "platform.learning.assessment-settlement-poll-delay=1h",
        "platform.learning.iam.base-url=${IAM_SERVICE_BASE_URL:http://127.0.0.1:8081}",
        "platform.learning.iam.service-token=${IAM_INTERNAL_SERVICE_TOKEN:}",
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${LEARNING_MYSQL_DATABASE}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=${MYSQL_SERVER_TIMEZONE:Asia/Shanghai}&useSSL=${MYSQL_SSL_ENABLED:false}&allowPublicKeyRetrieval=${MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL:true}&rewriteBatchedStatements=true"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T11C1_GRADEBOOK_CAPACITY_ENABLED", matches = "true")
class T11C1GradebookCapacityAcceptanceTest {
    private static final String DATABASE = System.getenv("LEARNING_MYSQL_DATABASE");
    private static final java.util.Set<String> ALLOWED_DATABASES = java.util.Set.of(
            "edu_platform_learning_t11c1_acceptance",
            "edu_platform_learning_t11c1_acceptance_v2", "edu_platform_learning_t11c1_acceptance_v3",
            "edu_platform_learning_t11c1_acceptance_v4", "edu_platform_learning_t11c1_acceptance_v5");
    private static final String TEACHER = "00000000-0000-4000-8000-000000000102";
    private static final String OTHER_TEACHER = "00000000-0000-4000-8000-000000000104";
    private static final String COURSE = "00000000-0000-4c11-8c01-000000000002";
    private static final String COURSE_VERSION = "t11c1-capacity-v2-course-version";
    private static final String TITLE_PREFIX = "[T11C.1容量验收-v2]";
    private static final int LEARNER_COUNT = 5_000;
    private static final int ASSESSMENT_COUNT = 100;
    private static final int HOMEWORK_COUNT = 50;
    private static final int BATCH_SIZE = 5_000;
    private static final List<String> ASSESSMENT_IDS = java.util.stream.IntStream.range(0, ASSESSMENT_COUNT)
            .mapToObj(index -> id("assessment-" + index)).toList();
    private static final List<String> LEARNER_IDS = buildLearnerIds();

    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    @Autowired LearningAssessmentService service;
    @Autowired LearnerDirectoryClient directory;
    @Autowired LearningProperties learningProperties;
    @Autowired Environment environment;
    @Autowired TransactionTemplate transactions;
    @MockitoBean CourseFactsClient courses;

    /** 在应用创建数据库连接前阻止误连非专用 Schema。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry properties) {
        if (DATABASE == null || !ALLOWED_DATABASES.contains(DATABASE)) {
            throw new IllegalStateException("T11C.1 capacity acceptance requires an explicit local/test Learning Schema");
        }
        // 验收脚本启动的是真实 IAM HTTP；显式绑定两项配置，避免运行器带入默认 8081。
        properties.add("platform.learning.iam.base-url", () -> System.getenv().getOrDefault(
                "IAM_SERVICE_BASE_URL", "http://127.0.0.1:18081"));
        properties.add("platform.learning.iam.service-token", () -> System.getenv().getOrDefault(
                "IAM_INTERNAL_SERVICE_TOKEN", ""));
    }

    /** 验证容量验收的 Spring 运行时确实使用真实 IAM HTTP，而不是测试替身或默认端口。 */
    @Test
    void realIamSpringBeanUsesConfiguredEndpoint() {
        assertThat(environment.getProperty("platform.learning.iam.base-url")).isEqualTo(System.getenv().getOrDefault(
                "IAM_SERVICE_BASE_URL", "http://127.0.0.1:18081"));
        assertThat(learningProperties.iam().baseUrl()).isEqualTo(System.getenv().getOrDefault(
                "IAM_SERVICE_BASE_URL", "http://127.0.0.1:18081"));
        assertThat(learningProperties.iam().serviceToken()).isNotBlank();
        LearnerDirectoryClient.NicknameLookup lookup = directory.nicknames(
                List.of("00000000-0000-4000-8000-000000000101"), "t11c1-capacity-iam-probe");
        assertThat(lookup.dependencyFailed()).isFalse();
        assertThat(lookup.values()).containsEntry("00000000-0000-4000-8000-000000000101", "测试学生");
    }

    /** 验证 5,000 行、100 场、304 列、BOM、真实 IAM 批量查询和 owner 越权。 */
    @Test
    void gradebookAndWideCsvMeetFiveThousandByHundredBoundary() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        when(courses.availability(anyString(),org.mockito.ArgumentMatchers.anyLong(),anyString()))
                .thenAnswer(call -> new com.eduplatform.learning.course.CourseAvailability(call.getArgument(0),TEACHER,0,false,"support@example.invalid",java.util.List.of(),false));
        CourseFactsClient.CourseFact ownedCourse = new CourseFactsClient.CourseFact(COURSE, COURSE_VERSION,
                "ENROLLMENT_REQUIRED", TEACHER, java.util.Set.of());
        when(courses.requireOwnedPublished(eq(COURSE), eq(TEACHER), anyString())).thenReturn(ownedCourse);

        try {
            ensureFixture();
            ensureFixture();

            Measurement<LearningDtos.Gradebook> gradebookMeasurement = measure("gradebook", () ->
                    service.teacherGradebook(TEACHER, COURSE, null, null, null, null, null, null,
                            1, 100, LearningDtos.GradebookSortField.STUDENT_NUMBER, false,
                            "t11c1-capacity-gradebook"));
            LearningDtos.Gradebook gradebook = gradebookMeasurement.value();
            assertThat(gradebook.aggregateStatus()).isEqualTo(LearningDtos.GradebookAggregateStatus.READY);
            assertThat(gradebook.total()).isEqualTo(LEARNER_COUNT);
            assertThat(gradebook.rows()).hasSize(100);
            assertThat(gradebook.assessments()).hasSize(ASSESSMENT_COUNT);
            assertThat(gradebook.assessments()).extracting(LearningDtos.GradebookAssessment::type)
                    .containsExactlyElementsOf(expectedAssessmentTypes());
            assertThat(gradebook.rows().get(0).nickname()).isEqualTo("测试学生");
            assertThat(gradebook.rows().stream().filter(row -> row.nickname() == null).count())
                    .isEqualTo(99L);
            assertThat(gradebookMeasurement.elapsedMillis()).isLessThan(300_000L);

            Measurement<CsvProbeOutputStream> csvMeasurement = measure("csv", () -> captureCsv(UUID.randomUUID(),
                    "t11c1-capacity-csv"));
            CsvProbeOutputStream csv = csvMeasurement.value();
            csv.assertValid(LEARNER_COUNT + 1, 2 + ASSESSMENT_COUNT * 3 + 2);
            assertThat(csvMeasurement.elapsedMillis()).isLessThan(300_000L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gradebook_export_audit WHERE operator_id=? AND course_id=?",
                    Integer.class, TEACHER, COURSE)).isEqualTo(1);

            assertThat(repository.teacherGradebookAssessments(COURSE, OTHER_TEACHER)).isEmpty();
            when(courses.requireOwnedPublished(eq(COURSE), eq(OTHER_TEACHER), anyString()))
                    .thenThrow(new QuestionException(HttpStatus.NOT_FOUND, "COURSE.NOT_FOUND", "课程不存在"));
            assertThatThrownBy(() -> service.teacherGradebook(OTHER_TEACHER, COURSE, null, null, null, null,
                    null, null, 1, 20, LearningDtos.GradebookSortField.STUDENT_NUMBER, false,
                    "t11c1-capacity-owner-deny"))
                    .isInstanceOfSatisfying(QuestionException.class,
                            error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        finally {
            cleanupFixture();
        }
    }

    /** 中断或失败后的显式清理入口；只删除本类确定性 Fixture，不影响现有演示 Seed。 */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "T11C1_CAPACITY_CLEANUP_ENABLED", matches = "true")
    void cleanupFixedFixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        cleanupFixture();
    }

    /** 幂等创建完整 Fixture；已有完整行直接复用，半成品明确失败而不覆盖未知数据。 */
    private void ensureFixture() {
        long assessments = jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id IN ("
                + placeholders(ASSESSMENT_COUNT) + ")", Long.class, ASSESSMENT_IDS.toArray());
        if (assessments == ASSESSMENT_COUNT) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE course_id=?", Long.class, COURSE))
                    .isEqualTo((long) LEARNER_COUNT);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_student_number WHERE course_id=?", Long.class, COURSE))
                    .isEqualTo((long) LEARNER_COUNT);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE assessment_id IN ("
                    + placeholders(ASSESSMENT_COUNT) + ")", Long.class, ASSESSMENT_IDS.toArray()))
                    .isEqualTo((long) LEARNER_COUNT * ASSESSMENT_COUNT);
            return;
        }
        if (assessments != 0) throw new IllegalStateException("T11C.1 capacity Fixture is partial; cleanup is required");
        seedFixture();
    }

    /** 批量写入报名、稳定课程编号和 100 场已关闭且结算完成的测评边界事实。 */
    private void seedFixture() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant publishedAt = now.minusSeconds(7_200);
        Instant closesAt = now.minusSeconds(3_600);
        transactions.executeWithoutResult(status -> seedEnrollmentAndNumbers(now));

        for (int index = 0; index < ASSESSMENT_COUNT; index++) {
            int assessmentIndex = index;
            transactions.executeWithoutResult(status -> seedAssessment(assessmentIndex, now, publishedAt, closesAt));
        }
        transactions.executeWithoutResult(status -> seedParticipantsFromCourseNumbers());
    }

    /** 在单独事务内写入报名和稳定学号，避免大规模测评事实回滚时长期持有课程锁。 */
    private void seedEnrollmentAndNumbers(Instant now) {
        insertBatches("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',?,?,?)",
                LEARNER_IDS, learner -> new Object[] { id("enrollment-" + learner), learner, COURSE, COURSE_VERSION,
                        Timestamp.from(now.minusSeconds(7_100)), Timestamp.from(now.minusSeconds(7_100)), Timestamp.from(now) });
        jdbc.update("INSERT INTO course_student_number_counter(course_id,next_number) VALUES(?,?)", COURSE, LEARNER_COUNT + 1);
        List<Object[]> numberRows = new ArrayList<>(LEARNER_COUNT);
        for (int index = 0; index < LEARNER_COUNT; index++) {
            String learner = LEARNER_IDS.get(index);
            numberRows.add(new Object[] { id("number-" + learner), COURSE, learner, index + 1,
                    Timestamp.from(now) });
        }
        insertRows("INSERT INTO course_student_number(id,course_id,learner_id,student_number,created_at) VALUES(?,?,?,?,?)",
                numberRows);
    }

    /** 每场测评单独提交课程归属和测评状态，保证中断后能精确重试/清理。 */
    private void seedAssessment(int index, Instant now, Instant publishedAt, Instant closesAt) {
        AssessmentType type = index < HOMEWORK_COUNT ? AssessmentType.HOMEWORK : AssessmentType.EXAM;
        Instant assessmentPublishedAt = publishedAt.plusSeconds(index);
        AssessmentRow assessment = new AssessmentRow(ASSESSMENT_IDS.get(index), TEACHER, COURSE, null,
                TITLE_PREFIX + (index == 0 ? " =SUM(A1)" : " 测评-" + (index + 1)),
                "T11C.1 5,000×100 local/test acceptance data.", type, AssessmentStatus.PUBLISHED,
                GradePublicationPolicy.MANUAL, AnswerPublicationPolicy.NEVER, AssessmentCloseStatus.CLOSED,
                AssessmentSettlementStatus.COMPLETED, now.minusSeconds(1_800), 0, 0, null, null,
                ResultPublicationStatus.UNPUBLISHED, ResultPublicationStatus.UNPUBLISHED, null, null,
                assessmentPublishedAt, closesAt, type == AssessmentType.EXAM ? 3_600 : null,
                HomeworkLatePolicy.DISALLOW, null, 1, 0, assessmentPublishedAt, null, assessmentPublishedAt, now);
        repository.insertAssessment(assessment);
        repository.ensureCourseAssignment(id("assignment-" + index), assessment.id(), COURSE, now);
    }

    /** 从已写入的课程学生编号集合式生成 500,000 条参与者，避免测试端构造超大 SQL 参数包。 */
    private void seedParticipantsFromCourseNumbers() {
        jdbc.update("INSERT INTO assessment_participant(id,assessment_id,learner_id,enrollment_status,submission_status,"
                + "grading_status,attempts_used,final_attempt_id,last_submitted_at,late_submission,revision,created_at,updated_at) "
                + "SELECT UUID(),a.id,n.learner_id,'ACTIVE','MISSING','NOT_REQUIRED',0,NULL,NULL,FALSE,0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3) "
                + "FROM assessment a JOIN course_student_number n ON n.course_id=? WHERE a.course_id=? AND a.id IN ("
                + placeholders(ASSESSMENT_COUNT) + ")", buildParticipantSeedArguments());
    }

    private Object[] buildParticipantSeedArguments() {
        List<Object> arguments = new ArrayList<>(ASSESSMENT_COUNT + 2);
        arguments.add(COURSE);
        arguments.add(COURSE);
        arguments.addAll(ASSESSMENT_IDS);
        return arguments.toArray();
    }

    /** 按固定 ID 逆序清理本次 Fixture；演示 Seed 使用不同 ID 和标题前缀，不会被触碰。 */
    private void cleanupFixture() {
        // 该测试只允许运行在全新 local/test Schema；先确认参与者表没有非本 Fixture 数据，再快速清空它。
        long allParticipants = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant", Long.class);
        long fixtureParticipants = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE assessment_id IN ("
                + placeholders(ASSESSMENT_COUNT) + ")", Long.class, ASSESSMENT_IDS.toArray());
        assertThat(fixtureParticipants).isEqualTo(allParticipants);
        jdbc.execute("TRUNCATE TABLE assessment_participant");
        // 其他表按固定 ID/课程删除，仍不会覆盖演示 Seed 或任何未标记记录。
        for (String assessmentId : ASSESSMENT_IDS) {
            jdbc.update("DELETE FROM assessment_attempt WHERE assessment_id=?", assessmentId);
            jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id=?", assessmentId);
            jdbc.update("DELETE FROM assessment WHERE id=?", assessmentId);
        }
        jdbc.update("DELETE FROM course_student_number WHERE course_id=?", COURSE);
        jdbc.update("DELETE FROM course_student_number_counter WHERE course_id=?", COURSE);
        jdbc.update("DELETE FROM enrollment WHERE course_id=?", COURSE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id IN (" + placeholders(ASSESSMENT_COUNT) + ")",
                Long.class, ASSESSMENT_IDS.toArray())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE course_id=?", Long.class, COURSE)).isZero();
    }

    /** 测试端收集流用于验证 BOM、行列数和公式安全；生产 HTTP 响应不会走该收集器。 */
    private CsvProbeOutputStream captureCsv(UUID requestId, String traceId) {
        CsvProbeOutputStream output = new CsvProbeOutputStream();
        service.streamGradebook(TEACHER, COURSE, null, null, null, null, null, null, requestId, traceId, output);
        return output;
    }

    /** 使用固定批量大小限制 JDBC 参数与单次事务内存，保留可重复执行的确定性行数。 */
    private void insertBatches(String sql, List<String> keys, java.util.function.Function<String, Object[]> values) {
        for (int from = 0; from < keys.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, keys.size());
            List<Object[]> batch = new ArrayList<>(to - from);
            for (int index = from; index < to; index++) batch.add(values.apply(keys.get(index)));
            insertMultiRow(sql, batch);
        }
    }

    /** 批量写入已经按索引构造的固定行，控制单次 JDBC 参数数量。 */
    private void insertRows(String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, rows.size());
            insertMultiRow(sql, rows.subList(from, to));
        }
    }

    /** 将每批最多 5,000 行拼成一条参数化多值 INSERT，避免 MySQL 驱动退化为逐行网络往返。 */
    private void insertMultiRow(String singleRowSql, List<Object[]> rows) {
        int valuesIndex = singleRowSql.indexOf("VALUES");
        if (valuesIndex < 0) throw new IllegalArgumentException("capacity Fixture SQL must contain VALUES");
        String prefix = singleRowSql.substring(0, valuesIndex + "VALUES".length());
        String rowTemplate = singleRowSql.substring(valuesIndex + "VALUES".length()).trim();
        StringBuilder sql = new StringBuilder(prefix);
        List<Object> arguments = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) sql.append(',');
            sql.append(rowTemplate);
            for (Object argument : rows.get(index)) arguments.add(argument);
        }
        jdbc.update(sql.toString(), arguments.toArray());
    }

    /** 测量端到端调用耗时和 JVM 堆峰值；结果只输出统计，不输出 CSV 内容或个人数据。 */
    private static <T> Measurement<T> measure(String label, Supplier<T> action) {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong(heapUsed(memory));
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                peak.accumulateAndGet(heapUsed(memory), Math::max);
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }, "t11c1-" + label + "-heap-sampler");
        sampler.start();
        long startedAt = System.nanoTime();
        try {
            T value = action.get();
            return new Measurement<>(value, Duration.ofNanos(System.nanoTime() - startedAt).toMillis(), peak.get());
        }
        finally {
            running.set(false);
            sampler.interrupt();
            try { sampler.join(2_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            System.out.printf(Locale.ROOT, "T11C.1 capacity %s durationMs=%d peakHeapMiB=%.1f%n", label,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(), peak.get() / 1024d / 1024d);
        }
    }

    private static long heapUsed(MemoryMXBean memory) { return memory.getHeapMemoryUsage().getUsed(); }

    private static List<AssessmentType> expectedAssessmentTypes() {
        List<AssessmentType> values = new ArrayList<>(ASSESSMENT_COUNT);
        for (int index = 0; index < HOMEWORK_COUNT; index++) values.add(AssessmentType.HOMEWORK);
        for (int index = HOMEWORK_COUNT; index < ASSESSMENT_COUNT; index++) values.add(AssessmentType.EXAM);
        return values;
    }

    private static List<String> buildLearnerIds() {
        List<String> values = new ArrayList<>(LEARNER_COUNT);
        values.add("00000000-0000-4000-8000-000000000101");
        for (int index = 1; index < LEARNER_COUNT; index++) values.add(id("learner-" + index));
        return List.copyOf(values);
    }

    private static String id(String value) {
        return UUID.nameUUIDFromBytes(("t11c1-capacity-v2:" + value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }

    private record Measurement<T>(T value, long elapsedMillis, long peakHeapBytes) {}

    /** 流式验收只保留 BOM、行列计数和公式安全标志，不在测试端重新构造完整 CSV byte[]。 */
    private static final class CsvProbeOutputStream extends OutputStream {
        private final byte[] prefix = new byte[3];
        private int prefixSize;
        private int lineCount;
        private int headerColumns;
        private int currentColumns = 1;
        private long byteCount;
        private final StringBuilder asciiWindow = new StringBuilder();

        @Override
        public void write(int value) throws IOException {
            int unsigned = value & 0xff;
            byteCount++;
            if (prefixSize < prefix.length) prefix[prefixSize++] = (byte) unsigned;
            if (unsigned < 128) {
                asciiWindow.append((char) unsigned);
                if (asciiWindow.length() > 32) asciiWindow.delete(0, asciiWindow.length() - 32);
            }
            if (unsigned == ',') currentColumns++;
            if (unsigned == '\n') {
                if (lineCount == 0) headerColumns = currentColumns;
                lineCount++;
                currentColumns = 1;
            }
        }

        private void assertValid(int expectedRowsIncludingHeader, int expectedColumns) {
            assertThat(byteCount).isGreaterThan(3L);
            assertThat(prefix).containsExactly((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
            assertThat(lineCount).isEqualTo(expectedRowsIncludingHeader);
            assertThat(headerColumns).isEqualTo(expectedColumns);
            assertThat(asciiWindow.toString()).doesNotContain("\"=SUM(A1)\"");
        }
    }

}
