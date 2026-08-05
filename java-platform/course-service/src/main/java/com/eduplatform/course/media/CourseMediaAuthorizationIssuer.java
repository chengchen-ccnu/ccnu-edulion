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
     * 签发仅允许一次 COURSE_PLAYBACK/hls-v1/INLINE 调用的令牌。
     *
     * @param visitorId Gateway 验证后的当前访问者 ID
     * @param mediaId course-service 从课时资源绑定中解析出的稳定媒体 ID
     * @param purpose course-service 从绑定行解析出的精确用途
     * @param variant media-service 必须签发的派生版本
     * @param traceId 当前请求追踪 ID，只用于媒体授权审计关联
     * @return 只供 course-service 到 media-service 内部调用使用的紧凑 JWT
     */
    public String issue(String visitorId, String mediaId, String purpose, String variant, String traceId) {
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
                .claim("accessType", "COURSE_PLAYBACK")
                .claim("variant", variant)
                .claim("disposition", "INLINE")
                .claim("traceId", traceId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
