package com.eduplatform.media.web;

import com.eduplatform.media.service.AuthorizedMediaAccessService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供受信领域服务调用的媒体授权入口。
 *
 * <p>Gateway 不路由 `/internal/**`；即使普通用户可直达服务端口，也必须持有一次性、
 * 强绑定且短时有效的 course-service 签名令牌才能调用成功。</p>
 */
@RestController
@ConditionalOnProperty(prefix = "platform.media", name = "enabled", havingValue = "true")
@Validated
@RequestMapping("/internal/v1/media")
public class InternalMediaAuthorizationController {
    private final AuthorizedMediaAccessService service;

    public InternalMediaAuthorizationController(AuthorizedMediaAccessService service) {
        this.service = service;
    }

    /** 为已经通过课程领域权限判断的单个 READY 视频签发短期 HLS 访问授权。 */
    @PostMapping("/{mediaId}/access-grants")
    ResponseEntity<MediaDtos.AccessGrant> access(
            @PathVariable String mediaId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody InternalMediaDtos.ServiceAccessGrantRequest request) {
        return ResponseEntity.status(201).body(service.issue(authorization, mediaId, request));
    }
}
