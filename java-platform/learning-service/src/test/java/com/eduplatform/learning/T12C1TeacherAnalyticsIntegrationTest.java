package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.identity.LearnerDirectoryClient;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.TeacherAnalyticsRepository;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.service.TeacherAnalyticsService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 固定伪 UUID 在独立 H2 MySQL 模式验收 ACTIVE、当前修订、owner、幂等日事实和月度去重。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t12c1_analytics_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@Transactional
class T12C1TeacherAnalyticsIntegrationTest {
    private static final String COURSE = "00000000-0000-4000-8000-0000000c1201";
    private static final String LESSON = "00000000-0000-4000-8000-0000000c1202";
    private static final String CURRENT = "00000000-0000-4000-8000-0000000c1203";
    private static final String OLD = "00000000-0000-4000-8000-0000000c1204";
    private static final String TEACHER = "00000000-0000-4000-8000-0000000c1205";
    private static final String OTHER = "00000000-0000-4000-8000-0000000c1206";
    private static final String ACTIVE = "00000000-0000-4000-8000-0000000c1207";
    private static final String WITHDRAWN = "00000000-0000-4000-8000-0000000c1208";
    private static final String SECOND = "00000000-0000-4000-8000-0000000c1209";
    private static final String THIRD = "00000000-0000-4000-8000-0000000c1210";
    private static final String HOMEWORK = "00000000-0000-4000-8000-0000000c1211";
    private static final String PRACTICE = "00000000-0000-4000-8000-0000000c1212";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository learning;
    @Autowired TeacherAnalyticsRepository data;
    @Autowired TeacherAnalyticsService analytics;
    @MockitoBean CourseFactsClient courses;
    @MockitoBean LearnerDirectoryClient directory;

