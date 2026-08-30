package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.service.IdentityNormalizer;
import org.junit.jupiter.api.Test;

/** 邮箱与首期中国大陆手机号规范化边界测试。 */
class IdentityNormalizerTest {
    @Test
    void emailIsTrimmedAndCaseInsensitive() {
        assertThat(IdentityNormalizer.normalize("EMAIL", " Student@Example.COM "))
                .isEqualTo("student@example.com");
    }

    @Test
    void mainlandPhoneIsStoredAsE164AndOtherFormatsAreRejected() {
        assertThat(IdentityNormalizer.normalize("PHONE", "13800138000")).isEqualTo("+8613800138000");
        assertThat(IdentityNormalizer.normalize("PHONE", "+8613800138000")).isEqualTo("+8613800138000");
        assertThatThrownBy(() -> IdentityNormalizer.normalize("PHONE", "+12025550123"))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> IdentityNormalizer.normalize("PHONE", "12800138000"))
                .isInstanceOf(AuthException.class);
    }
}
