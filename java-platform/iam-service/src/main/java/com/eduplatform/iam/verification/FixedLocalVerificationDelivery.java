package com.eduplatform.iam.verification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** local/test 固定码适配器：支持邮箱和手机，但不进行网络发送，也绝不把验证码写日志。 */
@Component
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "platform.iam.verification", name = "delivery-mode", havingValue = "fixed-local")
public class FixedLocalVerificationDelivery implements VerificationDeliveryPort {
    @Override public boolean supports(String identityType) {
        return "EMAIL".equals(identityType) || "PHONE".equals(identityType);
    }

    @Override public void deliver(Message message) {
        // 固定码由操作者通过本机加密配置获知；适配器故意不发网、不打印、不落库。
    }
}
