package com.eduplatform.iam.service;

import com.eduplatform.iam.config.IamProperties;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
/** 使用 IAM 配置签发短期 HS256 访问令牌，不负责刷新令牌持久化或校验。 */
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final IamProperties properties;

    public JwtTokenService(JwtEncoder encoder, IamProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * 把已从数据库读取的用户 ID、角色和权限写入短期 JWT。
     * @return 完整紧凑令牌；调用本方法不会写数据库
     */
    public String issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.jwt().accessTtl()))
                .subject(user.id())
                .claim("roles", user.roles())
                .claim("permissions", user.permissions())
                .claim("sessionVersion", user.sessionVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** @return OpenAPI 登录响应使用的访问令牌有效秒数。 */
    public long expiresInSeconds() {
        return properties.jwt().accessTtl().toSeconds();
    }
}
