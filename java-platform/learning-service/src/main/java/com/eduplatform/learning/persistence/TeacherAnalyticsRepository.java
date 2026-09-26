package com.eduplatform.learning.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 教师学情只查 Learning Schema 的 ACTIVE 报名和 Course 当前发布投影。
 * 查询在 MySQL 完成筛选、聚合与分页；不会装载全班×课时×日期明细到 JVM。
 */
@Repository
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true")
public class TeacherAnalyticsRepository {
    private final JdbcTemplate jdbc;

    public TeacherAnalyticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 对本课程 ACTIVE 学生计数；退课事实保留，但常规学情不暴露。 */
    public int activeCount(String courseId) { return number("SELECT COUNT(*) FROM enrollment WHERE course_id=? AND status='ACTIVE'", courseId); }
    public boolean active(String courseId, String learnerId) {
        return number("SELECT COUNT(*) FROM enrollment WHERE course_id=? AND learner_id=? AND status='ACTIVE'",
                courseId, learnerId) > 0;
    }
    /** 昵称筛选时按稳定课程编号流式扫描 ACTIVE ID，每次只把一页交给 IAM 批量投影。 */
    public List<String> activeIds(String courseId, int page, int size) {
        return jdbc.queryForList("SELECT n.learner_id FROM course_student_number n JOIN enrollment e "
                + "ON e.course_id=n.course_id AND e.learner_id=n.learner_id AND e.status='ACTIVE' "
                + "WHERE n.course_id=? ORDER BY n.student_number LIMIT ? OFFSET ?",
                String.class, courseId, size, ((long) page - 1) * size);
    }
    /** 返回一名 ACTIVE 学生在 COURSE PRACTICE 的真实提交次数，不读取公共或自主练习。 */
    public int practiceSubmissionCount(String courseId, String learnerId) {
        return number("SELECT COUNT(*) FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id "
                + "WHERE a.course_id=? AND a.ownership_type='COURSE' AND a.assessment_type='PRACTICE' "
                + "AND t.learner_id=? AND t.status<>'DRAFT'", courseId, learnerId);
    }
    /** 返回完成当前所有发布课时的 ACTIVE 学生数；零课时不视作整课完成。 */
    public int completedStudentCount(String courseId, int lessonCount) {
        if (lessonCount == 0) return 0;
        return number("SELECT COUNT(*) FROM (SELECT e.learner_id FROM enrollment e "
                + "JOIN lesson_progress p ON p.course_id=e.course_id AND p.learner_id=e.learner_id "
                + "JOIN published_lesson_content_projection c ON c.course_id=p.course_id "
                + "AND c.content_revision_id=p.content_revision_id AND c.lesson_type IN ('VIDEO','ARTICLE') "
                + "WHERE e.course_id=? AND e.status='ACTIVE' "
                + "AND p.completed=TRUE GROUP BY e.learner_id HAVING COUNT(DISTINCT c.lesson_id)=?) completed",
                courseId, lessonCount);
    }

    /** 未就绪时全课程正式分数排序和平均均禁用，防止通过跨任务聚合推断尚未开放的成绩。 */
    public boolean formalScoresReady(String courseId) {
        return number("SELECT COUNT(*) FROM assessment a WHERE a.course_id=? AND a.ownership_type='COURSE' "
                + "AND a.status<>'DRAFT' AND a.assessment_type IN ('HOMEWORK','EXAM') AND "
                + "(a.close_status<>'CLOSED' OR a.settlement_status<>'COMPLETED' OR EXISTS "
                + "(SELECT 1 FROM assessment_participant p JOIN enrollment e ON e.course_id=a.course_id "
                + "AND e.learner_id=p.learner_id AND e.status='ACTIVE' WHERE p.assessment_id=a.id "
                + "AND p.submission_status<>'MISSING' AND p.grading_status<>'GRADED'))", courseId) == 0;
    }

