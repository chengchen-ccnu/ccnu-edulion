package com.eduplatform.learning.media;

import com.eduplatform.learning.config.LearningProperties;
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
 * 为已通过 learning-service 测评访问事实校验的单个题目媒体签发一次性令牌。
 *
 * <p>令牌只发送给 media-service，绑定学习者、mediaId、用途、变体、处置方式和 jti；
 * 浏览器不能获取或重放该服务令牌。learning-service 不持有 OSS SDK 或存储凭据。</p>
 */
@Component
public class AssessmentMediaAuthorizationIssuer {
    private final LearningProperties.Media properties;

    public AssessmentMediaAuthorizationIssuer(LearningProperties properties) {
        this.properties = properties.media();
    }

    /**
     * 签发一枚短时 ASSESSMENT_QUESTION 令牌。
     * @param visitorId Gateway 已验证的学习者 UUID
     * @param mediaId 不可变试卷快照中的稳定媒体 ID
     * @param purpose 快照导出的精确题目媒体用途
     * @param disposition 图片/音频内联，附件下载
     * @param traceId 当前请求追踪 ID
     * @return 只供内部 HTTP 调用使用的紧凑 JWT
     */
    public String issue(String visitorId, String mediaId, String purpose, String disposition, String traceId) {
        byte[] secret = properties.authorizationSecret() == null ? new byte[0]
                : properties.authorizationSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32 || properties.authorizationTokenTtl() == null
                || properties.authorizationTokenTtl().isZero() || properties.authorizationTokenTtl().isNegative()) {
            throw new IllegalStateException("Assessment media service authorization is not configured");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.authorizationIssuer())
                .audience(List.of(properties.authorizationAudience()))
                .subject(visitorId)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.authorizationTokenTtl()))
                .claim("mediaId", mediaId)
                .claim("purpose", purpose)
                .claim("accessType", "ASSESSMENT_QUESTION")
                .claim("variant", "ORIGINAL")
                .claim("disposition", disposition)
                .claim("traceId", traceId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
