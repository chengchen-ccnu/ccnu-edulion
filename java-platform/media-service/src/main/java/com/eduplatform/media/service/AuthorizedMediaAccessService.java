package com.eduplatform.media.service;

import com.eduplatform.media.web.InternalMediaDtos;
import com.eduplatform.media.web.MediaDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接服务令牌校验与媒体短期授权，确保 jti 消费和授权审计处于同一数据库事务。
 * 外部 OSS 签发失败会使数据库事务回滚；course-service 可重新签发新的 jti 重试。
 */
@Service
public class AuthorizedMediaAccessService {
    private final ServiceAuthorizationVerifier verifier;
    private final MediaAssetService mediaAssetService;

    public AuthorizedMediaAccessService(ServiceAuthorizationVerifier verifier,
            MediaAssetService mediaAssetService) {
        this.verifier = verifier;
        this.mediaAssetService = mediaAssetService;
    }

    /**
     * 校验并消费一次服务授权，然后仅为用途一致的 READY 视频或课程封面签发精确变体。
     *
     * @param authorizationHeader course-service 生成的 Bearer 服务令牌
     * @param mediaId 内部 URL 中的媒体 ID
     * @param request 与令牌逐项绑定的内部请求
     * @return 不含 Object Key 的短期 HLS 或封面原件访问授权
     */
    @Transactional
    public MediaDtos.AccessGrant issue(String authorizationHeader, String mediaId,
            InternalMediaDtos.ServiceAccessGrantRequest request) {
        ServiceAuthorizationVerifier.VerifiedAuthorization authorization =
                verifier.verify(authorizationHeader, mediaId, request);
        return mediaAssetService.accessAuthorized(mediaId, authorization.visitorId(),
                authorization.purpose(), request.variant(), request.disposition(), authorization.traceId());
    }
}
