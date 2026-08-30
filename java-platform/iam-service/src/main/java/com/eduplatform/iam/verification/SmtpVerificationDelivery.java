package com.eduplatform.iam.verification;

import com.eduplatform.iam.config.IamProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 标准 SMTP 邮箱验证码适配器；账号、密码和发件地址只从本机安全环境注入。 */
@Component
@ConditionalOnProperty(prefix = "platform.iam.verification", name = "delivery-mode", havingValue = "smtp")
public class SmtpVerificationDelivery implements VerificationDeliveryPort {
    private final JavaMailSender sender;
    private final String from;

    public SmtpVerificationDelivery(JavaMailSender sender, IamProperties properties) {
        this.sender = sender;
        this.from = properties.verification().smtpFrom();
        if (from == null || from.isBlank()) throw new IllegalStateException("IAM_SMTP_FROM must be configured for SMTP delivery");
    }

    @Override public boolean supports(String identityType) { return "EMAIL".equals(identityType); }

    @Override
    public void deliver(Message message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.target());
        mail.setSubject("Edu Lion 一次性验证码");
        mail.setText("本次操作验证码为：" + message.code() + "。请勿向任何人透露；若非本人操作请忽略。\n用途："
                + purposeLabel(message.purpose()));
        sender.send(mail);
    }

    private static String purposeLabel(String purpose) {
        return switch (purpose) {
            case "REGISTRATION" -> "注册";
            case "PASSWORD_RESET" -> "找回密码";
            case "VERIFICATION_LOGIN" -> "验证码登录";
            case "IDENTITY_BIND" -> "绑定身份";
            case "IDENTITY_REPLACE" -> "换绑身份";
            default -> "账号安全验证";
        };
    }
}
