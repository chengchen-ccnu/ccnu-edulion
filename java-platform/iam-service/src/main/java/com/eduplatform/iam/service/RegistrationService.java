package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学生公开注册编排；只授予 STUDENT/learning:access，不接受角色输入。 */
@Service
public class RegistrationService {
    private final IamMapper mapper;
    private final PasswordEncoder encoder;
    private final VerificationChallengeService challenges;
    private final SecurityDigests digests;

    public RegistrationService(IamMapper mapper, PasswordEncoder encoder,
            VerificationChallengeService challenges, SecurityDigests digests) {
        this.mapper = mapper; this.encoder = encoder; this.challenges = challenges; this.digests = digests;
    }

    /**
     * 消费一次性挑战后，在一个 MySQL 事务中创建账号、唯一身份、协议同意和注册幂等事实。
     * @return 新账号或相同 clientRequestId 的原始账号投影
     */
    @Transactional
    public AccountLifecycleDtos.RegistrationResult register(AccountLifecycleDtos.RegisterRequest request,
            String remoteAddress, String traceId) {
        String type = request.identityType().name();
        String normalizedIdentity = IdentityNormalizer.normalize(type, request.target());
        String normalizedAccount = request.account().trim().toLowerCase(Locale.ROOT);
        String identityDigest = digests.digest("registration-identity", normalizedIdentity);
        String replayUserId = mapper.findRegistrationReplay(request.clientRequestId().toString(),
                normalizedAccount, type, identityDigest);
        if (replayUserId != null) return result(mapper.findUserById(replayUserId));
        validateAgreements(request.agreements());
        challenges.consume(request.challengeId().toString(), "REGISTRATION", type,
                request.target(), request.verificationCode());

        Instant now = Instant.now();
        String userId = UUID.randomUUID().toString();
        try {
            mapper.upsertRole("STUDENT", "STUDENT");
            mapper.upsertPermission("learning:access", "learning:access");
            mapper.assignPermission("STUDENT", "learning:access");
            mapper.insertUser(userId, normalizedAccount, request.displayName().trim(), encoder.encode(request.password()));
            mapper.insertIdentity(UUID.randomUUID().toString(), userId, type, normalizedIdentity, now);
            mapper.assignRole(userId, "STUDENT");
            for (AccountLifecycleDtos.Agreement agreement : request.agreements()) {
                mapper.insertAgreement(UUID.randomUUID().toString(), userId,
                        agreement.documentType().toUpperCase(Locale.ROOT), agreement.version(), now,
                        safe(remoteAddress));
            }
            mapper.insertRegistrationRequest(request.clientRequestId().toString(), userId, normalizedAccount,
                    type, identityDigest, now);
            mapper.insertSecurityAudit(UUID.randomUUID().toString(), userId, userId, "ACCOUNT_REGISTERED",
                    "学生自主注册", safe(remoteAddress), safeTrace(traceId), now);
            return result(mapper.findUserById(userId));
        } catch (DuplicateKeyException duplicate) {
            throw new AuthException(HttpStatus.CONFLICT, "IAM.IDENTITY_OCCUPIED", "账号或验证目标已被使用");
        }
    }

    private static void validateAgreements(java.util.List<AccountLifecycleDtos.Agreement> agreements) {
        Map<String, Long> counts = agreements.stream().collect(Collectors.groupingBy(
                value -> value.documentType().toUpperCase(Locale.ROOT), Collectors.counting()));
        if (!counts.keySet().equals(Set.of("TERMS", "PRIVACY")) || counts.values().stream().anyMatch(v -> v != 1L)) {
            throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED",
                    "必须分别同意当前版本的用户协议和隐私政策");
        }
    }

    private AccountLifecycleDtos.RegistrationResult result(UserAccount user) {
        return new AccountLifecycleDtos.RegistrationResult(UUID.fromString(user.getId()), user.getAccount(),
                user.getDisplayName(), java.util.List.of("STUDENT"), java.util.List.of("learning:access"),
                user.getCreatedAt());
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
    private static String safeTrace(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
