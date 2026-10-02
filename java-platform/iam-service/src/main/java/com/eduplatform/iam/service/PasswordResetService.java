package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 不可枚举密码重置；成功后 MySQL sessionVersion 和全部 Refresh Session 同步失效。 */
@Service
public class PasswordResetService {
    private final IamMapper mapper;
    private final PasswordEncoder encoder;
    private final VerificationChallengeService challenges;
    private final IdentitySecurityStore securityStore;

    public PasswordResetService(IamMapper mapper, PasswordEncoder encoder,
            VerificationChallengeService challenges, IdentitySecurityStore securityStore) {
        this.mapper = mapper; this.encoder = encoder; this.challenges = challenges;
        this.securityStore = securityStore;
    }

    /** 消费挑战并更新权威密码/会话事实；未知身份与无效挑战使用同一错误。 */
    @Transactional
    public void reset(AccountLifecycleDtos.ResetPasswordRequest request, String remoteAddress, String traceId) {
        String type = request.identityType().name();
        String normalized = challenges.consume(request.challengeId().toString(), "PASSWORD_RESET", type,
                request.target(), request.verificationCode());
        UserAccount user = mapper.findUserByIdentity(type, normalized);
        // 同登录/撤权共用账号锁，确保缓存失效后没有旧快照重新发布。
        if (user != null) user = mapper.findUserByIdForUpdate(user.getId());
        String passwordHash = encoder.encode(request.newPassword());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            mapper.insertSecurityAudit(UUID.randomUUID().toString(), user == null ? null : user.getId(),
                    user == null ? null : user.getId(), "PASSWORD_RESET_NO_CHANGE",
                    "重置挑战已消费但账号不可更新", safe(remoteAddress), safe(traceId), Instant.now());
            return;
        }
        try { securityStore.invalidateSessionVersion(user.getId()); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (mapper.changePasswordAndBumpSession(user.getId(), passwordHash) != 1) {
            verificationFailed();
        }
        mapper.revokeAllUserSessions(user.getId());
        mapper.insertSecurityAudit(UUID.randomUUID().toString(), user.getId(), user.getId(), "PASSWORD_RESET",
                "用户通过验证挑战重置密码", safe(remoteAddress), safe(traceId), Instant.now());
    }

    private static void verificationFailed() {
        throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "IAM.VERIFICATION_FAILED", "验证码无效或已过期");
    }
    private static AuthException unavailable() { return new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
            "IAM.SECURITY_STATE_UNAVAILABLE", "身份安全服务暂时不可用，请稍后重试", 2L); }
    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
