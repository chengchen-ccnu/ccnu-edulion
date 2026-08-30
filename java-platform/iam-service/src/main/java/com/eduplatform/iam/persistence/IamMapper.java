package com.eduplatform.iam.persistence;

import com.eduplatform.iam.domain.RefreshSession;
import com.eduplatform.iam.domain.ProtocolDocument;
import com.eduplatform.iam.domain.UserIdentity;
import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.domain.TeacherApplication;
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
    @Select("SELECT id, account, display_name, password_hash, status, avatar_url, session_version, revision, created_at, updated_at FROM `user` WHERE account = #{account}")
    UserAccount findUserByAccount(String account);

    @Select("SELECT id, account, display_name, password_hash, status, avatar_url, session_version, revision, created_at, updated_at FROM `user` WHERE id = #{id}")
    UserAccount findUserById(String id);

    @Select("SELECT id, account, display_name, password_hash, status, avatar_url, session_version, revision, created_at, updated_at FROM `user` WHERE id = #{id} FOR UPDATE")
    UserAccount findUserByIdForUpdate(String id);

    @Select("SELECT r.code FROM `role` r JOIN user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId} ORDER BY r.code")
    List<String> findRoles(String userId);

    @Select("SELECT DISTINCT p.code FROM permission p JOIN role_permission rp ON rp.permission_id = p.id JOIN user_role ur ON ur.role_id = rp.role_id WHERE ur.user_id = #{userId} ORDER BY p.code")
    List<String> findPermissions(String userId);

    @Insert("INSERT INTO refresh_session (id, family_id, user_id, parent_id, token_hash, status, expires_at, session_version, device_id, device_name, last_used_at) VALUES (#{id}, #{familyId}, #{userId}, #{parentId}, #{tokenHash}, 'ACTIVE', #{expiresAt}, #{sessionVersion}, #{deviceId}, #{deviceName}, #{now})")
    int insertRefreshSession(@Param("id") String id, @Param("familyId") String familyId,
            @Param("userId") String userId, @Param("parentId") String parentId,
            @Param("tokenHash") String tokenHash, @Param("expiresAt") Instant expiresAt,
            @Param("sessionVersion") long sessionVersion, @Param("deviceId") String deviceId,
            @Param("deviceName") String deviceName, @Param("now") Instant now);

    @Select("SELECT id, family_id, user_id, parent_id, token_hash, status, expires_at, session_version, device_id, device_name, created_at, last_used_at FROM refresh_session WHERE token_hash = #{tokenHash}")
    RefreshSession findRefreshSession(String tokenHash);

    @Update("UPDATE refresh_session SET status = 'ROTATED', rotated_at = CURRENT_TIMESTAMP(6) WHERE id = #{id} AND status = 'ACTIVE'")
    int rotateRefreshSession(String id);

    @Update("UPDATE refresh_session SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6) WHERE family_id = #{familyId} AND status <> 'REVOKED'")
    int revokeFamily(String familyId);

    @Update("UPDATE refresh_session SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6) WHERE token_hash = #{tokenHash} AND status = 'ACTIVE'")
    int revokeByTokenHash(String tokenHash);

    @Update("UPDATE refresh_session SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6) WHERE user_id = #{userId} AND status = 'ACTIVE'")
    int revokeAllUserSessions(String userId);

    @Select("SELECT id, family_id, user_id, parent_id, token_hash, status, expires_at, session_version, device_id, device_name, created_at, last_used_at FROM refresh_session WHERE user_id = #{userId} AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP(6) ORDER BY last_used_at DESC, id")
    List<RefreshSession> listActiveSessions(String userId);

    @Update("UPDATE refresh_session SET last_used_at = #{now} WHERE id = #{id}")
    int touchRefreshSession(@Param("id") String id, @Param("now") Instant now);

    @Update("UPDATE refresh_session SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6) WHERE user_id = #{userId} AND status = 'ACTIVE' AND id <> #{currentSessionId}")
    int revokeOtherUserSessions(@Param("userId") String userId, @Param("currentSessionId") String currentSessionId);

    @Update("UPDATE refresh_session SET session_version = #{sessionVersion}, last_used_at = #{now} WHERE id = #{sessionId} AND user_id = #{userId} AND status = 'ACTIVE'")
    int updateRefreshSessionVersion(@Param("sessionId") String sessionId, @Param("userId") String userId,
            @Param("sessionVersion") long sessionVersion, @Param("now") Instant now);

    @Insert("INSERT INTO login_audit (account, user_id, outcome, remote_address, trace_id, occurred_at) VALUES (#{account}, #{userId}, #{outcome}, #{remoteAddress}, #{traceId}, #{occurredAt})")
    int insertLoginAudit(@Param("account") String account, @Param("userId") String userId,
            @Param("outcome") String outcome, @Param("remoteAddress") String remoteAddress,
            @Param("traceId") String traceId, @Param("occurredAt") Instant occurredAt);

    @Select("SELECT COUNT(*) FROM login_audit WHERE account = #{account} AND remote_address = #{remoteAddress} AND outcome = 'BAD_CREDENTIALS' AND occurred_at >= #{since}")
    int countRecentFailures(@Param("account") String account, @Param("remoteAddress") String remoteAddress,
            @Param("since") Instant since);

    @Select("SELECT u.id, u.account, u.display_name, u.password_hash, u.status, u.avatar_url, u.session_version, u.revision, u.created_at, u.updated_at FROM `user` u JOIN user_identity i ON i.user_id = u.id WHERE i.identity_type = #{type} AND i.normalized_value = #{value}")
    UserAccount findUserByIdentity(@Param("type") String type, @Param("value") String value);

    @Insert("INSERT INTO `user` (id, account, display_name, password_hash, status, session_version, revision) VALUES (#{id}, #{account}, #{displayName}, #{passwordHash}, 'ACTIVE', 0, 0)")
    int insertUser(@Param("id") String id, @Param("account") String account,
            @Param("displayName") String displayName, @Param("passwordHash") String passwordHash);

    @Insert("INSERT INTO user_identity (id, user_id, identity_type, normalized_value, verified_at, created_at) VALUES (#{id}, #{userId}, #{type}, #{value}, #{now}, #{now})")
    int insertIdentity(@Param("id") String id, @Param("userId") String userId,
            @Param("type") String type, @Param("value") String value, @Param("now") Instant now);

    @Insert("INSERT INTO protocol_agreement (id, user_id, document_type, document_version, agreed_at, remote_address) VALUES (#{id}, #{userId}, #{type}, #{version}, #{now}, #{remoteAddress})")
    int insertAgreement(@Param("id") String id, @Param("userId") String userId,
            @Param("type") String type, @Param("version") String version,
            @Param("now") Instant now, @Param("remoteAddress") String remoteAddress);

    @Insert("INSERT INTO registration_request (client_request_id, user_id, account, identity_type, identity_digest, request_fingerprint, created_at) VALUES (#{requestId}, #{userId}, #{account}, #{identityType}, #{identityDigest}, #{fingerprint}, #{now})")
    int insertRegistrationRequest(@Param("requestId") String requestId, @Param("userId") String userId,
            @Param("account") String account, @Param("identityType") String identityType,
            @Param("identityDigest") String identityDigest, @Param("fingerprint") String fingerprint,
            @Param("now") Instant now);

    @Select("SELECT user_id FROM registration_request WHERE client_request_id = #{requestId} AND identity_type = #{identityType} AND identity_digest = #{identityDigest} AND request_fingerprint = #{fingerprint}")
    String findRegistrationReplay(@Param("requestId") String requestId,
            @Param("identityType") String identityType, @Param("identityDigest") String identityDigest,
            @Param("fingerprint") String fingerprint);

    @Update("UPDATE `user` SET password_hash = #{passwordHash}, session_version = session_version + 1, revision = revision + 1 WHERE id = #{userId} AND status = 'ACTIVE'")
    int changePasswordAndBumpSession(@Param("userId") String userId, @Param("passwordHash") String passwordHash);

    @Update("UPDATE `user` SET session_version = session_version + 1, revision = revision + 1 WHERE id = #{userId} AND status = 'ACTIVE'")
    int bumpSessionVersion(String userId);

    @Update("UPDATE `user` SET status = 'DISABLED', session_version = session_version + 1, revision = revision + 1 WHERE id = #{userId} AND revision = #{expectedRevision} AND status <> 'DISABLED'")
    int disableUser(@Param("userId") String userId, @Param("expectedRevision") long expectedRevision);

    @Update("UPDATE `user` SET revision = revision + 1 WHERE id = #{userId} AND revision = #{expectedRevision}")
    int touchUserRevision(@Param("userId") String userId, @Param("expectedRevision") long expectedRevision);

    @Update("UPDATE `user` SET session_version = session_version + 1, revision = revision + 1 WHERE id = #{userId} AND revision = #{expectedRevision} AND status = 'ACTIVE'")
    int forceRevokeSessions(@Param("userId") String userId, @Param("expectedRevision") long expectedRevision);

    @Update("UPDATE `user` SET session_version = session_version + 1, revision = revision + 1 WHERE id = #{userId} AND revision = #{expectedRevision} AND status = 'ACTIVE'")
    int bumpIdentityRevisionAndSession(@Param("userId") String userId,
            @Param("expectedRevision") long expectedRevision);

    @Insert("INSERT INTO account_security_audit (id, user_id, actor_id, action, reason, remote_address, trace_id, occurred_at) VALUES (#{id}, #{userId}, #{actorId}, #{action}, #{reason}, #{remoteAddress}, #{traceId}, #{now})")
    int insertSecurityAudit(@Param("id") String id, @Param("userId") String userId,
            @Param("actorId") String actorId, @Param("action") String action,
            @Param("reason") String reason, @Param("remoteAddress") String remoteAddress,
            @Param("traceId") String traceId, @Param("now") Instant now);

    @Select("SELECT id, user_id, qualification_media_id, statement, status, rejection_reason, revision, submitted_at, updated_at FROM teacher_application WHERE user_id = #{userId}")
    TeacherApplication findTeacherApplicationByUser(String userId);

    @Select("SELECT ta.id, ta.user_id, ta.qualification_media_id, ta.statement, ta.status, ta.rejection_reason, ta.revision, ta.submitted_at, ta.updated_at FROM teacher_application ta JOIN teacher_application_command c ON c.application_id = ta.id WHERE c.client_request_id = #{requestId} AND c.request_fingerprint = #{fingerprint}")
    TeacherApplication findTeacherApplicationReplay(@Param("requestId") String requestId,
            @Param("fingerprint") String fingerprint);

    @Insert("INSERT INTO teacher_application (id, user_id, qualification_media_id, statement, status, rejection_reason, revision, submitted_at, updated_at) VALUES (#{id}, #{userId}, #{mediaId}, #{statement}, 'SUBMITTED', NULL, 0, #{now}, #{now})")
    int insertTeacherApplication(@Param("id") String id, @Param("userId") String userId,
            @Param("mediaId") String mediaId, @Param("statement") String statement, @Param("now") Instant now);

    @Update("UPDATE teacher_application SET qualification_media_id = #{mediaId}, statement = #{statement}, status = 'SUBMITTED', rejection_reason = NULL, revision = revision + 1, submitted_at = #{now}, updated_at = #{now} WHERE user_id = #{userId} AND revision = #{expectedRevision} AND status = 'REJECTED'")
    int resubmitTeacherApplication(@Param("userId") String userId, @Param("mediaId") String mediaId,
            @Param("statement") String statement, @Param("expectedRevision") long expectedRevision,
            @Param("now") Instant now);

    @Insert("INSERT INTO teacher_application_command (client_request_id, application_id, request_fingerprint, created_at) VALUES (#{requestId}, #{applicationId}, #{fingerprint}, #{now})")
    int insertTeacherApplicationCommand(@Param("requestId") String requestId,
            @Param("applicationId") String applicationId, @Param("fingerprint") String fingerprint,
            @Param("now") Instant now);

    @Select("<script>SELECT id, account, display_name, password_hash, status, avatar_url, session_version, revision, created_at, updated_at FROM `user` WHERE 1=1 <if test='keyword != null'>AND (account LIKE CONCAT('%', #{keyword}, '%') OR display_name LIKE CONCAT('%', #{keyword}, '%'))</if> <if test='status != null'>AND status = #{status}</if> ORDER BY created_at DESC, id LIMIT #{limit} OFFSET #{offset}</script>")
    List<UserAccount> listUsers(@Param("keyword") String keyword, @Param("status") String status,
            @Param("offset") long offset, @Param("limit") int limit);

    @Select("<script>SELECT COUNT(*) FROM `user` WHERE 1=1 <if test='keyword != null'>AND (account LIKE CONCAT('%', #{keyword}, '%') OR display_name LIKE CONCAT('%', #{keyword}, '%'))</if> <if test='status != null'>AND status = #{status}</if></script>")
    long countUsers(@Param("keyword") String keyword, @Param("status") String status);

    @Select("SELECT CONCAT(identity_type, ':', normalized_value) FROM user_identity WHERE user_id = #{userId} ORDER BY identity_type")
    List<String> findIdentities(String userId);

    @Select("SELECT id, user_id, identity_type, normalized_value, verified_at, created_at FROM user_identity WHERE user_id = #{userId} ORDER BY identity_type")
    List<UserIdentity> listUserIdentities(String userId);

    @Select("SELECT id, user_id, identity_type, normalized_value, verified_at, created_at FROM user_identity WHERE user_id = #{userId} AND identity_type = #{type}")
    UserIdentity findUserIdentityByType(@Param("userId") String userId, @Param("type") String type);

    @Update("UPDATE user_identity SET normalized_value = #{value}, verified_at = #{now} WHERE user_id = #{userId} AND identity_type = #{type}")
    int replaceIdentity(@Param("userId") String userId, @Param("type") String type,
            @Param("value") String value, @Param("now") Instant now);

    @org.apache.ibatis.annotations.Delete("DELETE FROM user_identity WHERE user_id = #{userId} AND identity_type = #{type}")
    int deleteIdentity(@Param("userId") String userId, @Param("type") String type);

    @Select("SELECT COUNT(*) FROM user_identity WHERE user_id = #{userId}")
    int countUserIdentities(String userId);

    @Select("SELECT id, document_type, document_version, body, effective_at, is_current AS current, requires_reconsent, created_at FROM protocol_document WHERE is_current = TRUE AND effective_at <= CURRENT_TIMESTAMP(6) ORDER BY document_type")
    List<ProtocolDocument> listCurrentProtocolDocuments();

    @Select("SELECT id, document_type, document_version, body, effective_at, is_current AS current, requires_reconsent, created_at FROM protocol_document WHERE document_type = #{type} AND is_current = TRUE AND effective_at <= CURRENT_TIMESTAMP(6) ORDER BY effective_at DESC LIMIT 1")
    ProtocolDocument findCurrentProtocolDocument(String type);

    @Update("UPDATE protocol_document SET is_current = FALSE WHERE document_type = #{type} AND is_current = TRUE")
    int clearCurrentProtocolDocument(String type);

    @Insert("INSERT IGNORE INTO protocol_document (id, document_type, document_version, body, effective_at, is_current, requires_reconsent, created_at) VALUES (#{id}, #{type}, #{version}, #{body}, #{effectiveAt}, FALSE, #{requiresReconsent}, #{now})")
    int insertProtocolDocumentIfAbsent(@Param("id") String id, @Param("type") String type,
            @Param("version") String version, @Param("body") String body,
            @Param("effectiveAt") Instant effectiveAt, @Param("requiresReconsent") boolean requiresReconsent,
            @Param("now") Instant now);

    @Update("UPDATE protocol_document SET is_current = TRUE WHERE id = #{id}")
    int markProtocolDocumentCurrent(String id);

    @Select("SELECT resulting_revision FROM identity_change_command WHERE client_request_id = #{requestId} AND user_id = #{userId} AND command_type = #{type} AND request_fingerprint = #{fingerprint}")
    Long findIdentityCommandReplay(@Param("requestId") String requestId, @Param("userId") String userId,
            @Param("type") String type, @Param("fingerprint") String fingerprint);

    @Insert("INSERT INTO identity_change_command (client_request_id, user_id, command_type, request_fingerprint, resulting_revision, created_at) VALUES (#{requestId}, #{userId}, #{type}, #{fingerprint}, #{revision}, #{now})")
    int insertIdentityCommand(@Param("requestId") String requestId, @Param("userId") String userId,
            @Param("type") String type, @Param("fingerprint") String fingerprint,
            @Param("revision") long revision, @Param("now") Instant now);

    @Select("SELECT resulting_revision FROM admin_account_command WHERE actor_id = #{actorId} AND client_request_id = #{requestId} AND target_user_id = #{targetId} AND command_type = #{type} AND request_fingerprint = #{fingerprint}")
    Long findAdminCommandReplay(@Param("actorId") String actorId, @Param("requestId") String requestId,
            @Param("targetId") String targetId, @Param("type") String type, @Param("fingerprint") String fingerprint);

    @Insert("INSERT INTO admin_account_command (actor_id, client_request_id, target_user_id, command_type, request_fingerprint, resulting_revision, created_at) VALUES (#{actorId}, #{requestId}, #{targetId}, #{type}, #{fingerprint}, #{revision}, #{now})")
    int insertAdminCommand(@Param("actorId") String actorId, @Param("requestId") String requestId,
            @Param("targetId") String targetId, @Param("type") String type,
            @Param("fingerprint") String fingerprint, @Param("revision") long revision, @Param("now") Instant now);

    @Insert("INSERT INTO `role` (code, name) VALUES (#{code}, #{name}) ON DUPLICATE KEY UPDATE name = VALUES(name)")
    int upsertRole(@Param("code") String code, @Param("name") String name);

    @Insert("INSERT INTO permission (code, name) VALUES (#{code}, #{name}) ON DUPLICATE KEY UPDATE name = VALUES(name)")
    int upsertPermission(@Param("code") String code, @Param("name") String name);

    @Insert("INSERT INTO `user` (id, account, display_name, password_hash, status) VALUES (#{id}, #{account}, #{displayName}, #{passwordHash}, 'ACTIVE') ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), password_hash = VALUES(password_hash), status = 'ACTIVE'")
    int upsertSeedUser(@Param("id") String id, @Param("account") String account,
            @Param("displayName") String displayName, @Param("passwordHash") String passwordHash);

    @Insert("INSERT INTO user_identity (id, user_id, identity_type, normalized_value, verified_at, created_at) "
            + "VALUES (#{id}, #{userId}, #{type}, #{value}, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), verified_at = VALUES(verified_at)")
    int upsertSeedIdentity(@Param("id") String id, @Param("userId") String userId,
            @Param("type") String type, @Param("value") String value, @Param("now") Instant now);

    @Insert("INSERT INTO protocol_agreement (id, user_id, document_type, document_version, agreed_at, remote_address) "
            + "VALUES (#{id}, #{userId}, #{type}, #{version}, #{now}, '127.0.0.1') "
            + "ON DUPLICATE KEY UPDATE agreed_at = VALUES(agreed_at)")
    int upsertSeedAgreement(@Param("id") String id, @Param("userId") String userId,
            @Param("type") String type, @Param("version") String version, @Param("now") Instant now);

    @Insert("INSERT IGNORE INTO user_role (user_id, role_id) SELECT #{userId}, id FROM `role` WHERE code = #{roleCode}")
    int assignRole(@Param("userId") String userId, @Param("roleCode") String roleCode);

    @Insert("INSERT IGNORE INTO role_permission (role_id, permission_id) SELECT r.id, p.id FROM `role` r CROSS JOIN permission p WHERE r.code = #{roleCode} AND p.code = #{permissionCode}")
    int assignPermission(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode);
}
