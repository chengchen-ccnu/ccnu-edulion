package com.eduplatform.course.web;

import com.eduplatform.course.service.CourseException;
import com.eduplatform.course.service.CourseSuspensionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 仅服务调用的暂停命令与事实读取；独立鉴权，不向浏览器 Gateway 注册路由。 */
@RestController
@Validated
@RequestMapping("/internal/v1")
public class CourseSuspensionController {
    private final CourseSuspensionService service;
    private final String iamToken;
    private final String learningToken;
    public CourseSuspensionController(CourseSuspensionService service,
            @Value("${platform.course.suspension.iam-token:}") String iamToken,
            @Value("${platform.course.media.service-authorization.secret:}") String learningToken) {
        this.service = service; this.iamToken = iamToken; this.learningToken = learningToken;
    }

    /** IAM 以账号 revision 和周期 ID 请求暂停/恢复；成功返回提交后的事实，重复不再次写区间或 Outbox。 */
    @PutMapping("/teachers/{teacherId}/course-availability")
    public CourseSuspensionService.TeacherState change(@PathVariable UUID teacherId,
            @RequestHeader(value="X-Internal-Caller", defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token", defaultValue="") String token,
            @RequestHeader(value="X-Trace-Id", defaultValue="") String trace,
            @Valid @RequestBody Command command) {
        authorize(caller, token, "iam-service", iamToken);
        return service.change(teacherId.toString(), command.suspensionId().toString(), command.accessRevision(),
                command.paused(), trace.matches("[A-Za-z0-9_-]{8,64}") ? trace : UUID.randomUUID().toString());
    }

    /** IAM 读取本域预览范围；不写暂停事实，不返回其他域报名或身份数据。 */
    @GetMapping("/teachers/{teacherId}/availability-impact")
    public CourseSuspensionService.Impact impact(@PathVariable UUID teacherId,
            @RequestParam boolean paused, @RequestParam(required=false) UUID suspensionId,
            @RequestHeader(value="X-Internal-Caller", defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token", defaultValue="") String token) {
        authorize(caller, token, "iam-service", iamToken);
        return service.impact(teacherId.toString(), paused, suspensionId == null ? null : suspensionId.toString());
    }

    /** Learning 读取精确课程及有界区间；未知资源返回 404，缺失或其他服务身份返回 401。 */
    @GetMapping("/courses/{courseId}/availability")
    public CourseSuspensionService.Availability availability(@PathVariable UUID courseId,
            @RequestParam(defaultValue="0") @Min(0) long afterSequence,
            @RequestHeader(value="X-Internal-Caller", defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token", defaultValue="") String token) {
        authorize(caller, token, "learning-service", learningToken);
        return service.availability(courseId.toString(), afterSequence);
    }

    /** 固定 caller 与至少 32 字节秘密必须同时匹配；常量时间比较防止逐字节泄露秘密。 */
    private static void authorize(String caller, String token, String expectedCaller, String expected) {
        if (expected == null || expected.getBytes(StandardCharsets.UTF_8).length < 32 || !expectedCaller.equals(caller)
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new CourseException(HttpStatus.UNAUTHORIZED, "COURSE.INTERNAL_UNAUTHORIZED", "内部调用身份无效");
        }
    }

    /** 通用媒体 owner 入口的授权适配：撤权暂停拒绝，缺少内部身份拒绝；不暴露审核原因。 */
    @GetMapping("/teachers/{teacherId}/course-media-access")
    public org.springframework.http.ResponseEntity<Void> teacherMediaAccess(@PathVariable UUID teacherId,
            @RequestHeader(value="X-Internal-Caller", defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token", defaultValue="") String token) {
        authorize(caller, token, "media-service", learningToken);
        service.requireTeacherMediaAccess(teacherId.toString());
        return org.springframework.http.ResponseEntity.noContent().build();
    }
    public record Command(@jakarta.validation.constraints.NotNull UUID suspensionId, @Min(1) long accessRevision,
            @jakarta.validation.constraints.NotNull Boolean paused) {}
}
