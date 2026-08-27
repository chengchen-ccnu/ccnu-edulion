package com.eduplatform.course.media;

import com.eduplatform.course.config.CourseMediaProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * 为 course-service 已批准的单次媒体访问签发短时 HMAC JWT。
 *
 * <p>令牌绑定访问者、mediaId、用途、访问类型、变体、audience、有效期和一次性 jti。
 * 该令牌只发送给 media-service，绝不返回浏览器或写入数据库、事件和日志。</p>
 */
@Component
public class CourseMediaAuthorizationIssuer {
    private final CourseMediaProperties.ServiceAuthorization properties;
    private final NimbusJwtEncoder encoder;

    public CourseMediaAuthorizationIssuer(CourseMediaProperties properties) {
        this.properties = properties.serviceAuthorization();
        byte[] secret = this.properties.secret() == null
                ? new byte[0] : this.properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("COURSE_MEDIA_AUTH_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (this.properties.tokenTtl() == null || this.properties.tokenTtl().isZero()
                || this.properties.tokenTtl().isNegative()) {
            throw new IllegalStateException("COURSE_MEDIA_AUTH_TOKEN_TTL must be positive");
        }
        if (this.properties.issuer() == null || this.properties.issuer().isBlank()
                || this.properties.audience() == null || this.properties.audience().isBlank()) {
            throw new IllegalStateException("Course media authorization issuer and audience are required");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * 签发仅允许一次 COURSE_PLAYBACK/精确 HLS 变体/INLINE 调用的令牌。
     *
     * @param visitorId Gateway 验证后的当前访问者 ID
     * @param mediaId course-service 从课时资源绑定中解析出的稳定媒体 ID
     * @param purpose course-service 从绑定行解析出的精确用途
     * @param variant media-service 必须签发的派生版本
     * @param traceId 当前请求追踪 ID，只用于媒体授权审计关联
     * @return 只供 course-service 到 media-service 内部调用使用的紧凑 JWT
     */
    public String issuePlayback(String visitorId, String mediaId, String purpose, String variant, String traceId) {
        return issue(visitorId, mediaId, purpose, "COURSE_PLAYBACK", variant, "INLINE", traceId);
    }

    /**
     * 为已通过课程可见性判断的封面签发一次 COURSE_COVER_DISPLAY/ORIGINAL/INLINE 服务令牌。
     *
     * @param viewerId 教师 owner 或公开课程读取使用的可信 UUID；不会交给浏览器
     * @param mediaId 课程版本保存的稳定 COURSE_COVER 媒体 ID
     * @param traceId 当前课程读取调用链 ID，只用于跨服务审计关联
     * @return 仅供 course-service 调用 media-service 内部接口的一次性紧凑 JWT
     */
    public String issueCover(String viewerId, String mediaId, String traceId) {
        return issue(viewerId, mediaId, "COURSE_COVER", "COURSE_COVER_DISPLAY", "ORIGINAL", "INLINE", traceId);
    }

    /** 将明确的访问类型、用途、变体和处置方式全部绑定进短时 JWT，调用方不能省略业务边界。 */
    private String issue(String visitorId, String mediaId, String purpose, String accessType,
            String variant, String disposition, String traceId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(visitorId)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.tokenTtl()))
                .claim("mediaId", mediaId)
                .claim("purpose", purpose)
                .claim("accessType", accessType)
                .claim("variant", variant)
                .claim("disposition", disposition)
                .claim("traceId", traceId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
