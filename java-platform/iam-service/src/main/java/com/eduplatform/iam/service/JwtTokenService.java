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
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final IamProperties properties;

    public JwtTokenService(JwtEncoder encoder, IamProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

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
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long expiresInSeconds() {
        return properties.jwt().accessTtl().toSeconds();
    }
}
