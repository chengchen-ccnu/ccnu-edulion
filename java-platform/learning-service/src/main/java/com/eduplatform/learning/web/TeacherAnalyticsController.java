package com.eduplatform.learning.web;

import com.eduplatform.learning.config.TraceFilter;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.service.TeacherAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 教师学情只接收 Gateway 可信教学身份；每个请求再由 Course 验证课程 owner。 */
@RestController
@Validated
@ConditionalOnBean(TeacherAnalyticsService.class)
public class TeacherAnalyticsController {
    private static final String ROOT = "/api/v1/teacher/learning/courses/{courseId}";
    private static final Set<String> SORT = Set.of("STUDENT_NUMBER", "LAST_ACTIVITY", "PROGRESS",
            "WATCHED_SECONDS", "HOMEWORK_AVERAGE", "EXAM_AVERAGE");
    private static final Set<String> WINDOWS = Set.of("ALL", "LAST_7_DAYS", "LAST_30_DAYS");
    private final TeacherAnalyticsService analytics;

    public TeacherAnalyticsController(TeacherAnalyticsService analytics) { this.analytics = analytics; }

    /** 分页筛选 ACTIVE 学生；排序字段和方向固定白名单，正式分数未就绪时禁止按成绩排序。 */
    @GetMapping(ROOT + "/students")
    public TeacherAnalyticsDtos.StudentPage students(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "STUDENT_NUMBER") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) BigDecimal minProgressPercent,
            @RequestParam(required = false) BigDecimal maxProgressPercent,
            @RequestParam(defaultValue = "false") boolean missingOnly,
            @RequestParam(defaultValue = "false") boolean pendingOnly, HttpServletRequest request) {
        if (!SORT.contains(sort) || !("asc".equals(direction) || "desc".equals(direction))
                || keyword != null && keyword.length() > 120
                || minProgressPercent != null && (minProgressPercent.signum() < 0
                || minProgressPercent.compareTo(BigDecimal.valueOf(100)) > 0)
                || maxProgressPercent != null && (maxProgressPercent.signum() < 0
                || maxProgressPercent.compareTo(BigDecimal.valueOf(100)) > 0)
                || minProgressPercent != null && maxProgressPercent != null
                && minProgressPercent.compareTo(maxProgressPercent) > 0) invalid();
        return analytics.students(teacher(userId, roles, permissions), courseId, page, size, sort,
                "desc".equals(direction), keyword, minProgressPercent, maxProgressPercent,
                missingOnly, pendingOnly, trace(request));
    }

    /** 单个 ACTIVE 学生概览；昵称失败仍返回编号与 Learning 事实。 */
    @GetMapping(ROOT + "/students/{learnerId}/overview")
    public TeacherAnalyticsDtos.StudentOverview studentOverview(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @PathVariable String learnerId, HttpServletRequest request) {
        return analytics.studentOverview(teacher(userId, roles, permissions), courseId, learnerId, trace(request));
    }

    /** 逐课时只读取当前发布修订的一页，不遍历全班。 */
    @GetMapping(ROOT + "/students/{learnerId}/lessons")
    public TeacherAnalyticsDtos.StudentLessonPage studentLessons(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @PathVariable String learnerId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size, HttpServletRequest request) {
        return analytics.studentLessons(teacher(userId, roles, permissions), courseId, learnerId,
                page, size, trace(request));
    }

    /** 单个学生课程任务分页；正式成绩与排名门禁由服务层统一裁剪。 */
    @GetMapping(ROOT + "/students/{learnerId}/tasks")
    public TeacherAnalyticsDtos.StudentTaskPage studentTasks(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @PathVariable String learnerId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest request) {
        return analytics.studentTasks(teacher(userId, roles, permissions), courseId, learnerId,
                page, size, trace(request));
    }

    /** 单个学生当前内容日/月趋势；窗口限定 ALL、最近 7 天和最近 30 天。 */
    @GetMapping(ROOT + "/students/{learnerId}/trend")
    public TeacherAnalyticsDtos.Trend studentTrend(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @PathVariable String learnerId,
            @RequestParam(defaultValue = "LAST_7_DAYS") String window, HttpServletRequest request) {
        checkWindow(window);
        return analytics.trend(teacher(userId, roles, permissions), courseId, learnerId, null, window, trace(request));
    }

    /** 课程快照只汇总 ACTIVE 学生的当前发布内容。 */
    @GetMapping(ROOT + "/analytics/overview")
    public TeacherAnalyticsDtos.CourseOverview courseOverview(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, HttpServletRequest request) {
        return analytics.courseOverview(teacher(userId, roles, permissions), courseId, trace(request));
    }

    /** 课程课时按页数据库聚合；不产生全班学生×课时矩阵。 */
    @GetMapping(ROOT + "/analytics/lessons")
    public TeacherAnalyticsDtos.CourseLessonPage courseLessons(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size, HttpServletRequest request) {
        return analytics.courseLessons(teacher(userId, roles, permissions), courseId, page, size, trace(request));
    }

    /** 课程任务按页聚合；PRACTICE 单独计，正式成绩在门禁打开前不查询。 */
    @GetMapping(ROOT + "/analytics/tasks")
    public TeacherAnalyticsDtos.CourseTaskPage courseTasks(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest request) {
        return analytics.courseTasks(teacher(userId, roles, permissions), courseId, page, size, trace(request));
    }

    /** 课程或单个当前课时趋势；lessonId 来自 Course 当前发布目录。 */
    @GetMapping(ROOT + "/analytics/trend")
    public TeacherAnalyticsDtos.Trend courseTrend(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @RequestParam(required = false) String lessonId,
            @RequestParam(defaultValue = "LAST_7_DAYS") String window, HttpServletRequest request) {
        checkWindow(window);
        return analytics.trend(teacher(userId, roles, permissions), courseId, null, lessonId, window, trace(request));
    }

    private static String teacher(String userId, String roles, String permissions) {
        return LearningIdentity.teaching(userId, roles, permissions).userId();
    }
    private static String trace(HttpServletRequest request) {
        Object value = request.getAttribute(TraceFilter.ATTRIBUTE);
        return value == null ? "00000000-0000-0000-0000-000000000000" : value.toString();
    }
    private static void checkWindow(String window) { if (!WINDOWS.contains(window)) invalid(); }
    private static void invalid() {
        throw new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "LEARNING.ANALYTICS_FILTER_INVALID", "学情筛选参数无效");
    }
}
