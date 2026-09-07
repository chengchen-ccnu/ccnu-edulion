package com.eduplatform.learning.web;

import com.eduplatform.learning.config.TraceFilter;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.service.LearningAssessmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 教学端测评、批改和学情查询边界；访问能力与课程 owner 均由服务端校验。 */
@RestController
@Validated
@ConditionalOnBean(LearningAssessmentRepository.class)
public class TeacherAssessmentController {
    private final LearningAssessmentService learning;

    public TeacherAssessmentController(LearningAssessmentService learning) { this.learning = learning; }

    @GetMapping("/api/v1/teacher/assessments")
    /** 使用 Gateway 可信教学能力头分页返回当前教师的测评。 */
    public LearningDtos.AssessmentPage list(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam(required = false) String courseId,
            @RequestParam(required = false) LearningDtos.AssessmentStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest servlet) {
        return learning.teacherAssessments(LearningIdentity.teaching(userId, roles, permissions).userId(), courseId,
                status, page, size, trace(servlet));
    }

    @PostMapping("/api/v1/teacher/assessments")
    /** 创建教师测评草稿；首次返回 201，相同幂等请求返回 200 与原资源。 */
    public ResponseEntity<LearningDtos.TeacherAssessment> create(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @Valid @RequestBody LearningDtos.CreateAssessmentRequest request, HttpServletRequest servlet) {
        var value = learning.createAssessment(LearningIdentity.teaching(userId, roles, permissions).userId(),
                request, trace(servlet));
        return value.created()
                ? ResponseEntity.created(URI.create("/api/v1/teacher/assessments/" + value.assessment().id())).body(value.assessment())
                : ResponseEntity.ok(value.assessment());
    }

    @GetMapping("/api/v1/teacher/assessments/{assessmentId}")
    /** 返回当前教师拥有的测评与完整冻结题目快照。 */
    public LearningDtos.TeacherAssessment get(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId) {
        return learning.teacherAssessment(assessmentId,
                LearningIdentity.teaching(userId, roles, permissions).userId());
    }

    @PutMapping("/api/v1/teacher/assessments/{assessmentId}")
    /** 按 expectedRevision 更新未发布草稿元数据，并发冲突返回 409。 */
    public LearningDtos.TeacherAssessment update(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId, @Valid @RequestBody LearningDtos.UpdateAssessmentRequest request) {
        return learning.updateAssessment(LearningIdentity.teaching(userId, roles, permissions).userId(),
                assessmentId, request);
    }

