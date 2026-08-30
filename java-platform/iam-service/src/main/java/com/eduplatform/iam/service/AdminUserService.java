package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员账号查询、解锁、禁用与强制下线；所有写操作记录原因和幂等事实。 */
@Service
public class AdminUserService {
    private final IamMapper mapper;
    private final IdentitySecurityStore securityStore;
    private final SecurityDigests digests;

    public AdminUserService(IamMapper mapper, IdentitySecurityStore securityStore, SecurityDigests digests) {
        this.mapper = mapper; this.securityStore = securityStore; this.digests = digests;
    }

    public AccountLifecycleDtos.AdminUserPage list(String keyword, AccountLifecycleDtos.AccountStatus status,
            int page, int size) {
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = status == null ? null : status.name();
        List<AccountLifecycleDtos.AdminUserResponse> items = mapper.listUsers(normalizedKeyword, normalizedStatus,
                ((long) page - 1L) * size, size).stream().map(this::response).toList();
        return new AccountLifecycleDtos.AdminUserPage(items, page, size,
                mapper.countUsers(normalizedKeyword, normalizedStatus));
    }

    public AccountLifecycleDtos.AdminUserResponse get(String userId) { return response(requireUser(userId)); }

    @Transactional
    public AccountLifecycleDtos.AdminCommandResult unlock(String actorId, String targetId,
            AccountLifecycleDtos.AdminCommandRequest request, String remoteAddress, String traceId) {
        Replay replay = replay(actorId, targetId, "UNLOCK", request);
        if (replay.replayed()) return new AccountLifecycleDtos.AdminCommandResult(response(requireUser(targetId)), true);
        UserAccount target = requireUser(targetId);
        if (mapper.touchUserRevision(targetId, request.expectedRevision()) != 1) conflict();
        security(() -> { securityStore.unlockAccount(digests.digest("login-user", target.getId())); return null; });
        finish(actorId, targetId, "UNLOCK", request, request.expectedRevision() + 1,
                remoteAddress, traceId);
        return new AccountLifecycleDtos.AdminCommandResult(response(requireUser(targetId)), false);
    }

    @Transactional
    public AccountLifecycleDtos.AdminCommandResult disable(String actorId, String targetId,
            AccountLifecycleDtos.AdminCommandRequest request, String remoteAddress, String traceId) {
        if (actorId.equals(targetId)) throw new AuthException(HttpStatus.CONFLICT,
                "IAM.SELF_DISABLE_FORBIDDEN", "不能通过该接口禁用当前管理员账号");
        Replay replay = replay(actorId, targetId, "DISABLE", request);
        if (replay.replayed()) return new AccountLifecycleDtos.AdminCommandResult(response(requireUser(targetId)), true);
        requireUser(targetId);
        if (mapper.disableUser(targetId, request.expectedRevision()) != 1) conflict();
        invalidate(targetId);
        mapper.revokeAllUserSessions(targetId);
        finish(actorId, targetId, "DISABLE", request, request.expectedRevision() + 1,
                remoteAddress, traceId);
        return new AccountLifecycleDtos.AdminCommandResult(response(requireUser(targetId)), false);
    }

    @Transactional
    public AccountLifecycleDtos.AdminCommandResult forceLogout(String actorId, String targetId,
            AccountLifecycleDtos.AdminCommandRequest request, String remoteAddress, String traceId) {
        Replay replay = replay(actorId, targetId, "FORCE_LOGOUT", request);
        if (replay.replayed()) return new AccountLifecycleDtos.AdminCommandResult(response(requireUser(targetId)), true);
        requireUser(targetId);
        if (mapper.forceRevokeSessions(targetId, request.expectedRevision()) != 1) conflict();
        invalidate(targetId);
        mapper.revokeAllUserSessions(targetId);
        finish(actorId, targetId, "FORCE_LOGOUT", request, request.expectedRevision() + 1,
                remoteAddress, traceId);
        return new AccountLifecycleDtos.AdminCommandResult(response(requireUser(targetId)), false);
    }

    private Replay replay(String actorId, String targetId, String type,
            AccountLifecycleDtos.AdminCommandRequest request) {
        Long revision = mapper.findAdminCommandReplay(actorId, request.clientRequestId().toString(), targetId,
                type, fingerprint(type, request));
        return new Replay(revision != null, revision == null ? -1L : revision);
    }

    private void finish(String actorId, String targetId, String type,
            AccountLifecycleDtos.AdminCommandRequest request, long revision,
            String remoteAddress, String traceId) {
        Instant now = Instant.now();
        mapper.insertAdminCommand(actorId, request.clientRequestId().toString(), targetId, type,
                fingerprint(type, request), revision, now);
        mapper.insertSecurityAudit(UUID.randomUUID().toString(), targetId, actorId, "ADMIN_" + type,
                request.reason().trim(), safe(remoteAddress), safe(traceId), now);
    }

    private AccountLifecycleDtos.AdminUserResponse response(UserAccount user) {
        List<String> identities = mapper.findIdentities(user.getId()).stream().map(value -> {
            String[] parts = value.split(":", 2);
            return parts[0] + ":" + IdentityNormalizer.mask(parts[0], parts[1]);
        }).toList();
        boolean locked = security(() -> securityStore.loginLockRetrySeconds(
                digests.digest("login-user", user.getId()), digests.digest("login-address", "admin-query"))) > 0;
        return new AccountLifecycleDtos.AdminUserResponse(UUID.fromString(user.getId()), user.getAccount(),
                user.getDisplayName(), identities, user.getStatus(), locked, mapper.findRoles(user.getId()),
                mapper.findPermissions(user.getId()), user.getSessionVersion(), user.getRevision(),
                user.getCreatedAt(), user.getUpdatedAt());
    }

    private UserAccount requireUser(String userId) {
        UserAccount user = mapper.findUserById(userId);
        if (user == null) throw new AuthException(HttpStatus.NOT_FOUND, "IAM.NOT_FOUND", "账号或申请不存在");
        return user;
    }
    private void invalidate(String userId) { security(() -> { securityStore.invalidateSessionVersion(userId); return null; }); }
    private <T> T security(java.util.concurrent.Callable<T> action) {
        try { return action.call(); }
        catch (Exception exception) { throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                "IAM.SECURITY_STATE_UNAVAILABLE", "身份安全服务暂时不可用，请稍后重试", 2L); }
    }
    private static String fingerprint(String type, AccountLifecycleDtos.AdminCommandRequest request) {
        try {
            String input = type + "\n" + request.expectedRevision() + "\n" + request.reason().trim();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static void conflict() { throw new AuthException(HttpStatus.CONFLICT, "IAM.REVISION_CONFLICT",
            "账号状态已变化，请刷新后重试"); }
    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
    private record Replay(boolean replayed, long revision) {}
}
