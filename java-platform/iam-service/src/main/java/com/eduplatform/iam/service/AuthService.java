package com.eduplatform.iam.service;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.domain.RefreshSession;
import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
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
    private final SecureRandom secureRandom = new SecureRandom();
    private final String dummyHash;

    public AuthService(IamMapper mapper, PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService, IamProperties properties) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
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
    public TokenPair login(String account, String password, String remoteAddress, String traceId) {
        String normalized = account.trim().toLowerCase();
        Instant now = Instant.now();
        int failures = mapper.countRecentFailures(normalized, remoteAddress,
                now.minus(properties.loginRateLimit().window()));
        if (failures >= properties.loginRateLimit().attempts()) {
            audit(normalized, null, "RATE_LIMITED", remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "IAM.LOGIN_RATE_LIMITED", "登录尝试过于频繁，请稍后再试");
        }

        UserAccount user = mapper.findUserByAccount(normalized);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            audit(normalized, null, "BAD_CREDENTIALS", remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.BAD_CREDENTIALS", "账号或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            audit(normalized, user.getId(), "BAD_CREDENTIALS", remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.BAD_CREDENTIALS", "账号或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            audit(normalized, user.getId(), user.getStatus(), remoteAddress, traceId, now);
            throw new AuthException(HttpStatus.FORBIDDEN, "IAM.ACCOUNT_" + user.getStatus(), "账号当前不可用");
        }

        AuthenticatedUser current = toAuthenticatedUser(user);
        String refreshToken = randomToken();
        String familyId = UUID.randomUUID().toString();
        mapper.insertRefreshSession(UUID.randomUUID().toString(), familyId, user.getId(), null,
                hash(refreshToken), now.plus(properties.refresh().ttl()));
        audit(normalized, user.getId(), "SUCCESS", remoteAddress, traceId, now);
        return new TokenPair(jwtTokenService.issue(current), jwtTokenService.expiresInSeconds(), refreshToken);
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
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            mapper.revokeFamily(session.getFamilyId());
            throw unauthorizedRefresh();
        }
        String replacement = randomToken();
        mapper.insertRefreshSession(UUID.randomUUID().toString(), session.getFamilyId(), user.getId(),
                session.getId(), hash(replacement), Instant.now().plus(properties.refresh().ttl()));
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
    public AuthenticatedUser currentUser(String userId) {
        UserAccount user = mapper.findUserById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录");
        }
        return toAuthenticatedUser(user);
    }

    private AuthenticatedUser toAuthenticatedUser(UserAccount user) {
        List<String> roles = List.copyOf(mapper.findRoles(user.getId()));
        List<String> permissions = List.copyOf(mapper.findPermissions(user.getId()));
        return new AuthenticatedUser(user.getId(), user.getDisplayName(), user.getAvatarUrl(), roles, permissions);
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
}