    private static final String STUDENT_SELECT = "SELECT n.learner_id,n.student_number,"
            + "(SELECT COUNT(*) FROM lesson_progress p JOIN published_lesson_content_projection c "
            + "ON c.course_id=p.course_id AND c.content_revision_id=p.content_revision_id "
            + "AND c.lesson_type IN ('VIDEO','ARTICLE') "
            + "WHERE p.course_id=e.course_id AND p.learner_id=e.learner_id AND p.completed=TRUE) completed_lessons,"
            + "(SELECT COALESCE(SUM(p.watched_seconds),0) FROM lesson_progress p JOIN published_lesson_content_projection c "
            + "ON c.course_id=p.course_id AND c.content_revision_id=p.content_revision_id AND c.lesson_type='VIDEO' "
            + "WHERE p.course_id=e.course_id AND p.learner_id=e.learner_id) watched_seconds,"
            + "GREATEST(e.enrolled_at,COALESCE((SELECT MAX(p.last_event_at) FROM lesson_progress p "
            + "JOIN published_lesson_content_projection c ON c.course_id=p.course_id AND c.content_revision_id=p.content_revision_id "
            + "WHERE p.course_id=e.course_id AND p.learner_id=e.learner_id),e.enrolled_at),"
            + "COALESCE((SELECT MAX(t.submitted_at) FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id "
            + "WHERE a.course_id=e.course_id AND a.ownership_type='COURSE' AND t.learner_id=e.learner_id),e.enrolled_at),"
            + "COALESCE((SELECT MAX(ad.last_activity_at) FROM learning_activity_day ad "
            + "WHERE ad.course_id=e.course_id AND ad.learner_id=e.learner_id),e.enrolled_at)) last_activity_at,"
            + "(SELECT COUNT(*) FROM assessment_participant p JOIN assessment a ON a.id=p.assessment_id "
            + "WHERE a.course_id=e.course_id AND a.ownership_type='COURSE' AND a.assessment_type IN ('HOMEWORK','EXAM') "
            + "AND p.learner_id=e.learner_id AND p.submission_status='MISSING') missing_count,"
            + "(SELECT COUNT(*) FROM assessment_participant p JOIN assessment a ON a.id=p.assessment_id "
            + "WHERE a.course_id=e.course_id AND a.ownership_type='COURSE' AND a.assessment_type IN ('HOMEWORK','EXAM') "
            + "AND p.learner_id=e.learner_id AND p.grading_status IN ('PENDING','PARTIALLY_GRADED')) pending_count,"
            + "(SELECT AVG(CASE WHEN p.submission_status='MISSING' THEN 0 ELSE t.score*100/NULLIF(t.total_points,0) END) "
            + "FROM assessment_participant p JOIN assessment a ON a.id=p.assessment_id "
            + "LEFT JOIN assessment_attempt t ON t.id=p.final_attempt_id WHERE a.course_id=e.course_id "
            + "AND a.ownership_type='COURSE' AND a.assessment_type='HOMEWORK' AND p.learner_id=e.learner_id "
            + "AND (p.submission_status='MISSING' OR p.grading_status='GRADED')) homework_average,"
            + "(SELECT AVG(CASE WHEN p.submission_status='MISSING' THEN 0 ELSE t.score*100/NULLIF(t.total_points,0) END) "
            + "FROM assessment_participant p JOIN assessment a ON a.id=p.assessment_id "
            + "LEFT JOIN assessment_attempt t ON t.id=p.final_attempt_id WHERE a.course_id=e.course_id "
            + "AND a.ownership_type='COURSE' AND a.assessment_type='EXAM' AND p.learner_id=e.learner_id "
            + "AND (p.submission_status='MISSING' OR p.grading_status='GRADED')) exam_average "
            + "FROM enrollment e JOIN course_student_number n ON n.course_id=e.course_id AND n.learner_id=e.learner_id "
            + "WHERE e.course_id=? AND e.status='ACTIVE'";

