package com.eduplatform.iam.media;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** IAM 按已验证的申请轮次签发一次性、字段强绑定的 Media 内部访问令牌。 */
@Component
public class TeacherQualificationAuthorizationIssuer {
    private final NimbusJwtEncoder encoder;
    private final String issuer;
    private final String audience;

    public TeacherQualificationAuthorizationIssuer(
            @Value("${platform.iam.media.authorization-secret:}") String secret,
            @Value("${platform.iam.media.authorization-issuer:edu-platform-iam-service}") String issuer,
            @Value("${platform.iam.media.authorization-audience:edu-platform-media-service}") String audience) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalStateException("COURSE_MEDIA_AUTH_SECRET must contain at least 32 UTF-8 bytes");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
        this.issuer = issuer;
        this.audience = audience;
    }

    /** 输入是当前管理员、轮次中确定的 mediaId 和动作；输出只用于 IAM 到 Media 的单次 HTTP 调用。 */
    public String issue(String administratorId, String mediaId, boolean download, String traceId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(administratorId).id(UUID.randomUUID().toString()).issuedAt(now)
                .expiresAt(now.plusSeconds(30)).claim("mediaId", mediaId)
                .claim("purpose", "TEACHER_QUALIFICATION")
                .claim("accessType", download ? "TEACHER_QUALIFICATION_DOWNLOAD" : "TEACHER_QUALIFICATION_PREVIEW")
                .claim("variant", "ORIGINAL")
                .claim("disposition", download ? "ATTACHMENT" : "INLINE")
                .claim("traceId", traceId).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
