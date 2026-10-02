package com.eduplatform.iam.web;

import com.eduplatform.iam.config.TraceFilter;
import com.eduplatform.iam.service.AdminInvitationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** 管理邀请与精确公开接受入口；秘密只允许请求正文，不提供公开初始化或账号转换接口。 */
@RestController
@RequestMapping("/api/v1")
public class AdminInvitationController {
    private final AdminInvitationService service;
    public AdminInvitationController(AdminInvitationService service) { this.service=service; }

    /** 当前有效管理员创建邮件邀请；202不是已接受，发送失败会返回FAILED事实。 */
    @PostMapping("/admin/invitations")
    public ResponseEntity<AdminInvitationDtos.Invitation> create(JwtAuthenticationToken actor,
            @Valid @RequestBody AdminInvitationDtos.Create request,HttpServletRequest servlet) {
        return ResponseEntity.accepted().body(service.create(actor.getToken().getSubject(),request,
                servlet.getRemoteAddr(),trace(servlet)));
    }
    /** 按稳定ID读取脱敏邀请与真实发送/接受状态，不返回凭证。 */
    @GetMapping("/admin/invitations/{invitationId}")
    public AdminInvitationDtos.Invitation get(JwtAuthenticationToken actor,@PathVariable UUID invitationId) {
        return service.get(actor.getToken().getSubject(),invitationId.toString());
    }
    /** 确认revision后有限重发；旧凭证先作废，发送失败仍有审计。 */
    @PostMapping("/admin/invitations/{invitationId}/resend")
    public ResponseEntity<AdminInvitationDtos.Invitation> resend(JwtAuthenticationToken actor,@PathVariable UUID invitationId,
            @Valid @RequestBody AdminInvitationDtos.Resend request,HttpServletRequest servlet) {
        return ResponseEntity.accepted().body(service.resend(actor.getToken().getSubject(),invitationId.toString(),request,
                servlet.getRemoteAddr(),trace(servlet)));
    }
    /** 邮箱凭证验证与自行设密码后原子创建独立管理员；422不区分错主体/过期/重放。 */
    @PostMapping("/auth/admin-invitations/accept")
    public ResponseEntity<AdminInvitationDtos.Acceptance> accept(@Valid @RequestBody AdminInvitationDtos.Accept request,
            HttpServletRequest servlet) {
        return ResponseEntity.status(201).body(service.accept(request,servlet.getRemoteAddr(),trace(servlet)));
    }
    private static String trace(HttpServletRequest servlet) { return (String)servlet.getAttribute(TraceFilter.ATTRIBUTE); }
}
