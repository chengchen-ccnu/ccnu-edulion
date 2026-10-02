package com.eduplatform.iam.verification;

import com.eduplatform.iam.config.IamProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import com.eduplatform.iam.service.AuthException;

/** 管理邀请复用现有SMTP连接与加密配置，不冒用注册验证码用途，不落盘或记录原始邀请秘密。 */
@Component
public class AdminInvitationDelivery {
    private final JavaMailSender sender;
    private final IamProperties properties;
    public AdminInvitationDelivery(JavaMailSender sender,IamProperties properties) { this.sender=sender;this.properties=properties; }

    /** 输入邮箱、非秘密邀请ID和一次性秘密；SMTP接受只是发送结果，不声称收件箱送达，失败交由邀请事实留痕。 */
    public void send(String email,String invitationId,String secret) {
        if (!"smtp".equals(properties.verification().deliveryMode()) || properties.verification().smtpFrom()==null
                || properties.verification().smtpFrom().isBlank() || email.endsWith(".invalid"))
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,"IAM.INVITATION_DELIVERY_UNAVAILABLE","管理员邀请邮件渠道不可用");
        SimpleMailMessage mail=new SimpleMailMessage();
        mail.setFrom(properties.verification().smtpFrom());mail.setTo(email);
        mail.setSubject("Edu Lion 管理员邀请");
        mail.setText("你被邀请成为 Edu Lion 独立管理员。管理员不具备学习、教学或教师申请能力。\n"
                +"请在管理员邀请接受页面输入下列信息，验证本邮箱所有权并自行设置密码。\n邀请ID："+invitationId
                +"\n一次性邮箱凭证："+secret+"\n凭证24小时内有效，只能接受一次。重发后旧凭证立即失效。\n"
                +"请勿转发，不要把凭证放入URL。若不是你的操作，请忽略本邮件。");
        sender.send(mail);
    }
}
