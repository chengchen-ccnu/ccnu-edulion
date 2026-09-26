package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.service.TeacherAnalyticsService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * T12C.2 本地联调夹具：只在 Learning 专用开发库为既有固定演示课程增加一场临时作业。
 * 输入是明确的环境开关与释放文件；输出是个人已批/全班待批投影，等待浏览器读取后按精确 ID 清理。
 * 不修改 Course 课程、发布版本、媒体及既有测评；异常和超时也在 finally 清理本测试写入的行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "platform.learning.import.messaging-enabled=false")
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12C2_REAL_ACCEPTANCE", matches = "true")
class T12C2GradedProjectionRealAcceptanceTest {
    private static final String COURSE = "00000000-0000-4000-8000-000000000201";
    private static final String OWNER = "00000000-0000-4000-8000-000000000102";
    private static final String GRADED = "00000000-0000-4000-8000-000000000101";
    private static final String PENDING = "00000000-0000-4000-8000-000000000102";
    private static final String ASSESSMENT = "00000000-0000-4000-8000-0000000c2201";
    private static final String GRADED_ATTEMPT = "00000000-0000-4000-8000-0000000c2202";
    private static final String PENDING_ATTEMPT = "00000000-0000-4000-8000-0000000c2203";
    private static final String GRADED_PARTICIPANT = "00000000-0000-4000-8000-0000000c2204";
    private static final String PENDING_PARTICIPANT = "00000000-0000-4000-8000-0000000c2205";
    private static final String TITLE = "[T12C.2临时验收]个人已批班级待批";

    @Autowired JdbcTemplate jdbc;
    @Autowired TeacherAnalyticsService analytics;

    /** 连接前限定专用 Schema 和 Redis DB，不允许共享配置名称暗示可写其他环境。 */
    @DynamicPropertySource
    static void dedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_learning_dev".equals(System.getenv("LEARNING_MYSQL_DATABASE"))
                || !"4".equals(System.getenv("LEARNING_REDIS_DATABASE")))
            throw new IllegalStateException("T12C.2 acceptance requires dedicated Learning dev storage");
    }

    /** 插入一名已批和一名待批的最终 attempt，验证服务投影，再等待真实浏览器读取并精确回收。 */
    @Test
    void personalScoreVisibleBeforeClassAggregate() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_learning_dev");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=?", Integer.class, ASSESSMENT)).isZero();
        for (String learner : new String[] { GRADED, PENDING }) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE course_id=? AND learner_id=? AND status='ACTIVE'",
                    Integer.class, COURSE, learner)).isEqualTo(1);
        }
        String release = System.getenv("T12C2_ACCEPTANCE_RELEASE_FILE");
        if (release == null || release.isBlank()) throw new IllegalStateException("Missing release file path");
        Path releaseFile = Path.of(release);
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        Date day = Date.valueOf(LocalDate.ofInstant(now, ZoneId.of("Asia/Shanghai")));
        try {
            jdbc.update("INSERT INTO assessment(id,owner_teacher_id,course_id,lesson_id,title,description,"
                    + "assessment_type,status,answer_publication_policy,close_status,settlement_status,"
                    + "published_at,created_at,updated_at,ownership_type) "
                    + "VALUES(?,?,?,?,?,'T12C2 local acceptance','HOMEWORK','PUBLISHED','NEVER','CLOSED','COMPLETED',?,?,?,'COURSE')",
                    ASSESSMENT, OWNER, COURSE, null, TITLE, timestamp, timestamp, timestamp);
            attempt(GRADED_ATTEMPT, GRADED, "GRADED", 80, day, timestamp);
            attempt(PENDING_ATTEMPT, PENDING, "PARTIALLY_GRADED", 30, day, timestamp);
            participant(GRADED_PARTICIPANT, GRADED, GRADED_ATTEMPT, "GRADED", timestamp);
            participant(PENDING_PARTICIPANT, PENDING, PENDING_ATTEMPT, "PARTIALLY_GRADED", timestamp);

            var personal = analytics.studentTasks(OWNER, COURSE, GRADED, 1, 20, "t12c2-real").items().stream()
                    .filter(item -> ASSESSMENT.equals(item.assessmentId())).findFirst().orElseThrow();
            assertThat(personal.resultsReady()).isFalse();
            assertThat(personal.rawScore()).isEqualByComparingTo("80");
            assertThat(personal.scorePercent()).isEqualByComparingTo("80");
            assertThat(personal.rank()).isNull();
            var classTask = analytics.courseTasks(OWNER, COURSE, 1, 20, "t12c2-real").items().stream()
                    .filter(item -> ASSESSMENT.equals(item.assessmentId())).findFirst().orElseThrow();
            assertThat(classTask.resultsReady()).isFalse();
            assertThat(classTask.averageScorePercent()).isNull();
            System.out.println("T12C2_REAL_FIXTURE_READY");
            long deadline = System.currentTimeMillis() + 180_000;
            while (!Files.exists(releaseFile) && System.currentTimeMillis() < deadline) Thread.sleep(250);
            assertThat(Files.exists(releaseFile)).as("browser acceptance release file").isTrue();
        } finally {
            jdbc.update("DELETE FROM assessment_participant WHERE id IN (?,?)", GRADED_PARTICIPANT, PENDING_PARTICIPANT);
            jdbc.update("DELETE FROM assessment_attempt WHERE id IN (?,?)", GRADED_ATTEMPT, PENDING_ATTEMPT);
            jdbc.update("DELETE FROM assessment WHERE id=? AND title=?", ASSESSMENT, TITLE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=?", Integer.class, ASSESSMENT)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt WHERE id IN (?,?)", Integer.class,
                    GRADED_ATTEMPT, PENDING_ATTEMPT)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE id IN (?,?)", Integer.class,
                    GRADED_PARTICIPANT, PENDING_PARTICIPANT)).isZero();
            System.out.println("T12C2_REAL_FIXTURE_REMAINING=0");
        }
    }

    /** 写入本夹具指定学生的一次最终提交；状态和分数用于区分个人已批与仍待批。 */
    private void attempt(String id, String learner, String status, int score, Date day, Timestamp now) {
        jdbc.update("INSERT INTO assessment_attempt(id,assessment_id,learner_id,status,revision,score,total_points,"
                + "submitted_at,submission_day,attempt_sequence,created_at,updated_at) VALUES(?,?,?,?,1,?,100,?,?,1,?,?)",
                id, ASSESSMENT, learner, status, score, now, day, now, now);
    }

    /** 将上述提交关联到 ACTIVE 应交人；批改状态决定服务端的成绩可见性。 */
    private void participant(String id, String learner, String attempt, String grading, Timestamp now) {
        jdbc.update("INSERT INTO assessment_participant(id,assessment_id,learner_id,enrollment_status,submission_status,"
                + "grading_status,final_attempt_id,last_submitted_at,created_at,updated_at) "
                + "VALUES(?,?,?,'ACTIVE','SUBMITTED',?,?,?,?,?)", id, ASSESSMENT, learner, grading, attempt, now, now, now);
    }
}
