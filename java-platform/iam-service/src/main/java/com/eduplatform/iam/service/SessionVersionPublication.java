package com.eduplatform.iam.service;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.config.IamProperties;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

/** 会话事务与缓存发布边界；提交并归还连接后再锁账号读取权威版本，避免旧快照及连接池回调死锁。 */
@Component
public final class SessionVersionPublication {
    private final IamMapper mapper;
    private final IdentitySecurityStore store;
    private final IamProperties properties;
    private final TransactionTemplate transaction;
    private final TransactionTemplate issuance;

    public SessionVersionPublication(IamMapper mapper, IdentitySecurityStore store,
            IamProperties properties, PlatformTransactionManager manager) {
        this.mapper = mapper; this.store = store; this.properties = properties;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        issuance = new TransactionTemplate(manager);
        issuance.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        issuance.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 登录/刷新在独立事务中执行；认证失败仍提交失败审计/重放撤销，成功才发布缓存及返回凭证。 */
    public TokenPair issue(java.util.function.Supplier<Issued> action) {
        requireOutsideTransaction();
        Outcome outcome = issuance.execute(status -> {
            try { return new Outcome(action.get(), null); }
            catch (AuthException failure) { return new Outcome(null, failure); }
        });
        if (outcome.failure() != null) throw outcome.failure();
        publishCurrent(outcome.issued().userId());
        return outcome.issued().tokens();
    }

    /** 身份绑定先完整提交账号/会话修改，任意失败均回滚；提交成功后再发布当前已提交版本。 */
    public <T> T commitAndPublish(String userId, java.util.function.Supplier<T> action) {
        requireOutsideTransaction();
        T result = issuance.execute(status -> action.get());
        publishCurrent(userId);
        return result;
    }

    /** HTTP 签发边界必须独立完成；拒绝嵌入尚未提交的外层业务事务，防止提前提交或同账号自等待。 */
    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Session issuance must start outside a business transaction");
        }
    }

    /** 重新锁定并读取当前账号后写 Redis；与撤权共用锁序，Redis 故障报 503、缺失版本由 Gateway 拒绝。 */
    private void publishCurrent(String userId) {
        transaction.executeWithoutResult(status -> {
            var current = mapper.findUserByIdForUpdate(userId);
            try {
                if (current == null || !"ACTIVE".equals(current.getStatus())) store.invalidateSessionVersion(userId);
                else store.cacheSessionVersion(userId, current.getSessionVersion(), properties.sessionCache().ttl());
            } catch (RuntimeException failure) {
                throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "IAM.SECURITY_STATE_UNAVAILABLE",
                        "身份安全服务暂时不可用，请稍后重试", 2L);
            }
        });
    }

    /** 内部签发结果绑定账号 ID；只在进程内传递，原始刷新凭证不得记录日志。 */
    public record Issued(String userId, TokenPair tokens) {}
    private record Outcome(Issued issued, AuthException failure) {}
}
