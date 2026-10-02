package com.eduplatform.iam.web;

import com.eduplatform.iam.service.AuthException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 本人审核通知目标的最小授权；不复制材料、联系方式、完整理由或管理员审核详情。 */
@RestController
public class NotificationReviewController {
    private final JdbcTemplate jdbc;
    private final String secret;
    public NotificationReviewController(JdbcTemplate jdbc,@Value("${OPERATION_INTERNAL_SERVICE_TOKEN:}") String secret) {
        this.jdbc=jdbc; this.secret=secret;
    }
    /** 固定Operation身份输入本人及最多100个申请ID，返回当前ACTIVE普通账号可进入的本人申请目标。 */
    @PostMapping("/internal/v1/notification-review-access")
    public List<String> access(@Valid @RequestBody Request request,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32 || !"operation-service".equals(caller)
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new AuthException(HttpStatus.UNAUTHORIZED,"IAM.INTERNAL_UNAUTHORIZED","内部调用身份无效");
        if(request.applicationIds().isEmpty()) return List.of();
        var args=new java.util.ArrayList<Object>(); args.add(request.userId().toString()); request.applicationIds().forEach(id->args.add(id.toString()));
        String marks=String.join(",",java.util.Collections.nCopies(request.applicationIds().size(),"?"));
        return jdbc.query("SELECT a.id FROM teacher_application a JOIN `user` u ON u.id=a.user_id WHERE u.id=? AND u.status='ACTIVE' AND a.id IN ("+marks+") AND NOT EXISTS(SELECT 1 FROM user_role ur JOIN `role` r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='ADMIN')",
                (rs,n)->rs.getString(1),args.toArray());
    }
    public record Request(@NotNull(message="字段不可为空") UUID userId,@NotNull(message="字段不可为空") @Size(max=100,message="单批最多100项") List<@NotNull(message="字段不可为空") UUID> applicationIds) {}
}
