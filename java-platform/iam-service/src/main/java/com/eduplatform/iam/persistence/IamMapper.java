package com.eduplatform.iam.persistence;

import com.eduplatform.iam.domain.RefreshSession;
import com.eduplatform.iam.domain.UserAccount;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
/**
 * IAM Schema 的 MyBatis 持久化边界，包含账号读取、刷新令牌轮换、登录审计和本地 seed 写入。
 *
 * <p>调用方负责事务与权限语义；轮换/撤销方法用受影响行数支持并发重放判定。</p>
 */
public interface IamMapper {
    @Select("SELECT id, account, display_name, password_hash, status, avatar_url FROM `user` WHERE account = #{account}")
    UserAccount findUserByAccount(String account);

    @Select("SELECT id, account, display_name, password_hash, status, avatar_url FROM `user` WHERE id = #{id}")
    UserAccount findUserById(String id);

    @Select("SELECT r.code FROM `role` r JOIN user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId} ORDER BY r.code")
    List<String> findRoles(String userId);

    @Select("SELECT DISTINCT p.code FROM permission p JOIN role_permission rp ON rp.permission_id = p.id JOIN user_role ur ON ur.role_id = rp.role_id WHERE ur.user_id = #{userId} ORDER BY p.code")
    List<String> findPermissions(String userId);

    @Insert("INSERT INTO refresh_session (id, family_id, user_id, parent_id, token_hash, status, expires_at) VALUES (#{id}, #{familyId}, #{userId}, #{parentId}, #{tokenHash}, 'ACTIVE', #{expiresAt})")
    int insertRefreshSession(@Param("id") String id, @Param("familyId") String familyId,
            @Param("userId") String userId, @Param("parentId") String parentId,
            @Param("tokenHash") String tokenHash, @Param("expiresAt") Instant expiresAt);

    @Select("SELECT id, family_id, user_id, parent_id, token_hash, status, expires_at FROM refresh_session WHERE token_hash = #{tokenHash}")
    RefreshSession findRefreshSession(String tokenHash);

    @Update("UPDATE refresh_session SET status = 'ROTATED', rotated_at = CURRENT_TIMESTAMP(6) WHERE id = #{id} AND status = 'ACTIVE'")
    int rotateRefreshSession(String id);

    @Update("UPDATE refresh_session SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6) WHERE family_id = #{familyId} AND status <> 'REVOKED'")
    int revokeFamily(String familyId);

    @Update("UPDATE refresh_session SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6) WHERE token_hash = #{tokenHash} AND status = 'ACTIVE'")
    int revokeByTokenHash(String tokenHash);

    @Insert("INSERT INTO login_audit (account, user_id, outcome, remote_address, trace_id, occurred_at) VALUES (#{account}, #{userId}, #{outcome}, #{remoteAddress}, #{traceId}, #{occurredAt})")
    int insertLoginAudit(@Param("account") String account, @Param("userId") String userId,
            @Param("outcome") String outcome, @Param("remoteAddress") String remoteAddress,
            @Param("traceId") String traceId, @Param("occurredAt") Instant occurredAt);

    @Select("SELECT COUNT(*) FROM login_audit WHERE account = #{account} AND remote_address = #{remoteAddress} AND outcome = 'BAD_CREDENTIALS' AND occurred_at >= #{since}")
    int countRecentFailures(@Param("account") String account, @Param("remoteAddress") String remoteAddress,
            @Param("since") Instant since);

    @Insert("INSERT INTO `role` (code, name) VALUES (#{code}, #{name}) ON DUPLICATE KEY UPDATE name = VALUES(name)")
    int upsertRole(@Param("code") String code, @Param("name") String name);

    @Insert("INSERT INTO permission (code, name) VALUES (#{code}, #{name}) ON DUPLICATE KEY UPDATE name = VALUES(name)")
    int upsertPermission(@Param("code") String code, @Param("name") String name);

    @Insert("INSERT INTO `user` (id, account, display_name, password_hash, status) VALUES (#{id}, #{account}, #{displayName}, #{passwordHash}, 'ACTIVE') ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), password_hash = VALUES(password_hash), status = 'ACTIVE'")
    int upsertSeedUser(@Param("id") String id, @Param("account") String account,
            @Param("displayName") String displayName, @Param("passwordHash") String passwordHash);

    @Insert("INSERT IGNORE INTO user_role (user_id, role_id) SELECT #{userId}, id FROM `role` WHERE code = #{roleCode}")
    int assignRole(@Param("userId") String userId, @Param("roleCode") String roleCode);

    @Insert("INSERT IGNORE INTO role_permission (role_id, permission_id) SELECT r.id, p.id FROM `role` r CROSS JOIN permission p WHERE r.code = #{roleCode} AND p.code = #{permissionCode}")
    int assignPermission(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode);
}
