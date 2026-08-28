package com.eduplatform.learning.persistence;

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
import com.eduplatform.learning.web.LearningDtos.ResultPublicationStatus;
import com.eduplatform.learning.web.QuestionDtos;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * T11B 学习与测评持久化边界。只访问 learning Schema，锁定方法供关闭、结算、提交、评分和进度合并事务使用。
 */
@Repository
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true")
public class LearningAssessmentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LearningAssessmentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }

    public Optional<EnrollmentRow> enrollment(String learnerId, String courseId) {
        return jdbc.query("SELECT * FROM enrollment WHERE learner_id=? AND course_id=?", this::enrollment,
                learnerId, courseId).stream().findFirst();
    }
    public Optional<EnrollmentRow> ownedEnrollment(String id, String learnerId) {
        return jdbc.query("SELECT * FROM enrollment WHERE id=? AND learner_id=?", this::enrollment,
                id, learnerId).stream().findFirst();
    }
    public void insertEnrollment(EnrollmentRow value) {
        jdbc.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,canceled_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                value.id(), value.learnerId(), value.courseId(), value.courseVersionId(), value.status().name(),
                ts(value.enrolledAt()), ts(value.canceledAt()), ts(value.createdAt()), ts(value.updatedAt()));
    }
    public int reactivateEnrollment(String id, String version, Instant now) {
        return jdbc.update("UPDATE enrollment SET course_version_id=?,status='ACTIVE',enrolled_at=?,canceled_at=NULL,updated_at=? WHERE id=? AND status='CANCELED'",
                version, ts(now), ts(now), id);
    }
    public int cancelEnrollment(String id, String learnerId, Instant now) {
        return jdbc.update("UPDATE enrollment SET status='CANCELED',canceled_at=?,updated_at=? WHERE id=? AND learner_id=? AND status='ACTIVE'",
                ts(now), ts(now), id, learnerId);
    }
    public List<EnrollmentRow> listEnrollments(String learnerId, EnrollmentStatus status, int page, int size) {
        String filter = status == null ? "" : " AND status=?";
        Object[] args = status == null ? new Object[]{learnerId, size, (page - 1) * size}
                : new Object[]{learnerId, status.name(), size, (page - 1) * size};
        return jdbc.query("SELECT * FROM enrollment WHERE learner_id=?" + filter
                + " ORDER BY updated_at DESC,id LIMIT ? OFFSET ?", this::enrollment, args);
    }
    public long countEnrollments(String learnerId, EnrollmentStatus status) {
        Long value = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE learner_id=?", Long.class, learnerId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE learner_id=? AND status=?", Long.class,
                        learnerId, status.name());
        return value == null ? 0 : value;
    }

    /** 先记录业务幂等键；主键冲突表示相同 clientEventId 已处理，调用方直接返回当前进度。 */
    public boolean insertProgressEvent(String eventId, String learnerId, String courseId, String lessonId,
            Instant occurredAt, long position, int watched, boolean completed, Instant now) {
        try {
            jdbc.update("INSERT INTO lesson_progress_event(client_event_id,learner_id,course_id,lesson_id,occurred_at,position_seconds,watched_seconds,completed,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    eventId, learnerId, courseId, lessonId, ts(occurredAt), position, watched, completed, ts(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
    public void ensureProgress(String id, String learnerId, String courseId, String lessonId, Instant eventAt, Instant now) {
        jdbc.update("INSERT INTO lesson_progress(id,learner_id,course_id,lesson_id,position_seconds,watched_seconds,completed,last_event_at,revision,created_at,updated_at) VALUES(?,?,?,?,0,0,FALSE,?,0,?,?) ON DUPLICATE KEY UPDATE id=id",
                id, learnerId, courseId, lessonId, ts(eventAt), ts(now), ts(now));
    }
    public ProgressRow lockProgress(String learnerId, String courseId, String lessonId) {
        return jdbc.query("SELECT * FROM lesson_progress WHERE learner_id=? AND course_id=? AND lesson_id=? FOR UPDATE",
                this::progress, learnerId, courseId, lessonId).stream().findFirst().orElseThrow();
    }
    public void mergeProgress(ProgressRow current, long position, int watched, boolean completed,
            Instant eventAt, Instant now) {
        // MySQL/H2 TIMESTAMP(6) 会舍入 Java 纳秒；1ms 容差避免首个事件因精度差被误判为乱序。
        boolean newer = current.revision() == 0 || !eventAt.plusMillis(1).isBefore(current.lastEventAt());
        long mergedPosition = Math.max(current.positionSeconds(), position);
        long watchedCandidate = current.watchedSeconds() + (newer ? watched : 0);
        // 首期课程契约未暴露视频总时长，以最大播放位置的两倍加一个上报窗口作为防刷上限。
        long reasonableWatchedLimit = Math.max(300L, Math.max(current.positionSeconds(), position) * 2L + 300L);
        long mergedWatched = Math.min(watchedCandidate, reasonableWatchedLimit);
        boolean mergedCompleted = current.completed() || completed;
        Instant mergedEventAt = eventAt.isAfter(current.lastEventAt()) ? eventAt : current.lastEventAt();
        jdbc.update("UPDATE lesson_progress SET position_seconds=?,watched_seconds=?,completed=?,last_event_at=?,revision=revision+1,updated_at=? WHERE id=?",
                mergedPosition, mergedWatched, mergedCompleted, ts(mergedEventAt), ts(now), current.id());
    }
    public List<ProgressRow> progress(String learnerId, String courseId) {
        return jdbc.query("SELECT * FROM lesson_progress WHERE learner_id=? AND course_id=? ORDER BY updated_at,id",
                this::progress, learnerId, courseId);
    }

    public void insertNote(NoteRow value) {
        jdbc.update("INSERT INTO learning_note(id,learner_id,course_id,lesson_id,content,position_seconds,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                value.id(), value.learnerId(), value.courseId(), value.lessonId(), value.content(),
                value.positionSeconds(), value.revision(), ts(value.createdAt()), ts(value.updatedAt()));
    }
    public Optional<NoteRow> note(String id, String learnerId) {
        return jdbc.query("SELECT * FROM learning_note WHERE id=? AND learner_id=?", this::note, id, learnerId)
                .stream().findFirst();
    }
    public List<NoteRow> notes(String learnerId, String courseId, String lessonId) {
        return jdbc.query("SELECT * FROM learning_note WHERE learner_id=? AND course_id=? AND lesson_id=? ORDER BY updated_at DESC,id",
                this::note, learnerId, courseId, lessonId);
    }
    public int updateNote(String id, String learnerId, long revision, String content, Long position, Instant now) {
        return jdbc.update("UPDATE learning_note SET content=?,position_seconds=?,revision=revision+1,updated_at=? WHERE id=? AND learner_id=? AND revision=?",
                content, position, ts(now), id, learnerId, revision);
    }
    public int deleteNote(String id, String learnerId) {
        return jdbc.update("DELETE FROM learning_note WHERE id=? AND learner_id=?", id, learnerId);
    }

    public Optional<IdempotencyRow> idempotency(String ownerId, String requestId) {
        return jdbc.query("SELECT * FROM learning_idempotency_request WHERE owner_id=? AND client_request_id=?",
                this::idempotency, ownerId, requestId).stream().findFirst();
    }
    public void insertIdempotency(String ownerId, String requestId, String fingerprint, String type,
            String resourceId, Instant now) {
        jdbc.update("INSERT INTO learning_idempotency_request(owner_id,client_request_id,request_fingerprint,resource_type,resource_id,created_at) VALUES(?,?,?,?,?,?)",
                ownerId, requestId, fingerprint, type, resourceId, ts(now));
    }

    public void insertAssessment(AssessmentRow value) {
        jdbc.update("INSERT INTO assessment(id,owner_teacher_id,course_id,lesson_id,title,description,assessment_type,status,answer_publication_policy,answer_release_policy,grade_publication_policy,close_status,settlement_status,closed_at,close_affected_attempt_count,settlement_attempts,settlement_next_attempt_at,settlement_last_error,grade_publication_status,answer_publication_status,grade_published_at,answer_published_at,opens_at,closes_at,time_limit_seconds,revision,published_at,archived_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.ownerTeacherId(), value.courseId(), value.lessonId(), value.title(),
                value.description(), value.type().name(), value.status().name(), legacyPolicy(value.answerPolicy()),
                canonicalAnswerPolicy(value.answerPolicy()).name(), value.gradePolicy().name(), value.closeStatus().name(),
                value.settlementStatus().name(), ts(value.closedAt()), value.closeAffectedAttemptCount(),
                value.settlementAttempts(), ts(value.settlementNextAttemptAt()), value.settlementLastError(),
                value.gradePublicationStatus().name(), value.answerPublicationStatus().name(),
                ts(value.gradePublishedAt()), ts(value.answerPublishedAt()),
                ts(value.opensAt()), ts(value.closesAt()), value.timeLimitSeconds(), value.revision(),
                ts(value.publishedAt()), ts(value.archivedAt()), ts(value.createdAt()), ts(value.updatedAt()));
    }
    public Optional<AssessmentRow> assessment(String id) {
        return jdbc.query("SELECT * FROM assessment WHERE id=?", this::mapAssessment, id).stream().findFirst();
    }
    public Optional<AssessmentRow> ownedAssessment(String id, String teacherId) {
        return jdbc.query("SELECT * FROM assessment WHERE id=? AND owner_teacher_id=?", this::mapAssessment, id, teacherId)
                .stream().findFirst();
    }
    /** 锁定一场测评的全局状态，确保关闭、结算、提交和自动公布使用同一锁顺序。 */
    public AssessmentRow lockAssessment(String id) {
        return jdbc.query("SELECT * FROM assessment WHERE id=? FOR UPDATE", this::mapAssessment, id)
                .stream().findFirst().orElse(null);
    }
    public AssessmentRow lockOwnedAssessment(String id, String teacherId) {
        return jdbc.query("SELECT * FROM assessment WHERE id=? AND owner_teacher_id=? FOR UPDATE", this::mapAssessment,
                id, teacherId).stream().findFirst().orElse(null);
    }
    public int updateAssessment(String id, String teacherId, long revision, String title, String description,
            AssessmentType type, GradePublicationPolicy gradePolicy, AnswerPublicationPolicy answerPolicy,
            Instant opensAt, Instant closesAt,
            Integer timeLimitSeconds, Instant now) {
        return jdbc.update("UPDATE assessment SET title=?,description=?,assessment_type=?,grade_publication_policy=?,answer_publication_policy=?,answer_release_policy=?,opens_at=?,closes_at=?,time_limit_seconds=?,revision=revision+1,updated_at=? WHERE id=? AND owner_teacher_id=? AND revision=? AND status='DRAFT'",
                title, description, type.name(), gradePolicy.name(), legacyPolicy(answerPolicy),
                canonicalAnswerPolicy(answerPolicy).name(), ts(opensAt), ts(closesAt), timeLimitSeconds,
                ts(now), id, teacherId, revision);
    }
    public int bumpDraftAssessmentRevision(String id, String teacherId, long revision, Instant now) {
        return jdbc.update("UPDATE assessment SET revision=revision+1,updated_at=? WHERE id=? AND owner_teacher_id=? AND revision=? AND status='DRAFT'",
                ts(now), id, teacherId, revision);
    }
    public int deleteDraftAssessment(String id, String teacherId) {
        jdbc.update("DELETE FROM assessment_assignment WHERE assessment_id=?", id);
        jdbc.update("DELETE FROM assessment_question WHERE assessment_id=?", id);
        jdbc.update("DELETE FROM learning_idempotency_request WHERE resource_type='ASSESSMENT' AND resource_id=?", id);
        return jdbc.update("DELETE FROM assessment WHERE id=? AND owner_teacher_id=? AND status='DRAFT'", id, teacherId);
    }
    public int advanceAssessmentStatus(String id, String teacherId, long revision, AssessmentStatus from,
            AssessmentStatus to, Instant now) {
        return jdbc.update("UPDATE assessment SET status=?,revision=revision+1,published_at=CASE WHEN ?='PUBLISHED' THEN ? ELSE published_at END,archived_at=CASE WHEN ?='ARCHIVED' THEN ? ELSE archived_at END,updated_at=? WHERE id=? AND owner_teacher_id=? AND revision=? AND status=?",
                to.name(), to.name(), ts(now), to.name(), ts(now), ts(now), id, teacherId, revision, from.name());
    }

    /** 原子写入不可撤销关闭事实，并冻结当时受影响的草稿数量；调用前必须锁定 assessment owner 行。 */
    public int closeAssessment(String id, String teacherId, long revision, int affectedDrafts, Instant now) {
        String settlement = affectedDrafts == 0 ? "COMPLETED" : "PENDING";
        return jdbc.update("UPDATE assessment SET close_status='CLOSED',closed_at=?,close_affected_attempt_count=?,settlement_status=?,settlement_attempts=0,settlement_next_attempt_at=?,settlement_last_error=NULL,revision=revision+1,updated_at=? WHERE id=? AND owner_teacher_id=? AND revision=? AND status='PUBLISHED' AND close_status='OPEN'",
                ts(now), affectedDrafts, settlement, affectedDrafts == 0 ? null : ts(now), ts(now), id,
                teacherId, revision);
    }

    /** 返回当前仍需按服务端最后草稿结算的数量；关闭前也作为二次确认影响人数。 */
    public int countDraftAttempts(String assessmentId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assessment_attempt WHERE assessment_id=? AND status='DRAFT'",
                Integer.class, assessmentId);
        return value == null ? 0 : value;
    }

    /** 每轮只领取固定数量到期任务，不使用 while 扫描整个测评集合。 */
    public List<String> dueSettlementAssessmentIds(Instant now, int limit) {
        return jdbc.query("SELECT id FROM assessment WHERE close_status='CLOSED' AND settlement_status IN ('PENDING','PROCESSING') AND (settlement_next_attempt_at IS NULL OR settlement_next_attempt_at<=?) ORDER BY updated_at,id LIMIT ?",
                (rs, row) -> rs.getString(1), ts(now), limit);
    }

    /** 锁定一次有界结算批次；同一 assessment 行锁使并发调度器不能重复领取同一批。 */
    public List<AttemptRow> draftAttemptsForSettlement(String assessmentId, int limit) {
        return jdbc.query("SELECT * FROM assessment_attempt WHERE assessment_id=? AND status='DRAFT' ORDER BY created_at,id LIMIT ? FOR UPDATE",
                this::mapAttempt, assessmentId, limit);
    }

    /** 将已领取场次标记为处理中；进程中断时整批事务回滚，下一轮仍可重新领取。 */
    public void markSettlementProcessing(String assessmentId, Instant now) {
        jdbc.update("UPDATE assessment SET settlement_status='PROCESSING',updated_at=? WHERE id=? AND close_status='CLOSED' AND settlement_status IN ('PENDING','PROCESSING')",
                ts(now), assessmentId);
    }

    /** 成功批次按剩余草稿推进 PENDING/COMPLETED，并清空本轮瞬时失败计数。 */
    public void markSettlementBatchSucceeded(String assessmentId, int remaining, Instant now) {
        jdbc.update("UPDATE assessment SET settlement_status=?,settlement_attempts=0,settlement_next_attempt_at=?,settlement_last_error=NULL,updated_at=? WHERE id=? AND close_status='CLOSED'",
                remaining == 0 ? "COMPLETED" : "PENDING", remaining == 0 ? null : ts(now), ts(now), assessmentId);
    }

    /** 自动重试最多五次；耗尽后保留 FAILED 事实和待结算数量，不会误判结果就绪。 */
    public void markSettlementBatchFailed(String assessmentId, int attempts, Instant nextAttemptAt,
            String safeError, Instant now) {
        jdbc.update("UPDATE assessment SET settlement_status=?,settlement_attempts=?,settlement_next_attempt_at=?,settlement_last_error=?,updated_at=? WHERE id=? AND close_status='CLOSED' AND settlement_status<>'COMPLETED'",
                attempts >= 5 ? "FAILED" : "PENDING", Math.min(attempts, 5),
                attempts >= 5 ? null : ts(nextAttemptAt), safeError, ts(now), assessmentId);
    }

    /** 读取教师关闭/公布按钮所需精确计数；只聚合本测评，不跨 Schema。 */
    public AssessmentProjectionCounts assessmentProjectionCounts(String assessmentId) {
        return jdbc.query("SELECT "
                        + "(SELECT COUNT(*) FROM assessment_attempt t WHERE t.assessment_id=? AND t.status='DRAFT') active_attempts,"
                        + "(SELECT COUNT(DISTINCT t.id) FROM assessment_attempt t JOIN assessment_answer aa ON aa.attempt_id=t.id WHERE t.assessment_id=? AND t.status<>'DRAFT' AND aa.grading_status='PENDING_MANUAL') pending_manual_attempts,"
                        + "(SELECT COUNT(*) FROM assessment_attempt t JOIN assessment_answer aa ON aa.attempt_id=t.id WHERE t.assessment_id=? AND t.status<>'DRAFT' AND aa.grading_status='PENDING_MANUAL') pending_manual_answers",
                (rs, row) -> new AssessmentProjectionCounts(rs.getInt("active_attempts"),
                        rs.getInt("pending_manual_attempts"), rs.getInt("pending_manual_answers")),
                assessmentId, assessmentId, assessmentId).stream().findFirst()
                .orElse(new AssessmentProjectionCounts(0, 0, 0));
    }

    /** 按 revision 首次写入成绩公布事实；返回 0 表示并发推进或已经公布。 */
    public int publishGrades(String assessmentId, long revision, Instant now) {
        return jdbc.update("UPDATE assessment SET grade_publication_status='PUBLISHED',grade_published_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND grade_publication_status='UNPUBLISHED'",
                ts(now), ts(now), assessmentId, revision);
    }

    /** 按 revision 首次写入参考答案公布事实；返回 0 表示并发推进或已经公布。 */
    public int publishAnswers(String assessmentId, long revision, Instant now) {
        return jdbc.update("UPDATE assessment SET answer_publication_status='PUBLISHED',answer_published_at=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND answer_publication_status='UNPUBLISHED'",
                ts(now), ts(now), assessmentId, revision);
    }
    public void deleteAssessmentQuestions(String assessmentId) {
        jdbc.update("DELETE FROM assessment_question WHERE assessment_id=?", assessmentId);
    }
    public void insertAssessmentQuestion(AssessmentQuestionRow value) {
        jdbc.update("INSERT INTO assessment_question(id,assessment_id,question_id,question_version_id,sequence_no,points,snapshot_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                value.id(), value.assessmentId(), value.questionId(), value.questionVersionId(), value.sequence(),
                value.points(), json(value.snapshot()), ts(value.createdAt()));
    }
    public List<AssessmentQuestionRow> assessmentQuestions(String assessmentId) {
        return jdbc.query("SELECT * FROM assessment_question WHERE assessment_id=? ORDER BY sequence_no,id",
                this::assessmentQuestion, assessmentId);
    }
    public void ensureCourseAssignment(String id, String assessmentId, String courseId, Instant now) {
        jdbc.update("INSERT INTO assessment_assignment(id,assessment_id,assignment_type,target_id,created_at) VALUES(?,?,'COURSE',?,?) ON DUPLICATE KEY UPDATE id=id",
                id, assessmentId, courseId, ts(now));
    }
    /** @return 已发布测评是否真正分配到该课程，避免只凭 assessment.course_id 扩大可见范围。 */
    public boolean isAssignedToCourse(String assessmentId, String courseId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_assignment WHERE assessment_id=? AND assignment_type='COURSE' AND target_id=?",
                Integer.class, assessmentId, courseId);
        return count != null && count > 0;
    }
    public List<AssessmentRow> listTeacherAssessments(String teacherId, String courseId, AssessmentStatus status,
            int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM assessment WHERE owner_teacher_id=?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(); args.add(teacherId);
        if (courseId != null) { sql.append(" AND course_id=?"); args.add(courseId); }
        if (status != null) { sql.append(" AND status=?"); args.add(status.name()); }
        sql.append(" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?"); args.add(size); args.add((page - 1) * size);
        return jdbc.query(sql.toString(), this::mapAssessment, args.toArray());
    }
    public long countTeacherAssessments(String teacherId, String courseId, AssessmentStatus status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM assessment WHERE owner_teacher_id=?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(); args.add(teacherId);
        if (courseId != null) { sql.append(" AND course_id=?"); args.add(courseId); }
        if (status != null) { sql.append(" AND status=?"); args.add(status.name()); }
        Long value = jdbc.queryForObject(sql.toString(), Long.class, args.toArray()); return value == null ? 0 : value;
    }
    public List<AssessmentRow> listLearnerAssessments(String learnerId, String courseId, int page, int size) {
        String extra = courseId == null ? "" : " AND a.course_id=?";
        return jdbc.query("SELECT DISTINCT a.* FROM assessment a JOIN assessment_assignment aa ON aa.assessment_id=a.id AND aa.assignment_type='COURSE' AND aa.target_id=a.course_id LEFT JOIN enrollment e ON e.course_id=a.course_id AND e.learner_id=? AND e.status='ACTIVE' LEFT JOIN assessment_attempt t ON t.assessment_id=a.id AND t.learner_id=? WHERE ((a.status='PUBLISHED' AND e.id IS NOT NULL) OR t.id IS NOT NULL)" + extra
                + " ORDER BY a.published_at DESC,a.id LIMIT ? OFFSET ?", this::mapAssessment,
                courseId == null ? new Object[]{learnerId, learnerId, size, (page - 1) * size}
                        : new Object[]{learnerId, learnerId, courseId, size, (page - 1) * size});
    }
    public long countLearnerAssessments(String learnerId, String courseId) {
        String extra = courseId == null ? "" : " AND a.course_id=?";
        Object[] args = courseId == null ? new Object[]{learnerId, learnerId}
                : new Object[]{learnerId, learnerId, courseId};
        Long value = jdbc.queryForObject("SELECT COUNT(DISTINCT a.id) FROM assessment a JOIN assessment_assignment aa ON aa.assessment_id=a.id AND aa.assignment_type='COURSE' AND aa.target_id=a.course_id LEFT JOIN enrollment e ON e.course_id=a.course_id AND e.learner_id=? AND e.status='ACTIVE' LEFT JOIN assessment_attempt t ON t.assessment_id=a.id AND t.learner_id=? WHERE ((a.status='PUBLISHED' AND e.id IS NOT NULL) OR t.id IS NOT NULL)" + extra,
                Long.class, args); return value == null ? 0 : value;
    }

    public Optional<AttemptRow> attemptForLearner(String assessmentId, String learnerId) {
        return jdbc.query("SELECT * FROM assessment_attempt WHERE assessment_id=? AND learner_id=?", this::mapAttempt,
                assessmentId, learnerId).stream().findFirst();
    }
    public Optional<AttemptRow> ownedAttempt(String id, String learnerId) {
        return jdbc.query("SELECT * FROM assessment_attempt WHERE id=? AND learner_id=?", this::mapAttempt, id, learnerId)
                .stream().findFirst();
    }
    /** 非加锁读取 attempt，用于先确定 assessment 再按统一顺序加锁，避免交叉死锁。 */
    public Optional<AttemptRow> attempt(String id) {
        return jdbc.query("SELECT * FROM assessment_attempt WHERE id=?", this::mapAttempt, id)
                .stream().findFirst();
    }
    public AttemptRow lockAttempt(String id) {
        return jdbc.query("SELECT * FROM assessment_attempt WHERE id=? FOR UPDATE", this::mapAttempt, id)
                .stream().findFirst().orElse(null);
    }
    public void insertAttempt(AttemptRow value) {
        jdbc.update("INSERT INTO assessment_attempt(id,assessment_id,learner_id,status,revision,draft_request_id,draft_fingerprint,submit_request_id,submission_fingerprint,score,total_points,started_at,deadline_at,submitted_at,graded_at,grade_updated_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.assessmentId(), value.learnerId(), value.status().name(), value.revision(),
                value.draftRequestId(), value.draftFingerprint(), value.submitRequestId(), value.submissionFingerprint(),
                value.score(), value.totalPoints(), ts(value.startedAt()), ts(value.deadlineAt()),
                ts(value.submittedAt()), ts(value.gradedAt()), ts(value.gradeUpdatedAt()),
                ts(value.createdAt()), ts(value.updatedAt()));
    }
    public void replaceDraftAnswers(String attemptId, List<LearningDtos.AnswerInput> answers, Instant now) {
        jdbc.update("DELETE FROM assessment_answer WHERE attempt_id=?", attemptId);
        for (LearningDtos.AnswerInput answer : answers) jdbc.update("INSERT INTO assessment_answer(id,attempt_id,assessment_question_id,answer_json,score,correct,grading_status,revision,created_at,updated_at) VALUES(?,?,?,?,NULL,NULL,'PENDING_MANUAL',0,?,?)",
                java.util.UUID.randomUUID().toString(), attemptId, answer.assessmentQuestionId().toString(),
                json(answer.answer()), ts(now), ts(now));
    }
    public int updateDraftMetadata(String attemptId, long revision, String requestId, String fingerprint, Instant now) {
        return jdbc.update("UPDATE assessment_attempt SET draft_request_id=?,draft_fingerprint=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND status='DRAFT'",
                requestId, fingerprint, ts(now), attemptId, revision);
    }
    public List<AnswerRow> answers(String attemptId) {
        return jdbc.query("SELECT * FROM assessment_answer WHERE attempt_id=? ORDER BY assessment_question_id",
                this::answer, attemptId);
    }
    public Optional<AnswerRow> answer(String attemptId, String questionId) {
        return jdbc.query("SELECT * FROM assessment_answer WHERE attempt_id=? AND assessment_question_id=?",
                this::answer, attemptId, questionId).stream().findFirst();
    }
    public void gradeAnswer(String answerId, BigDecimal score, Boolean correct, GradingStatus status,
            String graderId, Instant now) {
        jdbc.update("UPDATE assessment_answer SET score=?,correct=?,grading_status=?,revision=revision+1,graded_by=?,graded_at=?,updated_at=? WHERE id=?",
                score, correct, status.name(), graderId, ts(now), ts(now), answerId);
    }
    public int submitAttempt(String id, long revision, String requestId, String fingerprint, AttemptStatus status,
            BigDecimal score, Instant now) {
        return jdbc.update("UPDATE assessment_attempt SET status=?,revision=revision+1,submit_request_id=?,submission_fingerprint=?,score=?,submitted_at=?,graded_at=CASE WHEN ?='GRADED' THEN ? ELSE NULL END,updated_at=? WHERE id=? AND revision=? AND status='DRAFT'",
                status.name(), requestId, fingerprint, score, ts(now), status.name(), ts(now), ts(now), id, revision);
    }
    /** 汇总首次或重新批改后的最新总分；保留首次完成时间，并推进对学生可见的最后更新时间。 */
    public void updateAttemptGrade(String id, AttemptStatus status, BigDecimal score, Instant gradedAt, Instant now) {
        jdbc.update("UPDATE assessment_attempt SET status=?,revision=revision+1,score=?,graded_at=CASE WHEN graded_at IS NULL THEN ? ELSE graded_at END,grade_updated_at=?,updated_at=? WHERE id=?",
                status.name(), score, ts(gradedAt), ts(now), ts(now), id);
    }
    public void insertManualGrade(String id, String requestId, String attemptId, String answerId,
            String graderId, long answerRevision, BigDecimal score, String comment, Instant now) {
        jdbc.update("INSERT INTO manual_grading_record(id,client_request_id,attempt_id,assessment_answer_id,grader_id,answer_revision,score,comment,graded_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, requestId, attemptId, answerId, graderId, answerRevision, score, comment, ts(now));
    }
    public List<AttemptRow> assessmentAttempts(String assessmentId, int page, int size) {
        return jdbc.query("SELECT * FROM assessment_attempt WHERE assessment_id=? ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
                this::mapAttempt, assessmentId, size, (page - 1) * size);
    }
    public long countAssessmentAttempts(String assessmentId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt WHERE assessment_id=?", Long.class,
                assessmentId); return value == null ? 0 : value;
    }

    /** 同一 learner/course/questionVersion 只累加一行；同一次 attempt 只在首次提交事务调用一次。 */
    public void accumulateWrongQuestion(String id, String learnerId, String courseId, String assessmentId,
            String versionId, String attemptId, Instant now) {
        jdbc.update("INSERT INTO wrong_question(id,learner_id,course_id,assessment_id,question_version_id,wrong_count,mastery_status,revision,redo_attempts_used,first_wrong_at,last_wrong_at,last_attempt_id,updated_at) VALUES(?,?,?,?,?,1,'LEARNING',0,0,?,?,?,?) ON DUPLICATE KEY UPDATE wrong_count=wrong_count+1,mastery_status='LEARNING',revision=revision+1,last_wrong_at=VALUES(last_wrong_at),last_attempt_id=VALUES(last_attempt_id),assessment_id=VALUES(assessment_id),updated_at=VALUES(updated_at)",
                id, learnerId, courseId, assessmentId, versionId, ts(now), ts(now), attemptId, ts(now));
    }
    /** 每个 attempt 的题目只累计一次错题，重复提交或重复批改不会无限增加计数。 */
    public boolean markWrongOccurrence(String attemptId, String assessmentQuestionId, Instant now) {
        try {
            jdbc.update("INSERT INTO wrong_question_occurrence(attempt_id,assessment_question_id,created_at) VALUES(?,?,?)",
                    attemptId, assessmentQuestionId, ts(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
    public List<WrongRow> wrongQuestions(String learnerId, String courseId, int page, int size) {
        String extra = courseId == null ? "" : " AND course_id=?";
        Object[] args = courseId == null ? new Object[]{learnerId, size, (page - 1) * size}
                : new Object[]{learnerId, courseId, size, (page - 1) * size};
        return jdbc.query("SELECT * FROM wrong_question WHERE learner_id=?" + extra
                + " ORDER BY last_wrong_at DESC,id LIMIT ? OFFSET ?", this::wrong, args);
    }
    public long countWrongQuestions(String learnerId, String courseId) {
        Long value = courseId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question WHERE learner_id=?", Long.class, learnerId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM wrong_question WHERE learner_id=? AND course_id=?", Long.class, learnerId, courseId);
        return value == null ? 0 : value;
    }
    public Optional<WrongRow> wrongQuestion(String id, String learnerId) {
        return jdbc.query("SELECT * FROM wrong_question WHERE id=? AND learner_id=?", this::wrong, id, learnerId)
                .stream().findFirst();
    }
    public WrongRow lockWrongQuestion(String id, String learnerId) {
        return jdbc.query("SELECT * FROM wrong_question WHERE id=? AND learner_id=? FOR UPDATE", this::wrong,
                id, learnerId).stream().findFirst().orElse(null);
    }
    public int updateWrongMastery(String id, String learnerId, long revision,
            LearningDtos.WrongQuestionMasteryStatus status, Instant now) {
        return jdbc.update("UPDATE wrong_question SET mastery_status=?,revision=revision+1,updated_at=? WHERE id=? AND learner_id=? AND revision=?",
                status.name(), ts(now), id, learnerId, revision);
    }
    public Optional<RedoRow> redoByRequest(String learnerId, String requestId) {
        return jdbc.query("SELECT * FROM wrong_question_redo WHERE learner_id=? AND client_request_id=?",
                this::redo, learnerId, requestId).stream().findFirst();
    }
    public List<RedoRow> wrongQuestionRedos(String wrongQuestionId) {
        return jdbc.query("SELECT * FROM wrong_question_redo WHERE wrong_question_id=? ORDER BY sequence_no",
                this::redo, wrongQuestionId);
    }
    /** 锁定错题后写入一次独立重做，并原子消耗最多三次配额。 */
    public void insertWrongQuestionRedo(RedoRow value, long expectedRevision, Instant now) {
        int updated = jdbc.update("UPDATE wrong_question SET redo_attempts_used=redo_attempts_used+1,revision=revision+1,updated_at=? WHERE id=? AND learner_id=? AND revision=? AND redo_attempts_used<3",
                ts(now), value.wrongQuestionId(), value.learnerId(), expectedRevision);
        if (updated == 0) throw new org.springframework.dao.OptimisticLockingFailureException("wrong question redo conflict");
        jdbc.update("INSERT INTO wrong_question_redo(id,wrong_question_id,learner_id,client_request_id,request_fingerprint,sequence_no,answer_json,score,correct,submitted_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.wrongQuestionId(), value.learnerId(), value.clientRequestId(),
                value.requestFingerprint(), value.sequence(), json(value.answer()), value.score(), value.correct(),
                ts(value.submittedAt()));
    }
    /** 查询某个媒体是否冻结在指定测评题目快照内，不查 media Schema。 */
    public boolean assessmentContainsMedia(String assessmentId, String mediaId) {
        return assessmentQuestions(assessmentId).stream().anyMatch(question -> snapshotContainsMedia(question.snapshot(), mediaId));
    }
    public List<StudentProjectionRow> studentProjections(String courseId, int page, int size) {
        return jdbc.query("SELECT e.learner_id,e.status enrollment_status,"
                + "COALESCE((SELECT COUNT(*) FROM lesson_progress p WHERE p.course_id=e.course_id AND p.learner_id=e.learner_id AND p.completed=TRUE),0) completed_lessons,"
                + "COALESCE((SELECT SUM(p.watched_seconds) FROM lesson_progress p WHERE p.course_id=e.course_id AND p.learner_id=e.learner_id),0) watched_seconds,"
                + "COALESCE((SELECT COUNT(*) FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id WHERE a.course_id=e.course_id AND t.learner_id=e.learner_id AND t.status<>'DRAFT'),0) submitted_attempts,"
                + "COALESCE((SELECT COUNT(*) FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id WHERE a.course_id=e.course_id AND t.learner_id=e.learner_id AND t.status='GRADED'),0) graded_attempts,"
                + "(SELECT AVG(t.score) FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id WHERE a.course_id=e.course_id AND t.learner_id=e.learner_id AND t.status='GRADED') average_score "
                + "FROM enrollment e WHERE e.course_id=? ORDER BY e.updated_at DESC,e.learner_id LIMIT ? OFFSET ?",
                this::studentProjection, courseId, size, (page - 1) * size);
    }
    public long countStudentProjections(String courseId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE course_id=?", Long.class, courseId);
        return value == null ? 0 : value;
    }

    public void insertOutbox(QuestionRepository.Outbox event) {
        jdbc.update("INSERT INTO learning_outbox_event(event_id,event_type,event_version,aggregate_id,exchange_name,routing_key,trace_id,payload_json,status,attempts,next_attempt_at,last_error,occurred_at,published_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                event.eventId(), event.eventType(), event.eventVersion(), event.aggregateId(), event.exchange(),
                event.routingKey(), event.traceId(), event.payloadJson(), event.status(), event.attempts(),
                ts(event.nextAttemptAt()), event.lastError(), ts(event.occurredAt()), ts(event.publishedAt()),
                ts(event.updatedAt()));
    }

    private EnrollmentRow enrollment(ResultSet rs, int row) throws SQLException { return new EnrollmentRow(rs.getString("id"), rs.getString("learner_id"), rs.getString("course_id"), rs.getString("course_version_id"), EnrollmentStatus.valueOf(rs.getString("status")), instant(rs,"enrolled_at"), instantNullable(rs,"canceled_at"), instant(rs,"created_at"), instant(rs,"updated_at")); }
    private ProgressRow progress(ResultSet rs, int row) throws SQLException { return new ProgressRow(rs.getString("id"),rs.getString("learner_id"),rs.getString("course_id"),rs.getString("lesson_id"),rs.getLong("position_seconds"),rs.getLong("watched_seconds"),rs.getBoolean("completed"),instant(rs,"last_event_at"),rs.getLong("revision"),instant(rs,"created_at"),instant(rs,"updated_at")); }
    private NoteRow note(ResultSet rs, int row) throws SQLException { long p=rs.getLong("position_seconds"); return new NoteRow(rs.getString("id"),rs.getString("learner_id"),rs.getString("course_id"),rs.getString("lesson_id"),rs.getString("content"),rs.wasNull()?null:p,rs.getLong("revision"),instant(rs,"created_at"),instant(rs,"updated_at")); }
    private IdempotencyRow idempotency(ResultSet rs,int row)throws SQLException{return new IdempotencyRow(rs.getString("owner_id"),rs.getString("client_request_id"),rs.getString("request_fingerprint"),rs.getString("resource_type"),rs.getString("resource_id"),instant(rs,"created_at"));}
    private AssessmentRow mapAssessment(ResultSet rs,int row)throws SQLException{return new AssessmentRow(rs.getString("id"),rs.getString("owner_teacher_id"),rs.getString("course_id"),rs.getString("lesson_id"),rs.getString("title"),rs.getString("description"),AssessmentType.valueOf(rs.getString("assessment_type")),AssessmentStatus.valueOf(rs.getString("status")),GradePublicationPolicy.valueOf(rs.getString("grade_publication_policy")),AnswerPublicationPolicy.valueOf(rs.getString("answer_release_policy")),AssessmentCloseStatus.valueOf(rs.getString("close_status")),AssessmentSettlementStatus.valueOf(rs.getString("settlement_status")),instantNullable(rs,"closed_at"),rs.getInt("close_affected_attempt_count"),rs.getInt("settlement_attempts"),instantNullable(rs,"settlement_next_attempt_at"),rs.getString("settlement_last_error"),ResultPublicationStatus.valueOf(rs.getString("grade_publication_status")),ResultPublicationStatus.valueOf(rs.getString("answer_publication_status")),instantNullable(rs,"grade_published_at"),instantNullable(rs,"answer_published_at"),instantNullable(rs,"opens_at"),instantNullable(rs,"closes_at"),(Integer)rs.getObject("time_limit_seconds"),rs.getLong("revision"),instantNullable(rs,"published_at"),instantNullable(rs,"archived_at"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private AssessmentQuestionRow assessmentQuestion(ResultSet rs,int row)throws SQLException{return new AssessmentQuestionRow(rs.getString("id"),rs.getString("assessment_id"),rs.getString("question_id"),rs.getString("question_version_id"),rs.getInt("sequence_no"),rs.getBigDecimal("points"),read(rs.getString("snapshot_json"),QuestionDtos.VersionResponse.class),instant(rs,"created_at"));}
    private AttemptRow mapAttempt(ResultSet rs,int row)throws SQLException{return new AttemptRow(rs.getString("id"),rs.getString("assessment_id"),rs.getString("learner_id"),AttemptStatus.valueOf(rs.getString("status")),rs.getLong("revision"),rs.getString("draft_request_id"),rs.getString("draft_fingerprint"),rs.getString("submit_request_id"),rs.getString("submission_fingerprint"),rs.getBigDecimal("score"),rs.getBigDecimal("total_points"),instant(rs,"started_at"),instantNullable(rs,"deadline_at"),instantNullable(rs,"submitted_at"),instantNullable(rs,"graded_at"),instantNullable(rs,"grade_updated_at"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private AnswerRow answer(ResultSet rs,int row)throws SQLException{Object raw=rs.getObject("correct");Boolean correct=raw==null?null:(raw instanceof Boolean value?value:((Number)raw).intValue()!=0);return new AnswerRow(rs.getString("id"),rs.getString("attempt_id"),rs.getString("assessment_question_id"),read(rs.getString("answer_json"),new TypeReference<List<String>>(){}),rs.getBigDecimal("score"),correct,GradingStatus.valueOf(rs.getString("grading_status")),rs.getLong("revision"),rs.getString("graded_by"),instantNullable(rs,"graded_at"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private WrongRow wrong(ResultSet rs,int row)throws SQLException{return new WrongRow(rs.getString("id"),rs.getString("learner_id"),rs.getString("course_id"),rs.getString("assessment_id"),rs.getString("question_version_id"),rs.getInt("wrong_count"),LearningDtos.WrongQuestionMasteryStatus.valueOf(rs.getString("mastery_status")),rs.getLong("revision"),rs.getInt("redo_attempts_used"),instant(rs,"first_wrong_at"),instant(rs,"last_wrong_at"),rs.getString("last_attempt_id"),instant(rs,"updated_at"));}
    private RedoRow redo(ResultSet rs,int row)throws SQLException{Object raw=rs.getObject("correct");Boolean correct=raw==null?null:(raw instanceof Boolean value?value:((Number)raw).intValue()!=0);return new RedoRow(rs.getString("id"),rs.getString("wrong_question_id"),rs.getString("learner_id"),rs.getString("client_request_id"),rs.getString("request_fingerprint"),rs.getInt("sequence_no"),read(rs.getString("answer_json"),new TypeReference<List<String>>(){}),rs.getBigDecimal("score"),correct,instant(rs,"submitted_at"));}
    private StudentProjectionRow studentProjection(ResultSet rs,int row)throws SQLException{return new StudentProjectionRow(rs.getString("learner_id"),EnrollmentStatus.valueOf(rs.getString("enrollment_status")),rs.getInt("completed_lessons"),rs.getLong("watched_seconds"),rs.getInt("submitted_attempts"),rs.getInt("graded_attempts"),rs.getBigDecimal("average_score"));}

    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <T> T read(String value, Class<T> type) { try { return mapper.readValue(value,type); } catch(Exception e){throw new IllegalStateException(e);} }
    private <T> T read(String value, TypeReference<T> type) { try { return mapper.readValue(value,type); } catch(Exception e){throw new IllegalStateException(e);} }
    private static boolean snapshotContainsMedia(QuestionDtos.VersionResponse snapshot, String mediaId) {
        if (snapshot.media().stream().anyMatch(media -> media.mediaId().equals(mediaId))) return true;
        return snapshot.children().stream().anyMatch(child -> child.media().stream()
                .anyMatch(media -> media.mediaId().equals(mediaId)));
    }
    private static Timestamp ts(Instant value){return value==null?null:Timestamp.from(value);}
    private static Instant instant(ResultSet rs,String name)throws SQLException{return rs.getTimestamp(name).toInstant();}
    private static Instant instantNullable(ResultSet rs,String name)throws SQLException{Timestamp value=rs.getTimestamp(name);return value==null?null:value.toInstant();}

    public record EnrollmentRow(String id,String learnerId,String courseId,String courseVersionId,EnrollmentStatus status,Instant enrolledAt,Instant canceledAt,Instant createdAt,Instant updatedAt){}
    public record ProgressRow(String id,String learnerId,String courseId,String lessonId,long positionSeconds,long watchedSeconds,boolean completed,Instant lastEventAt,long revision,Instant createdAt,Instant updatedAt){}
    public record NoteRow(String id,String learnerId,String courseId,String lessonId,String content,Long positionSeconds,long revision,Instant createdAt,Instant updatedAt){}
    public record IdempotencyRow(String ownerId,String requestId,String fingerprint,String resourceType,String resourceId,Instant createdAt){}
    public record AssessmentRow(String id,String ownerTeacherId,String courseId,String lessonId,String title,
            String description,AssessmentType type,AssessmentStatus status,GradePublicationPolicy gradePolicy,
            AnswerPublicationPolicy answerPolicy,AssessmentCloseStatus closeStatus,
            AssessmentSettlementStatus settlementStatus,Instant closedAt,int closeAffectedAttemptCount,
            int settlementAttempts,Instant settlementNextAttemptAt,String settlementLastError,
            ResultPublicationStatus gradePublicationStatus,ResultPublicationStatus answerPublicationStatus,
            Instant gradePublishedAt,Instant answerPublishedAt,Instant opensAt,Instant closesAt,
            Integer timeLimitSeconds,long revision,Instant publishedAt,Instant archivedAt,Instant createdAt,
            Instant updatedAt){
        /** 兼容 T11/T11A 测试夹具；新写入默认手动成绩公布、未关闭且未公布。 */
        public AssessmentRow(String id,String ownerTeacherId,String courseId,String lessonId,String title,
                String description,AssessmentType type,AssessmentStatus status,AnswerPublicationPolicy answerPolicy,
                Instant opensAt,Instant closesAt,Integer timeLimitSeconds,long revision,Instant publishedAt,
                Instant archivedAt,Instant createdAt,Instant updatedAt) {
            this(id, ownerTeacherId, courseId, lessonId, title, description, type, status,
                    GradePublicationPolicy.MANUAL, canonicalAnswerPolicy(answerPolicy), AssessmentCloseStatus.OPEN,
                    AssessmentSettlementStatus.NOT_STARTED, null, 0, 0, null, null,
                    ResultPublicationStatus.UNPUBLISHED, ResultPublicationStatus.UNPUBLISHED, null, null,
                    opensAt, closesAt, timeLimitSeconds, revision, publishedAt, archivedAt, createdAt, updatedAt);
        }
    }
    public record AssessmentQuestionRow(String id,String assessmentId,String questionId,String questionVersionId,int sequence,BigDecimal points,QuestionDtos.VersionResponse snapshot,Instant createdAt){}
    public record AttemptRow(String id,String assessmentId,String learnerId,AttemptStatus status,long revision,
            String draftRequestId,String draftFingerprint,String submitRequestId,String submissionFingerprint,
            BigDecimal score,BigDecimal totalPoints,Instant startedAt,Instant deadlineAt,Instant submittedAt,
            Instant gradedAt,Instant gradeUpdatedAt,Instant createdAt,Instant updatedAt){
        /** 兼容既有构造点；首次历史成绩更新时间等于 gradedAt。 */
        public AttemptRow(String id,String assessmentId,String learnerId,AttemptStatus status,long revision,
                String draftRequestId,String draftFingerprint,String submitRequestId,String submissionFingerprint,
                BigDecimal score,BigDecimal totalPoints,Instant startedAt,Instant deadlineAt,Instant submittedAt,
                Instant gradedAt,Instant createdAt,Instant updatedAt) {
            this(id, assessmentId, learnerId, status, revision, draftRequestId, draftFingerprint, submitRequestId,
                    submissionFingerprint, score, totalPoints, startedAt, deadlineAt, submittedAt, gradedAt,
                    gradedAt, createdAt, updatedAt);
        }
    }
    public record AnswerRow(String id,String attemptId,String assessmentQuestionId,List<String> answer,BigDecimal score,Boolean correct,GradingStatus gradingStatus,long revision,String gradedBy,Instant gradedAt,Instant createdAt,Instant updatedAt){}
    public record WrongRow(String id,String learnerId,String courseId,String assessmentId,String questionVersionId,int wrongCount,LearningDtos.WrongQuestionMasteryStatus masteryStatus,long revision,int redoAttemptsUsed,Instant firstWrongAt,Instant lastWrongAt,String lastAttemptId,Instant updatedAt){}
    public record RedoRow(String id,String wrongQuestionId,String learnerId,String clientRequestId,String requestFingerprint,int sequence,List<String> answer,BigDecimal score,Boolean correct,Instant submittedAt){}
    public record StudentProjectionRow(String learnerId,EnrollmentStatus enrollmentStatus,int completedLessons,long watchedSeconds,int submittedAttempts,int gradedAttempts,BigDecimal averageScore){}
    public record AssessmentProjectionCounts(int activeAttempts,int pendingManualAttempts,int pendingManualAnswers){}

    private static AnswerPublicationPolicy canonicalAnswerPolicy(AnswerPublicationPolicy value) {
        return value == AnswerPublicationPolicy.AFTER_GRADED
                ? AnswerPublicationPolicy.AFTER_ASSESSMENT_GRADED : value;
    }

    private static String legacyPolicy(AnswerPublicationPolicy value) {
        return switch (canonicalAnswerPolicy(value)) {
            case AFTER_SUBMISSION -> "AFTER_SUBMISSION";
            case AFTER_ASSESSMENT_GRADED -> "AFTER_GRADED";
            case MANUAL_AFTER_CLOSE, NEVER -> "NEVER";
            case AFTER_GRADED -> throw new IllegalStateException("兼容策略应先规范化");
        };
    }
}