    @BeforeEach
    void facts() {
        var fact = new CourseFactsClient.CourseFact(COURSE, "v2", "PUBLIC", TEACHER, 2,
                Set.of(LESSON), Map.of(LESSON, "VIDEO"), Map.of(LESSON, CURRENT));
        when(courses.ownedPublishedOrEmpty(eq(COURSE), eq(TEACHER), anyString())).thenReturn(fact);
        when(courses.ownedPublishedOrEmpty(eq(COURSE), eq(OTHER), anyString()))
                .thenThrow(new QuestionException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "LEARNING.NOT_FOUND", "资源不存在"));
        when(directory.nicknames(org.mockito.ArgumentMatchers.anyList(), anyString()))
                .thenReturn(new LearnerDirectoryClient.NicknameLookup(Map.of(), true));
        Instant now = Instant.now();
        for (String learner : Set.of(ACTIVE, WITHDRAWN)) {
            jdbc.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) "
                    + "VALUES(?,?,?,?,?,?,?,?)", java.util.UUID.randomUUID().toString(), learner, COURSE, "v2",
                    learner.equals(ACTIVE) ? "ACTIVE" : "CANCELED", Timestamp.from(now), Timestamp.from(now),
                    Timestamp.from(now));
            jdbc.update("INSERT INTO course_student_number(id,course_id,learner_id,student_number,created_at) "
                    + "VALUES(?,?,?,?,?)", java.util.UUID.randomUUID().toString(), COURSE, learner,
                    learner.equals(ACTIVE) ? 1 : 2, Timestamp.from(now));
        }
        learning.reconcilePublishedContent(fact, now);
    }

    @Test
    /** 旧修订与 WITHDRAWN 已落库事实仍存在，但学生页、课程时长与课时聚合只看 ACTIVE 当前修订。 */
    void currentRevisionAndActiveOnlyWithIamDegradationAndOwner404() {
        Instant now = Instant.now();
        progress(ACTIVE, OLD, 90, now);
        progress(ACTIVE, CURRENT, 20, now);
        progress(WITHDRAWN, CURRENT, 70, now);
        var page = analytics.students(TEACHER, COURSE, 1, 20, "STUDENT_NUMBER", false,
                null, null, null, false, false, "trace-t12c1");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.courseStudentNumber()).isEqualTo("0001");
            assertThat(item.watchedSeconds()).isEqualTo(20);
            assertThat(item.nickname()).isNull();
        });
        assertThat(page.nicknameDependencyFailed()).isTrue();
        assertThat(analytics.courseOverview(TEACHER, COURSE, "trace-t12c1").watchedSeconds()).isEqualTo(20);
        assertThatThrownBy(() -> analytics.studentOverview(TEACHER, COURSE, WITHDRAWN, "trace-t12c1"))
                .isInstanceOf(QuestionException.class);
        assertThatThrownBy(() -> analytics.courseOverview(OTHER, COURSE, "trace-t12c1"))
                .isInstanceOf(QuestionException.class);
    }

    @Test
    /** 本人无发布版本为明确空态；已发布无 ACTIVE 学生为零值，不能暴露历史退课数据。 */
    void unpublishedAndZeroActiveStudents() {
        when(courses.ownedPublishedOrEmpty(eq(COURSE), eq(TEACHER), anyString())).thenReturn(null);
        assertThat(analytics.students(TEACHER, COURSE, 1, 20, "STUDENT_NUMBER", false,
                null, null, null, false, false, "trace-t12c1").state()).isEqualTo("NO_PUBLISHED_VERSION");
        assertThat(analytics.courseOverview(TEACHER, COURSE, "trace-t12c1").state())
                .isEqualTo("NO_PUBLISHED_VERSION");
        var fact = new CourseFactsClient.CourseFact(COURSE, "v2", "PUBLIC", TEACHER, 2,
                Set.of(LESSON), Map.of(LESSON, "VIDEO"), Map.of(LESSON, CURRENT));
        when(courses.ownedPublishedOrEmpty(eq(COURSE), eq(TEACHER), anyString())).thenReturn(fact);
        jdbc.update("UPDATE enrollment SET status='CANCELED' WHERE course_id=?", COURSE);
        var overview = analytics.courseOverview(TEACHER, COURSE, "trace-t12c1");
        assertThat(overview.state()).isEqualTo("READY");
        assertThat(overview.activeStudentCount()).isZero();
        assertThat(overview.watchedSeconds()).isZero();
    }

    /** Course 无法确定当前发布身份时全部教师读请求安全 503，不退化成旧投影。 */
    @Test
    void courseDependencyFailureDoesNotUseStaleProjection() {
        when(courses.ownedPublishedOrEmpty(eq(COURSE), eq(TEACHER), anyString()))
                .thenThrow(new QuestionException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "LEARNING.COURSE_DEPENDENCY_UNAVAILABLE", "课程暂不可用"));
        assertThatThrownBy(() -> analytics.courseOverview(TEACHER, COURSE, "trace-t12c1"))
                .isInstanceOfSatisfying(QuestionException.class,
                        error -> assertThat(error.status().value()).isEqualTo(503));
    }

    @Test
    /** Worker 以版本和行锁去重；同一快照第二次落库不会再增加日时长或课时累计。 */
    void bufferedRetryKeepsDailySecondsIdempotent() {
        Instant now = Instant.now();
        LocalDate day = now.atZone(SHANGHAI).toLocalDate();
        learning.applyBufferedProgress(ACTIVE, COURSE, LESSON, CURRENT, 10, 10, false,
                now, 1, now, Map.of(day, 10L), day);
        learning.applyBufferedProgress(ACTIVE, COURSE, LESSON, CURRENT, 10, 10, false,
                now, 1, now, Map.of(day, 10L), day);
        assertThat(jdbc.queryForObject("SELECT watched_seconds FROM lesson_learning_day WHERE learner_id=?",
                Long.class, ACTIVE)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT watched_seconds FROM lesson_progress WHERE learner_id=?",
                Long.class, ACTIVE)).isEqualTo(10);
    }

    @Test
    /** 跨午夜的 Redis 快照按上报自然日分配，不把两天秒数全记在 Worker 执行日。 */
    void bufferedSnapshotKeepsTwoShanghaiDays() {
        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDate yesterday = today.minusDays(1);
        Instant now = Instant.now();
        learning.applyBufferedProgress(ACTIVE, COURSE, LESSON, CURRENT, 50, 12, false,
                now, 2, now, Map.of(yesterday, 5L, today, 7L), yesterday);
        assertThat(jdbc.queryForObject("SELECT watched_seconds FROM lesson_learning_day "
                + "WHERE learner_id=? AND activity_date=?", Long.class, ACTIVE,
                java.sql.Date.valueOf(yesterday))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT watched_seconds FROM lesson_learning_day "
                + "WHERE learner_id=? AND activity_date=?", Long.class, ACTIVE,
                java.sql.Date.valueOf(today))).isEqualTo(7);
    }

    @Test
    /** ALL 跨 180 天转月度；同一学习者同月跨两日仍只算一个活跃学生。 */
    void monthlyTrendDeduplicatesStudents() {
        Instant now = Instant.now();
        LocalDate first = LocalDate.now(SHANGHAI).minusDays(190).withDayOfMonth(1);
        for (int offset : new int[]{0, 1}) {
            LocalDate day = first.plusDays(offset);
            jdbc.update("INSERT INTO lesson_learning_day(course_id,learner_id,lesson_id,content_revision_id,"
                    + "activity_date,watched_seconds,first_started,first_completed,last_activity_at) "
                    + "VALUES(?,?,?,?,?,5,?,?,?)", COURSE, ACTIVE, LESSON, CURRENT,
                    java.sql.Date.valueOf(day), offset == 0, false,
                    Timestamp.from(day.atStartOfDay(SHANGHAI).toInstant()));
        }
        var trend = analytics.trend(TEACHER, COURSE, null, null, "ALL", "trace-t12c1");
        assertThat(trend.period().granularity()).isEqualTo("MONTH");
        assertThat(trend.points().get(0).activeStudentCount()).isEqualTo(1);
        assertThat(trend.points().get(0).watchedSeconds()).isEqualTo(10);
    }

    @Test
    /** 正式测评在关闭/结算/全批改前分数统计为 null；就绪后并列为 1、1、3。 */
    void formalGateAndCompetitionRanking() {
        enroll(SECOND, 3); enroll(THIRD, 4);
        assessment(HOMEWORK, "HOMEWORK", "OPEN", "NOT_STARTED");
        scored(HOMEWORK, ACTIVE, 90, 1); scored(HOMEWORK, SECOND, 90, 1); scored(HOMEWORK, THIRD, 70, 1);
        var pending = analytics.courseTasks(TEACHER, COURSE, 1, 20, "trace-t12c1").items().get(0);
        assertThat(pending.resultsReady()).isFalse();
        assertThat(pending.averageScorePercent()).isNull();
        assertThat(analytics.studentTasks(TEACHER, COURSE, ACTIVE, 1, 20, "trace-t12c1")
                .items().get(0).scorePercent()).isNull();
        assertThatThrownBy(() -> analytics.students(TEACHER, COURSE, 1, 20, "HOMEWORK_AVERAGE",
                true, null, null, null, false, false, "trace-t12c1"))
                .isInstanceOf(QuestionException.class);
        jdbc.update("UPDATE assessment SET close_status='CLOSED',settlement_status='COMPLETED' WHERE id=?", HOMEWORK);
        var first = analytics.studentTasks(TEACHER, COURSE, ACTIVE, 1, 20, "trace-t12c1").items().get(0);
        var tied = analytics.studentTasks(TEACHER, COURSE, SECOND, 1, 20, "trace-t12c1").items().get(0);
        var third = analytics.studentTasks(TEACHER, COURSE, THIRD, 1, 20, "trace-t12c1").items().get(0);
        assertThat(first.rank()).isEqualTo(1); assertThat(tied.rank()).isEqualTo(1);
        assertThat(third.rank()).isEqualTo(3);
        assertThat(first.rankingPercent()).isEqualByComparingTo("100");
        assertThat(tied.rankingPercent()).isEqualByComparingTo("100");
        assertThat(third.rankingPercent()).isEqualByComparingTo("33.33");
    }

    @Test
    /** 课程 PRACTICE 每人使用最高有效百分制；不会改变正式作业平均。 */
    void practiceUsesBestValidSubmissionWithoutFormalAverage() {
        enroll(SECOND, 3);
        assessment(PRACTICE, "PRACTICE", "OPEN", "NOT_STARTED");
        scored(PRACTICE, ACTIVE, 60, 1); scored(PRACTICE, ACTIVE, 95, 2);
        scored(PRACTICE, SECOND, 80, 1);
        var student = analytics.studentTasks(TEACHER, COURSE, ACTIVE, 1, 20, "trace-t12c1").items().get(0);
        assertThat(student.scorePercent()).isEqualByComparingTo("95");
        assertThat(student.rank()).isEqualTo(1);
        assertThat(analytics.studentOverview(TEACHER, COURSE, ACTIVE, "trace-t12c1")
                .student().homeworkAverage()).isNull();
        var courseTask = analytics.courseTasks(TEACHER, COURSE, 1, 20, "trace-t12c1").items().get(0);
        assertThat(courseTask.submittedCount()).isEqualTo(2);
        assertThat(courseTask.averageScorePercent()).isEqualByComparingTo("87.5");
    }

    @Test
    /** 默认课程编号稳定排序、分页总数和最后活动排序均由 MySQL 处理。 */
    void studentPaginationAndSortAreStable() {
        enroll(SECOND, 3);
        var first = analytics.students(TEACHER, COURSE, 1, 1, "STUDENT_NUMBER", false,
                null, null, null, false, false, "trace-t12c1");
        var second = analytics.students(TEACHER, COURSE, 2, 1, "STUDENT_NUMBER", false,
                null, null, null, false, false, "trace-t12c1");
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items().get(0).learnerId()).isEqualTo(ACTIVE);
        assertThat(second.items().get(0).learnerId()).isEqualTo(SECOND);
    }

    private void enroll(String learner, int studentNumber) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) "
                + "VALUES(?,?,?,'v2','ACTIVE',?,?,?)", java.util.UUID.randomUUID().toString(), learner, COURSE,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO course_student_number(id,course_id,learner_id,student_number,created_at) "
                + "VALUES(?,?,?,?,?)", java.util.UUID.randomUUID().toString(), COURSE, learner,
                studentNumber, Timestamp.from(now));
    }

    private void assessment(String id, String type, String closeStatus, String settlementStatus) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO assessment(id,owner_teacher_id,course_id,lesson_id,title,description,"
                + "assessment_type,status,answer_publication_policy,close_status,settlement_status,"
                + "published_at,created_at,updated_at,ownership_type) "
                + "VALUES(?,?,?,?,?,'test',?,'PUBLISHED','NEVER',?,?,?,?,?,'COURSE')",
                id, TEACHER, COURSE, null, type + " test", type, closeStatus, settlementStatus,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private void scored(String assessmentId, String learner, int score, int sequence) {
        Instant now = Instant.now();
        String attempt = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO assessment_attempt(id,assessment_id,learner_id,status,revision,score,total_points,"
                + "submitted_at,submission_day,attempt_sequence,created_at,updated_at) "
                + "VALUES(?,?,?,'GRADED',1,?,100,?,?,?, ?,?)", attempt, assessmentId, learner, score,
                Timestamp.from(now), java.sql.Date.valueOf(now.atZone(SHANGHAI).toLocalDate()), sequence,
                Timestamp.from(now), Timestamp.from(now));
        if (assessmentId.equals(HOMEWORK)) jdbc.update("INSERT INTO assessment_participant(id,assessment_id,learner_id,"
                + "enrollment_status,submission_status,grading_status,final_attempt_id,last_submitted_at,"
                + "created_at,updated_at) VALUES(?,?,?,'ACTIVE','SUBMITTED','GRADED',?,?,?,?)",
                java.util.UUID.randomUUID().toString(), assessmentId, learner, attempt,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private void progress(String learner, String revision, long watched, Instant at) {
        jdbc.update("INSERT INTO lesson_progress(id,learner_id,course_id,lesson_id,content_revision_id,"
                + "position_seconds,watched_seconds,completed,last_event_at,revision,buffer_version,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,10,?,FALSE,?,1,0,?,?)", java.util.UUID.randomUUID().toString(),
                learner, COURSE, LESSON, revision, watched, Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
    }
}
