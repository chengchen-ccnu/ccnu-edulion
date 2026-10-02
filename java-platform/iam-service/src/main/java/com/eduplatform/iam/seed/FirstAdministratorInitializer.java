package com.eduplatform.iam.seed;

import com.eduplatform.iam.service.AdminInvitationService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 默认不装配的受控首位管理员CLI；双重显式启用，仍走邮箱邀请，不自动创建账号或给管理员设置密码。 */
@Component
@ConditionalOnProperty(prefix="platform.iam.first-administrator",name="enabled",havingValue="true")
public class FirstAdministratorInitializer implements ApplicationRunner {
    private final AdminInvitationService service;
    private final String email;
    private final String requestId;
    public FirstAdministratorInitializer(AdminInvitationService service,
            @Value("${platform.iam.first-administrator.email:}") String email,
            @Value("${platform.iam.first-administrator.request-id:}") String requestId) {
        this.service=service;this.email=email;this.requestId=requestId;
    }
    /** 必须传--initialize-first-admin且无历史管理员事实；请求ID重放不重复发信，失败阻止此受控初始化启动。 */
    @Override public void run(ApplicationArguments args) {
        if(!args.containsOption("initialize-first-admin"))throw new IllegalStateException("首位管理员初始化需要显式CLI参数");
        var invitation=service.bootstrap(UUID.fromString(requestId),email);
        System.out.println("首位管理员邀请ID="+invitation.invitationId()+"；发送状态="+invitation.deliveryStatus()+"；未创建管理员账号");
    }
}
