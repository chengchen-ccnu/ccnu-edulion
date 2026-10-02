package com.eduplatform.learning.web;

import com.eduplatform.learning.service.NotificationTaskService;
import com.eduplatform.learning.service.QuestionException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Learning有界收件人与当前授权读取；仅Operation独立秘密，不给IAM人数协议增加学生名单。 */
@RestController
public class NotificationLearningController {
    private final JdbcTemplate jdbc;
    private final NotificationTaskService tasks;
    private final String secret;
    public NotificationLearningController(JdbcTemplate jdbc,NotificationTaskService tasks,
            @Value("${OPERATION_INTERNAL_SERVICE_TOKEN:}") String secret) {
        this.jdbc=jdbc; this.tasks=tasks; this.secret=secret;
    }
    /** 按触发时点落在报名区间筛出最多100人；稳定learnerId游标，后来报名不能混入历史提醒。 */
    @GetMapping("/internal/v1/notification-recipients/{courseId}")
    public Recipients recipients(@PathVariable UUID courseId,@RequestParam Instant at,
            @RequestParam(defaultValue="") String after,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        authorize(caller,token);
        if(!after.isEmpty()) {
            try { UUID.fromString(after); } catch(IllegalArgumentException e) {
                throw new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT,"LEARNING.VALIDATION_FAILED","收件人游标不合法");
            }
        }
        var ids=jdbc.query("SELECT DISTINCT learner_id FROM notification_enrollment_interval WHERE course_id=? AND learner_id>? AND starts_at<=? AND (ends_at IS NULL OR ends_at>?) ORDER BY learner_id LIMIT 101",
                (rs,n)->rs.getString(1),courseId.toString(),after,Timestamp.from(at),Timestamp.from(at));
        return new Recipients(ids.subList(0,Math.min(100,ids.size())),ids.size()>100);
    }
    /** 输入本人和最多100个课程/任务；输出当前ACTIVE及有效课程任务目标，无题干答案，不授作答权限。 */
    @PostMapping("/internal/v1/notification-access")
    public Access access(@Valid @RequestBody AccessRequest request,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        authorize(caller,token);
        List<String> active=selectIds("SELECT course_id FROM enrollment WHERE learner_id=? AND status='ACTIVE' AND course_id IN (%s)",request.learnerId(),request.courseIds());
        List<String> accessible=selectIds("SELECT a.id FROM assessment a JOIN enrollment e ON e.course_id=a.course_id AND e.learner_id=? AND e.status='ACTIVE' LEFT JOIN assessment_participant p ON p.assessment_id=a.id AND p.learner_id=e.learner_id WHERE a.id IN (%s) AND a.assessment_type IN ('HOMEWORK','EXAM') AND a.status<>'DRAFT' AND (a.close_status='OPEN' OR p.enrollment_status='ACTIVE')",request.learnerId(),request.assessmentIds());
        List<String> eligible=new java.util.ArrayList<>();
        for(Check check:request.checks()) {
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM notification_enrollment_interval i JOIN enrollment e ON e.id=i.enrollment_id WHERE e.learner_id=? AND e.status='ACTIVE' AND i.course_id=? AND i.starts_at<=? AND (i.ends_at IS NULL OR i.ends_at>?)",Long.class,
                    request.learnerId().toString(),check.courseId().toString(),Timestamp.from(check.at()),Timestamp.from(check.at()));
            if(count!=null && count>0) eligible.add(check.key().toString());
        }
        return new Access(active,accessible,eligible);
    }
    /** 读取任务的安全事实，Course时钟失败503；暂停或结束返回不可投递，不发误导性开放消息。 */
    @GetMapping("/internal/v1/notification-assessments/{assessmentId}")
    public NotificationTaskService.Fact task(@PathVariable UUID assessmentId,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        authorize(caller,token); return tasks.fact(assessmentId.toString());
    }
    /** 异步fan-out单页最多100位当前报名再授权；只返回仍ACTIVE的ID，避免逐学生跨服务请求。 */
    @PostMapping("/internal/v1/notification-current-recipients")
    public List<String> current(@Valid @RequestBody Current request,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        authorize(caller,token);
        if(request.learnerIds().isEmpty()) return List.of();
        var args=new java.util.ArrayList<Object>(); args.add(request.courseId().toString()); request.learnerIds().forEach(id->args.add(id.toString()));
        return jdbc.query("SELECT learner_id FROM enrollment WHERE course_id=? AND status='ACTIVE' AND learner_id IN ("+String.join(",",java.util.Collections.nCopies(request.learnerIds().size(),"?"))+")",(rs,n)->rs.getString(1),args.toArray());
    }
    public record Current(@NotNull(message="字段不可为空") UUID courseId,@NotNull(message="字段不可为空") @Size(max=100,message="单批最多100项") List<@NotNull(message="字段不可为空") UUID> learnerIds) {}
    private List<String> selectIds(String sql,UUID learner,List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        var args=new java.util.ArrayList<Object>(); args.add(learner.toString()); ids.forEach(id->args.add(id.toString()));
        return jdbc.query(sql.formatted(String.join(",",java.util.Collections.nCopies(ids.size(),"?"))), (rs,n)->rs.getString(1),args.toArray());
    }
    /** caller和独立强秘密同时匹配；空配置关闭入口，不能冒用Learning或IAM服务身份。 */
    private void authorize(String caller,String token) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32 || !"operation-service".equals(caller)
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new QuestionException(HttpStatus.UNAUTHORIZED,"LEARNING.INTERNAL_UNAUTHORIZED","内部调用身份无效");
    }
    public record Recipients(List<String> learnerIds,boolean hasMore) {}
    public record AccessRequest(@NotNull(message="字段不可为空") UUID learnerId,@NotNull(message="字段不可为空") @Size(max=100,message="单批最多100项") List<@NotNull(message="字段不可为空") UUID> courseIds,
            @NotNull(message="字段不可为空") @Size(max=100,message="单批最多100项") List<@NotNull(message="字段不可为空") UUID> assessmentIds,
            @NotNull(message="字段不可为空") @Size(max=100,message="单批最多100项") List<@NotNull(message="字段不可为空") @Valid Check> checks) {}
    /** 单个历史资格检查输入不可信目标URL；key仅用于关联结果，at为Operation持久触发时点。 */
    public record Check(@NotNull(message="字段不可为空") UUID key,@NotNull(message="字段不可为空") UUID courseId,@NotNull(message="字段不可为空") Instant at) {}
    public record Access(List<String> activeCourseIds,List<String> accessibleAssessmentIds,List<String> eligibleKeys) {}
}
