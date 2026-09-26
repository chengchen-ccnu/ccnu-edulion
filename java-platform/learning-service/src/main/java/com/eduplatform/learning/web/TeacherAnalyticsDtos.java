package com.eduplatform.learning.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 教师学情的按需响应；所有分数门禁由服务层裁剪后才构造响应，避免序列化隐藏字段。 */
public final class TeacherAnalyticsDtos {
    private TeacherAnalyticsDtos() {}

    /** 快照为 LIFETIME，趋势为上海自然日/月；dataThrough 仅表示已落库事实，空数据仍显式返回 null。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Period(LocalDate periodStart, LocalDate periodEnd, String zoneId, String granularity,
            Instant generatedAt, Instant dataThrough) {}
    /** 学生身份与学习事实；无昵称、活动或可见成绩时仍输出契约要求的 null 字段。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Student(String learnerId, String courseStudentNumber, String nickname,
            boolean nicknameAvailable, int completedLessons, int totalLessons, BigDecimal progressPercent,
            long watchedSeconds, Instant lastActivityAt, BigDecimal homeworkAverage, BigDecimal examAverage,
            int missingCount, int pendingCount) {}
    public record StudentPage(List<Student> items, int page, int size, long total, String state,
            boolean nicknameDependencyFailed, Period period) {}
    public record StudentOverview(Student student, int practiceSubmissionCount,
            boolean nicknameDependencyFailed, Period period) {}
    /** 当前课时进度；非视频课时的观看秒数和无活动时间以 null 表示。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StudentLesson(String lessonId, String contentRevisionId, String lessonType,
            boolean started, boolean completed, Long watchedSeconds, Instant lastActivityAt) {}
    public record StudentLessonPage(List<StudentLesson> items, int page, int size, long total, Period period) {}
    /** HOMEWORK/EXAM 的本人已批分数可先显示；整场未就绪时排名及人数仍为 null。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StudentTask(String assessmentId, String lessonId, String title, String type, String status,
            boolean submitted, boolean late, boolean gradingPending, boolean resultsReady,
            BigDecimal rawScore, BigDecimal totalPoints, BigDecimal scorePercent,
            Integer rank, Integer rankedStudentCount, BigDecimal rankingPercent) {}
    public record StudentTaskPage(List<StudentTask> items, int page, int size, long total, Period period) {}
    public record CourseOverview(String state, int activeStudentCount, int totalLessonCount,
            int completedStudentCount, long watchedSeconds, int actualViewerCount,
            BigDecimal averageWatchedPerActiveStudent, BigDecimal averageWatchedPerViewer, Period period) {}
    /** 课程课时聚合；非视频课时的观看人数与时长类指标以 null 表示。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CourseLesson(String lessonId, String contentRevisionId, String lessonType,
            int activeStudentCount, int startedCount, int completedCount, BigDecimal completionRate,
            Long watchedSeconds, Integer actualViewerCount,
            BigDecimal averageWatchedPerActiveStudent, BigDecimal averageWatchedPerViewer) {}
    public record CourseLessonPage(List<CourseLesson> items, int page, int size, long total, Period period) {}
    /** 课程任务聚合；正式成绩未就绪时分数统计与分布保持 null。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CourseTask(String assessmentId, String lessonId, String title, String type, String status,
            int expectedCount, int submittedCount, int missingCount, int lateCount, int pendingCount,
            boolean resultsReady, BigDecimal averageScorePercent, BigDecimal highestScorePercent,
            BigDecimal lowestScorePercent, Map<String, Integer> distribution) {}
    public record CourseTaskPage(List<CourseTask> items, int page, int size, long total, Period period) {}
    public record TrendPoint(LocalDate periodStart, LocalDate periodEnd, long watchedSeconds,
            int activeStudentCount, int firstStartedLessons, int completedLessons, int formalSubmissionCount,
            int distinctSubmittingStudents, int autoSettledSubmissionCount) {}
    public record Trend(String window, List<TrendPoint> points, Period period) {}
}
