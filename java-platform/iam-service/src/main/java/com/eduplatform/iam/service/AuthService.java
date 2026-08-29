package com.eduplatform.iam.service;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.domain.RefreshSession;
import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.security.SecurityDigests;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 处理账号登录、Refresh Token 轮换、退出和当前用户读取。
 *
 * <p>登录会写登录审计与刷新会话；刷新令牌只以 SHA-256 摘要入库。刷新采用令牌族轮换，
 * 已轮换令牌被再次使用时会撤销整个族，阻止重放。服务不保存课程或学习数据。</p>
 */
public class AuthService {
    private final IamMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final IamProperties properties;
    private final IdentitySecurityStore securityStore;
    private final SecurityDigests digests;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String dummyHash;

    public AuthService(IamMapper mapper, PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService, IamProperties properties,
            IdentitySecurityStore securityStore, SecurityDigests digests) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
        this.securityStore = securityStore;
        this.digests = digests;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = AuthException.class)
    /**
     * 校验账号密码及账号/IP 时间窗限流，成功后签发访问令牌并创建刷新会话。
     *
     * @param account 登录账号，按去空格和小写规则归一化
     * @param password 明文仅用于本次哈希比对，不会持久化
     * @param remoteAddress 登录来源地址，用于限流和审计
     * @param traceId Gateway 传入的请求追踪 ID
     * @return 访问令牌、有效秒数和仅返回一次的刷新令牌
     * @throws AuthException 凭据错误、账号不可用或限流时抛出；失败审计仍提交
     */
    public TokenPair login(String account, String password, String remoteAddress, String traceId,
            String deviceId, String deviceName) {
        String normalized = account.trim().toLowerCase();
        Instant now = Instant.now();
        String accountDigest = digests.digest("login-account", normalized);
        String addressDigest = digests.digest("login-address", safeAddress(remoteAddress));
        long retryAfter = security(() -> securityStore.loginLockRetrySeconds(accountDigest, addressDigest));
        if (retryAfter > 0) {
            audit(normalized, null, "RATE_LIMITED", remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "IAM.LOGIN_RATE_LIMITED",
                    "登录尝试过于频繁，请稍后再试", retryAfter);
        }

        UserAccount user = mapper.findUserByAccount(normalized);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            security(() -> securityStore.recordLoginFailure(accountDigest, addressDigest,
                    properties.loginRateLimit().attempts(), properties.loginRateLimit().window()));
            audit(normalized, null, "BAD_CREDENTIALS", remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.BAD_CREDENTIALS", "账号或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            security(() -> securityStore.recordLoginFailure(accountDigest, addressDigest,
                    properties.loginRateLimit().attempts(), properties.loginRateLimit().window()));
            audit(normalized, user.getId(), "BAD_CREDENTIALS", remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.BAD_CREDENTIALS", "账号或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            audit(normalized, user.getId(), user.getStatus(), remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.FORBIDDEN, "IAM.ACCOUNT_" + user.getStatus(), "账号当前不可用");
        }

        security(() -> { securityStore.clearLoginFailures(accountDigest, addressDigest); return null; });
        cacheVersion(user);

        AuthenticatedUser current = toAuthenticatedUser(user);
        String refreshToken = randomToken();
        String familyId = UUID.randomUUID().toString();
        mapper.insertRefreshSession(UUID.randomUUID().toString(), familyId, user.getId(), null,
                hash(refreshToken), now.plus(properties.refresh().ttl()), user.getSessionVersion(),
                deviceId, normalizeDeviceName(deviceName), now);
        audit(normalized, user.getId(), "SUCCESS", remoteAddress, traceId, now);
        return new TokenPair(jwtTokenService.issue(current), jwtTokenService.expiresInSeconds(), refreshToken);
    }

    /** 兼容既有调用方；未提供设备标签时使用安全默认值。 */
    public TokenPair login(String account, String password, String remoteAddress, String traceId) {
        return login(account, password, remoteAddress, traceId, null, null);
    }

    @Transactional(noRollbackFor = AuthException.class)
    /**
     * 原子轮换有效刷新令牌并签发新的令牌对。
     *
     * @param refreshToken HttpOnly Cookie 中的原始刷新令牌
     * @return 新访问令牌和替换后的刷新令牌
     * @throws AuthException 令牌缺失、过期或重放时抛出；可疑重放会撤销同族全部会话
     */
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw unauthorizedRefresh();
        }
        RefreshSession session = mapper.findRefreshSession(hash(refreshToken));
        if (session == null) {
            throw unauthorizedRefresh();
        }
        if (!"ACTIVE".equals(session.getStatus())) {
            mapper.revokeFamily(session.getFamilyId());
            throw new AuthException(HttpStatus.CONFLICT, "IAM.REFRESH_REUSED", "登录会话已失效，请重新登录");
        }
        if (!session.getExpiresAt().isAfter(Instant.now())) {
            mapper.revokeFamily(session.getFamilyId());
            throw unauthorizedRefresh();
        }
        if (mapper.rotateRefreshSession(session.getId()) != 1) {
            mapper.revokeFamily(session.getFamilyId());
            throw new AuthException(HttpStatus.CONFLICT, "IAM.REFRESH_REUSED", "登录会话已失效，请重新登录");
        }
        UserAccount user = mapper.findUserById(session.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())
                || session.getSessionVersion() != user.getSessionVersion()) {
            mapper.revokeFamily(session.getFamilyId());
            throw unauthorizedRefresh();
        }
        cacheVersion(user);
        String replacement = randomToken();
        Instant now = Instant.now();
        mapper.insertRefreshSession(UUID.randomUUID().toString(), session.getFamilyId(), user.getId(),
                session.getId(), hash(replacement), now.plus(properties.refresh().ttl()),
                user.getSessionVersion(), session.getDeviceId(), session.getDeviceName(), now);
        return new TokenPair(jwtTokenService.issue(toAuthenticatedUser(user)),
                jwtTokenService.expiresInSeconds(), replacement);
    }

    @Transactional
    /**
     * 撤销当前刷新令牌；空令牌视为幂等成功，重复退出不会产生额外状态变化。
     */
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            mapper.revokeByTokenHash(hash(refreshToken));
        }
    }

    /**
     * 根据 JWT subject 重新读取启用用户及实时角色权限。
     * @throws AuthException 用户不存在或已停用时要求重新登录
     */
    public AuthenticatedUser currentUser(String userId, long tokenSessionVersion) {
        UserAccount user = mapper.findUserById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())
                || user.getSessionVersion() != tokenSessionVersion) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录");
        }
        return toAuthenticatedUser(user);
    }

    /** 仅供既有服务级测试读取当前持久化版本；HTTP 路径必须传入 JWT 中的版本。 */
    public AuthenticatedUser currentUser(String userId) {
        UserAccount user = mapper.findUserById(userId);
        if (user == null) throw unauthorizedRefresh();
        return currentUser(userId, user.getSessionVersion());
    }

    private AuthenticatedUser toAuthenticatedUser(UserAccount user) {
        List<String> roles = List.copyOf(mapper.findRoles(user.getId()));
        List<String> permissions = List.copyOf(mapper.findPermissions(user.getId()));
        return new AuthenticatedUser(user.getId(), user.getDisplayName(), user.getAvatarUrl(), roles, permissions,
                user.getSessionVersion());
    }

    /** 查询本人未过期 Refresh Session，当前标记来自 HttpOnly Cookie 摘要匹配。 */
    public List<AccountSession> sessions(String userId, String refreshToken) {
        String currentHash = refreshToken == null || refreshToken.isBlank() ? null : hash(refreshToken);
        return mapper.listActiveSessions(userId).stream().map(value -> new AccountSession(value.getId(),
                value.getDeviceName(), value.getTokenHash().equals(currentHash), value.getCreatedAt(),
                value.getLastUsedAt(), value.getExpiresAt())).toList();
    }

    /** 当前用户修改密码；Redis 先失效，MySQL 事务再更新密码、版本、会话和审计。 */
    @Transactional
    public void changePassword(String userId, long tokenVersion, String currentPassword, String newPassword,
            String remoteAddress, String traceId) {
        UserAccount user = mapper.findUserById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus()) || user.getSessionVersion() != tokenVersion
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "IAM.CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        requireNewPassword(currentPassword, newPassword);
        invalidateVersion(userId);
        if (mapper.changePasswordAndBumpSession(userId, passwordEncoder.encode(newPassword)) != 1) throw unauthorizedRefresh();
        mapper.revokeAllUserSessions(userId);
        securityAudit(userId, userId, "PASSWORD_CHANGED", "用户修改密码", remoteAddress, traceId);
    }

    /** 全部设备退出；持久化版本递增和 Refresh 撤销处于同一事务。 */
    @Transactional
    public void logoutAll(String userId, long tokenVersion, String remoteAddress, String traceId) {
        UserAccount user = mapper.findUserById(userId);
        if (user == null || user.getSessionVersion() != tokenVersion) throw unauthorizedRefresh();
        invalidateVersion(userId);
        if (mapper.bumpSessionVersion(userId) != 1) throw unauthorizedRefresh();
        mapper.revokeAllUserSessions(userId);
        securityAudit(userId, userId, "ALL_SESSIONS_REVOKED", "用户退出全部设备", remoteAddress, traceId);
    }

    private void audit(String account, String userId, String outcome, String remoteAddress,
            String traceId, Instant occurredAt) {
        mapper.insertLoginAudit(account, userId, outcome, remoteAddress, traceId, occurredAt);
    }

    private String randomToken() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static AuthException unauthorizedRefresh() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "IAM.INVALID_REFRESH", "登录会话已失效，请重新登录");
    }

    private void cacheVersion(UserAccount user) {
        security(() -> { securityStore.cacheSessionVersion(user.getId(), user.getSessionVersion(),
                properties.sessionCache().ttl()); return null; });
    }

    private void invalidateVersion(String userId) {
        security(() -> { securityStore.invalidateSessionVersion(userId); return null; });
    }

    private <T> T security(java.util.concurrent.Callable<T> action) {
        try { return action.call(); }
        catch (AuthException exception) { throw exception; }
        catch (Exception exception) { throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                "IAM.SECURITY_STATE_UNAVAILABLE", "身份安全服务暂时不可用，请稍后重试", 2L); }
    }

    private void securityAudit(String userId, String actorId, String action, String reason,
            String remoteAddress, String traceId) {
        mapper.insertSecurityAudit(UUID.randomUUID().toString(), userId, actorId, action, reason,
                safeAddress(remoteAddress), traceId == null ? "unknown" : traceId, Instant.now());
    }

    private static void requireNewPassword(String current, String replacement) {
        if (replacement == null || replacement.length() < 12 || replacement.length() > 128
                || replacement.equals(current)) throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "COMMON.VALIDATION_FAILED", "新密码不符合要求");
    }

    private static String normalizeDeviceName(String value) {
        if (value == null || value.isBlank()) return "未知设备";
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.isBlank() ? "未知设备" : cleaned.substring(0, Math.min(120, cleaned.length()));
    }

    private static String safeAddress(String value) { return value == null || value.isBlank() ? "unknown" : value; }

    public record AccountSession(String id, String deviceName, boolean current,
            Instant createdAt, Instant lastUsedAt, Instant expiresAt) {}
}
