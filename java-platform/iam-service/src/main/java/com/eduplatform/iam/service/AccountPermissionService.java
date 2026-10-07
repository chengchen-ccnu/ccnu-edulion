package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.web.AccountPermissionDtos;
import com.eduplatform.iam.web.AccountPermissionDtos.Action;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 账号权限决定与Course协调；只写IAM本域，预览/确认、审计、会话撤销和幂等均受本地事务保护。 */
@Service
public class AccountPermissionService {
    private final JdbcTemplate jdbc;
    private final IamMapper users;
    private final AdminAuthority admins;
    private final IdentitySecurityStore security;
    private final AccountCourseClient course;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final com.eduplatform.iam.persistence.TeacherReviewRepository events;

    public AccountPermissionService(JdbcTemplate jdbc, IamMapper users, AdminAuthority admins,
            IdentitySecurityStore security, AccountCourseClient course, ObjectMapper json,
            PlatformTransactionManager transactions, com.eduplatform.iam.persistence.TeacherReviewRepository events) {
        this.jdbc = jdbc; this.users = users; this.admins = admins; this.security = security;
        this.course = course; this.json = json;
        this.events=events;
        this.tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 输入当前管理员、目标和动作，核对合法状态及历史批准；读取真实影响，保存五分钟确认快照。 */
    public AccountPermissionDtos.Preview preview(String actor, String userId, Action action) {
        return tx.execute(status -> {
            admins.lock(); admins.require(actor);
            UserAccount user = locked(userId);
            State state = state(userId);
            Boolean paused = validate(actor, user, state, action);
            AccountCourseClient.Impact impact = impact(userId, state, paused);
            String id = UUID.randomUUID().toString();
            Instant expires = Instant.now().plus(Duration.ofMinutes(5));
            jdbc.update("INSERT INTO account_permission_preview(id,actor_id,user_id,action,expected_revision,course_ids_json,affected_students,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, actor, userId, action.name(), user.getRevision(), json.writeValueAsString(impact.courseIds()),
                    impact.activeStudents(), Timestamp.from(expires));
            return new AccountPermissionDtos.Preview(UUID.fromString(id), UUID.fromString(userId), action,
                    user.getRevision(), impact.courseIds().size(), impact.activeStudents(), expires);
        });
    }

    /** 明确确认同一预览及revision；决定、会话撤销、审计和持久化协调同事务。返回PENDING并不表示Course已应用。 */
    public AccountPermissionDtos.Command change(String actor, String userId, AccountPermissionDtos.CommandRequest request,
            String remote, String trace) {
        if (!Boolean.TRUE.equals(request.confirmed()) || request.reason() == null
                || request.reason().trim().length() < 10 || request.reason().trim().length() > 500) invalid();
        String canonical = json.writeValueAsString(new java.util.TreeMap<>(Map.of("actor", actor, "userId", userId, "previewId", request.previewId(),
                "revision", request.expectedRevision(), "action", request.action(), "reason", request.reason().trim(), "confirmed", true)));
        try {
            return tx.execute(status -> {
                admins.lock(); admins.require(actor);
                UserAccount user = locked(userId);
                Row existing = row(request.clientRequestId().toString(), false);
                if (existing != null) {
                    if (!canonical.equals(existing.requestJson())) conflict("IAM.REQUEST_REUSED", "请求标识已被另一内容使用");
                    return response(existing, true);
                }
                State state = state(userId);
                if (user.getRevision() != request.expectedRevision()) revisionConflict();
                Boolean paused = validate(actor, user, state, request.action());
                var previews = jdbc.query("SELECT * FROM account_permission_preview WHERE id=?",
                        (rs,n) -> new PreviewRow(rs.getString("actor_id"), rs.getString("user_id"), rs.getString("action"),
                                rs.getLong("expected_revision"), rs.getString("course_ids_json"), rs.getLong("affected_students"),
                                rs.getTimestamp("expires_at").toInstant()), request.previewId().toString());
                if (previews.isEmpty()) conflict("IAM.PREVIEW_EXPIRED", "预览不存在或已到期，请重新预览");
                PreviewRow preview = previews.get(0);
                if (!actor.equals(preview.actor()) || !userId.equals(preview.userId()) || !request.action().name().equals(preview.action())
                        || preview.revision() != user.getRevision() || !preview.expires().isAfter(Instant.now()))
                    conflict("IAM.PREVIEW_EXPIRED", "预览状态已变化或到期，请重新预览");
                AccountCourseClient.Impact impact = impact(userId, state, paused);
                if (!preview.courseJson().equals(json.writeValueAsString(impact.courseIds())) || preview.students() != impact.activeStudents())
                    conflict("IAM.IMPACT_CHANGED", "受影响课程或学生已变化，请重新预览并确认");
                boolean admin = users.findRoles(userId).contains("ADMIN");
                invalidate(userId);
                if (request.action() == Action.REVOKE_TEACHING || request.action() == Action.BAN)
                    jdbc.update("DELETE FROM user_role WHERE user_id=? AND role_id IN (SELECT id FROM `role` WHERE code='TEACHER')", userId);
                if (request.action() == Action.UNBAN && !admin) {
                    jdbc.update("DELETE FROM user_role WHERE user_id=?", userId);
                    users.assignRole(userId, "STUDENT");
                }
                String accountStatus = request.action() == Action.BAN ? "DISABLED" : "ACTIVE";
                long accessRevision = state.revision() + (paused == null ? 0 : 1);
                String suspension = paused == null ? state.suspensionId()
                        : paused ? UUID.randomUUID().toString() : state.suspensionId();
                jdbc.update("UPDATE `user` SET status=?,revision=revision+1,session_version=session_version+1,access_revision=?,suspension_id=?,course_paused=? WHERE id=?",
                        accountStatus, accessRevision, suspension, paused == null ? state.paused() : paused, userId);
                users.revokeAllUserSessions(userId);
                String id = request.clientRequestId().toString();
                Instant now = Instant.now();
                jdbc.update("INSERT INTO account_permission_command(id,actor_id,user_id,request_json,action,decided_revision,access_revision,suspension_id,desired_paused,coordination_status,affected_courses,next_attempt_at,trace_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, actor, userId, canonical, request.action().name(), user.getRevision()+1, accessRevision,
                        paused == null ? null : suspension, paused, paused == null ? "NOT_REQUIRED" : "PENDING",
                        impact.courseIds().size(), Timestamp.from(now), safe(trace), Timestamp.from(now));
                users.insertSecurityAudit(UUID.randomUUID().toString(), userId, actor, "ACCOUNT_" + request.action().name(),
                        request.reason().trim(), safe(remote), safe(trace), now);
                Row decided=row(id,false);
                emit(decided,now);
                return response(decided, false);
            });
        } catch (DuplicateKeyException duplicate) {
            conflict("IAM.REQUEST_REUSED", "请求标识已被另一内容使用"); return null;
        }
    }

    /** 当前有效管理员读取命令事实；决定revision稳定，联动状态和次数返回当前事实，不泄露内部凭据或审核理由。 */
    public AccountPermissionDtos.Command get(String actor, String id) {
        admins.require(actor);
        return response(required(row(id, false)), false);
    }

    /**
     * 当前有效管理员凭目标账号ID找回最近决定，允许跨操作者接手；未知账号404，无命令返回空。
     * 按决定revision及ID降序确定次序，返回既有安全投影；只读本域，不加锁写入、不消耗预算或调用Course。
     * 返回的是读取时事实，后台协调器仍可随后推进状态。
     * @param actor 当前操作者账号ID，必须具备有效独立管理员身份
     * @param userId 目标账号ID，不限定命令原操作者
     * @return 最近命令；目标存在但尚无命令时为空
     */
    public java.util.Optional<AccountPermissionDtos.Command> latest(String actor, String userId) {
        admins.require(actor);
        if (users.findUserById(userId) == null)
            throw new AuthException(HttpStatus.NOT_FOUND, "IAM.NOT_FOUND", "账号不存在");
        return jdbc.query("SELECT * FROM account_permission_command WHERE user_id=? ORDER BY decided_revision DESC,id DESC LIMIT 1",
                (rs, n) -> map(rs), userId).stream().findFirst().map(value -> response(value, false));
    }

    /** 对FAILED原命令增加一次有限预算；锁下复核管理员、次数和幂等请求，最多六次，不新建暂停周期。 */
    public AccountPermissionDtos.Command retry(String actor, String id, AccountPermissionDtos.RetryRequest request) {
        try {
            return tx.execute(status -> {
                admins.lock(); admins.require(actor);
                Row command = required(row(id, true));
                var replays = jdbc.query("SELECT actor_id,command_id,expected_attempts FROM account_permission_retry WHERE id=?",
                        (rs,n) -> List.of(rs.getString(1), rs.getString(2), Integer.toString(rs.getInt(3))), request.clientRequestId().toString());
                if (!replays.isEmpty()) {
                    if (!replays.get(0).equals(List.of(actor,id,Integer.toString(request.expectedAttempts()))))
                        conflict("IAM.REQUEST_REUSED", "补偿请求标识已被另一内容使用");
                    return response(command, true);
                }
                if (!"FAILED".equals(command.status()) || command.attempts()!=request.expectedAttempts() || command.attempts()>=6)
                    conflict("IAM.COORDINATION_RETRY_CONFLICT", "联动状态或补偿预算已变化，请刷新");
                jdbc.update("INSERT INTO account_permission_retry(id,actor_id,command_id,expected_attempts) VALUES(?,?,?,?)",
                        request.clientRequestId().toString(), actor, id, request.expectedAttempts());
                jdbc.update("UPDATE account_permission_command SET coordination_status='PENDING',retry_ceiling=?,next_attempt_at=?,last_error_code=NULL WHERE id=?",
                        command.attempts()+1, Timestamp.from(Instant.now()), id);
                users.insertSecurityAudit(UUID.randomUUID().toString(), command.userId(), actor, "ACCOUNT_COORDINATION_RETRY",
                        "对原权限命令执行一次有限补偿 " + id, "internal", command.trace(), Instant.now());
                return response(row(id, false), false);
            });
        } catch (DuplicateKeyException duplicate) { conflict("IAM.REQUEST_REUSED", "补偿请求标识已被另一内容使用"); return null; }
    }

    /** Worker读取最多20个到期持久命令；进程退出也保留预算/状态，关闭worker不会改成成功。 */
    public List<String> due() {
        return jdbc.query("SELECT id FROM account_permission_command WHERE coordination_status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 20",
                (rs,n) -> rs.getString(1), Timestamp.from(Instant.now()));
    }

    /** 先提交一次尝试和30秒租期，再调用Course；超时/崩溃保留同ID补偿。确认必须精确匹配水位及周期，恢复授能另在本域事务完成。 */
    public void coordinate(String id) {
        Row claimed = tx.execute(status -> {
            Row current = row(id, true);
            if (current == null || !"PENDING".equals(current.status()) || current.nextAttempt().isAfter(Instant.now())) return null;
            if (current.attempts() >= current.ceiling()) {
                jdbc.update("UPDATE account_permission_command SET coordination_status='FAILED',last_error_code='IAM.COORDINATION_ATTEMPTS_EXHAUSTED' WHERE id=?", id);
                return null;
            }
            jdbc.update("UPDATE account_permission_command SET attempts=attempts+1,next_attempt_at=? WHERE id=?",
                    Timestamp.from(Instant.now().plusSeconds(30)), id);
            return row(id, false);
        });
        if (claimed == null) return;
        try {
            AccountCourseClient.Applied applied = course.apply(claimed.userId(), claimed.suspensionId(), claimed.accessRevision(),
                    claimed.paused(), claimed.trace());
            tx.executeWithoutResult(status -> {
                admins.lock();
                UserAccount user = locked(claimed.userId());
                Row current = required(row(id, true));
                if (!"PENDING".equals(current.status()) || current.attempts()!=claimed.attempts()) return;
                State state = state(user.getId());
                if (state.revision()!=claimed.accessRevision() || !Objects.equals(state.suspensionId(), claimed.suspensionId()))
                    throw new IllegalStateException("账号课程周期已变化");
                if (current.action()==Action.RESTORE_TEACHING) {
                    if (!"ACTIVE".equals(user.getStatus()) || !users.findRoles(user.getId()).equals(List.of("STUDENT")) || !approved(user.getId()))
                        throw new IllegalStateException("恢复批准事实或账号状态已变化");
                    invalidate(user.getId());
                    users.assignRole(user.getId(),"TEACHER");
                    users.bumpSessionVersion(user.getId());
                    users.revokeAllUserSessions(user.getId());
                    users.insertSecurityAudit(UUID.randomUUID().toString(), user.getId(), current.actor(), "ACCOUNT_TEACHING_RESTORED",
                            "原暂停周期联动确认后授回教学能力 " + id, "internal", current.trace(), Instant.now());
                }
                jdbc.update("UPDATE account_permission_command SET coordination_status='APPLIED',affected_courses=?,last_error_code=NULL WHERE id=?",
                        applied.affectedCourses(),id);
                emit(row(id,false),Instant.now());
            });
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(status -> {
                Row current = row(id,true);
                if (current == null || !"PENDING".equals(current.status()) || current.attempts()!=claimed.attempts()) return;
                jdbc.update("UPDATE account_permission_command SET coordination_status=?,last_error_code='IAM.COORDINATION_UNCONFIRMED',next_attempt_at=? WHERE id=?",
                        current.attempts()>=current.ceiling() ? "FAILED" : "PENDING",
                        Timestamp.from(Instant.now().plusSeconds(Math.min(30, 2L*current.attempts()))),id);
            });
        }
    }

    /** 核对独立角色、动作前置和最后管理员；存在未完成周期时禁止覆盖，要求先有限补偿，不伪造历史批准。 */
    private Boolean validate(String actor, UserAccount user, State state, Action action) {
        List<String> roles = users.findRoles(user.getId());
        boolean admin = roles.equals(List.of("ADMIN"));
        if (!admin && !roles.equals(List.of("STUDENT")) && !roles.equals(List.of("STUDENT","TEACHER")))
            conflict("IAM.ILLEGAL_ACCOUNT_CAPABILITIES", "账号能力组合不合法，请联系管理员");
        long unfinished = jdbc.queryForObject("SELECT COUNT(*) FROM account_permission_command WHERE user_id=? AND coordination_status IN ('PENDING','FAILED')", Long.class,user.getId());
        if (unfinished>0) conflict("IAM.COORDINATION_PENDING", "该账号已有未完成课程联动，请先完成原命令");
        boolean active = "ACTIVE".equals(user.getStatus());
        switch(action) {
            case REVOKE_TEACHING:
                if (!active || admin || !roles.contains("TEACHER")) revisionConflict();
                return true;
            case RESTORE_TEACHING:
                if (!active || admin || roles.contains("TEACHER") || !approved(user.getId()) || state.suspensionId()==null || !state.paused())
                    conflict("IAM.TEACHING_RESTORE_FORBIDDEN", "恢复需要真实历史批准及对应撤权周期；首次授予必须审核");
                return false;
            case BAN:
                if (!active) revisionConflict();
                if (actor.equals(user.getId())) conflict("IAM.SELF_DISABLE_FORBIDDEN", "不能封禁自己的管理员账号");
                if (admin && admins.activeCount()<=1) conflict("IAM.LAST_ADMIN_REQUIRED", "必须保留至少一名可用管理员");
                return roles.contains("TEACHER") ? true : null;
            case UNBAN:
                if (!"DISABLED".equals(user.getStatus())) revisionConflict();
                return null;
            default: throw new IllegalStateException("Unknown permission action");
        }
    }

    private AccountCourseClient.Impact impact(String userId, State state, Boolean paused) {
        return paused == null ? new AccountCourseClient.Impact(List.of(),0)
                : course.impact(userId,paused,paused ? null : state.suspensionId());
    }

    /** 最小权限状态事件与IAM决定/应用同事务，复用已有有确认/有限重试Outbox；不发送审核原因或联系方式。 */
    private void emit(Row row,Instant now) {
        Map<String,Object> payload=new java.util.LinkedHashMap<>();
        payload.put("commandId",row.id());payload.put("userId",row.userId());payload.put("action",row.action().name());
        payload.put("decidedRevision",row.decidedRevision());payload.put("accessRevision",row.accessRevision());
        payload.put("coordinationStatus",row.status());
        if(row.suspensionId()!=null)payload.put("suspensionId",row.suspensionId());
        events.outbox("AccountPermissionChanged","edu.iam.events.v1","iam.account-permission.changed.v1",row.userId(),payload,row.trace(),now);
    }

    /** 不可变审核决定才是恢复资格；既有seed教师角色或申请状态本身不构成批准事实。 */
    private boolean approved(String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision d JOIN teacher_application a ON a.id=d.application_id WHERE a.user_id=? AND d.decision='APPROVED'",Long.class,userId)>0;
    }
    private UserAccount locked(String id) {
        UserAccount user = users.findUserByIdForUpdate(id);
        if (user==null) throw new AuthException(HttpStatus.NOT_FOUND,"IAM.NOT_FOUND","账号不存在");
        return user;
    }
    private State state(String id) {
        return jdbc.query("SELECT access_revision,suspension_id,course_paused FROM `user` WHERE id=?",
                (rs,n) -> new State(rs.getLong(1),rs.getString(2),rs.getBoolean(3)),id).get(0);
    }
    private Row row(String id, boolean lock) {
        return jdbc.query("SELECT * FROM account_permission_command WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs,n) -> map(rs),id).stream().findFirst().orElse(null);
    }
    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getString("id"),rs.getString("actor_id"),rs.getString("user_id"),rs.getString("request_json"),
                Action.valueOf(rs.getString("action")),rs.getLong("decided_revision"),rs.getLong("access_revision"),
                rs.getString("suspension_id"),rs.getObject("desired_paused")==null ? null : rs.getBoolean("desired_paused"),
                rs.getString("coordination_status"),rs.getInt("attempts"),rs.getInt("retry_ceiling"),rs.getInt("affected_courses"),
                rs.getString("last_error_code"),rs.getTimestamp("next_attempt_at").toInstant(),rs.getString("trace_id"));
    }
    private static Row required(Row value) {
        if (value==null) throw new AuthException(HttpStatus.NOT_FOUND,"IAM.NOT_FOUND","权限命令不存在");
        return value;
    }
    private static AccountPermissionDtos.Command response(Row row, boolean replay) {
        return new AccountPermissionDtos.Command(UUID.fromString(row.id()),UUID.fromString(row.userId()),row.action(),
                row.decidedRevision(),row.accessRevision(),row.suspensionId()==null ? null : UUID.fromString(row.suspensionId()),
                row.status(),row.attempts(),6,row.courses(),row.error(),replay);
    }
    /** 在持有user锁时使缓存失效；Redis失败整体回滚，最多造成安全拒绝，不能接受旧JWT。 */
    private void invalidate(String id) {
        try { security.invalidateSessionVersion(id); }
        catch(RuntimeException failure) { throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                "IAM.SECURITY_STATE_UNAVAILABLE","身份安全服务暂时不可用，请稍后重试",2L); }
    }
    private static void conflict(String code,String message) { throw new AuthException(HttpStatus.CONFLICT,code,message); }
    private static void revisionConflict() { conflict("IAM.REVISION_CONFLICT","账号状态已变化，请刷新后重试"); }
    private static void invalid() { throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT,"IAM.VALIDATION_FAILED","请明确确认并填写10至500字原因"); }
    private static String safe(String value) { return value==null || value.isBlank() ? "unknown" : value; }
    private record State(long revision,String suspensionId,boolean paused) {}
    private record PreviewRow(String actor,String userId,String action,long revision,String courseJson,long students,Instant expires) {}
    private record Row(String id,String actor,String userId,String requestJson,Action action,long decidedRevision,long accessRevision,
            String suspensionId,Boolean paused,String status,int attempts,int ceiling,int courses,String error,Instant nextAttempt,String trace) {}
}
