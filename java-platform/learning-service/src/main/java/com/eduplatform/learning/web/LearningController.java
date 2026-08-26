package com.eduplatform.learning.web;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.service.LearningAssessmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学习端报名、进度、笔记和测评 HTTP 边界；所有 owner 均来自 Gateway 可信身份头。 */
@RestController
@Validated
@ConditionalOnBean(LearningAssessmentRepository.class)
public class LearningController {
    private final LearningAssessmentService learning;

    public LearningController(LearningAssessmentService learning) { this.learning = learning; }

    @GetMapping("/api/v1/learning/enrollments")
    /** 按当前可信学习者和可选状态分页返回报名。 */
    public LearningDtos.EnrollmentPage enrollments(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(required = false) LearningDtos.EnrollmentStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return learning.enrollments(LearningIdentity.learner(userId, roles, permissions).userId(), status, page, size);
    }

    @PostMapping("/api/v1/learning/enrollments")
    /** 报名当前已发布课程；首次 201，幂等重试 200。 */
    public ResponseEntity<LearningDtos.Enrollment> enroll(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody LearningDtos.CreateEnrollmentRequest request, HttpServletRequest servlet) {
        var result = learning.enroll(LearningIdentity.learner(userId, roles, permissions).userId(),
                request.courseId().toString(), trace(servlet));
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/learning/enrollments/" + result.enrollment().id())).body(result.enrollment())
                : ResponseEntity.ok(result.enrollment());
    }

    @DeleteMapping("/api/v1/learning/enrollments/{enrollmentId}")
    /** 取消本人报名并发出访问取消事件，保留历史成绩。 */
    public ResponseEntity<Void> cancel(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String enrollmentId, HttpServletRequest servlet) {
        learning.cancelEnrollment(LearningIdentity.learner(userId, roles, permissions).userId(),
                enrollmentId, trace(servlet));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/learning/courses/{courseId}/progress")
    /** 返回本人对当前发布课程的课时进度和完成率。 */
    public LearningDtos.CourseProgress progress(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, HttpServletRequest servlet) {
        return learning.progress(LearningIdentity.learner(userId, roles, permissions).userId(), courseId, trace(servlet));
    }

    @PostMapping("/api/v1/learning/courses/{courseId}/lessons/{lessonId}/progress-events")
    /** 使用 clientEventId 幂等合并播放位置、观看时长与完成标志。 */
    public LearningDtos.LessonProgress reportProgress(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @PathVariable String lessonId,
            @Valid @RequestBody LearningDtos.ReportProgressRequest request, HttpServletRequest servlet) {
        return learning.reportProgress(LearningIdentity.learner(userId, roles, permissions).userId(), courseId,
                lessonId, request, trace(servlet));
    }

    @GetMapping("/api/v1/learning/courses/{courseId}/lessons/{lessonId}/notes")
    /** 返回本人在当前课时的学习笔记。 */
    public List<LearningDtos.LearningNote> notes(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @PathVariable String lessonId, HttpServletRequest servlet) {
        return learning.notes(LearningIdentity.learner(userId, roles, permissions).userId(), courseId, lessonId,
                trace(servlet));
    }

    @PostMapping("/api/v1/learning/courses/{courseId}/lessons/{lessonId}/notes")
    /** 在本人可学习课时中创建笔记并返回 201。 */
    public ResponseEntity<LearningDtos.LearningNote> createNote(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @PathVariable String lessonId,
            @Valid @RequestBody LearningDtos.CreateNoteRequest request, HttpServletRequest servlet) {
        var value = learning.createNote(LearningIdentity.learner(userId, roles, permissions).userId(), courseId,
                lessonId, request, trace(servlet));
        return ResponseEntity.created(URI.create("/api/v1/learning/notes/" + value.id())).body(value);
    }

    @PutMapping("/api/v1/learning/notes/{noteId}")
    /** 按 expectedRevision 更新本人笔记，他人资源按 404 隐藏。 */
    public LearningDtos.LearningNote updateNote(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String noteId, @Valid @RequestBody LearningDtos.UpdateNoteRequest request) {
        return learning.updateNote(LearningIdentity.learner(userId, roles, permissions).userId(), noteId, request);
    }

    @DeleteMapping("/api/v1/learning/notes/{noteId}")
    /** 删除本人笔记，成功时返回 204。 */
    public ResponseEntity<Void> deleteNote(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String noteId) {
        learning.deleteNote(LearningIdentity.learner(userId, roles, permissions).userId(), noteId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/learning/assessments")
    /** 分页返回已分配到本人 ACTIVE 报名课程的已发布测评。 */
    public LearningDtos.AssessmentPage assessments(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(required = false) String courseId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return learning.learnerAssessments(LearningIdentity.learner(userId, roles, permissions).userId(), courseId,
                page, size);
    }

    @GetMapping("/api/v1/learning/assessments/{assessmentId}")
    /** 返回不含答案、解析或评分规则的学生安全试卷。 */
    public LearningDtos.LearnerAssessment assessment(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId) {
        return learning.learnerAssessment(LearningIdentity.learner(userId, roles, permissions).userId(), assessmentId);
    }

    @PostMapping("/api/v1/learning/assessments/{assessmentId}/attempts")
    /** 开始或幂等恢复本人对该测评的唯一作答。 */
    public ResponseEntity<LearningDtos.AttemptDraft> startAttempt(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId, @Valid @RequestBody LearningDtos.StartAttemptRequest request) {
        var value = learning.startAttempt(LearningIdentity.learner(userId, roles, permissions).userId(),
                assessmentId, request);
        return ResponseEntity.created(URI.create("/api/v1/learning/attempts/" + value.id() + "/draft")).body(value);
    }

    @PutMapping("/api/v1/learning/attempts/{attemptId}/draft")
    /** 按幂等请求和 revision 保存本人草稿，供刷新恢复。 */
    public LearningDtos.AttemptDraft draft(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String attemptId, @Valid @RequestBody LearningDtos.SaveDraftRequest request) {
        return learning.saveDraft(LearningIdentity.learner(userId, roles, permissions).userId(), attemptId, request);
    }

    @PostMapping("/api/v1/learning/attempts/{attemptId}/submission")
    /** 一次性提交本人试卷并在同一事务中完成可靠自动判分。 */
    public LearningDtos.AttemptResult submit(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String attemptId, @Valid @RequestBody LearningDtos.SubmitAttemptRequest request) {
        return learning.submit(LearningIdentity.learner(userId, roles, permissions).userId(), attemptId, request);
    }

    @GetMapping("/api/v1/learning/attempts/{attemptId}/result")
    /** 返回本人已提交结果，答案与解析严格按公布策略投影。 */
    public LearningDtos.AttemptResult result(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String attemptId) {
        return learning.learnerResult(LearningIdentity.learner(userId, roles, permissions).userId(), attemptId);
    }

    @GetMapping("/api/v1/learning/wrong-questions")
    /** 按本人和可选课程分页返回幂等累计的错题记录。 */
    public LearningDtos.WrongQuestionPage wrongQuestions(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(required = false) String courseId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return learning.wrongQuestions(LearningIdentity.learner(userId, roles, permissions).userId(), courseId,
                page, size);
    }

    private static String trace(HttpServletRequest request) {
        Object value = request.getAttribute("traceId");
        return value == null ? UUID_FALLBACK : value.toString();
    }

    private static final String UUID_FALLBACK = "00000000-0000-0000-0000-000000000000";
}
