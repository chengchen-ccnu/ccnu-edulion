package com.eduplatform.iam.web;

import com.eduplatform.iam.service.AccountPermissionService;
import com.eduplatform.iam.config.TraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** 管理员权限预览、明确确认及有限补偿HTTP入口；所有写决定记录原因，联动状态不冒充成功。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AccountPermissionController {
    private final AccountPermissionService service;
    public AccountPermissionController(AccountPermissionService service) { this.service=service; }

    /** 当前管理员预览目标动作；返回五分钟确认快照，依赖失败安全503。 */
    @PostMapping("/users/{userId}/permission-previews")
    public ResponseEntity<AccountPermissionDtos.Preview> preview(JwtAuthenticationToken actor,@PathVariable UUID userId,
            @Valid @RequestBody AccountPermissionDtos.PreviewRequest request) {
        return ResponseEntity.status(201).body(service.preview(actor.getToken().getSubject(),userId.toString(),request.action()));
    }
    /** 明确确认后提交IAM决定及协调事实；202响应仍需查询PENDING/FAILED/APPLIED。 */
    @PostMapping("/users/{userId}/permission-commands")
    public ResponseEntity<AccountPermissionDtos.Command> change(JwtAuthenticationToken actor,@PathVariable UUID userId,
            @Valid @RequestBody AccountPermissionDtos.CommandRequest request,HttpServletRequest servlet) {
        return ResponseEntity.accepted().body(service.change(actor.getToken().getSubject(),userId.toString(),request,
                servlet.getRemoteAddr(),(String)servlet.getAttribute(TraceFilter.ATTRIBUTE)));
    }
    /** 查询本域决定及真实课程协调状态，不包含审核理由或任何凭证。 */
    @GetMapping("/permission-commands/{commandId}")
    public AccountPermissionDtos.Command get(JwtAuthenticationToken actor,@PathVariable UUID commandId) {
        return service.get(actor.getToken().getSubject(),commandId.toString());
    }
    /** 当前管理员发起一次有界补偿，原周期及水位不变，状态先为PENDING。 */
    @PostMapping("/permission-commands/{commandId}/retry")
    public ResponseEntity<AccountPermissionDtos.Command> retry(JwtAuthenticationToken actor,@PathVariable UUID commandId,
            @Valid @RequestBody AccountPermissionDtos.RetryRequest request) {
        return ResponseEntity.accepted().body(service.retry(actor.getToken().getSubject(),commandId.toString(),request));
    }
}