    /** 稳定白名单排序；昵称候选先由 IAM 解析，不能把请求文本拼进 SQL。 */
    public List<Map<String, Object>> students(String courseId, int lessonCount, String sort, boolean descending,
            String keyword, List<String> nicknameMatches, BigDecimal minProgress, BigDecimal maxProgress,
            boolean missingOnly, boolean pendingOnly, int page, int size) {
        StudentQuery query = studentQuery(courseId, lessonCount, keyword, nicknameMatches, minProgress, maxProgress,
                missingOnly, pendingOnly);
        String order = switch (sort) {
            case "LAST_ACTIVITY" -> "last_activity_at";
            case "PROGRESS" -> "completed_lessons";
            case "WATCHED_SECONDS" -> "watched_seconds";
            case "HOMEWORK_AVERAGE" -> "homework_average";
            case "EXAM_AVERAGE" -> "exam_average";
            default -> "student_number";
        };
        query.args().add(size);
        query.args().add(((long) page - 1) * size);
        return jdbc.queryForList("SELECT * FROM (" + STUDENT_SELECT + ") s " + query.where()
                + " ORDER BY " + order + (descending ? " DESC" : " ASC")
                + ",student_number ASC LIMIT ? OFFSET ?", query.args().toArray());
    }

    /** 使用与列表同一筛选条件计数，供分页器展示真实总数。 */
    public int countStudents(String courseId, int lessonCount, String keyword, List<String> nicknameMatches,
            BigDecimal minProgress, BigDecimal maxProgress, boolean missingOnly, boolean pendingOnly) {
        StudentQuery query = studentQuery(courseId, lessonCount, keyword, nicknameMatches, minProgress, maxProgress,
                missingOnly, pendingOnly);
        return jdbc.queryForObject("SELECT COUNT(*) FROM (" + STUDENT_SELECT + ") s " + query.where(),
                Integer.class, query.args().toArray());
    }

