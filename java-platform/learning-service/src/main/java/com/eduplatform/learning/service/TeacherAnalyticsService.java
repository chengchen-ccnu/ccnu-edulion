package com.eduplatform.learning.service;

import com.eduplatform.learning.course.CourseFactsClient;
import com.eduplatform.learning.course.CourseFactsClient.CourseFact;
import com.eduplatform.learning.identity.LearnerDirectoryClient;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.TeacherAnalyticsRepository;
import com.eduplatform.learning.web.TeacherAnalyticsDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 教师学情按每个面板分别查询。Course HTTP 确认 owner 和当前发布身份；Learning 只读 MySQL 持久事实，
 * IAM 昵称失败仅降级身份显示。结果不含旧修订、WITHDRAWN 或 Redis 尚未落库的进度。
 */
@Service
@ConditionalOnBean(TeacherAnalyticsRepository.class)
public class TeacherAnalyticsService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final CourseFactsClient courses;
    private final LearningAssessmentRepository learning;
    private final TeacherAnalyticsRepository data;
    private final LearnerDirectoryClient directory;

    public TeacherAnalyticsService(CourseFactsClient courses, LearningAssessmentRepository learning,
            TeacherAnalyticsRepository data, LearnerDirectoryClient directory) {
        this.courses = courses; this.learning = learning; this.data = data; this.directory = directory;
    }

    /** 当前发布版本每次经 Course 校验并对齐 Learning 投影；Course 失败不能拿旧投影冒充最新事实。 */
    private CourseFact course(String teacherId, String courseId, String traceId) {
        CourseFact fact = courses.ownedPublishedOrEmpty(courseId, teacherId, traceId);
        if (fact != null) learning.reconcilePublishedContent(fact, Instant.now());
        return fact;
    }

    /** 课程 ACTIVE 学生分页；关键词昵称在 IAM 限量批量解析，排序始终由 MySQL 完成。 */
    public TeacherAnalyticsDtos.StudentPage students(String teacherId, String courseId, int page, int size,
            String sort, boolean descending, String keyword, BigDecimal minProgress, BigDecimal maxProgress,
            boolean missingOnly, boolean pendingOnly, String traceId) {
        CourseFact fact = course(teacherId, courseId, traceId);
        TeacherAnalyticsDtos.Period period = fact == null ? emptyPeriod() : lifetime(courseId);
        if (fact == null) return new TeacherAnalyticsDtos.StudentPage(List.of(), page, size, 0,
                "NO_PUBLISHED_VERSION", false, period);
        boolean ready = data.formalScoresReady(courseId);
        if (!ready && ("HOMEWORK_AVERAGE".equals(sort) || "EXAM_AVERAGE".equals(sort)))
            throw new QuestionException(HttpStatus.CONFLICT, "LEARNING.ANALYTICS_SCORE_SORT_NOT_READY",
                    "正式任务成绩尚未全部就绪，不能按成绩排序");
        List<String> nicknameMatches = List.of();
        boolean nicknameFailure = false;
        if (keyword != null && !keyword.isBlank()) {
            List<String> matches = new ArrayList<>();
            for (int batch = 1; ; batch++) {
                var numbers = data.activeIds(courseId, batch, 100);
                if (numbers.isEmpty()) break;
                var lookup = directory.nicknames(numbers, traceId);
                nicknameFailure |= lookup.dependencyFailed();
                for (String id : numbers) {
                    String nickname = lookup.values().get(id);
                    if (nickname != null && nickname.contains(keyword)) matches.add(id);
                }
                if (numbers.size() < 100) break;
            }
            nicknameMatches = matches;
        }
        int lessonCount = learningLessons(fact).size();
        int total = data.countStudents(courseId, lessonCount, keyword, nicknameMatches,
                minProgress, maxProgress, missingOnly, pendingOnly);
        var rows = data.students(courseId, lessonCount, sort, descending, keyword, nicknameMatches,
                minProgress, maxProgress, missingOnly, pendingOnly, page, size);
        var names = directory.nicknames(rows.stream().map(row -> string(row, "learner_id")).toList(), traceId);
        List<TeacherAnalyticsDtos.Student> items = rows.stream().map(row -> student(row, lessonCount,
                ready, names.values())).toList();
        return new TeacherAnalyticsDtos.StudentPage(items, page, size, total, "READY",
                nicknameFailure || names.dependencyFailed(), period);
    }

    /** 单个学生仅在当前 ACTIVE 报名下可读；课程 owner 和学员状态均失败为安全 404。 */
    private CourseFact requireStudent(String teacherId, String courseId, String learnerId, String traceId) {
        CourseFact fact = course(teacherId, courseId, traceId);
        if (fact == null || !data.active(courseId, learnerId)) throw notFound();
        return fact;
    }

    /** 输入 owner、课程与学生 ID，返回本人 ACTIVE 学生概览；昵称依赖失败仅标记降级。 */
    public TeacherAnalyticsDtos.StudentOverview studentOverview(String teacherId, String courseId,
            String learnerId, String traceId) {
        CourseFact fact = requireStudent(teacherId, courseId, learnerId, traceId);
        Map<String, Object> row = data.student(courseId, learnerId);
        var name = directory.nicknames(List.of(learnerId), traceId);
        // PRACTICE 只计真实提交；课程正式成绩均来自对应 participant，不在这里叠加 PRACTICE。
        int practiceCount = data.practiceSubmissionCount(courseId, learnerId);
        return new TeacherAnalyticsDtos.StudentOverview(student(row, learningLessons(fact).size(),
                data.formalScoresReady(courseId), name.values()), practiceCount,
                name.dependencyFailed(), lifetime(courseId));
    }

    /** 按 Course 当前目录顺序分页单生课时；ARTICLE 无阅读秒数，ASSESSMENT 作答另查任务。 */
    public TeacherAnalyticsDtos.StudentLessonPage studentLessons(String teacherId, String courseId,
            String learnerId, int page, int size, String traceId) {
        CourseFact fact = requireStudent(teacherId, courseId, learnerId, traceId);
        List<String> currentLessons = learningLessons(fact);
        List<String> ids = currentLessons.stream().skip((long) (page - 1) * size).limit(size).toList();
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (var row : data.studentLessonsByIds(courseId, learnerId, ids)) byId.put(string(row, "lesson_id"), row);
        List<TeacherAnalyticsDtos.StudentLesson> items = ids.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(row -> {
                    String type = string(row, "lesson_type");
                    boolean exists = row.get("progress_id") != null;
                    return new TeacherAnalyticsDtos.StudentLesson(string(row, "lesson_id"),
                            string(row, "content_revision_id"), type, exists && !"ASSESSMENT".equals(type),
                            bool(row, "completed"), "VIDEO".equals(type) ? (exists ? longValue(row, "watched_seconds") : 0L) : null,
                            instant(row, "last_event_at"));
                }).toList();
        return new TeacherAnalyticsDtos.StudentLessonPage(items, page, size, currentLessons.size(), lifetime(courseId));
    }

    /** 课程快照聚合 ACTIVE 当前进度；本人草稿返回空态，其他 owner 隐藏为 404。 */
    public TeacherAnalyticsDtos.CourseOverview courseOverview(String teacherId, String courseId, String traceId) {
        CourseFact fact = course(teacherId, courseId, traceId);
        if (fact == null) return new TeacherAnalyticsDtos.CourseOverview("NO_PUBLISHED_VERSION", 0, 0,
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, emptyPeriod());
        int active = data.activeCount(courseId);
        var totals = data.courseTotals(courseId);
        long watched = longValue(totals, "watched_seconds");
        int viewers = integer(totals, "viewer_count");
        int lessonCount = learningLessons(fact).size();
        return new TeacherAnalyticsDtos.CourseOverview("READY", active, lessonCount,
                data.completedStudentCount(courseId, lessonCount), watched, viewers,
                ratio(watched, active), ratio(watched, viewers), lifetime(courseId));
    }

    /** 按当前发布目录分页课程课时统计，VIDEO 同时返回全员与实际观看者人均秒数。 */
    public TeacherAnalyticsDtos.CourseLessonPage courseLessons(String teacherId, String courseId,
            int page, int size, String traceId) {
        CourseFact fact = course(teacherId, courseId, traceId);
        if (fact == null) return new TeacherAnalyticsDtos.CourseLessonPage(List.of(), page, size, 0, emptyPeriod());
        int active = data.activeCount(courseId);
        List<String> currentLessons = learningLessons(fact);
        List<String> ids = currentLessons.stream().skip((long) (page - 1) * size).limit(size).toList();
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (var row : data.courseLessonsByIds(courseId, ids)) byId.put(string(row, "lesson_id"), row);
        List<TeacherAnalyticsDtos.CourseLesson> items = ids.stream().map(byId::get)
                .filter(java.util.Objects::nonNull).map(row -> {
            String type = string(row, "lesson_type");
            int completed = integer(row, "completed_count");
            long watched = longValue(row, "watched_seconds");
            int viewers = integer(row, "viewer_count");
            return new TeacherAnalyticsDtos.CourseLesson(string(row, "lesson_id"), string(row, "content_revision_id"),
                    type, active, "ASSESSMENT".equals(type) ? 0 : integer(row, "started_count"),
                    "ASSESSMENT".equals(type) ? 0 : completed, ratio(completed * 100L, active),
                    "VIDEO".equals(type) ? watched : null, "VIDEO".equals(type) ? viewers : null,
                    "VIDEO".equals(type) ? ratio(watched, active) : null,
                    "VIDEO".equals(type) ? ratio(watched, viewers) : null);
        }).toList();
        return new TeacherAnalyticsDtos.CourseLessonPage(items, page, size, currentLessons.size(), lifetime(courseId));
    }

    /** 分页课程任务；正式成绩在 T11C 关闭、结算和全员批改门禁满足后才做统计查询。 */
    public TeacherAnalyticsDtos.CourseTaskPage courseTasks(String teacherId, String courseId,
            int page, int size, String traceId) {
        CourseFact fact = course(teacherId, courseId, traceId);
        if (fact == null) return new TeacherAnalyticsDtos.CourseTaskPage(List.of(), page, size, 0, emptyPeriod());
        List<TeacherAnalyticsDtos.CourseTask> items = data.tasks(courseId, page, size).stream().map(row -> {
            String id = string(row, "id");
            boolean practice = "PRACTICE".equals(string(row, "assessment_type"));
            Map<String, Object> counts = data.taskCounts(courseId, id, practice);
            boolean ready = practice || ("CLOSED".equals(string(row, "close_status"))
                    && "COMPLETED".equals(string(row, "settlement_status"))
                    && integer(counts, "pending_count") == 0);
            Map<String, Object> stats = ready ? data.scoreStats(courseId, id, practice) : Map.of();
            return new TeacherAnalyticsDtos.CourseTask(id, string(row, "lesson_id"), string(row, "title"),
                    string(row, "assessment_type"), string(row, "status"), integer(counts, "expected_count"),
                    integer(counts, "submitted_count"), integer(counts, "missing_count"),
                    integer(counts, "late_count"), integer(counts, "pending_count"), ready,
                    decimal(stats, "average_score"), decimal(stats, "highest_score"),
                    decimal(stats, "lowest_score"), ready ? distribution(stats) : null);
        }).toList();
        return new TeacherAnalyticsDtos.CourseTaskPage(items, page, size, data.taskCount(courseId), lifetime(courseId));
    }

    /** 单生任务分页；正式成绩未就绪时不返回得分、排名或可推断班级分数的聚合。 */
    public TeacherAnalyticsDtos.StudentTaskPage studentTasks(String teacherId, String courseId,
            String learnerId, int page, int size, String traceId) {
        requireStudent(teacherId, courseId, learnerId, traceId);
        List<TeacherAnalyticsDtos.StudentTask> items = data.tasks(courseId, page, size).stream().map(row -> {
            String id = string(row, "id");
            boolean practice = "PRACTICE".equals(string(row, "assessment_type"));
            Map<String, Object> counts = data.taskCounts(courseId, id, practice);
            boolean ready = practice || ("CLOSED".equals(string(row, "close_status"))
                    && "COMPLETED".equals(string(row, "settlement_status"))
                    && integer(counts, "pending_count") == 0);
            var own = data.studentTask(courseId, id, learnerId, practice);
            boolean submitted = practice ? integer(own, "submission_count") > 0
                    : "SUBMITTED".equals(string(own, "submission_status"));
            BigDecimal pct = ready ? decimal(own, "score_percent") : null;
            Integer rank = null, rankedCount = null;
            BigDecimal rankPercent = null;
            if (pct != null) {
                var ranking = data.rank(courseId, id, learnerId, practice);
                rankedCount = integer(ranking, "ranked_count");
                rank = integer(ranking, "rank_value");
                rankPercent = ratio((rankedCount - rank + 1) * 100L, rankedCount);
            }
            return new TeacherAnalyticsDtos.StudentTask(id, string(row, "lesson_id"), string(row, "title"),
                    string(row, "assessment_type"), string(row, "status"), submitted,
                    bool(own, "late_submission"), !practice && submitted
                    && !"GRADED".equals(string(own, "grading_status")), ready,
                    ready ? decimal(own, "raw_score") : null, ready ? decimal(own, "total_points") : null,
                    pct, rank, rankedCount, rankPercent);
        }).toList();
        return new TeacherAnalyticsDtos.StudentTaskPage(items, page, size, data.taskCount(courseId), lifetime(courseId));
    }

    /** 日/月趋势只返回有限周期聚合；月度去重在 SQL 内按月重新 COUNT DISTINCT。 */
    public TeacherAnalyticsDtos.Trend trend(String teacherId, String courseId, String learnerId,
            String lessonId, String window, String traceId) {
        CourseFact fact = learnerId == null ? course(teacherId, courseId, traceId)
                : requireStudent(teacherId, courseId, learnerId, traceId);
        if (lessonId != null && (fact == null || !fact.lessonIds().contains(lessonId))) throw notFound();
        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDate start = switch (window) {
            case "LAST_30_DAYS" -> today.minusDays(29);
            case "ALL" -> fact == null ? null : data.earliestCurrentDay(courseId, learnerId, lessonId);
            default -> today.minusDays(6);
        };
        if (start == null) start = today;
        boolean monthly = "ALL".equals(window) && ChronoUnit.DAYS.between(start, today) > 180;
        String granularity = monthly ? "MONTH" : "DAY";
        Map<LocalDate, Map<String, Object>> rows = new HashMap<>();
        if (fact != null) for (var row : data.trend(courseId, learnerId, lessonId, start, today, monthly)) {
            Object raw = row.get("period_key");
            LocalDate date = monthly ? LocalDate.of(((Number) raw).intValue() / 100,
                    ((Number) raw).intValue() % 100, 1)
                    : raw instanceof Date value ? value.toLocalDate() : LocalDate.parse(raw.toString());
            rows.put(date, row);
        }
        List<TeacherAnalyticsDtos.TrendPoint> points = new ArrayList<>();
        LocalDate cursor = monthly ? start.withDayOfMonth(1) : start;
        while (!cursor.isAfter(today)) {
            LocalDate end = monthly ? cursor.plusMonths(1).minusDays(1) : cursor;
            Map<String, Object> row = rows.getOrDefault(cursor, Map.of());
            points.add(new TeacherAnalyticsDtos.TrendPoint(cursor, end.isAfter(today) ? today : end,
                    longValue(row, "watched_seconds"), integer(row, "active_students"),
                    integer(row, "started_lessons"), integer(row, "completed_lessons"),
                    integer(row, "formal_submissions"), integer(row, "submitting_students"),
                    integer(row, "auto_settled_submissions")));
            cursor = monthly ? cursor.plusMonths(1) : cursor.plusDays(1);
        }
        return new TeacherAnalyticsDtos.Trend(window, points,
                new TeacherAnalyticsDtos.Period(start, today, SHANGHAI.getId(), granularity,
                        Instant.now(), fact == null ? null : data.dataThrough(courseId)));
    }

    private TeacherAnalyticsDtos.Student student(Map<String, Object> row, int lessonCount, boolean scoresReady,
            Map<String, String> nicknames) {
        String learnerId = string(row, "learner_id");
        String nickname = nicknames.get(learnerId);
        int completed = integer(row, "completed_lessons");
        return new TeacherAnalyticsDtos.Student(learnerId,
                String.format(Locale.ROOT, "%04d", integer(row, "student_number")), nickname, nickname != null,
                completed, lessonCount, ratio(completed * 100L, lessonCount), longValue(row, "watched_seconds"),
                instant(row, "last_activity_at"), scoresReady ? decimal(row, "homework_average") : null,
                scoresReady ? decimal(row, "exam_average") : null,
                integer(row, "missing_count"), integer(row, "pending_count"));
    }

    /** ASSESSMENT 课时的正式作答只进入任务统计，不稀释 VIDEO/ARTICLE 内容完成率。 */
    private static List<String> learningLessons(CourseFact fact) {
        return fact.lessonIds().stream()
                .filter(id -> !"ASSESSMENT".equals(fact.lessonTypes().get(id))).toList();
    }

    private TeacherAnalyticsDtos.Period lifetime(String courseId) {
        LocalDate today = LocalDate.now(SHANGHAI);
        Instant earliest = data.earliestLifetimeFact(courseId);
        LocalDate first = earliest == null ? null : earliest.atZone(SHANGHAI).toLocalDate();
        return new TeacherAnalyticsDtos.Period(first == null ? today : first, today,
                SHANGHAI.getId(), "LIFETIME", Instant.now(), data.dataThrough(courseId));
    }

    private TeacherAnalyticsDtos.Period emptyPeriod() {
        LocalDate today = LocalDate.now(SHANGHAI);
        return new TeacherAnalyticsDtos.Period(today, today, SHANGHAI.getId(), "LIFETIME", Instant.now(), null);
    }

    private static Map<String, Integer> distribution(Map<String, Object> stats) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("0-59", integer(stats, "band_0_59")); result.put("60-69", integer(stats, "band_60_69"));
        result.put("70-79", integer(stats, "band_70_79")); result.put("80-89", integer(stats, "band_80_89"));
        result.put("90-100", integer(stats, "band_90_100")); return result;
    }
    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? null : value.toString();
    }
    private static int integer(Map<String, Object> row, String key) {
        Object value = row.get(key); return value instanceof Number number ? number.intValue() : 0;
    }
    private static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key); return value instanceof Number number ? number.longValue() : 0L;
    }
    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key); return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }
    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key); return value instanceof BigDecimal big ? big : value instanceof Number number
                ? BigDecimal.valueOf(number.doubleValue()) : null;
    }
    private static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key); return value instanceof Timestamp stamp ? stamp.toInstant() : null;
    }
    private static BigDecimal ratio(long value, long divisor) {
        return divisor == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
    }
    private static QuestionException notFound() {
        return new QuestionException(HttpStatus.NOT_FOUND, "LEARNING.NOT_FOUND", "资源不存在");
    }
}
