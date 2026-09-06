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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
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
        return insertProgressEvent(eventId, learnerId, courseId, lessonId, occurredAt, position, watched, completed,
                null, now);
    }

    /** 强制检查点保留稳定 clientEventId 幂等事实；普通 HEARTBEAT 不调用此方法。 */
    public boolean insertProgressEvent(String eventId, String learnerId, String courseId, String lessonId,
            Instant occurredAt, long position, int watched, boolean completed, LearningDtos.ProgressEventType eventType,
            Instant now) {
        try {
            jdbc.update("INSERT INTO lesson_progress_event(client_event_id,learner_id,course_id,lesson_id,occurred_at,position_seconds,watched_seconds,completed,event_type,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    eventId, learnerId, courseId, lessonId, ts(occurredAt), position, watched, completed,
                    eventType == null ? null : eventType.name(), ts(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
    public void ensureProgress(String id, String learnerId, String courseId, String lessonId, Instant eventAt, Instant now) {
        jdbc.update("INSERT INTO lesson_progress(id,learner_id,course_id,lesson_id,position_seconds,watched_seconds,completed,last_event_at,revision,buffer_version,created_at,updated_at) VALUES(?,?,?,?,0,0,FALSE,?,0,0,?,?) ON DUPLICATE KEY UPDATE id=id",
                id, learnerId, courseId, lessonId, ts(eventAt), ts(now), ts(now));
    }

    /** 非加锁读取 MySQL 基线，供 Redis Lua 在首次心跳时避免把已有观看时长重置为零。 */
    public Optional<ProgressRow> findProgress(String learnerId, String courseId, String lessonId) {
        return jdbc.query("SELECT * FROM lesson_progress WHERE learner_id=? AND course_id=? AND lesson_id=?",
                this::progress, learnerId, courseId, lessonId).stream().findFirst();
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

    /**
     * 以 Redis 快照幂等更新 MySQL 聚合。快照中的 watchedSeconds 已是累计值，因此这里取最大值而不是再次相加；
     * buffer_version 防止 MySQL 已提交但 Redis ACK 失败后的重试重复增加 revision。
     */
    @Transactional
    public void applyBufferedProgress(String learnerId, String courseId, String lessonId, long position,
            long watched, boolean completed, Instant eventAt, long bufferVersion, Instant now) {
        ensureProgress(java.util.UUID.randomUUID().toString(), learnerId, courseId, lessonId, eventAt, now);
        ProgressRow current = lockProgress(learnerId, courseId, lessonId);
        if (bufferVersion <= current.bufferVersion()) return;
        long mergedPosition = Math.max(current.positionSeconds(), position);
        long mergedWatched = Math.max(current.watchedSeconds(), watched);
        boolean mergedCompleted = current.completed() || completed;
        Instant mergedEventAt = eventAt.isAfter(current.lastEventAt()) ? eventAt : current.lastEventAt();
        boolean changed = current.revision() == 0 || mergedPosition != current.positionSeconds()
                || mergedWatched != current.watchedSeconds()
                || mergedCompleted != current.completed() || !mergedEventAt.equals(current.lastEventAt());
        jdbc.update("UPDATE lesson_progress SET position_seconds=?,watched_seconds=?,completed=?,last_event_at=?,buffer_version=?,revision=revision+?,updated_at=? WHERE id=?",
                mergedPosition, mergedWatched, mergedCompleted, ts(mergedEventAt), bufferVersion,
                changed ? 1 : 0, ts(now), current.id());
    }
    public List<ProgressRow> progress(String learnerId, String courseId) {
        return jdbc.query("SELECT * FROM lesson_progress WHERE learner_id=? AND course_id=? ORDER BY updated_at,id",
                this::progress, learnerId, courseId);
    }

    /** 查询 ARTICLE 完成命令幂等键；调用方据此拒绝同一 ID 被挪用于其他课时。 */
    public Optional<ArticleCompletionRequestRow> articleCompletionRequest(String clientRequestId) {
        return jdbc.query("SELECT * FROM article_completion_request WHERE client_request_id=?", (rs, row) ->
                new ArticleCompletionRequestRow(rs.getString("client_request_id"), rs.getString("learner_id"),
                        rs.getString("course_id"), rs.getString("lesson_id"), rs.getString("completion_id")),
                clientRequestId).stream().findFirst();
    }

    /** 仅首次插入单调完成事实；唯一约束吸收其他请求 ID 或并发调用。 */
    public boolean insertArticleCompletionFact(ArticleCompletionRow value) {
        try {
            jdbc.update("INSERT INTO article_lesson_completion(id,learner_id,course_id,lesson_id,completed_at,revision,created_at) VALUES(?,?,?,?,?,1,?)",
                    value.id(), value.learnerId(), value.courseId(), value.lessonId(),
                    ts(value.completedAt()), ts(value.completedAt()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public ArticleCompletionRow articleCompletion(String learnerId, String courseId, String lessonId) {
        return jdbc.query("SELECT * FROM article_lesson_completion WHERE learner_id=? AND course_id=? AND lesson_id=?",
                (rs, row) -> new ArticleCompletionRow(rs.getString("id"), rs.getString("learner_id"),
                        rs.getString("course_id"), rs.getString("lesson_id"),
                        rs.getTimestamp("completed_at").toInstant(), rs.getLong("revision")),
                learnerId, courseId, lessonId).stream().findFirst().orElseThrow();
    }

    public boolean insertArticleCompletionRequest(String clientRequestId, String learnerId,
            String courseId, String lessonId, String completionId, Instant now) {
        try {
            jdbc.update("INSERT INTO article_completion_request(client_request_id,learner_id,course_id,lesson_id,completion_id,created_at) VALUES(?,?,?,?,?,?)",
                    clientRequestId, learnerId, courseId, lessonId, completionId, ts(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 首次 ARTICLE 完成只把既有通用进度单向推进为 completed，不写视频位置或观看时长。 */
    public void markArticleProgressCompleted(String learnerId, String courseId, String lessonId, Instant now) {
        jdbc.update("UPDATE lesson_progress SET completed=TRUE,last_event_at=?,revision=revision+1,updated_at=? WHERE learner_id=? AND course_id=? AND lesson_id=? AND completed=FALSE",
                ts(now), ts(now), learnerId, courseId, lessonId);
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
        jdbc.update("INSERT INTO assessment(id,owner_teacher_id,course_id,lesson_id,title,description,assessment_type,status,answer_publication_policy,answer_release_policy,grade_publication_policy,close_status,settlement_status,closed_at,close_affected_attempt_count,settlement_attempts,settlement_next_attempt_at,settlement_last_error,grade_publication_status,answer_publication_status,grade_published_at,answer_published_at,opens_at,closes_at,time_limit_seconds,late_policy,late_until,max_attempts,revision,published_at,archived_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.ownerTeacherId(), value.courseId(), value.lessonId(), value.title(),
                value.description(), value.type().name(), value.status().name(), legacyPolicy(value.answerPolicy()),
                canonicalAnswerPolicy(value.answerPolicy()).name(), value.gradePolicy().name(), value.closeStatus().name(),
                value.settlementStatus().name(), ts(value.closedAt()), value.closeAffectedAttemptCount(),
                value.settlementAttempts(), ts(value.settlementNextAttemptAt()), value.settlementLastError(),
                value.gradePublicationStatus().name(), value.answerPublicationStatus().name(),
                ts(value.gradePublishedAt()), ts(value.answerPublishedAt()),
                ts(value.opensAt()), ts(value.closesAt()), value.timeLimitSeconds(), value.latePolicy().name(),
                ts(value.lateUntil()), value.maxAttempts(), value.revision(),
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
            com.eduplatform.learning.web.LearningDtos.HomeworkLatePolicy latePolicy, Instant lateUntil,
            int maxAttempts, Integer timeLimitSeconds, Instant now) {
        return jdbc.update("UPDATE assessment SET title=?,description=?,assessment_type=?,grade_publication_policy=?,answer_publication_policy=?,answer_release_policy=?,opens_at=?,closes_at=?,time_limit_seconds=?,late_policy=?,late_until=?,max_attempts=?,revision=revision+1,updated_at=? WHERE id=? AND owner_teacher_id=? AND revision=? AND status='DRAFT'",
                title, description, type.name(), gradePolicy.name(), legacyPolicy(answerPolicy),
                canonicalAnswerPolicy(answerPolicy).name(), ts(opensAt), ts(closesAt), timeLimitSeconds,
                latePolicy.name(), ts(lateUntil), maxAttempts, ts(now), id, teacherId, revision);
    }
    /** 只更新尚未最终关闭的时间节点，并按 revision 防止教师覆盖彼此的调整。 */
    public int updateAssessmentDeadline(String id, String teacherId, long revision, Instant closesAt,
            Instant lateUntil, Instant now) {
        return jdbc.update("UPDATE assessment SET closes_at=?,late_until=?,revision=revision+1,updated_at=? WHERE id=? AND owner_teacher_id=? AND revision=? AND status='PUBLISHED' AND close_status='OPEN' AND grade_publication_status='UNPUBLISHED'",
                ts(closesAt), ts(lateUntil), ts(now), id, teacherId, revision);
    }
    /** 截止节点调整后按最终提交时间重算迟交事实，旧 attempt 仍保留不可变审计记录。 */
    public void reclassifyLateSubmissions(String assessmentId, Instant closesAt, Instant now) {
        jdbc.update("UPDATE assessment_attempt SET late_submission=CASE WHEN submitted_at IS NOT NULL AND submitted_at>=? THEN TRUE ELSE FALSE END,updated_at=? WHERE assessment_id=? AND status<>'DRAFT'",
                ts(closesAt), ts(now), assessmentId);
        jdbc.update("UPDATE assessment_participant SET late_submission=COALESCE((SELECT t.late_submission FROM assessment_attempt t WHERE t.id=assessment_participant.final_attempt_id),FALSE),updated_at=?,revision=revision+1 WHERE assessment_id=? AND final_attempt_id IS NOT NULL",
                ts(now), assessmentId);
    }
    /** 截止调整审计只写旧/新节点、操作者和 traceId，不写答案或个人联系方式。 */
    public void insertDeadlineAudit(String id, String assessmentId, String operatorId, long expectedRevision,
            Instant oldClosesAt, Instant newClosesAt, Instant oldLateUntil, Instant newLateUntil,
            String reason, String traceId, Instant occurredAt) {
        jdbc.update("INSERT INTO assessment_deadline_audit(id,assessment_id,operator_id,expected_revision,old_closes_at,new_closes_at,old_late_until,new_late_until,reason,trace_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, assessmentId, operatorId, expectedRevision, ts(oldClosesAt), ts(newClosesAt),
                ts(oldLateUntil), ts(newLateUntil), reason, traceId, ts(occurredAt));
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

    /** 自动关闭使用与教师关闭相同的事务更新，确保多实例只有一个实例能推进 OPEN→CLOSED。 */
    public int closeAssessmentAutomatically(String id, int affectedDrafts, Instant now) {
        String settlement = affectedDrafts == 0 ? "COMPLETED" : "PENDING";
        return jdbc.update("UPDATE assessment SET close_status='CLOSED',closed_at=?,close_affected_attempt_count=?,settlement_status=?,settlement_attempts=0,settlement_next_attempt_at=?,settlement_last_error=NULL,revision=revision+1,updated_at=? WHERE id=? AND status='PUBLISHED' AND close_status='OPEN' AND ((late_policy='DISALLOW' AND closes_at IS NOT NULL AND closes_at<=?) OR (late_policy='ALLOW_UNTIL' AND late_until IS NOT NULL AND late_until<=?))",
                ts(now), affectedDrafts, settlement, affectedDrafts == 0 ? null : ts(now), ts(now), id,
                ts(now), ts(now));
    }

    /** 每轮只领取固定数量到期任务，不使用 while 扫描整个测评集合。 */
    public List<String> dueSettlementAssessmentIds(Instant now, int limit) {
        return jdbc.query("SELECT id FROM assessment WHERE close_status='CLOSED' AND settlement_status IN ('PENDING','PROCESSING') AND (settlement_next_attempt_at IS NULL OR settlement_next_attempt_at<=?) ORDER BY updated_at,id LIMIT ?",
                (rs, row) -> rs.getString(1), ts(now), limit);
    }
    /** 查找达到最终时间节点但尚未关闭的已发布 HOMEWORK/EXAM；每轮由调度器限制数量。 */
    public List<String> dueAssessmentCloseIds(Instant now, int limit) {
        return jdbc.query("SELECT id FROM assessment WHERE status='PUBLISHED' AND close_status='OPEN' AND ((late_policy='DISALLOW' AND closes_at IS NOT NULL AND closes_at<=?) OR (late_policy='ALLOW_UNTIL' AND late_until IS NOT NULL AND late_until<=?)) ORDER BY closes_at,id LIMIT ?",
                (rs, row) -> rs.getString(1), ts(now), ts(now), limit);
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
    /** 结算完全空白草稿；空白草稿不消耗次数，删除的是尚未成为正式 attempt 的临时草稿。 */
    public void deleteEmptyDraftAttempt(String attemptId) {
        jdbc.update("DELETE FROM assessment_attempt WHERE id=? AND status='DRAFT'", attemptId);
    }
    public void insertSettlementAudit(String id, String assessmentId, String operationKey, int processedCount,
            String status, String safeError, Instant occurredAt, Instant updatedAt) {
        jdbc.update("INSERT INTO assessment_settlement_audit(id,assessment_id,operation_key,processed_count,status,safe_error,occurred_at,updated_at) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE processed_count=VALUES(processed_count),status=VALUES(status),safe_error=VALUES(safe_error),updated_at=VALUES(updated_at)",
                id, assessmentId, operationKey, processedCount, status, safeError, ts(occurredAt), ts(updatedAt));
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
        return jdbc.query("SELECT * FROM assessment_attempt WHERE assessment_id=? AND learner_id=? ORDER BY attempt_sequence DESC,id DESC LIMIT 1", this::mapAttempt,
                assessmentId, learnerId).stream().findFirst();
    }
    /** 返回该学习者在该场测评的当前最终 attempt 指针；历史 attempt 永不通过普通投影暴露。 */
    public Optional<AttemptRow> finalAttemptForLearner(String assessmentId, String learnerId) {
        return jdbc.query("SELECT t.* FROM assessment_participant p JOIN assessment_attempt t ON t.id=p.final_attempt_id WHERE p.assessment_id=? AND p.learner_id=?",
                this::mapAttempt, assessmentId, learnerId).stream().findFirst();
    }
    public int latestAttemptSequence(String assessmentId, String learnerId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_sequence),0) FROM assessment_attempt WHERE assessment_id=? AND learner_id=?",
                Integer.class, assessmentId, learnerId);
        return value == null ? 0 : value;
    }
    public boolean isFinalAttempt(String assessmentId, String learnerId, String attemptId) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_participant WHERE assessment_id=? AND learner_id=? AND final_attempt_id=?",
                Integer.class, assessmentId, learnerId, attemptId);
        return value != null && value > 0;
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
        jdbc.update("INSERT INTO assessment_attempt(id,assessment_id,learner_id,status,revision,draft_request_id,draft_fingerprint,submit_request_id,submission_fingerprint,score,total_points,started_at,deadline_at,submitted_at,graded_at,grade_updated_at,attempt_sequence,late_submission,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.assessmentId(), value.learnerId(), value.status().name(), value.revision(),
                value.draftRequestId(), value.draftFingerprint(), value.submitRequestId(), value.submissionFingerprint(),
                value.score(), value.totalPoints(), ts(value.startedAt()), ts(value.deadlineAt()),
                ts(value.submittedAt()), ts(value.gradedAt()), ts(value.gradeUpdatedAt()),
                value.attemptSequence(), value.lateSubmission(), ts(value.createdAt()), ts(value.updatedAt()));
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
    /** 领取下一次不可变 attempt；参与者行由调用方先锁定，数据库唯一键防止并发重复序号。 */
    public void insertParticipant(String id, String assessmentId, String learnerId, Instant now) {
        jdbc.update("INSERT INTO assessment_participant(id,assessment_id,learner_id,enrollment_status,submission_status,grading_status,attempts_used,final_attempt_id,last_submitted_at,late_submission,revision,created_at,updated_at) VALUES(?,?,?,'ACTIVE','NOT_STARTED','NOT_REQUIRED',0,NULL,NULL,FALSE,0,?,?) ON DUPLICATE KEY UPDATE id=id",
                id, assessmentId, learnerId, ts(now), ts(now));
    }

    /**
     * 在测评发布或关闭前批量建立课程当前 ACTIVE 报名的应交名单。
     * 输入是课程和测评稳定 ID，输出是实际插入的行数；唯一键吸收重试，已有 ACTIVE 行保持 revision 不变，
     * 已关闭测评直接跳过，避免任何读取路径重新修复名单。
     */
    public int ensureParticipantsForAssessment(String courseId, String assessmentId, Instant now) {
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM assessment WHERE id=? AND course_id=? "
                + "AND status='PUBLISHED' AND close_status='OPEN'",
                Integer.class, assessmentId, courseId);
        if (open == null || open == 0) return 0;
        List<Object[]> arguments = activeEnrollments(courseId).stream()
                .map(enrollment -> new Object[]{UUID.randomUUID().toString(), assessmentId,
                        enrollment.learnerId(), ts(now), ts(now)})
                .toList();
        int inserted = arguments.isEmpty() ? 0 : java.util.Arrays.stream(jdbc.batchUpdate(
                "INSERT INTO assessment_participant(id,assessment_id,learner_id,enrollment_status,submission_status,"
                        + "grading_status,attempts_used,final_attempt_id,last_submitted_at,late_submission,revision,created_at,updated_at) "
                        + "VALUES(?,?,?,'ACTIVE','NOT_STARTED','NOT_REQUIRED',0,NULL,NULL,FALSE,0,?,?) "
                        + "ON DUPLICATE KEY UPDATE id=id", arguments)).sum();
        // 退课后重新报名时，只有 WITHDRAWN 行需要推进；ACTIVE 行绝不重复增加 revision。
        int reactivated = jdbc.update("UPDATE assessment_participant p SET enrollment_status='ACTIVE',"
                + "submission_status=CASE WHEN p.final_attempt_id IS NULL THEN 'NOT_STARTED' ELSE 'SUBMITTED' END,"
                + "updated_at=?,revision=revision+1 WHERE p.assessment_id=? AND p.enrollment_status='WITHDRAWN' "
                + "AND p.learner_id IN (SELECT e.learner_id FROM enrollment e WHERE e.course_id=? AND e.status='ACTIVE')",
                ts(now), assessmentId, courseId);
        return inserted + reactivated;
    }

    /** 在报名/重报名事务中批量建立或恢复该学习者在所有未关闭正式测评中的名单行。 */
    public int ensureOpenParticipantsForLearner(String courseId, String learnerId, Instant now) {
        List<AssessmentRow> assessments = openPublishedCourseAssessments(courseId);
        if (assessments.isEmpty()) return 0;
        List<Object[]> arguments = assessments.stream()
                .map(assessment -> new Object[]{UUID.randomUUID().toString(), assessment.id(), learnerId,
                        ts(now), ts(now)})
                .toList();
        int inserted = java.util.Arrays.stream(jdbc.batchUpdate(
                "INSERT INTO assessment_participant(id,assessment_id,learner_id,enrollment_status,submission_status,"
                        + "grading_status,attempts_used,final_attempt_id,last_submitted_at,late_submission,revision,created_at,updated_at) "
                        + "VALUES(?,?,?,'ACTIVE','NOT_STARTED','NOT_REQUIRED',0,NULL,NULL,FALSE,0,?,?) "
                        + "ON DUPLICATE KEY UPDATE id=id", arguments)).sum();
        int reactivated = jdbc.update("UPDATE assessment_participant p SET enrollment_status='ACTIVE',"
                + "submission_status=CASE WHEN p.final_attempt_id IS NULL THEN 'NOT_STARTED' ELSE 'SUBMITTED' END,"
                + "updated_at=?,revision=revision+1 WHERE p.learner_id=? AND p.enrollment_status='WITHDRAWN' "
                + "AND p.assessment_id IN (SELECT a.id FROM assessment a WHERE a.course_id=? "
                + "AND a.status='PUBLISHED' AND a.close_status='OPEN' AND a.assessment_type IN ('HOMEWORK','EXAM'))",
                ts(now), learnerId, courseId);
        return inserted + reactivated;
    }
    public Optional<ParticipantRow> participant(String assessmentId, String learnerId) {
        return jdbc.query("SELECT * FROM assessment_participant WHERE assessment_id=? AND learner_id=?",
                this::participant, assessmentId, learnerId).stream().findFirst();
    }
    public ParticipantRow lockParticipant(String assessmentId, String learnerId) {
        return jdbc.query("SELECT * FROM assessment_participant WHERE assessment_id=? AND learner_id=? FOR UPDATE",
                this::participant, assessmentId, learnerId).stream().findFirst().orElse(null);
    }
    public int updateParticipantOnDraft(String assessmentId, String learnerId, int attemptsUsed, String finalAttemptId,
            Instant now) {
        return jdbc.update("UPDATE assessment_participant SET submission_status='IN_PROGRESS',attempts_used=?,final_attempt_id=?,revision=revision+1,updated_at=? WHERE assessment_id=? AND learner_id=? AND enrollment_status='ACTIVE'",
                attemptsUsed, finalAttemptId, ts(now), assessmentId, learnerId);
    }
    public int updateParticipantOnSubmit(String assessmentId, String learnerId, int attemptsUsed, String attemptId,
            Instant submittedAt, boolean late, String gradingStatus, Instant now) {
        return jdbc.update("UPDATE assessment_participant SET submission_status='SUBMITTED',grading_status=?,attempts_used=?,final_attempt_id=?,last_submitted_at=?,late_submission=?,revision=revision+1,updated_at=? WHERE assessment_id=? AND learner_id=? AND enrollment_status='ACTIVE'",
                gradingStatus, attemptsUsed, attemptId, ts(submittedAt), late, ts(now), assessmentId, learnerId);
    }
    public int markParticipantMissing(String assessmentId, String learnerId, Instant now) {
        return jdbc.update("UPDATE assessment_participant SET submission_status='MISSING',grading_status='NOT_REQUIRED',final_attempt_id=NULL,updated_at=?,revision=revision+1 WHERE assessment_id=? AND learner_id=? AND enrollment_status='ACTIVE' AND final_attempt_id IS NULL AND submission_status IN ('NOT_STARTED','IN_PROGRESS')",
                ts(now), assessmentId, learnerId);
    }
    public int markParticipantWithdrawn(String assessmentId, String learnerId, Instant now) {
        return jdbc.update("UPDATE assessment_participant p SET enrollment_status='WITHDRAWN',submission_status='WITHDRAWN',updated_at=?,revision=revision+1 WHERE p.assessment_id=? AND p.learner_id=? AND p.enrollment_status='ACTIVE' AND EXISTS (SELECT 1 FROM assessment a WHERE a.id=p.assessment_id AND a.status='PUBLISHED' AND a.close_status='OPEN')",
                ts(now), assessmentId, learnerId);
    }
    public int reactivateParticipant(String assessmentId, String learnerId, Instant now) {
        return jdbc.update("UPDATE assessment_participant p SET enrollment_status='ACTIVE',submission_status=CASE WHEN p.final_attempt_id IS NULL THEN 'NOT_STARTED' ELSE 'SUBMITTED' END,updated_at=?,revision=revision+1 WHERE p.assessment_id=? AND p.learner_id=? AND p.enrollment_status='WITHDRAWN' AND EXISTS (SELECT 1 FROM assessment a WHERE a.id=p.assessment_id AND a.status='PUBLISHED' AND a.close_status='OPEN')",
                ts(now), assessmentId, learnerId);
    }
    /** 退课只撤销尚未关闭测评的名单；关闭后的名单冻结，避免历史成绩随报名状态改变。 */
    public int withdrawOpenParticipantsForLearner(String courseId, String learnerId, Instant now) {
        return jdbc.update("UPDATE assessment_participant p SET enrollment_status='WITHDRAWN',submission_status='WITHDRAWN',updated_at=?,revision=revision+1 "
                + "WHERE p.learner_id=? AND p.enrollment_status='ACTIVE' AND p.assessment_id IN "
                + "(SELECT a.id FROM assessment a WHERE a.course_id=? AND a.status='PUBLISHED' "
                + "AND a.close_status='OPEN' AND a.assessment_type IN ('HOMEWORK','EXAM'))",
                ts(now), learnerId, courseId);
    }
    /** 关闭时把所有还没有草稿/最终 attempt 的应交人确定为 MISSING，退课者保持 WITHDRAWN。 */
    public int markUnsubmittedParticipantsMissing(String assessmentId, Instant now) {
        return jdbc.update("UPDATE assessment_participant SET submission_status='MISSING',grading_status='NOT_REQUIRED',updated_at=?,revision=revision+1 WHERE assessment_id=? AND enrollment_status='ACTIVE' AND final_attempt_id IS NULL AND submission_status IN ('NOT_STARTED','IN_PROGRESS') AND NOT EXISTS (SELECT 1 FROM assessment_attempt t WHERE t.assessment_id=assessment_participant.assessment_id AND t.learner_id=assessment_participant.learner_id AND t.status='DRAFT')",
                ts(now), assessmentId);
    }
    public List<EnrollmentRow> activeEnrollments(String courseId) {
        return jdbc.query("SELECT * FROM enrollment WHERE course_id=? AND status='ACTIVE' ORDER BY enrolled_at,learner_id", this::enrollment, courseId);
    }
    /** 返回课程全部报名历史，成绩册回填编号时按首次报名时间和 learnerId 稳定排序。 */
    public List<EnrollmentRow> allEnrollments(String courseId) {
        return jdbc.query("SELECT * FROM enrollment WHERE course_id=? ORDER BY enrolled_at,learner_id,id",
                this::enrollment, courseId);
    }
    public List<AssessmentRow> publishedCourseAssessments(String courseId) {
        return jdbc.query("SELECT a.* FROM assessment a JOIN assessment_assignment aa ON aa.assessment_id=a.id AND aa.assignment_type='COURSE' AND aa.target_id=a.course_id WHERE a.course_id=? AND a.status='PUBLISHED' AND a.assessment_type IN ('HOMEWORK','EXAM') ORDER BY a.published_at,a.id",
                this::mapAssessment, courseId);
    }
    public List<AssessmentRow> openPublishedCourseAssessments(String courseId) {
        return jdbc.query("SELECT a.* FROM assessment a JOIN assessment_assignment aa ON aa.assessment_id=a.id AND aa.assignment_type='COURSE' AND aa.target_id=a.course_id WHERE a.course_id=? AND a.status='PUBLISHED' AND a.close_status='OPEN' AND a.assessment_type IN ('HOMEWORK','EXAM') ORDER BY a.published_at,a.id",
                this::mapAssessment, courseId);
    }
    /** 教师成绩册只读取本人创建的非草稿 HOMEWORK/EXAM，避免同课程其他教师的测评越权。 */
    public List<AssessmentRow> teacherGradebookAssessments(String courseId, String ownerTeacherId) {
        return jdbc.query("SELECT * FROM assessment WHERE course_id=? AND owner_teacher_id=? AND status<>'DRAFT' AND assessment_type IN ('HOMEWORK','EXAM') ORDER BY published_at,id",
                this::mapAssessment, courseId, ownerTeacherId);
    }
    public List<ParticipantRow> participants(String assessmentId) {
        return jdbc.query("SELECT * FROM assessment_participant WHERE assessment_id=? ORDER BY learner_id", this::participant, assessmentId);
    }
    public List<ParticipantRow> participantsForCourse(String courseId) {
        return jdbc.query("SELECT p.* FROM assessment_participant p JOIN assessment a ON a.id=p.assessment_id WHERE a.course_id=? AND a.assessment_type IN ('HOMEWORK','EXAM') ORDER BY p.learner_id,p.assessment_id",
                this::participant, courseId);
    }
    public List<AttemptRow> finalAttempts(String assessmentId) {
        return jdbc.query("SELECT t.* FROM assessment_participant p JOIN assessment_attempt t ON t.id=p.final_attempt_id WHERE p.assessment_id=? ORDER BY t.learner_id",
                this::mapAttempt, assessmentId);
    }
    public Optional<CourseStudentNumberRow> courseStudentNumber(String courseId, String learnerId) {
        return jdbc.query("SELECT * FROM course_student_number WHERE course_id=? AND learner_id=?",
                this::courseStudentNumber, courseId, learnerId).stream().findFirst();
    }
    /** 使用唯一约束原子领取课程编号；冲突重试由服务层在事务内读取现有编号。 */
    public void insertCourseStudentNumber(String id, String courseId, String learnerId, int number, Instant now) {
        jdbc.update("INSERT INTO course_student_number(id,course_id,learner_id,student_number,created_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                id, courseId, learnerId, number, ts(now));
    }
    public int nextCourseStudentNumber(String courseId) {
        jdbc.update("INSERT INTO course_student_number_counter(course_id,next_number) VALUES(?,2) ON DUPLICATE KEY UPDATE next_number=next_number+1",
                courseId);
        Integer value = jdbc.queryForObject("SELECT next_number FROM course_student_number_counter WHERE course_id=? FOR UPDATE",
                Integer.class, courseId);
        return value == null ? 1 : value - 1;
    }
    public List<CourseStudentNumberRow> courseStudentNumbers(String courseId, int page, int size) {
        return jdbc.query("SELECT * FROM course_student_number WHERE course_id=? ORDER BY student_number LIMIT ? OFFSET ?",
                this::courseStudentNumber, courseId, size, (page - 1) * size);
    }
    public long countCourseStudentNumbers(String courseId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM course_student_number WHERE course_id=?", Long.class, courseId);
        return value == null ? 0 : value;
    }

    /**
     * 只读取成绩册当前页的课程学生编号。状态筛选使用 EXISTS，排序和总数均由数据库完成，避免把全课程
     * 5,000 名学生及其测评矩阵装入 JVM；昵称筛选由 IAM 投影层另行处理，数据库不跨 Schema 查询。
     */
    public List<CourseStudentNumberRow> gradebookStudentNumbers(String courseId, List<String> assessmentIds,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword, int page, int size,
            LearningDtos.GradebookSortField sort, boolean descending) {
        return gradebookStudentNumbers(courseId, assessmentIds, submissionStatus, gradingStatus, late, keyword,
                null, page, size, sort, descending);
    }

    /** 只读取当前页；昵称筛选的候选 learnerId 由 IAM 投影得到，仍不跨 Schema 查询。 */
    public List<CourseStudentNumberRow> gradebookStudentNumbers(String courseId, List<String> assessmentIds,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword,
            List<String> candidateLearnerIds, int page, int size,
            LearningDtos.GradebookSortField sort, boolean descending) {
        GradebookNumberQuery query = gradebookNumberQuery(courseId, assessmentIds, submissionStatus, gradingStatus,
                late, keyword, candidateLearnerIds);
        StringBuilder sql = new StringBuilder("SELECT n.* FROM course_student_number n").append(query.whereSql());
        appendGradebookOrder(sql, query.arguments(), assessmentIds, sort);
        sql.append(descending ? " DESC" : " ASC").append(",n.student_number ASC LIMIT ? OFFSET ?");
        query.arguments().add(size);
        query.arguments().add((page - 1) * size);
        return jdbc.query(sql.toString(), this::courseStudentNumber, query.arguments().toArray());
    }

    /** 返回和成绩册当前筛选一致的学生总数，不读取学生测评单元格。 */
    public long countGradebookStudentNumbers(String courseId, List<String> assessmentIds,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword) {
        return countGradebookStudentNumbers(courseId, assessmentIds, submissionStatus, gradingStatus, late, keyword,
                null);
    }

    /** 按当前筛选候选集聚合学生总数，不读取学生测评单元格。 */
    public long countGradebookStudentNumbers(String courseId, List<String> assessmentIds,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword,
            List<String> candidateLearnerIds) {
        GradebookNumberQuery query = gradebookNumberQuery(courseId, assessmentIds, submissionStatus, gradingStatus,
                late, keyword, candidateLearnerIds);
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM course_student_number n" + query.whereSql(), Long.class,
                query.arguments().toArray());
        return value == null ? 0 : value;
    }

    /** 一次读取当前页涉及的参与者及最终 attempt 投影；每页最多 size×测评数，而不是全课程矩阵。 */
    public List<GradebookParticipantRow> gradebookParticipantRows(List<String> assessmentIds, List<String> learnerIds) {
        if (assessmentIds.isEmpty() || learnerIds.isEmpty()) return List.of();
        String assessmentPlaceholders = placeholders(assessmentIds.size());
        String learnerPlaceholders = placeholders(learnerIds.size());
        List<Object> arguments = new ArrayList<>(assessmentIds.size() + learnerIds.size());
        arguments.addAll(assessmentIds);
        arguments.addAll(learnerIds);
        return jdbc.query("SELECT p.*,t.id AS final_attempt_record_id,t.score AS final_score,t.total_points AS final_total_points "
                        + "FROM assessment_participant p LEFT JOIN assessment_attempt t ON t.id=p.final_attempt_id "
                        + "WHERE p.assessment_id IN (" + assessmentPlaceholders + ") AND p.learner_id IN ("
                        + learnerPlaceholders + ") ORDER BY p.learner_id,p.assessment_id",
                (rs, row) -> new GradebookParticipantRow(participant(rs, row), rs.getString("final_attempt_record_id"),
                        rs.getBigDecimal("final_score"), rs.getBigDecimal("final_total_points")),
                arguments.toArray());
    }

    /** 由数据库聚合每场测评的应交、缺交、待批改、提交率、分数和分布；服务层只组装稳定 DTO。 */
    public Map<String, GradebookAggregateRow> gradebookAggregates(List<String> assessmentIds) {
        if (assessmentIds.isEmpty()) return Map.of();
        String placeholders = placeholders(assessmentIds.size());
        String score = "t.score * 100 / NULLIF(t.total_points,0)";
        String scored = "p.enrollment_status='ACTIVE' AND p.submission_status='SUBMITTED' "
                + "AND t.score IS NOT NULL AND t.total_points<>0";
        String sql = "SELECT p.assessment_id,"
                + "SUM(CASE WHEN p.enrollment_status='ACTIVE' THEN 1 ELSE 0 END) expected_count,"
                + "SUM(CASE WHEN p.enrollment_status='ACTIVE' AND p.submission_status='MISSING' THEN 1 ELSE 0 END) missing_count,"
                + "SUM(CASE WHEN p.enrollment_status='ACTIVE' AND p.submission_status<>'MISSING' "
                + "AND p.grading_status<>'GRADED' THEN 1 ELSE 0 END) pending_count,"
                + "SUM(CASE WHEN " + scored + " THEN 1 ELSE 0 END) scored_count,"
                + "SUM(CASE WHEN p.enrollment_status='ACTIVE' AND p.submission_status='SUBMITTED' THEN 1 ELSE 0 END) submitted_count,"
                + "SUM(CASE WHEN p.enrollment_status='ACTIVE' AND p.submission_status='SUBMITTED' "
                + "AND p.late_submission=FALSE THEN 1 ELSE 0 END) on_time_count,"
                + "SUM(CASE WHEN " + scored + " THEN " + score + " ELSE 0 END) score_sum,"
                + "MAX(CASE WHEN " + scored + " THEN " + score + " ELSE NULL END) highest_score,"
                + "MIN(CASE WHEN " + scored + " THEN " + score + " ELSE NULL END) lowest_score,"
                + "SUM(CASE WHEN " + scored + " AND " + score + " < 60 THEN 1 ELSE 0 END) band_0_59,"
                + "SUM(CASE WHEN " + scored + " AND " + score + " >= 60 AND " + score + " < 70 THEN 1 ELSE 0 END) band_60_69,"
                + "SUM(CASE WHEN " + scored + " AND " + score + " >= 70 AND " + score + " < 80 THEN 1 ELSE 0 END) band_70_79,"
                + "SUM(CASE WHEN " + scored + " AND " + score + " >= 80 AND " + score + " < 90 THEN 1 ELSE 0 END) band_80_89,"
                + "SUM(CASE WHEN " + scored + " AND " + score + " >= 90 THEN 1 ELSE 0 END) band_90_100 "
                + "FROM assessment_participant p LEFT JOIN assessment_attempt t ON t.id=p.final_attempt_id "
                + "WHERE p.assessment_id IN (" + placeholders + ") GROUP BY p.assessment_id";
        Map<String, GradebookAggregateRow> result = new LinkedHashMap<>();
        jdbc.query(sql, (rs, row) -> new GradebookAggregateRow(rs.getString("assessment_id"),
                rs.getInt("expected_count"), rs.getInt("missing_count"), rs.getInt("pending_count"),
                rs.getInt("scored_count"), rs.getInt("submitted_count"), rs.getInt("on_time_count"),
                rs.getBigDecimal("score_sum"), rs.getBigDecimal("highest_score"), rs.getBigDecimal("lowest_score"),
                rs.getInt("band_0_59"), rs.getInt("band_60_69"), rs.getInt("band_70_79"),
                rs.getInt("band_80_89"), rs.getInt("band_90_100")), assessmentIds.toArray())
                .forEach(value -> result.put(value.assessmentId(), value));
        return result;
    }

    /** 聚合所有学生的课程级 HOMEWORK/EXAM 平均分，避免把全量学生行加载后再由 JVM 统计。 */
    public Map<AssessmentType, GradebookTypeAverageRow> gradebookTypeAverages(String courseId,
            List<String> assessmentIds) {
        if (assessmentIds.isEmpty()) return Map.of();
        String placeholders = placeholders(assessmentIds.size());
        String score = "t.score * 100 / NULLIF(t.total_points,0)";
        String sql = "SELECT a.assessment_type,SUM(CASE WHEN p.enrollment_status='ACTIVE' AND "
                + "(p.submission_status='MISSING' OR (p.grading_status='GRADED' AND t.score IS NOT NULL "
                + "AND t.total_points<>0)) THEN CASE WHEN p.submission_status='MISSING' THEN 0 ELSE " + score
                + " END ELSE 0 END) score_sum,"
                + "SUM(CASE WHEN p.enrollment_status='ACTIVE' AND (p.submission_status='MISSING' OR "
                + "(p.grading_status='GRADED' AND t.score IS NOT NULL AND t.total_points<>0)) THEN 1 ELSE 0 END) score_count "
                + "FROM assessment a LEFT JOIN assessment_participant p ON p.assessment_id=a.id "
                + "LEFT JOIN assessment_attempt t ON t.id=p.final_attempt_id WHERE a.course_id=? AND a.id IN ("
                + placeholders + ") GROUP BY a.assessment_type";
        List<Object> arguments = new ArrayList<>();
        arguments.add(courseId);
        arguments.addAll(assessmentIds);
        Map<AssessmentType, GradebookTypeAverageRow> result = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> result.put(AssessmentType.valueOf(rs.getString("assessment_type")),
                new GradebookTypeAverageRow(rs.getBigDecimal("score_sum"), rs.getInt("score_count"))),
                arguments.toArray());
        return result;
    }

    private GradebookNumberQuery gradebookNumberQuery(String courseId, List<String> assessmentIds,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus, Boolean late, String keyword,
            List<String> candidateLearnerIds) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(courseId);
        StringBuilder where = new StringBuilder(" WHERE n.course_id=?");
        if (candidateLearnerIds != null) {
            if (candidateLearnerIds.isEmpty()) {
                where.append(" AND 1=0");
            } else {
                where.append(" AND n.learner_id IN (").append(placeholders(candidateLearnerIds.size())).append(')');
                arguments.addAll(candidateLearnerIds);
            }
        }
        if (candidateLearnerIds == null && keyword != null && !keyword.isBlank()) {
            where.append(" AND LPAD(CAST(n.student_number AS CHAR),4,'0') LIKE ?");
            arguments.add("%" + keyword + "%");
        }
        if (submissionStatus != null || gradingStatus != null || late != null) {
            if (assessmentIds.isEmpty()) {
                where.append(" AND 1=0");
            } else {
                where.append(" AND EXISTS (SELECT 1 FROM assessment_participant fp WHERE fp.learner_id=n.learner_id")
                        .append(" AND fp.assessment_id IN (").append(placeholders(assessmentIds.size())).append(')');
                arguments.addAll(assessmentIds);
                if (submissionStatus != null) {
                    where.append(" AND fp.submission_status=?");
                    arguments.add(submissionStatus.name());
                }
                if (gradingStatus != null) {
                    where.append(" AND fp.grading_status=?");
                    arguments.add(gradingStatus.name());
                }
                if (late != null) {
                    where.append(" AND fp.late_submission=?");
                    arguments.add(late);
                }
                where.append(')');
            }
        }
        return new GradebookNumberQuery(where.toString(), arguments);
    }

    private void appendGradebookOrder(StringBuilder sql, List<Object> arguments, List<String> assessmentIds,
            LearningDtos.GradebookSortField sort) {
        LearningDtos.GradebookSortField effective = sort == null
                ? LearningDtos.GradebookSortField.STUDENT_NUMBER : sort;
        if (assessmentIds.isEmpty() || effective == LearningDtos.GradebookSortField.STUDENT_NUMBER) {
            sql.append(" ORDER BY n.student_number");
            return;
        }
        String ids = placeholders(assessmentIds.size());
        switch (effective) {
            case LAST_SUBMITTED_AT -> sql.append(" ORDER BY (SELECT MAX(fp.last_submitted_at) FROM assessment_participant fp "
                    + "WHERE fp.learner_id=n.learner_id AND fp.assessment_id IN (").append(ids).append("))");
            case SCORE -> sql.append(" ORDER BY (SELECT AVG(CASE WHEN fp.enrollment_status='ACTIVE' "
                    + "AND fp.grading_status='GRADED' AND ft.score IS NOT NULL AND ft.total_points<>0 "
                    + "THEN ft.score*100/NULLIF(ft.total_points,0) END) FROM assessment_participant fp "
                    + "LEFT JOIN assessment_attempt ft ON ft.id=fp.final_attempt_id WHERE fp.learner_id=n.learner_id "
                    + "AND fp.assessment_id IN (").append(ids).append("))");
            case SUBMISSION_STATUS -> sql.append(" ORDER BY (SELECT fp.submission_status FROM assessment_participant fp "
                    + "WHERE fp.learner_id=n.learner_id AND fp.assessment_id=? LIMIT 1)");
            case GRADING_STATUS -> sql.append(" ORDER BY (SELECT fp.grading_status FROM assessment_participant fp "
                    + "WHERE fp.learner_id=n.learner_id AND fp.assessment_id=? LIMIT 1)");
            default -> sql.append(" ORDER BY n.student_number");
        }
        if (effective == LearningDtos.GradebookSortField.SUBMISSION_STATUS
                || effective == LearningDtos.GradebookSortField.GRADING_STATUS) {
            arguments.add(assessmentIds.get(0));
        } else {
            arguments.addAll(assessmentIds);
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
    /** 记录同步成绩册导出审计，不保存 CSV 内容、答案或学生联系方式。 */
    public void insertGradebookExportAudit(String id, String clientRequestId, String operatorId, String courseId,
            String filterJson, int rowCount, int assessmentCount, String traceId, Instant now) {
        jdbc.update("INSERT INTO gradebook_export_audit(id,client_request_id,operator_id,course_id,filter_json,row_count,assessment_count,trace_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE row_count=VALUES(row_count),assessment_count=VALUES(assessment_count),trace_id=VALUES(trace_id)",
                id, clientRequestId, operatorId, courseId, filterJson, rowCount, assessmentCount, traceId, ts(now));
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
    /** 迟交是提交时的事实快照；截止时间调整后的重新分类由服务层按最终提交时间计算。 */
    public void updateAttemptLateSubmission(String attemptId, boolean late, Instant now) {
        jdbc.update("UPDATE assessment_attempt SET late_submission=?,updated_at=? WHERE id=? AND status='DRAFT'",
                late, ts(now), attemptId);
    }
    /** 人工批改完成后同步成绩册批改状态，不复制答案或评语。 */
    public void updateParticipantGrading(String assessmentId, String learnerId,
            LearningDtos.AssessmentGradingStatus status, Instant now) {
        jdbc.update("UPDATE assessment_participant SET grading_status=?,updated_at=?,revision=revision+1 WHERE assessment_id=? AND learner_id=? AND final_attempt_id IS NOT NULL",
                status.name(), ts(now), assessmentId, learnerId);
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
        return jdbc.query("SELECT t.* FROM assessment_attempt t LEFT JOIN assessment_participant p ON p.final_attempt_id=t.id WHERE t.assessment_id=? AND (p.id IS NOT NULL OR NOT EXISTS (SELECT 1 FROM assessment_participant p2 WHERE p2.assessment_id=t.assessment_id AND p2.learner_id=t.learner_id)) AND t.status<>'DRAFT' ORDER BY t.updated_at DESC,t.id LIMIT ? OFFSET ?",
                this::mapAttempt, assessmentId, size, (page - 1) * size);
    }
    public long countAssessmentAttempts(String assessmentId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM assessment_attempt t LEFT JOIN assessment_participant p ON p.final_attempt_id=t.id WHERE t.assessment_id=? AND (p.id IS NOT NULL OR NOT EXISTS (SELECT 1 FROM assessment_participant p2 WHERE p2.assessment_id=t.assessment_id AND p2.learner_id=t.learner_id)) AND t.status<>'DRAFT'", Long.class,
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
    private ProgressRow progress(ResultSet rs, int row) throws SQLException { return new ProgressRow(rs.getString("id"),rs.getString("learner_id"),rs.getString("course_id"),rs.getString("lesson_id"),rs.getLong("position_seconds"),rs.getLong("watched_seconds"),rs.getBoolean("completed"),instant(rs,"last_event_at"),rs.getLong("revision"),rs.getLong("buffer_version"),instant(rs,"created_at"),instant(rs,"updated_at")); }
    private NoteRow note(ResultSet rs, int row) throws SQLException { long p=rs.getLong("position_seconds"); return new NoteRow(rs.getString("id"),rs.getString("learner_id"),rs.getString("course_id"),rs.getString("lesson_id"),rs.getString("content"),rs.wasNull()?null:p,rs.getLong("revision"),instant(rs,"created_at"),instant(rs,"updated_at")); }
    private IdempotencyRow idempotency(ResultSet rs,int row)throws SQLException{return new IdempotencyRow(rs.getString("owner_id"),rs.getString("client_request_id"),rs.getString("request_fingerprint"),rs.getString("resource_type"),rs.getString("resource_id"),instant(rs,"created_at"));}
    private AssessmentRow mapAssessment(ResultSet rs,int row)throws SQLException{return new AssessmentRow(rs.getString("id"),rs.getString("owner_teacher_id"),rs.getString("course_id"),rs.getString("lesson_id"),rs.getString("title"),rs.getString("description"),AssessmentType.valueOf(rs.getString("assessment_type")),AssessmentStatus.valueOf(rs.getString("status")),GradePublicationPolicy.valueOf(rs.getString("grade_publication_policy")),AnswerPublicationPolicy.valueOf(rs.getString("answer_release_policy")),AssessmentCloseStatus.valueOf(rs.getString("close_status")),AssessmentSettlementStatus.valueOf(rs.getString("settlement_status")),instantNullable(rs,"closed_at"),rs.getInt("close_affected_attempt_count"),rs.getInt("settlement_attempts"),instantNullable(rs,"settlement_next_attempt_at"),rs.getString("settlement_last_error"),ResultPublicationStatus.valueOf(rs.getString("grade_publication_status")),ResultPublicationStatus.valueOf(rs.getString("answer_publication_status")),instantNullable(rs,"grade_published_at"),instantNullable(rs,"answer_published_at"),instantNullable(rs,"opens_at"),instantNullable(rs,"closes_at"),(Integer)rs.getObject("time_limit_seconds"),com.eduplatform.learning.web.LearningDtos.HomeworkLatePolicy.valueOf(rs.getString("late_policy")),instantNullable(rs,"late_until"),rs.getInt("max_attempts"),rs.getLong("revision"),instantNullable(rs,"published_at"),instantNullable(rs,"archived_at"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private AssessmentQuestionRow assessmentQuestion(ResultSet rs,int row)throws SQLException{return new AssessmentQuestionRow(rs.getString("id"),rs.getString("assessment_id"),rs.getString("question_id"),rs.getString("question_version_id"),rs.getInt("sequence_no"),rs.getBigDecimal("points"),read(rs.getString("snapshot_json"),QuestionDtos.VersionResponse.class),instant(rs,"created_at"));}
    private AttemptRow mapAttempt(ResultSet rs,int row)throws SQLException{return new AttemptRow(rs.getString("id"),rs.getString("assessment_id"),rs.getString("learner_id"),AttemptStatus.valueOf(rs.getString("status")),rs.getLong("revision"),rs.getString("draft_request_id"),rs.getString("draft_fingerprint"),rs.getString("submit_request_id"),rs.getString("submission_fingerprint"),rs.getBigDecimal("score"),rs.getBigDecimal("total_points"),instant(rs,"started_at"),instantNullable(rs,"deadline_at"),instantNullable(rs,"submitted_at"),instantNullable(rs,"graded_at"),instantNullable(rs,"grade_updated_at"),rs.getInt("attempt_sequence"),rs.getBoolean("late_submission"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private ParticipantRow participant(ResultSet rs,int row)throws SQLException{return new ParticipantRow(rs.getString("id"),rs.getString("assessment_id"),rs.getString("learner_id"),rs.getString("enrollment_status"),com.eduplatform.learning.web.LearningDtos.AssessmentSubmissionStatus.valueOf(rs.getString("submission_status")),com.eduplatform.learning.web.LearningDtos.AssessmentGradingStatus.valueOf(rs.getString("grading_status")),rs.getInt("attempts_used"),rs.getString("final_attempt_id"),instantNullable(rs,"last_submitted_at"),rs.getBoolean("late_submission"),rs.getLong("revision"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private CourseStudentNumberRow courseStudentNumber(ResultSet rs,int row)throws SQLException{return new CourseStudentNumberRow(rs.getString("id"),rs.getString("course_id"),rs.getString("learner_id"),rs.getInt("student_number"),instant(rs,"created_at"));}
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
    public record ProgressRow(String id,String learnerId,String courseId,String lessonId,long positionSeconds,long watchedSeconds,boolean completed,Instant lastEventAt,long revision,long bufferVersion,Instant createdAt,Instant updatedAt){}
    public record ArticleCompletionRow(String id, String learnerId, String courseId, String lessonId,
            Instant completedAt, long revision) {
        public ArticleCompletionRow(String id, String learnerId, String courseId, String lessonId, Instant completedAt) {
            this(id, learnerId, courseId, lessonId, completedAt, 1);
        }
    }
    public record ArticleCompletionRequestRow(String clientRequestId, String learnerId,
            String courseId, String lessonId, String completionId) {}
    public record NoteRow(String id,String learnerId,String courseId,String lessonId,String content,Long positionSeconds,long revision,Instant createdAt,Instant updatedAt){}
    public record IdempotencyRow(String ownerId,String requestId,String fingerprint,String resourceType,String resourceId,Instant createdAt){}
    public record AssessmentRow(String id,String ownerTeacherId,String courseId,String lessonId,String title,
            String description,AssessmentType type,AssessmentStatus status,GradePublicationPolicy gradePolicy,
            AnswerPublicationPolicy answerPolicy,AssessmentCloseStatus closeStatus,
            AssessmentSettlementStatus settlementStatus,Instant closedAt,int closeAffectedAttemptCount,
            int settlementAttempts,Instant settlementNextAttemptAt,String settlementLastError,
            ResultPublicationStatus gradePublicationStatus,ResultPublicationStatus answerPublicationStatus,
            Instant gradePublishedAt,Instant answerPublishedAt,Instant opensAt,Instant closesAt,
            Integer timeLimitSeconds,LearningDtos.HomeworkLatePolicy latePolicy,Instant lateUntil,int maxAttempts,
            long revision,Instant publishedAt,Instant archivedAt,Instant createdAt,
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
                    opensAt, closesAt, timeLimitSeconds, LearningDtos.HomeworkLatePolicy.DISALLOW, null, 1,
                    revision, publishedAt, archivedAt, createdAt, updatedAt);
        }
        /** 兼容 T11B 的完整构造点；V14 新字段使用 DISALLOW、一次提交默认值。 */
        public AssessmentRow(String id,String ownerTeacherId,String courseId,String lessonId,String title,
                String description,AssessmentType type,AssessmentStatus status,GradePublicationPolicy gradePolicy,
                AnswerPublicationPolicy answerPolicy,AssessmentCloseStatus closeStatus,
                AssessmentSettlementStatus settlementStatus,Instant closedAt,int closeAffectedAttemptCount,
                int settlementAttempts,Instant settlementNextAttemptAt,String settlementLastError,
                ResultPublicationStatus gradePublicationStatus,ResultPublicationStatus answerPublicationStatus,
                Instant gradePublishedAt,Instant answerPublishedAt,Instant opensAt,Instant closesAt,
                Integer timeLimitSeconds,long revision,Instant publishedAt,Instant archivedAt,Instant createdAt,
                Instant updatedAt) {
            this(id, ownerTeacherId, courseId, lessonId, title, description, type, status, gradePolicy,
                    canonicalAnswerPolicy(answerPolicy), closeStatus, settlementStatus, closedAt,
                    closeAffectedAttemptCount, settlementAttempts, settlementNextAttemptAt, settlementLastError,
                    gradePublicationStatus, answerPublicationStatus, gradePublishedAt, answerPublishedAt,
                    opensAt, closesAt, timeLimitSeconds, LearningDtos.HomeworkLatePolicy.DISALLOW, null, 1,
                    revision, publishedAt, archivedAt, createdAt, updatedAt);
        }
    }
    public record AssessmentQuestionRow(String id,String assessmentId,String questionId,String questionVersionId,int sequence,BigDecimal points,QuestionDtos.VersionResponse snapshot,Instant createdAt){}
    public record AttemptRow(String id,String assessmentId,String learnerId,AttemptStatus status,long revision,
            String draftRequestId,String draftFingerprint,String submitRequestId,String submissionFingerprint,
            BigDecimal score,BigDecimal totalPoints,Instant startedAt,Instant deadlineAt,Instant submittedAt,
            Instant gradedAt,Instant gradeUpdatedAt,int attemptSequence,boolean lateSubmission,
            Instant createdAt,Instant updatedAt){
        /** 兼容既有构造点；首次历史成绩更新时间等于 gradedAt。 */
        public AttemptRow(String id,String assessmentId,String learnerId,AttemptStatus status,long revision,
                String draftRequestId,String draftFingerprint,String submitRequestId,String submissionFingerprint,
                BigDecimal score,BigDecimal totalPoints,Instant startedAt,Instant deadlineAt,Instant submittedAt,
                Instant gradedAt,Instant createdAt,Instant updatedAt) {
            this(id, assessmentId, learnerId, status, revision, draftRequestId, draftFingerprint, submitRequestId,
                    submissionFingerprint, score, totalPoints, startedAt, deadlineAt, submittedAt, gradedAt,
                    gradedAt, 1, false, createdAt, updatedAt);
        }
        /** 兼容 T12B seed 的 V11 构造点；序号 1 且非迟交。 */
        public AttemptRow(String id,String assessmentId,String learnerId,AttemptStatus status,long revision,
                String draftRequestId,String draftFingerprint,String submitRequestId,String submissionFingerprint,
                BigDecimal score,BigDecimal totalPoints,Instant startedAt,Instant deadlineAt,Instant submittedAt,
                Instant gradedAt,Instant gradeUpdatedAt,Instant createdAt,Instant updatedAt) {
            this(id, assessmentId, learnerId, status, revision, draftRequestId, draftFingerprint, submitRequestId,
                    submissionFingerprint, score, totalPoints, startedAt, deadlineAt, submittedAt, gradedAt,
                    gradeUpdatedAt, 1, false, createdAt, updatedAt);
        }
    }
    public record AnswerRow(String id,String attemptId,String assessmentQuestionId,List<String> answer,BigDecimal score,Boolean correct,GradingStatus gradingStatus,long revision,String gradedBy,Instant gradedAt,Instant createdAt,Instant updatedAt){}
    public record WrongRow(String id,String learnerId,String courseId,String assessmentId,String questionVersionId,int wrongCount,LearningDtos.WrongQuestionMasteryStatus masteryStatus,long revision,int redoAttemptsUsed,Instant firstWrongAt,Instant lastWrongAt,String lastAttemptId,Instant updatedAt){}
    public record RedoRow(String id,String wrongQuestionId,String learnerId,String clientRequestId,String requestFingerprint,int sequence,List<String> answer,BigDecimal score,Boolean correct,Instant submittedAt){}
    public record StudentProjectionRow(String learnerId,EnrollmentStatus enrollmentStatus,int completedLessons,long watchedSeconds,int submittedAttempts,int gradedAttempts,BigDecimal averageScore){}
    public record AssessmentProjectionCounts(int activeAttempts,int pendingManualAttempts,int pendingManualAnswers){}
    public record ParticipantRow(String id,String assessmentId,String learnerId,String enrollmentStatus,
            LearningDtos.AssessmentSubmissionStatus submissionStatus,
            LearningDtos.AssessmentGradingStatus gradingStatus,int attemptsUsed,
            String finalAttemptId,Instant lastSubmittedAt,boolean lateSubmission,long revision,
            Instant createdAt,Instant updatedAt) {}
    public record CourseStudentNumberRow(String id,String courseId,String learnerId,int studentNumber,Instant createdAt) {}
    /** 当前成绩册页所需的最小参与者/最终 attempt 投影，不携带答案或历史 attempt。 */
    public record GradebookParticipantRow(ParticipantRow participant, String finalAttemptId,
            BigDecimal finalScore, BigDecimal finalTotalPoints) {}
    /** 一场测评的数据库聚合结果，服务层只负责按就绪状态包装公开统计。 */
    public record GradebookAggregateRow(String assessmentId, int expectedCount, int missingCount, int pendingCount,
            int scoredCount, int submittedCount, int onTimeCount, BigDecimal scoreSum, BigDecimal highestScore,
            BigDecimal lowestScore, int band0To59, int band60To69, int band70To79, int band80To89,
            int band90To100) {}
    /** 课程级类型平均分的数据库聚合结果。 */
    public record GradebookTypeAverageRow(BigDecimal scoreSum, int scoreCount) {}
    private record GradebookNumberQuery(String whereSql, List<Object> arguments) {}

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
