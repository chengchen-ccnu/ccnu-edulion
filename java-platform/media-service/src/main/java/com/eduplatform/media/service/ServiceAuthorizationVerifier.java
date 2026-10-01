package com.eduplatform.media.service;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.web.InternalMediaDtos;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * 校验并一次性消费 Course、Learning 或 IAM 领域已批准的媒体授权令牌。
 *
 * <p>验证签名、issuer、audience、时间窗、访问者、mediaId、用途、访问类型、变体、处置方式和 jti。
 * 任一失败都返回同一拒绝语义，不泄露具体是哪项错误；成功 jti 由数据库唯一键防重放。</p>
 */
@Component
public class ServiceAuthorizationVerifier {
    private final MediaProperties.ServiceAuthorization properties;
    private final NimbusJwtDecoder decoder;
    private final MediaRepository repository;

    public ServiceAuthorizationVerifier(MediaProperties properties, MediaRepository repository) {
        this.properties = properties.serviceAuthorization();
        this.repository = repository;
        byte[] secret = this.properties.secret() == null
                ? new byte[0] : this.properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("COURSE_MEDIA_AUTH_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (this.properties.issuer() == null || this.properties.issuer().isBlank()
                || this.properties.assessmentIssuer() == null || this.properties.assessmentIssuer().isBlank()
                || this.properties.iamIssuer() == null || this.properties.iamIssuer().isBlank()
                || this.properties.audience() == null || this.properties.audience().isBlank()
                || this.properties.maxTokenTtl() == null || this.properties.maxTokenTtl().isZero()
                || this.properties.maxTokenTtl().isNegative()
                || this.properties.clockSkew() == null || this.properties.clockSkew().isNegative()) {
            throw new IllegalStateException("Media service authorization settings are invalid");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.decoder = NimbusJwtDecoder.withSecretKey(key).build();
        JwtTimestampValidator timestamp = new JwtTimestampValidator(this.properties.clockSkew());
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamp,
                new JwtAudienceValidator(this.properties.audience())));
    }

    /**
     * 验证 Authorization Bearer 与内部请求的全部绑定字段，并原子消费一次性 jti。
     *
     * @param authorizationHeader 可信领域服务生成的 Bearer 头
     * @param mediaId 内部 URL 中的稳定媒体 ID
     * @param request course-service 同时提交、必须与令牌相等的授权参数
     * @return 验证后的访问者、用途和追踪信息
     * @throws MediaException 伪造、过期、错 audience、字段错配或重放均统一拒绝
     */
    public VerifiedAuthorization verify(String authorizationHeader, String mediaId,
            InternalMediaDtos.ServiceAccessGrantRequest request) {
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                throw rejected();
            }
            Jwt jwt = decoder.decode(authorizationHeader.substring(7));
            Instant now = Instant.now();
            requireUuid(jwt.getId());
            requireUuid(jwt.getSubject());
            requireTimeWindow(jwt, now);
            // 现有 issuer 是稳定服务标识而非 URL，不能调用 Jwt#getIssuer() 的 URL 强制转换。
            String issuer = jwt.getClaimAsString("iss");
            if (!properties.issuer().equals(issuer) && !properties.assessmentIssuer().equals(issuer)
                    && !properties.iamIssuer().equals(issuer)) {
                throw rejected();
            }
            requireEqual(mediaId, jwt.getClaimAsString("mediaId"));
            requireEqual(request.visitorId().toString(), jwt.getSubject());
            requireEqual(request.purpose().name(), jwt.getClaimAsString("purpose"));
            requireEqual(request.accessType(), jwt.getClaimAsString("accessType"));
            requireEqual(request.variant(), jwt.getClaimAsString("variant"));
            requireEqual(request.disposition().name(), jwt.getClaimAsString("disposition"));
            boolean courseIssuer = properties.issuer().equals(issuer);
            boolean learningIssuer = properties.assessmentIssuer().equals(issuer);
            boolean iamIssuer = properties.iamIssuer().equals(issuer);
            boolean playback = courseIssuer && "COURSE_PLAYBACK".equals(request.accessType())
                    && request.purpose() == com.eduplatform.media.domain.MediaPurpose.LESSON_VIDEO
                    && (MediaAssetService.PRESET_VERSION.equals(request.variant())
                            || MediaAssetService.LEGACY_PLAYBACK_VARIANT.equals(request.variant()))
                    && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.INLINE;
            boolean coverDisplay = courseIssuer && "COURSE_COVER_DISPLAY".equals(request.accessType())
                    && request.purpose() == com.eduplatform.media.domain.MediaPurpose.COURSE_COVER
                    && "ORIGINAL".equals(request.variant())
                    && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.INLINE;
            boolean articleImage = courseIssuer && "COURSE_ARTICLE_IMAGE".equals(request.accessType())
                    && request.purpose() == com.eduplatform.media.domain.MediaPurpose.ARTICLE_IMAGE
                    && "ORIGINAL".equals(request.variant())
                    && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.INLINE;
            boolean poster = courseIssuer && "COURSE_POSTER".equals(request.accessType())
                    && ((request.purpose() == com.eduplatform.media.domain.MediaPurpose.VIDEO_POSTER
                            && "ORIGINAL".equals(request.variant()))
                        || (request.purpose() == com.eduplatform.media.domain.MediaPurpose.LESSON_VIDEO
                            && "poster-v1".equals(request.variant())))
                    && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.INLINE;
            boolean courseResourcePurpose = Set.of(com.eduplatform.media.domain.MediaPurpose.COURSEWARE,
                    com.eduplatform.media.domain.MediaPurpose.ARTICLE_ATTACHMENT).contains(request.purpose());
            boolean resourcePreview = courseIssuer && "COURSE_RESOURCE_PREVIEW".equals(request.accessType())
                    && courseResourcePurpose && "ORIGINAL".equals(request.variant())
                    && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.INLINE;
            boolean resourceDownload = courseIssuer && "COURSE_RESOURCE_DOWNLOAD".equals(request.accessType())
                    && courseResourcePurpose && "ORIGINAL".equals(request.variant())
                    && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.ATTACHMENT;
            // Learning 已分别验证测评公布事实或当前公共题正文；Media 只校验来源令牌及技术资产边界。
            boolean assessmentMedia = learningIssuer
                    && Set.of("ASSESSMENT_QUESTION", "SYSTEM_QUESTION_CONTENT").contains(request.accessType())
                    && Set.of(com.eduplatform.media.domain.MediaPurpose.QUESTION_IMAGE,
                            com.eduplatform.media.domain.MediaPurpose.QUESTION_AUDIO,
                            com.eduplatform.media.domain.MediaPurpose.QUESTION_ATTACHMENT).contains(request.purpose())
                    && "ORIGINAL".equals(request.variant())
                    && ((request.purpose() == com.eduplatform.media.domain.MediaPurpose.QUESTION_ATTACHMENT
                            && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.ATTACHMENT)
                        || (request.purpose() != com.eduplatform.media.domain.MediaPurpose.QUESTION_ATTACHMENT
                            && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.INLINE));
            // IAM 已核对管理员与具体申请轮次；Media 仍限制来源、用途、动作、原件和一次性 jti。
            boolean qualificationMedia = iamIssuer
                    && request.purpose() == com.eduplatform.media.domain.MediaPurpose.TEACHER_QUALIFICATION
                    && "ORIGINAL".equals(request.variant())
                    && (("TEACHER_QUALIFICATION_PREVIEW".equals(request.accessType())
                            && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.INLINE)
                        || ("TEACHER_QUALIFICATION_DOWNLOAD".equals(request.accessType())
                            && request.disposition() == com.eduplatform.media.web.MediaDtos.Disposition.ATTACHMENT));
            if (!playback && !coverDisplay && !articleImage && !poster
                    && !resourcePreview && !resourceDownload && !assessmentMedia && !qualificationMedia) {
                throw rejected();
            }
            if (!repository.consumeServiceAuthorization(jwt.getId(), issuer,
                    properties.audience(), jwt.getSubject(), jwt.getExpiresAt(), now)) {
                throw rejected();
            }
            String traceId = jwt.getClaimAsString("traceId");
            if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
                throw rejected();
            }
            return new VerifiedAuthorization(jwt.getSubject(), request.purpose(), traceId);
        } catch (MediaException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private void requireTimeWindow(Jwt jwt, Instant now) {
        if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(properties.maxTokenTtl()))
                || jwt.getIssuedAt().isAfter(now.plus(properties.clockSkew()))) {
            throw rejected();
        }
    }

    private static void requireUuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
            throw rejected();
        }
    }

    private static void requireEqual(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) {
            throw rejected();
        }
    }

    private static MediaException rejected() {
        return new MediaException(HttpStatus.FORBIDDEN, "MEDIA.SERVICE_AUTHORIZATION_REJECTED",
                "服务授权无效或已使用");
    }

    /** 验证成功后的最小可信上下文，不包含原始 JWT 或签名 URL。 */
    public record VerifiedAuthorization(
            String visitorId, com.eduplatform.media.domain.MediaPurpose purpose, String traceId) {}
}