    /** 只读取一名 ACTIVE 学生当前内容和课程任务事实；无记录返回 null。 */
    public Map<String, Object> student(String courseId, String learnerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM (" + STUDENT_SELECT
                + ") s WHERE learner_id=?", courseId, learnerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private StudentQuery studentQuery(String courseId, int lessonCount, String keyword, List<String> nicknameMatches,
            BigDecimal minProgress, BigDecimal maxProgress, boolean missingOnly, boolean pendingOnly) {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> args = new ArrayList<>(); args.add(courseId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (CAST(student_number AS CHAR) LIKE ?");
            args.add("%" + keyword + "%");
            if (!nicknameMatches.isEmpty()) {
                where.append(" OR learner_id IN (").append("?,".repeat(nicknameMatches.size()));
                where.setLength(where.length() - 1); where.append(')'); args.addAll(nicknameMatches);
            }
            where.append(')');
        }
        if (minProgress != null) { where.append(" AND completed_lessons*100.0/? >= ?"); args.add(Math.max(lessonCount, 1)); args.add(minProgress); }
        if (maxProgress != null) { where.append(" AND completed_lessons*100.0/? <= ?"); args.add(Math.max(lessonCount, 1)); args.add(maxProgress); }
        if (missingOnly) where.append(" AND missing_count>0");
        if (pendingOnly) where.append(" AND pending_count>0");
        return new StudentQuery(where.toString(), args);
    }
    private record StudentQuery(String where, List<Object> args) {}

    /** 只查 Course 发布目录当前页的课时 ID；调用方按 Course 返回顺序组装，不依赖 UUID 字典序。 */
    public List<Map<String, Object>> studentLessonsByIds(String courseId, String learnerId, List<String> lessonIds) {
        if (lessonIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(); args.add(learnerId); args.add(courseId); args.addAll(lessonIds);
        return jdbc.queryForList("SELECT c.lesson_id,c.content_revision_id,c.lesson_type,p.id progress_id,"
                + "p.completed,p.watched_seconds,p.last_event_at FROM published_lesson_content_projection c "
                + "LEFT JOIN lesson_progress p ON p.course_id=c.course_id AND p.content_revision_id=c.content_revision_id "
                + "AND p.learner_id=? WHERE c.course_id=? AND c.lesson_id IN ("
                + placeholders(lessonIds.size()) + ")", args.toArray());
    }

    /** 仅聚合 Course 当前页指定的课时 ID；无 ID 不访问数据库。 */
    public List<Map<String, Object>> courseLessonsByIds(String courseId, List<String> lessonIds) {
        if (lessonIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(); args.add(courseId); args.addAll(lessonIds);
        return jdbc.queryForList("SELECT c.lesson_id,c.content_revision_id,c.lesson_type,"
                + "COUNT(p.id) started_count,SUM(CASE WHEN p.completed THEN 1 ELSE 0 END) completed_count,"
                + "COALESCE(SUM(CASE WHEN c.lesson_type='VIDEO' THEN p.watched_seconds ELSE 0 END),0) watched_seconds,"
                + "SUM(CASE WHEN c.lesson_type='VIDEO' AND p.watched_seconds>0 THEN 1 ELSE 0 END) viewer_count "
                + "FROM published_lesson_content_projection c LEFT JOIN lesson_progress p "
                + "ON p.course_id=c.course_id AND p.content_revision_id=c.content_revision_id "
                + "AND EXISTS (SELECT 1 FROM enrollment e WHERE e.course_id=c.course_id AND e.learner_id=p.learner_id "
                + "AND e.status='ACTIVE') WHERE c.course_id=? AND c.lesson_id IN ("
                + placeholders(lessonIds.size()) + ") "
                + "GROUP BY c.lesson_id,c.content_revision_id,c.lesson_type", args.toArray());
    }

    /** 课程总览使用数据库聚合，不构造全班课时矩阵。 */
    public Map<String, Object> courseTotals(String courseId) {
        return jdbc.queryForMap("SELECT COUNT(DISTINCT CASE WHEN p.watched_seconds>0 AND c.lesson_type='VIDEO' "
                + "THEN p.learner_id END) viewer_count,COALESCE(SUM(CASE WHEN c.lesson_type='VIDEO' "
                + "THEN p.watched_seconds ELSE 0 END),0) watched_seconds FROM lesson_progress p "
                + "JOIN published_lesson_content_projection c ON c.course_id=p.course_id "
                + "AND c.content_revision_id=p.content_revision_id JOIN enrollment e ON e.course_id=p.course_id "
                + "AND e.learner_id=p.learner_id AND e.status='ACTIVE' WHERE p.course_id=?", courseId);
    }

    /** 当前页只读取 COURSE 归属、已发布或归档任务；自主和公共 PRACTICE 均被 ownership_type 排除。 */
    public List<Map<String, Object>> tasks(String courseId, int page, int size) {
        return jdbc.queryForList("SELECT id,lesson_id,title,assessment_type,status,close_status,settlement_status "
                + "FROM assessment WHERE course_id=? AND ownership_type='COURSE' AND status IN ('PUBLISHED','ARCHIVED') "
                + "ORDER BY published_at DESC,id LIMIT ? OFFSET ?", courseId, size, ((long) page - 1) * size);
    }
    /** 仅统计 COURSE 归属的已发布或归档任务数，供分页总数使用。 */
    public int taskCount(String courseId) {
        return number("SELECT COUNT(*) FROM assessment WHERE course_id=? AND ownership_type='COURSE' "
                + "AND status IN ('PUBLISHED','ARCHIVED')", courseId);
    }

    /** 正式任务使用 participant 的最后有效 attempt；PRACTICE 按学生最高有效百分制分组。 */
    public Map<String, Object> taskCounts(String courseId, String assessmentId, boolean practice) {
        if (practice) return jdbc.queryForMap("SELECT COUNT(DISTINCT t.learner_id) submitted_count,"
                + "COUNT(DISTINCT t.learner_id) expected_count,0 missing_count,0 late_count,0 pending_count "
                + "FROM assessment_attempt t JOIN enrollment e ON e.learner_id=t.learner_id AND e.course_id=? "
                + "AND e.status='ACTIVE' WHERE t.assessment_id=? AND t.status<>'DRAFT'", courseId, assessmentId);
        return jdbc.queryForMap("SELECT COUNT(p.id) expected_count,"
                + "SUM(CASE WHEN p.submission_status='SUBMITTED' THEN 1 ELSE 0 END) submitted_count,"
                + "SUM(CASE WHEN p.submission_status='MISSING' THEN 1 ELSE 0 END) missing_count,"
                + "SUM(CASE WHEN p.late_submission THEN 1 ELSE 0 END) late_count,"
                + "SUM(CASE WHEN p.submission_status<>'MISSING' AND p.grading_status<>'GRADED' "
                + "THEN 1 ELSE 0 END) pending_count FROM assessment_participant p JOIN enrollment e "
                + "ON e.course_id=? AND e.learner_id=p.learner_id AND e.status='ACTIVE' WHERE p.assessment_id=?",
                courseId, assessmentId);
    }

    /** 读取单人正式最终 attempt 的提交与批改状态或 PRACTICE 最高有效成绩；服务层分别裁剪个人分数和班级排名。 */
    public Map<String, Object> studentTask(String courseId, String assessmentId, String learnerId, boolean practice) {
        if (practice) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT t.score raw_score,t.total_points,"
                    + "t.score*100/NULLIF(t.total_points,0) score_percent,"
                    + "(SELECT COUNT(*) FROM assessment_attempt all_t WHERE all_t.assessment_id=? "
                    + "AND all_t.learner_id=? AND all_t.status<>'DRAFT') submission_count "
                    + "FROM assessment_attempt t WHERE t.assessment_id=? AND t.learner_id=? "
                    + "AND t.status='GRADED' AND t.score IS NOT NULL AND t.total_points>0 "
                    + "ORDER BY score_percent DESC,t.submitted_at DESC,t.id DESC LIMIT 1",
                    assessmentId, learnerId, assessmentId, learnerId);
            if (!rows.isEmpty()) return rows.get(0);
            return jdbc.queryForMap("SELECT COUNT(*) submission_count FROM assessment_attempt t "
                    + "WHERE t.assessment_id=? AND t.learner_id=? AND t.status<>'DRAFT'", assessmentId, learnerId);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT p.submission_status,p.grading_status,p.late_submission,"
                + "t.status attempt_status,"
                + "t.score raw_score,t.total_points,t.score*100/NULLIF(t.total_points,0) score_percent "
                + "FROM assessment_participant p LEFT JOIN assessment_attempt t ON t.id=p.final_attempt_id "
                + "WHERE p.assessment_id=? AND p.learner_id=?", assessmentId, learnerId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** 比较同任务 ACTIVE 学生有效成绩，COUNT(>) 生成竞赛排名 1、1、3。 */
    public Map<String, Object> rank(String courseId, String assessmentId, String learnerId, boolean practice) {
        String scored = practice
                ? "SELECT t.learner_id,MAX(t.score*100/NULLIF(t.total_points,0)) pct FROM assessment_attempt t "
                + "JOIN enrollment e ON e.course_id=? AND e.learner_id=t.learner_id AND e.status='ACTIVE' "
                + "WHERE t.assessment_id=? AND t.status='GRADED' AND t.score IS NOT NULL AND t.total_points>0 "
                + "GROUP BY t.learner_id"
                : "SELECT p.learner_id,t.score*100/NULLIF(t.total_points,0) pct FROM assessment_participant p "
                + "JOIN enrollment e ON e.course_id=? AND e.learner_id=p.learner_id AND e.status='ACTIVE' "
                + "JOIN assessment_attempt t ON t.id=p.final_attempt_id WHERE p.assessment_id=? "
                + "AND p.submission_status='SUBMITTED' AND p.grading_status='GRADED' "
                + "AND t.score IS NOT NULL AND t.total_points>0";
        return jdbc.queryForMap("WITH scored AS (" + scored + "), mine AS "
                + "(SELECT pct FROM scored WHERE learner_id=?) SELECT COUNT(*) ranked_count,"
                + "SUM(CASE WHEN s.pct>(SELECT pct FROM mine) THEN 1 ELSE 0 END)+1 rank_value "
                + "FROM scored s", courseId, assessmentId, learnerId);
    }

    /** 仅由已打开成绩门禁的服务调用，数据库按 ACTIVE 有效成绩计算平均/极值/分布。 */
    public Map<String, Object> scoreStats(String courseId, String assessmentId, boolean practice) {
        String scored = practice
                ? "SELECT t.learner_id,MAX(t.score*100/NULLIF(t.total_points,0)) pct FROM assessment_attempt t "
                + "JOIN enrollment e ON e.course_id=? AND e.learner_id=t.learner_id AND e.status='ACTIVE' "
                + "WHERE t.assessment_id=? AND t.status='GRADED' AND t.score IS NOT NULL AND t.total_points>0 "
                + "GROUP BY t.learner_id"
                : "SELECT p.learner_id,t.score*100/NULLIF(t.total_points,0) pct FROM assessment_participant p "
                + "JOIN enrollment e ON e.course_id=? AND e.learner_id=p.learner_id AND e.status='ACTIVE' "
                + "JOIN assessment_attempt t ON t.id=p.final_attempt_id WHERE p.assessment_id=? "
                + "AND p.submission_status='SUBMITTED' AND p.grading_status='GRADED' "
                + "AND t.score IS NOT NULL AND t.total_points>0";
        return jdbc.queryForMap("SELECT AVG(pct) average_score,MAX(pct) highest_score,MIN(pct) lowest_score,"
                + "SUM(CASE WHEN pct<60 THEN 1 ELSE 0 END) band_0_59,"
                + "SUM(CASE WHEN pct>=60 AND pct<70 THEN 1 ELSE 0 END) band_60_69,"
                + "SUM(CASE WHEN pct>=70 AND pct<80 THEN 1 ELSE 0 END) band_70_79,"
                + "SUM(CASE WHEN pct>=80 AND pct<90 THEN 1 ELSE 0 END) band_80_89,"
                + "SUM(CASE WHEN pct>=90 THEN 1 ELSE 0 END) band_90_100 FROM (" + scored + ") s",
                courseId, assessmentId);
    }

    /** 当前修订日事实和真实 COURSE 正式提交按周期在数据库去重；月度人数不能累加每日人数。 */
    public List<Map<String, Object>> trend(String courseId, String learnerId, String lessonId,
            LocalDate start, LocalDate end, boolean monthly) {
        String period = monthly ? "EXTRACT(YEAR FROM activity_date)*100+EXTRACT(MONTH FROM activity_date)"
                : "activity_date";
        StringBuilder sql = new StringBuilder("SELECT " + period + " period_key,"
                + "SUM(watched_seconds) watched_seconds,COUNT(DISTINCT learner_id) active_students,"
                + "SUM(first_started) started_lessons,SUM(first_completed) completed_lessons,"
                + "SUM(formal_submission) formal_submissions,COUNT(DISTINCT CASE WHEN formal_submission=1 "
                + "THEN learner_id END) submitting_students,SUM(auto_settled) auto_settled_submissions FROM ("
                + "SELECT d.activity_date,d.learner_id,d.watched_seconds,d.first_started,d.first_completed,"
                + "0 formal_submission,0 auto_settled FROM lesson_learning_day d "
                + "JOIN published_lesson_content_projection c ON c.course_id=d.course_id "
                + "AND c.content_revision_id=d.content_revision_id AND c.lesson_type IN ('VIDEO','ARTICLE') "
                + "JOIN enrollment e ON e.course_id=d.course_id "
                + "AND e.learner_id=d.learner_id AND e.status='ACTIVE' WHERE d.course_id=? "
                + "AND d.activity_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(courseId, Date.valueOf(start), Date.valueOf(end)));
        if (learnerId != null) { sql.append(" AND d.learner_id=?"); args.add(learnerId); }
        if (lessonId != null) { sql.append(" AND d.lesson_id=?"); args.add(lessonId); }
        if (lessonId == null) {
            sql.append(" UNION ALL SELECT ad.activity_date,ad.learner_id,0,0,0,0,0 "
                    + "FROM learning_activity_day ad JOIN enrollment e ON e.course_id=ad.course_id "
                    + "AND e.learner_id=ad.learner_id AND e.status='ACTIVE' "
                    + "WHERE ad.course_id=? AND ad.activity_date BETWEEN ? AND ?");
            args.add(courseId); args.add(Date.valueOf(start)); args.add(Date.valueOf(end));
            if (learnerId != null) { sql.append(" AND ad.learner_id=?"); args.add(learnerId); }
        }
        sql.append(" UNION ALL SELECT t.submission_day,t.learner_id,0,0,0,1,t.auto_settled "
                + "FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id "
                + "JOIN enrollment e ON e.course_id=a.course_id AND e.learner_id=t.learner_id "
                + "AND e.status='ACTIVE' WHERE a.course_id=? AND a.ownership_type='COURSE' "
                + "AND a.assessment_type IN ('HOMEWORK','EXAM') AND t.status<>'DRAFT' "
                + "AND t.submission_day BETWEEN ? AND ?");
        args.add(courseId); args.add(Date.valueOf(start)); args.add(Date.valueOf(end));
        if (learnerId != null) { sql.append(" AND t.learner_id=?"); args.add(learnerId); }
        if (lessonId != null) { sql.append(" AND a.lesson_id=?"); args.add(lessonId); }
        sql.append(") facts GROUP BY period_key ORDER BY period_key");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 趋势 ALL 的最早真实日事实；旧累计时长和无 submission_day 的旧提交不伪造日期。 */
    public LocalDate earliestCurrentDay(String courseId, String learnerId, String lessonId) {
        StringBuilder sql = new StringBuilder("SELECT MIN(d.activity_date) FROM lesson_learning_day d "
                + "JOIN published_lesson_content_projection c ON c.course_id=d.course_id "
                + "AND c.content_revision_id=d.content_revision_id AND c.lesson_type IN ('VIDEO','ARTICLE') "
                + "JOIN enrollment e ON e.course_id=d.course_id "
                + "AND e.learner_id=d.learner_id AND e.status='ACTIVE' WHERE d.course_id=?");
        List<Object> args = new ArrayList<>(List.of(courseId));
        if (learnerId != null) { sql.append(" AND d.learner_id=?"); args.add(learnerId); }
        if (lessonId != null) { sql.append(" AND d.lesson_id=?"); args.add(lessonId); }
        Date value = jdbc.queryForObject(sql.toString(), Date.class, args.toArray());
        Date activity = null;
        if (lessonId == null) {
            String activitySql = "SELECT MIN(ad.activity_date) FROM learning_activity_day ad JOIN enrollment e "
                    + "ON e.course_id=ad.course_id AND e.learner_id=ad.learner_id AND e.status='ACTIVE' "
                    + "WHERE ad.course_id=?" + (learnerId == null ? "" : " AND ad.learner_id=?");
            activity = learnerId == null ? jdbc.queryForObject(activitySql, Date.class, courseId)
                    : jdbc.queryForObject(activitySql, Date.class, courseId, learnerId);
        }
        StringBuilder submissionSql = new StringBuilder("SELECT MIN(t.submission_day) FROM assessment_attempt t "
                + "JOIN assessment a ON a.id=t.assessment_id JOIN enrollment e ON e.course_id=a.course_id "
                + "AND e.learner_id=t.learner_id AND e.status='ACTIVE' WHERE a.course_id=? "
                + "AND a.ownership_type='COURSE' AND a.assessment_type IN ('HOMEWORK','EXAM') "
                + "AND t.status<>'DRAFT'");
        List<Object> submissionArgs = new ArrayList<>(List.of(courseId));
        if (learnerId != null) { submissionSql.append(" AND t.learner_id=?"); submissionArgs.add(learnerId); }
        if (lessonId != null) { submissionSql.append(" AND a.lesson_id=?"); submissionArgs.add(lessonId); }
        Date submission = jdbc.queryForObject(submissionSql.toString(), Date.class, submissionArgs.toArray());
        LocalDate earliest = value == null ? null : value.toLocalDate();
        for (Date candidate : new Date[]{activity, submission}) {
            if (candidate != null && (earliest == null || candidate.toLocalDate().isBefore(earliest)))
                earliest = candidate.toLocalDate();
        }
        return earliest;
    }

    /** 返回 ACTIVE 当前内容或课程任务已持久化的最新时间；不查看 Redis 未落库值。 */
    public Instant dataThrough(String courseId) {
        Timestamp value = jdbc.queryForObject("SELECT MAX(last_event_at) FROM lesson_progress p "
                + "JOIN published_lesson_content_projection c ON c.course_id=p.course_id "
                + "AND c.content_revision_id=p.content_revision_id AND c.lesson_type IN ('VIDEO','ARTICLE') "
                + "JOIN enrollment e ON e.course_id=p.course_id "
                + "AND e.learner_id=p.learner_id AND e.status='ACTIVE' WHERE p.course_id=?",
                Timestamp.class, courseId);
        Timestamp submission = jdbc.queryForObject("SELECT MAX(t.submitted_at) FROM assessment_attempt t "
                + "JOIN assessment a ON a.id=t.assessment_id JOIN enrollment e ON e.course_id=a.course_id "
                + "AND e.learner_id=t.learner_id AND e.status='ACTIVE' WHERE a.course_id=? "
                + "AND a.ownership_type='COURSE' AND t.status<>'DRAFT'", Timestamp.class, courseId);
        Timestamp activity = jdbc.queryForObject("SELECT MAX(ad.last_activity_at) FROM learning_activity_day ad "
                + "JOIN enrollment e ON e.course_id=ad.course_id AND e.learner_id=ad.learner_id "
                + "AND e.status='ACTIVE' WHERE ad.course_id=?", Timestamp.class, courseId);
        Timestamp enrollment = jdbc.queryForObject("SELECT MAX(enrolled_at) FROM enrollment "
                + "WHERE course_id=? AND status='ACTIVE'", Timestamp.class, courseId);
        Instant latest = null;
        for (Timestamp candidate : new Timestamp[]{value, submission, activity, enrollment}) {
            if (candidate != null && (latest == null || candidate.toInstant().isAfter(latest)))
                latest = candidate.toInstant();
        }
        return latest;
    }

    /** 快照 LIFETIME 的起点可以来自上线前持久事实；它不被拿来伪造历史日趋势。 */
    public Instant earliestLifetimeFact(String courseId) {
        Timestamp progress = jdbc.queryForObject("SELECT MIN(p.created_at) FROM lesson_progress p "
                + "JOIN published_lesson_content_projection c ON c.course_id=p.course_id "
                + "AND c.content_revision_id=p.content_revision_id AND c.lesson_type IN ('VIDEO','ARTICLE') "
                + "JOIN enrollment e ON e.course_id=p.course_id "
                + "AND e.learner_id=p.learner_id AND e.status='ACTIVE' WHERE p.course_id=?",
                Timestamp.class, courseId);
        Timestamp enrolled = jdbc.queryForObject("SELECT MIN(enrolled_at) FROM enrollment "
                + "WHERE course_id=? AND status='ACTIVE'", Timestamp.class, courseId);
        Timestamp submitted = jdbc.queryForObject("SELECT MIN(t.submitted_at) FROM assessment_attempt t "
                + "JOIN assessment a ON a.id=t.assessment_id JOIN enrollment e ON e.course_id=a.course_id "
                + "AND e.learner_id=t.learner_id AND e.status='ACTIVE' WHERE a.course_id=? "
                + "AND a.ownership_type='COURSE' AND t.status<>'DRAFT'", Timestamp.class, courseId);
        Instant earliest = null;
        for (Timestamp candidate : new Timestamp[]{progress, enrolled, submitted}) {
            if (candidate != null && (earliest == null || candidate.toInstant().isBefore(earliest)))
                earliest = candidate.toInstant();
        }
        return earliest;
    }

    private int number(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }
    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
