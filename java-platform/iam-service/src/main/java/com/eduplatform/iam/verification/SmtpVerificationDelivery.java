package com.eduplatform.iam.verification;

import com.eduplatform.iam.config.IamProperties;
import java.time.Duration;
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
    private final Duration ttl;

    public SmtpVerificationDelivery(JavaMailSender sender, IamProperties properties) {
        this.sender = sender;
        this.from = properties.verification().smtpFrom();
        this.ttl = properties.verification().ttl();
        if (from == null || from.isBlank()) throw new IllegalStateException("IAM_SMTP_FROM must be configured for SMTP delivery");
    }

    @Override public boolean supports(String identityType) { return "EMAIL".equals(identityType); }

    @Override
    public void deliver(Message message) {
        if (message.target().toLowerCase(java.util.Locale.ROOT).endsWith(".invalid")) {
            throw new IllegalArgumentException("保留测试域不得进入 SMTP");
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.target());
        mail.setSubject("Edu Lion " + purposeLabel(message.purpose()) + "验证码");
        mail.setText("你正在" + purposeAction(message.purpose()) + "，验证码是：" + message.code()
                + "\n\n请在 " + ttlLabel(ttl) + "内输入验证码" + completionAction(message.purpose())
                + "。验证码只能使用一次，请勿转发给他人。\n如果这不是你的操作，请忽略本邮件。");
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

    private static String purposeAction(String purpose) {
        return switch (purpose) {
            case "REGISTRATION" -> "注册 Edu Lion";
            case "PASSWORD_RESET" -> "重置 Edu Lion 登录密码";
            case "VERIFICATION_LOGIN" -> "登录 Edu Lion";
            case "IDENTITY_BIND" -> "绑定新的账号身份";
            case "IDENTITY_REPLACE" -> "更换账号身份";
            default -> "进行 Edu Lion 账号安全验证";
        };
    }

    private static String completionAction(String purpose) {
        return switch (purpose) {
            case "REGISTRATION" -> "完成注册";
            case "PASSWORD_RESET" -> "完成密码重置";
            case "VERIFICATION_LOGIN" -> "完成登录";
            case "IDENTITY_BIND" -> "完成身份绑定";
            case "IDENTITY_REPLACE" -> "完成身份更换";
            default -> "完成安全验证";
        };
    }

    private static String ttlLabel(Duration ttl) {
        long seconds = ttl.toSeconds();
        return seconds >= 60 && seconds % 60 == 0 ? (seconds / 60) + " 分钟" : seconds + " 秒";
    }
}
