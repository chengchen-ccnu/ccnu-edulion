package com.eduplatform.iam.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 持久权限命令的有限协调调度器；每轮最多20项，失败留在原命令，不回滚撤权或无限补偿。 */
@Component
@ConditionalOnProperty(prefix="platform.iam.account-permissions",name="worker-enabled",havingValue="true",matchIfMissing=true)
public class AccountCoordinationWorker {
    private final AccountPermissionService service;
    public AccountCoordinationWorker(AccountPermissionService service) { this.service=service; }

    /** 读取到期命令并逐个调用有界协调；单项存储故障不传播秘密，也不妨碍其他账号推进。 */
    @Scheduled(fixedDelayString="${platform.iam.account-permissions.poll-delay:2s}")
    public void run() {
        for (String id:service.due()) {
            try { service.coordinate(id); }
            catch(RuntimeException ignored) { /* 命令仍在数据库；下轮按原预算和租期推进，不假称成功。 */ }
        }
    }
}
