package com.eduplatform.iam.service;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.domain.RefreshSession;
import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.domain.UserIdentity;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import com.eduplatform.iam.web.LoginResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已验证身份安全状态机：查询只返回脱敏投影；绑定保留当前 Refresh Session 并撤销其他设备，
 * 换绑和解绑撤销全部设备。所有写操作以用户 revision 串行化并写 MySQL 安全审计。
 */
@Service
public class IdentityService {
    private final IamMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final VerificationChallengeService challenges;
    private final IdentitySecurityStore securityStore;
    private final SecurityDigests digests;
    private final JwtTokenService jwtTokens;
    private final IamProperties properties;
    private final SessionVersionPublication versionPublication;

    public IdentityService(IamMapper mapper, PasswordEncoder passwordEncoder,
            VerificationChallengeService challenges, IdentitySecurityStore securityStore,
            SecurityDigests digests, JwtTokenService jwtTokens, IamProperties properties,
            SessionVersionPublication versionPublication) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.challenges = challenges;
        this.securityStore = securityStore;
        this.digests = digests;
        this.jwtTokens = jwtTokens;
        this.properties = properties;
        this.versionPublication = versionPublication;
    }

    /** @return 当前账号最多两个脱敏身份及写操作所需 revision。 */
    public AccountLifecycleDtos.VerifiedIdentityCollection list(String userId, long tokenVersion) {
        UserAccount user = requireCurrentUser(userId, tokenVersion, false);
        return collection(user);
    }

    /**
     * 校验当前密码、revision、目标唯一性和操作类型后发送验证码。挑战用途绑定 BIND/REPLACE，
     * 后续不能跨操作或跨目标消费。
     */
    public VerificationChallengeService.Challenge createChallenge(String userId, long tokenVersion,
            AccountLifecycleDtos.IdentityChallengeRequest request) {
        UserAccount user = requireCurrentUser(userId, tokenVersion, false);
        requireRevision(user, request.expectedRevision());
        requirePassword(user, request.currentPassword());
        String type = request.identityType().name();
        challenges.requireSupported(type);
        UserIdentity current = mapper.findUserIdentityByType(userId, type);
        if (request.operation() == AccountLifecycleDtos.IdentityChangeOperation.BIND && current != null) {
            throw conflict("该类型身份已绑定");
        }
        if (request.operation() == AccountLifecycleDtos.IdentityChangeOperation.REPLACE && current == null) {
            throw conflict("该类型身份尚未绑定");
        }
        String normalized = IdentityNormalizer.normalize(type, request.target());
        UserAccount occupied = mapper.findUserByIdentity(type, normalized);
        if (occupied != null) throw conflict("邮箱或手机号已被使用");
        String purpose = request.operation() == AccountLifecycleDtos.IdentityChangeOperation.BIND
                ? "IDENTITY_BIND" : "IDENTITY_REPLACE";
        return challenges.create(purpose, type, normalized, true);
    }

    /**
     * 新增缺失的第二身份。成功后 sessionVersion/revision 均加一，当前 Refresh Session 升级，
     * 其他 Refresh Session 撤销，并返回替换旧 JWT 的 15 分钟访问令牌。
     */
    public AccountLifecycleDtos.IdentityMutationResult bind(String userId, long tokenVersion,
            AccountLifecycleDtos.BindIdentityRequest request, String refreshToken,
            String remoteAddress, String traceId) {
        return versionPublication.commitAndPublish(userId, () -> bindLocked(userId, tokenVersion,
                request, refreshToken, remoteAddress, traceId));
    }

    /** 绑定在账号锁下更新身份和保留设备，返回已签发响应；外层在完整提交后发布会话版本。 */
    private AccountLifecycleDtos.IdentityMutationResult bindLocked(String userId, long tokenVersion,
            AccountLifecycleDtos.BindIdentityRequest request, String refreshToken,
            String remoteAddress, String traceId) {
        String type = request.identityType().name();
        String normalized = IdentityNormalizer.normalize(type, request.target());
        String fingerprint = fingerprint("BIND", type, normalized, request.expectedRevision(),
                request.currentPassword());
        AccountLifecycleDtos.IdentityMutationResult replay = replay(userId, request.clientRequestId(),
                "BIND", fingerprint, false, refreshToken);
        if (replay != null) return replay;
        challenges.consume(request.challengeId().toString(), "IDENTITY_BIND", type,
                normalized, request.verificationCode());

        UserAccount user = requireCurrentUser(userId, tokenVersion, true);
        replay = replay(userId, request.clientRequestId(), "BIND", fingerprint, false, refreshToken);
        if (replay != null) return replay;
        requireRevision(user, request.expectedRevision());
        requirePassword(user, request.currentPassword());
        if (mapper.findUserIdentityByType(userId, type) != null) throw conflict("该类型身份已绑定");
        RefreshSession current = requireCurrentRefresh(user, refreshToken);
        invalidateVersion(userId);
        try {
            mapper.insertIdentity(UUID.randomUUID().toString(), userId, type, normalized, Instant.now());
            if (mapper.bumpIdentityRevisionAndSession(userId, request.expectedRevision()) != 1) throw revisionConflict();
        } catch (DuplicateKeyException duplicate) {
            throw conflict("邮箱或手机号已被使用");
        }
        UserAccount changed = mapper.findUserById(userId);
        mapper.revokeOtherUserSessions(userId, current.getId());
        if (mapper.updateRefreshSessionVersion(current.getId(), userId, changed.getSessionVersion(), Instant.now()) != 1) {
            throw invalidSession();
        }
        audit(userId, "IDENTITY_BOUND", "用户新增第二个已验证身份", remoteAddress, traceId);
        mapper.insertIdentityCommand(request.clientRequestId().toString(), userId, "BIND", fingerprint,
                changed.getRevision(), Instant.now());
        LoginResponse session = new LoginResponse("Bearer", jwtTokens.issue(authenticated(changed)),
                jwtTokens.expiresInSeconds());
        return result(changed, "OTHER_DEVICES", false, session);
    }

    /** 换绑先消费新身份验证码，再原子替换旧值并撤销包括当前设备在内的全部会话。 */
    @Transactional
    public AccountLifecycleDtos.IdentityMutationResult replace(String userId, long tokenVersion, String pathType,
            AccountLifecycleDtos.BindIdentityRequest request, String remoteAddress, String traceId) {
        if (!pathType.equals(request.identityType().name())) throw validation("路径与请求身份类型不一致");
        String normalized = IdentityNormalizer.normalize(pathType, request.target());
        String fingerprint = fingerprint("REPLACE", pathType, normalized, request.expectedRevision(),
                request.currentPassword());
        AccountLifecycleDtos.IdentityMutationResult replay = replay(userId, request.clientRequestId(),
                "REPLACE", fingerprint, true, null);
        if (replay != null) return replay;
        challenges.consume(request.challengeId().toString(), "IDENTITY_REPLACE", pathType,
                normalized, request.verificationCode());

        UserAccount user = requireCurrentUser(userId, tokenVersion, true);
        replay = replay(userId, request.clientRequestId(), "REPLACE", fingerprint, true, null);
        if (replay != null) return replay;
        requireRevision(user, request.expectedRevision());
        requirePassword(user, request.currentPassword());
        UserIdentity existing = mapper.findUserIdentityByType(userId, pathType);
        if (existing == null) throw conflict("该类型身份尚未绑定");
        if (existing.getNormalizedValue().equals(normalized)) throw conflict("新身份不能与当前身份相同");
        invalidateVersion(userId);
        try {
            if (mapper.replaceIdentity(userId, pathType, normalized, Instant.now()) != 1) throw conflict("身份已变化");
            if (mapper.bumpIdentityRevisionAndSession(userId, request.expectedRevision()) != 1) throw revisionConflict();
        } catch (DuplicateKeyException duplicate) {
            throw conflict("邮箱或手机号已被使用");
        }
        UserAccount changed = mapper.findUserById(userId);
        mapper.revokeAllUserSessions(userId);
        audit(userId, "IDENTITY_REPLACED", "用户换绑已验证身份", remoteAddress, traceId);
        mapper.insertIdentityCommand(request.clientRequestId().toString(), userId, "REPLACE", fingerprint,
                changed.getRevision(), Instant.now());
        return result(changed, "ALL_DEVICES", true, null);
    }

    /** 解绑要求仍保留另一身份；成功后撤销全部访问与刷新会话并要求重新登录。 */
    @Transactional
    public AccountLifecycleDtos.IdentityMutationResult unbind(String userId, long tokenVersion, String type,
            AccountLifecycleDtos.UnbindIdentityRequest request, String remoteAddress, String traceId) {
        String fingerprint = fingerprint("UNBIND", type, "", request.expectedRevision(), request.currentPassword());
        AccountLifecycleDtos.IdentityMutationResult replay = replay(userId, request.clientRequestId(),
                "UNBIND", fingerprint, true, null);
        if (replay != null) return replay;
        UserAccount user = requireCurrentUser(userId, tokenVersion, true);
        replay = replay(userId, request.clientRequestId(), "UNBIND", fingerprint, true, null);
        if (replay != null) return replay;
        requireRevision(user, request.expectedRevision());
        requirePassword(user, request.currentPassword());
        if (mapper.countUserIdentities(userId) <= 1) throw conflict("不能解绑最后一个登录身份");
        if (mapper.findUserIdentityByType(userId, type) == null) throw conflict("该类型身份尚未绑定");
        invalidateVersion(userId);
        if (mapper.deleteIdentity(userId, type) != 1) throw conflict("身份已变化");
        if (mapper.bumpIdentityRevisionAndSession(userId, request.expectedRevision()) != 1) throw revisionConflict();
        UserAccount changed = mapper.findUserById(userId);
        mapper.revokeAllUserSessions(userId);
        audit(userId, "IDENTITY_UNBOUND", "用户解绑已验证身份", remoteAddress, traceId);
        mapper.insertIdentityCommand(request.clientRequestId().toString(), userId, "UNBIND", fingerprint,
                changed.getRevision(), Instant.now());
        return result(changed, "ALL_DEVICES", true, null);
    }

    private AccountLifecycleDtos.IdentityMutationResult replay(String userId, UUID requestId, String command,
            String fingerprint, boolean reauthentication, String refreshToken) {
        Long revision = mapper.findIdentityCommandReplay(requestId.toString(), userId, command, fingerprint);
        if (revision == null) return null;
        UserAccount user = mapper.findUserById(userId);
        if (user == null || user.getRevision() != revision) throw revisionConflict();
        LoginResponse session = null;
        if (!reauthentication) {
            requireCurrentRefresh(user, refreshToken);
            session = new LoginResponse("Bearer", jwtTokens.issue(authenticated(user)), jwtTokens.expiresInSeconds());
        }
        return result(user, reauthentication ? "ALL_DEVICES" : "OTHER_DEVICES", reauthentication, session);
    }

    private AccountLifecycleDtos.IdentityMutationResult result(UserAccount user, String scope,
            boolean reauthentication, LoginResponse session) {
        return new AccountLifecycleDtos.IdentityMutationResult(identities(user.getId()), user.getRevision(),
                user.getSessionVersion(), scope, reauthentication, session);
    }

    private AccountLifecycleDtos.VerifiedIdentityCollection collection(UserAccount user) {
        return new AccountLifecycleDtos.VerifiedIdentityCollection(identities(user.getId()), user.getRevision());
    }

    private List<AccountLifecycleDtos.VerifiedIdentity> identities(String userId) {
        return mapper.listUserIdentities(userId).stream().map(value -> new AccountLifecycleDtos.VerifiedIdentity(
                AccountLifecycleDtos.IdentityType.valueOf(value.getIdentityType()),
                IdentityNormalizer.mask(value.getIdentityType(), value.getNormalizedValue()), value.getVerifiedAt()))
                .toList();
    }

    private UserAccount requireCurrentUser(String userId, long tokenVersion, boolean lock) {
        UserAccount user = lock ? mapper.findUserByIdForUpdate(userId) : mapper.findUserById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus()) || user.getSessionVersion() != tokenVersion) {
            throw invalidSession();
        }
        return user;
    }

    private RefreshSession requireCurrentRefresh(UserAccount user, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw invalidSession();
        RefreshSession session = mapper.findRefreshSession(AuthService.hash(refreshToken));
        if (session == null || !user.getId().equals(session.getUserId()) || !"ACTIVE".equals(session.getStatus())
                || !session.getExpiresAt().isAfter(Instant.now())
                || session.getSessionVersion() != user.getSessionVersion()) throw invalidSession();
        return session;
    }

    private AuthenticatedUser authenticated(UserAccount user) {
        return new AuthenticatedUser(user.getId(), user.getDisplayName(), user.getAvatarUrl(),
                List.copyOf(mapper.findRoles(user.getId())), List.copyOf(mapper.findPermissions(user.getId())),
                user.getSessionVersion());
    }

    private void requirePassword(UserAccount user, String currentPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "IAM.CURRENT_PASSWORD_INVALID",
                    "当前密码不正确");
        }
    }

    private static void requireRevision(UserAccount user, long expectedRevision) {
        if (user.getRevision() != expectedRevision) throw revisionConflict();
    }

    private String fingerprint(String command, String type, String normalized, long revision, String password) {
        return digests.digest("identity-command", command + "|" + type + "|" + normalized + "|" + revision
                + "|" + digests.digest("identity-current-password", password));
    }

    private void invalidateVersion(String userId) {
        try { securityStore.invalidateSessionVersion(userId); }
        catch (RuntimeException exception) { throw unavailable(); }
    }

    private void audit(String userId, String action, String reason, String remoteAddress, String traceId) {
        mapper.insertSecurityAudit(UUID.randomUUID().toString(), userId, userId, action, reason,
                safe(remoteAddress), safe(traceId), Instant.now());
    }

    private static AuthException revisionConflict() {
        return new AuthException(HttpStatus.CONFLICT, "IAM.REVISION_CONFLICT", "账号身份已变化，请刷新后重试");
    }
    private static AuthException conflict(String message) {
        return new AuthException(HttpStatus.CONFLICT, "IAM.IDENTITY_CONFLICT", message);
    }
    private static AuthException validation(String message) {
        return new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "COMMON.VALIDATION_FAILED", message);
    }
    private static AuthException invalidSession() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "IAM.SESSION_INVALID", "登录会话已失效，请重新登录");
    }
    private static AuthException unavailable() {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "IAM.SECURITY_STATE_UNAVAILABLE",
                "身份安全服务暂时不可用，请稍后重试", 2L);
    }
    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
