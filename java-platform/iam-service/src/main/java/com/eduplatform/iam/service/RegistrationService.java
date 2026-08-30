package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
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
    private final ProtocolDocumentService protocols;

    public RegistrationService(IamMapper mapper, PasswordEncoder encoder,
            VerificationChallengeService challenges, SecurityDigests digests,
            ProtocolDocumentService protocols) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.challenges = challenges;
        this.digests = digests;
        this.protocols = protocols;
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
        String nickname = normalizeNickname(request.nickname());
        String identityDigest = digests.digest("registration-identity", normalizedIdentity);
        String fingerprint = fingerprint(request, identityDigest, nickname);
        String replayUserId = mapper.findRegistrationReplay(request.clientRequestId().toString(),
                type, identityDigest, fingerprint);
        if (replayUserId != null) return result(mapper.findUserById(replayUserId));
        protocols.requireCurrentAgreements(request.agreements());
        challenges.consume(request.challengeId().toString(), "REGISTRATION", type,
                request.target(), request.verificationCode());

        Instant now = Instant.now();
        String userId = UUID.randomUUID().toString();
        String internalAccount = "usr_" + userId.replace("-", "");
        try {
            mapper.upsertRole("STUDENT", "STUDENT");
            mapper.upsertPermission("learning:access", "learning:access");
            mapper.assignPermission("STUDENT", "learning:access");
            mapper.insertUser(userId, internalAccount, nickname, encoder.encode(request.password()));
            mapper.insertIdentity(UUID.randomUUID().toString(), userId, type, normalizedIdentity, now);
            mapper.assignRole(userId, "STUDENT");
            for (AccountLifecycleDtos.Agreement agreement : request.agreements()) {
                mapper.insertAgreement(UUID.randomUUID().toString(), userId,
                        agreement.documentType().toUpperCase(Locale.ROOT), agreement.version(), now,
                        safe(remoteAddress));
            }
            mapper.insertRegistrationRequest(request.clientRequestId().toString(), userId, internalAccount,
                    type, identityDigest, fingerprint, now);
            mapper.insertSecurityAudit(UUID.randomUUID().toString(), userId, userId, "ACCOUNT_REGISTERED",
                    "学生自主注册", safe(remoteAddress), safeTrace(traceId), now);
            return result(mapper.findUserById(userId));
        } catch (DuplicateKeyException duplicate) {
            throw new AuthException(HttpStatus.CONFLICT, "IAM.IDENTITY_OCCUPIED", "邮箱或手机号已被使用");
        }
    }

    private static String normalizeNickname(String value) {
        String nickname = value == null ? "" : value.trim();
        int characters = nickname.codePointCount(0, nickname.length());
        boolean invisible = nickname.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
        if (characters < 1 || characters > 30 || invisible) {
            throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED",
                    "昵称必须包含 1 至 30 个可见字符");
        }
        return nickname;
    }

    private String fingerprint(AccountLifecycleDtos.RegisterRequest request,
            String identityDigest, String nickname) {
        String agreements = request.agreements().stream()
                .sorted(Comparator.comparing(value -> value.documentType().toUpperCase(Locale.ROOT)))
                .map(value -> value.documentType().toUpperCase(Locale.ROOT) + "=" + value.version())
                .collect(Collectors.joining("&"));
        String passwordDigest = digests.digest("registration-password", request.password());
        return digests.digest("registration-request", request.identityType().name() + "|" + identityDigest
                + "|" + nickname + "|" + passwordDigest + "|" + agreements);
    }

    private AccountLifecycleDtos.RegistrationResult result(UserAccount user) {
        return new AccountLifecycleDtos.RegistrationResult(UUID.fromString(user.getId()),
                user.getDisplayName(), java.util.List.of("STUDENT"), java.util.List.of("learning:access"),
                user.getCreatedAt());
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
    private static String safeTrace(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