    @DeleteMapping("/api/v1/teacher/assessments/{assessmentId}")
    /** 删除本人未发布草稿；已发布历史不可删除。 */
    public ResponseEntity<Void> delete(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId) {
        learning.deleteAssessment(LearningIdentity.teaching(userId, roles, permissions).userId(), assessmentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/teacher/assessments/{assessmentId}/questions")
    /** 按连续顺序替换草稿题目版本和分值，服务层校验可用范围。 */
    public LearningDtos.TeacherAssessment questions(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId, @Valid @RequestBody LearningDtos.ReplaceQuestionsRequest request) {
        return learning.replaceQuestions(LearningIdentity.teaching(userId, roles, permissions).userId(),
                assessmentId, request);
    }

    @PutMapping("/api/v1/teacher/assessments/{assessmentId}/publication")
    /** 发布或归档本人测评，发布交易冻结并分配当前课程范围。 */
    public LearningDtos.TeacherAssessment publication(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId, @Valid @RequestBody LearningDtos.UpdatePublicationRequest request,
            HttpServletRequest servlet) {
        return learning.publication(LearningIdentity.teaching(userId, roles, permissions).userId(), assessmentId,
                request, trace(servlet));
    }

    @PutMapping("/api/v1/teacher/assessments/{assessmentId}/deadline")
    /** 按 owner 和 expectedRevision 调整截止节点，并记录旧值、新值、原因和 traceId 审计。 */
    public LearningDtos.TeacherAssessment deadline(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId, @Valid @RequestBody LearningDtos.UpdateAssessmentDeadlineRequest request,
            HttpServletRequest servlet) {
        return learning.updateAssessmentDeadline(LearningIdentity.teaching(userId, roles, permissions).userId(),
                assessmentId, request, trace(servlet));
    }

    @PostMapping("/api/v1/teacher/assessments/{assessmentId}/closure")
    /** 不可撤销结束本人测评，冻结受影响草稿数并启动有界结算；重复相同请求返回原关闭事实。 */
    public LearningDtos.CloseAssessmentResult close(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId, @Valid @RequestBody LearningDtos.CloseAssessmentRequest request) {
        return learning.closeAssessment(LearningIdentity.teaching(userId, roles, permissions).userId(),
                assessmentId, request);
    }

    @PostMapping("/api/v1/teacher/assessments/{assessmentId}/grade-publication")
    /** 在关闭、草稿结算和人工批改均完成后不可撤回地公布成绩。 */
    public LearningDtos.AssessmentPublicationCommandResult publishGrades(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId,
            @Valid @RequestBody LearningDtos.PublishAssessmentResultRequest request) {
        return learning.publishGrades(LearningIdentity.teaching(userId, roles, permissions).userId(),
                assessmentId, request);
    }

    @PostMapping("/api/v1/teacher/assessments/{assessmentId}/answer-publication")
    /** 在关闭且草稿结算完成后不可撤回地公布参考答案；不要求主观题批改完成。 */
    public LearningDtos.AssessmentPublicationCommandResult publishAnswers(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId,
            @Valid @RequestBody LearningDtos.PublishAssessmentResultRequest request) {
        return learning.publishAnswers(LearningIdentity.teaching(userId, roles, permissions).userId(),
                assessmentId, request);
    }

    @GetMapping("/api/v1/teacher/assessments/{assessmentId}/results")
    /** 分页返回本人测评的学生提交和评分结果。 */
    public LearningDtos.AttemptResultPage results(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String assessmentId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return learning.teacherResults(LearningIdentity.teaching(userId, roles, permissions).userId(), assessmentId,
                page, size);
    }

    @GetMapping("/api/v1/teacher/attempts/{attemptId}")
    /** 返回关闭且结算完成测评中的最后有效单份答卷；不放宽整场结果列表门禁。 */
    public LearningDtos.AttemptResult attempt(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String attemptId) {
        return learning.teacherResult(LearningIdentity.teaching(userId, roles, permissions).userId(), attemptId);
    }

    @PostMapping("/api/v1/teacher/attempts/{attemptId}/grades")
    /** 按 attempt revision 人工批改待评分答案，并事务汇总最终成绩。 */
    public LearningDtos.AttemptResult grade(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String attemptId, @Valid @RequestBody LearningDtos.ManualGradeRequest request) {
        return learning.manualGrade(LearningIdentity.teaching(userId, roles, permissions).userId(), attemptId, request);
    }

    @GetMapping("/api/v1/teacher/learning/courses/{courseId}/students")
    /** 验证课程 owner 后分页返回报名学生进度与成绩投影。 */
    public LearningDtos.StudentLearningPage students(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @PathVariable String courseId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest servlet) {
        return learning.studentLearning(LearningIdentity.teaching(userId, roles, permissions).userId(), courseId,
                page, size, trace(servlet));
    }

    @GetMapping("/api/v1/teacher/gradebook")
    /** 查询课程成绩册矩阵；服务层在每次请求验证 teaching 能力、课程 owner 和 Learning 数据范围。 */
    public LearningDtos.Gradebook gradebook(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam String courseId, @RequestParam(required = false) String assessmentId,
            @RequestParam(required = false) LearningDtos.AssessmentType type,
            @RequestParam(required = false) LearningDtos.AssessmentSubmissionStatus submissionStatus,
            @RequestParam(required = false) LearningDtos.AssessmentGradingStatus gradingStatus,
            @RequestParam(required = false) Boolean late, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "STUDENT_NUMBER") LearningDtos.GradebookSortField sort,
            @RequestParam(defaultValue = "asc") String direction, HttpServletRequest servlet) {
        return learning.teacherGradebook(LearningIdentity.teaching(userId, roles, permissions).userId(), courseId,
                assessmentId, type, submissionStatus, gradingStatus, late, keyword, page, size, sort,
                "desc".equalsIgnoreCase(direction), trace(servlet));
    }

    @GetMapping(value = "/api/v1/teacher/gradebook/export", produces = "text/csv")
    /** 按学生批次流式下载带 UTF-8 BOM 的成绩册 CSV，文件名只含课程 ID 和日期，不含学生隐私。 */
    public ResponseEntity<StreamingResponseBody> exportGradebook(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Permissions", required = false) String permissions,
            @RequestParam String courseId, @RequestParam java.util.UUID clientRequestId,
            @RequestParam(required = false) String assessmentId,
            @RequestParam(required = false) LearningDtos.AssessmentType type,
            @RequestParam(required = false) LearningDtos.AssessmentSubmissionStatus submissionStatus,
            @RequestParam(required = false) LearningDtos.AssessmentGradingStatus gradingStatus,
            @RequestParam(required = false) Boolean late, @RequestParam(required = false) String keyword,
            HttpServletRequest servlet) {
        String teacherId = LearningIdentity.teaching(userId, roles, permissions).userId();
        StreamingResponseBody csv = output -> learning.streamGradebook(teacherId, courseId, assessmentId, type,
                submissionStatus, gradingStatus, late, keyword, clientRequestId, trace(servlet), output);
        String filename = "gradebook-" + courseId + "-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    private static String trace(HttpServletRequest request) {
        Object value = request.getAttribute(TraceFilter.ATTRIBUTE);
        return value == null ? "00000000-0000-0000-0000-000000000000" : value.toString();
    }
}
