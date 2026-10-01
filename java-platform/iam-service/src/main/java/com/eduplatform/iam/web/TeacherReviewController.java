package com.eduplatform.iam.web;

import com.eduplatform.iam.config.TraceFilter;
import com.eduplatform.iam.media.TeacherQualificationMediaClient;
import com.eduplatform.iam.service.TeacherReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员教师资格审核 HTTP 边界；权限由 Gateway、IAM 安全过滤器和业务服务重复校验。 */
@RestController
@Validated
@RequestMapping("/api/v1/admin/teacher-applications")
public class TeacherReviewController {
    private final TeacherReviewService service;

    public TeacherReviewController(TeacherReviewService service) { this.service = service; }

    /** 有界分页和精确身份查找只返回无材料、无联系信息的安全列表。 */
    @GetMapping
    public TeacherReviewDtos.Page list(JwtAuthenticationToken token,
            @RequestParam(required = false) TeacherReviewDtos.Status status,
            @RequestParam(required = false) String exact,
            @RequestParam(defaultValue = "NEWEST") TeacherReviewDtos.Order order,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(token.getToken().getSubject(), status, exact, order, page, size);
    }

    /** 管理员主动打开详情时才开始审核，首次查看人和时间作为审计事实保留。 */
    @GetMapping("/{applicationId}")
    public TeacherReviewDtos.Detail open(JwtAuthenticationToken token, @PathVariable UUID applicationId,
            HttpServletRequest servlet) {
        return service.open(token.getToken().getSubject(), applicationId.toString(),
                servlet.getRemoteAddr(), trace(servlet));
    }

    /** revision 和稳定请求 ID 保护最终决定；批准同时授予同账号教学能力。 */
    @PostMapping("/{applicationId}/decision")
    public TeacherReviewDtos.Detail decide(JwtAuthenticationToken token, @PathVariable UUID applicationId,
            @Valid @RequestBody TeacherReviewDtos.DecisionRequest request, HttpServletRequest servlet) {
        return service.decide(token.getToken().getSubject(), applicationId.toString(), request,
                servlet.getRemoteAddr(), trace(servlet));
    }

    /** 只依据已保存的轮次材料授权，浏览器无权直接指定 mediaId。 */
    @PostMapping("/{applicationId}/material-access")
    public ResponseEntity<TeacherQualificationMediaClient.Grant> grant(JwtAuthenticationToken token,
            @PathVariable UUID applicationId, @Valid @RequestBody TeacherReviewDtos.AccessRequest request,
            HttpServletRequest servlet) {
        return ResponseEntity.status(201).body(service.grant(token.getToken().getSubject(),
                applicationId.toString(), request, trace(servlet)));
    }

    private static String trace(HttpServletRequest request) {
        Object value = request.getAttribute(TraceFilter.ATTRIBUTE);
        return value == null ? "unknown-trace" : value.toString();
    }
}
