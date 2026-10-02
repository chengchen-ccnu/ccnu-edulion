package com.eduplatform.iam.web;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

/** 管理员邀请的中文业务边界：邮箱秘密只写请求，不进管理投影，受邀者自己设置密码。 */
public final class AdminInvitationDtos {
    private AdminInvitationDtos() {}
    public record Create(@NotNull UUID clientRequestId,@NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(min=10,max=500) String reason) {}
    public record Resend(@NotNull UUID clientRequestId,@NotNull @Min(0) Long expectedRevision,
            @NotBlank @Size(min=10,max=500) String reason) {}
    public record Accept(@NotNull UUID invitationId,@NotBlank @Email @Size(max=254) String email,
            @NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String secret,
            @NotNull @Size(min=12,max=72) String password,@NotBlank @Size(max=80) String displayName) {
        /** 不允许框架/调试toString把邮箱、密码或一次性秘密写入日志。 */
        @Override public String toString() { return "AdminInvitationAccept[redacted]"; }
    }
    public record Invitation(UUID invitationId,String maskedEmail,String deliveryStatus,String acceptanceStatus,
            long revision,int sendAttempts,Instant expiresAt,Instant resendAfter,boolean replayed) {}
    public record Acceptance(UUID userId) {}
}
