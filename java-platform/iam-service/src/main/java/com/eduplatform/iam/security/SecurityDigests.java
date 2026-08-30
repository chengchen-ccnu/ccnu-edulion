package com.eduplatform.iam.security;

import com.eduplatform.iam.config.IamProperties;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 使用独立 HMAC 密钥摘要验证码、身份目标与风控维度，避免 Redis Key 泄露隐私。 */
@Component
public class SecurityDigests {
    private final byte[] secret;

    public SecurityDigests(IamProperties properties) {
        String configured = properties.verification().hmacSecret();
        if (configured == null || configured.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("IAM_VERIFICATION_HMAC_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    /** @return 不可逆的小写十六进制 HMAC-SHA256。 */
    public String digest(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((namespace + "\n" + value)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 unavailable", exception);
        }
    }
}
