package com.eduplatform.iam.service;

import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.SecurityDigests;
import com.eduplatform.iam.verification.AdminInvitationDelivery;
import com.eduplatform.iam.web.AdminInvitationDtos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 独立管理员邮箱邀请、受控首位初始化和一次性接受；SMTP位于事务外，数据库只保存HMAC，所有结果有审计。 */
@Service
public class AdminInvitationService {
    private final JdbcTemplate jdbc;
    private final IamMapper users;
    private final AdminAuthority admins;
    private final SecurityDigests digests;
    private final PasswordEncoder passwords;
    private final AdminInvitationDelivery delivery;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final SecureRandom random=new SecureRandom();

    public AdminInvitationService(JdbcTemplate jdbc,IamMapper users,AdminAuthority admins,SecurityDigests digests,
            PasswordEncoder passwords,AdminInvitationDelivery delivery,ObjectMapper json,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.users=users;this.admins=admins;this.digests=digests;this.passwords=passwords;
        this.delivery=delivery;this.json=json;this.tx=new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 有效管理员向未绑定邮箱创建24小时邀请；相同请求精确重放不再发信，邮件失败保留FAILED事实。 */
    public AdminInvitationDtos.Invitation create(String actor,AdminInvitationDtos.Create request,String remote,String trace) {
        String email=IdentityNormalizer.normalize("EMAIL",request.email());
        reason(request.reason());
        String canonical=json.writeValueAsString(new java.util.TreeMap<>(Map.of("action","CREATE","actor",actor,"email",email,"reason",request.reason().trim())));
        Prepared prepared=prepare(() -> {
            admins.lock();admins.require(actor);
            Prepared replay=replay(request.clientRequestId(),canonical);
            if(replay!=null)return replay;
            unbound(email);
            String id=UUID.randomUUID().toString();
            Pending pending=insert(id,email,actor,false);
            saveCommand(request.clientRequestId(),actor,id,canonical);
            audit(actor,null,"ADMIN_INVITATION_CREATED",request.reason().trim(),remote,trace);
            return new Prepared(id,pending,false);
        });
        return deliver(prepared);
    }

    /** 当前管理员读取脱敏发送/接受状态；到期由服务器时间计算，不暴露秘密或完整邮箱。 */
    public AdminInvitationDtos.Invitation get(String actor,String id) {
        admins.require(actor);
        return projection(required(find(id,false)),false);
    }

    /** 重发未接受邀请，60秒冷却最多六次；事务先作废旧秘密，SMTP未知结果仍不能接受PENDING邀请。 */
    public AdminInvitationDtos.Invitation resend(String actor,String id,AdminInvitationDtos.Resend request,String remote,String trace) {
        reason(request.reason());
        String canonical=json.writeValueAsString(new java.util.TreeMap<>(Map.of("action","RESEND","actor",actor,"id",id,
                "revision",request.expectedRevision(),"reason",request.reason().trim())));
        Prepared prepared=prepare(() -> {
            admins.lock();admins.require(actor);
            Prepared replay=replay(request.clientRequestId(),canonical);
            if(replay!=null)return replay;
            Invitation invitation=required(find(id,true));
            if(invitation.revision()!=request.expectedRevision())conflict("IAM.REVISION_CONFLICT","邀请状态已变化，请刷新");
            unbound(invitation.email());
            Pending pending=rotate(invitation);
            saveCommand(request.clientRequestId(),actor,id,canonical);
            audit(actor,null,"ADMIN_INVITATION_RESENT",request.reason().trim(),remote,trace);
            return new Prepared(id,pending,false);
        });
        return deliver(prepared);
    }

    /** 仅受控CLI调用，无HTTP路由；只有从未存在ADMIN时允许首位邀请，重复启动不会自动造账号或无限发信。 */
    public AdminInvitationDtos.Invitation bootstrap(UUID requestId,String rawEmail) {
        String email=IdentityNormalizer.normalize("EMAIL",rawEmail);
        String canonical=json.writeValueAsString(new java.util.TreeMap<>(Map.of("action","BOOTSTRAP","email",email)));
        Prepared prepared=prepare(() -> {
            admins.lock();requireNoAdministrator();
            Prepared replay=replay(requestId,canonical);
            if(replay!=null)return replay;
            unbound(email);
            List<Invitation> pending=jdbc.query("SELECT * FROM administrator_invitation WHERE bootstrap=TRUE AND accepted_at IS NULL ORDER BY created_at,id FOR UPDATE",
                    (rs,n)->map(rs));
            String id;Pending mail;
            if(pending.isEmpty()) { id=UUID.randomUUID().toString();mail=insert(id,email,null,true); }
            else {
                Invitation original=pending.get(0);
                if(!original.email().equals(email))conflict("IAM.BOOTSTRAP_ALREADY_STARTED","首位管理员初始化已经开始，不可静默更换邮箱");
                id=original.id();mail=rotate(original);
            }
            saveCommand(requestId,null,id,canonical);
            audit(null,null,"ADMIN_BOOTSTRAP_INVITED","受控首位管理员邮箱邀请 "+id,"controlled-cli","controlled-bootstrap");
            return new Prepared(id,mail,false);
        });
        return deliver(prepared);
    }

    /** 邮箱所有者以正文秘密接受；无效/过期/已使用/错邮箱统一422。账号、验证身份、仅ADMIN角色和消费/审计原子提交，不自动登录。 */
    public AdminInvitationDtos.Acceptance accept(AdminInvitationDtos.Accept request,String remote,String trace) {
        String email=IdentityNormalizer.normalize("EMAIL",request.email());
        if(request.password()==null || request.password().length()<12
                || request.password().getBytes(StandardCharsets.UTF_8).length>72 || request.displayName()==null
                || request.displayName().trim().isEmpty() || request.displayName().trim().length()>80)invalid();
        // 慢密码哈希在行锁外完成，但不能在错误响应/日志中携带原密码。
        String passwordHash=passwords.encode(request.password());
        try {
            return tx.execute(status -> {
                admins.lock();
                Invitation invitation=find(request.invitationId().toString(),true);
                if(invitation==null || invitation.acceptedAt()!=null || !"SENT".equals(invitation.delivery())
                        || !invitation.expires().isAfter(Instant.now()) || !email.equals(invitation.email())
                        || request.secret()==null || !MessageDigest.isEqual(
                                invitation.digest().getBytes(StandardCharsets.UTF_8),
                                digest(invitation.id(),email,request.secret()).getBytes(StandardCharsets.UTF_8)))invalid();
                if(invitation.bootstrap())requireNoAdministrator();
                else {
                    try { admins.require(invitation.actor()); }
                    catch(AuthException noLongerAdmin) { invalid(); }
                }
                unbound(email);
                String userId=UUID.randomUUID().toString();
                users.insertUser(userId,"admin_"+UUID.randomUUID().toString().replace("-",""),request.displayName().trim(),passwordHash);
                users.insertIdentity(UUID.randomUUID().toString(),userId,"EMAIL",email,Instant.now());
                users.assignRole(userId,"ADMIN");
                if(!users.findRoles(userId).equals(List.of("ADMIN")) || !users.findPermissions(userId).equals(List.of("administration:access")))
                    throw new IllegalStateException("独立管理员能力配置不合法");
                jdbc.update("UPDATE administrator_invitation SET accepted_by=?,accepted_at=?,revision=revision+1 WHERE id=?",
                        userId,Timestamp.from(Instant.now()),invitation.id());
                audit(invitation.actor(),userId,"ADMIN_INVITATION_ACCEPTED","邮箱所有者接受一次性管理员邀请 "+invitation.id(),remote,trace);
                return new AdminInvitationDtos.Acceptance(UUID.fromString(userId));
            });
        }catch(DuplicateKeyException collision) {
            conflict("IAM.INVITATION_EMAIL_BOUND","该邮箱已绑定账号，不能转换为管理员");return null;
        }
    }

    /** 保存本域邀请后才发送；SMTP成功/失败另外提交，原秘密只活在当前调用内存，不作自动无限重试。 */
    private AdminInvitationDtos.Invitation deliver(Prepared prepared) {
        if(prepared.pending()!=null) {
            Pending pending=prepared.pending();
            boolean sent;
            try { delivery.send(pending.email(),pending.id(),pending.secret());sent=true; }
            catch(RuntimeException failure) { sent=false; }
            boolean delivered=sent;
            tx.executeWithoutResult(status -> {
                admins.lock();
                Invitation current=required(find(prepared.id(),true));
                if(current.revision()!=pending.revision() || current.acceptedAt()!=null || !"PENDING".equals(current.delivery()))return;
                jdbc.update("UPDATE administrator_invitation SET delivery_status=? WHERE id=?",
                        delivered?"SENT":"FAILED",prepared.id());
                audit(current.actor(),null,delivered?"ADMIN_INVITATION_SENT":"ADMIN_INVITATION_SEND_FAILED",
                        "管理员邀请SMTP结果 "+prepared.id(),"smtp","invitation-delivery");
            });
        }
        return projection(required(find(prepared.id(),false)),prepared.replayed());
    }

    private Prepared prepare(java.util.function.Supplier<Prepared> action) {
        try { return tx.execute(status->action.get()); }
        catch(DuplicateKeyException duplicate) { conflict("IAM.REQUEST_REUSED","邀请请求标识已被另一内容使用");return null; }
    }
    /** 保存新邀请和秘密摘要；24小时到期、60秒冷却，未发送PENDING凭证不能接受。 */
    private Pending insert(String id,String email,String actor,boolean bootstrap) {
        String secret=secret();Instant now=Instant.now();
        jdbc.update("INSERT INTO administrator_invitation(id,email,secret_digest,invited_by,bootstrap,delivery_status,expires_at,resend_after,created_at) VALUES(?,?,?,?,?,'PENDING',?,?,?)",
                id,email,digest(id,email,secret),actor,bootstrap,Timestamp.from(now.plusSeconds(86400)),
                Timestamp.from(now.plusSeconds(60)),Timestamp.from(now));
        return new Pending(id,0,email,secret);
    }
    /** 锁内重发作废旧凭证，最多六次；已接受/冷却中/耗尽次数明确拒绝，不静默生成第二管理员。 */
    private Pending rotate(Invitation invitation) {
        if(invitation.acceptedAt()!=null)conflict("IAM.INVITATION_ALREADY_ACCEPTED","邀请已接受，不能重发");
        if(invitation.sends()>=6)conflict("IAM.INVITATION_SEND_LIMIT","邀请重发次数已用尽");
        if(invitation.resendAfter().isAfter(Instant.now()))throw new AuthException(HttpStatus.TOO_MANY_REQUESTS,
                "IAM.INVITATION_RESEND_COOLDOWN","请等待邀请重发冷却结束",Math.max(1L,java.time.Duration.between(Instant.now(),invitation.resendAfter()).toSeconds()));
        String secret=secret();Instant now=Instant.now();
        jdbc.update("UPDATE administrator_invitation SET secret_digest=?,delivery_status='PENDING',revision=revision+1,send_attempts=send_attempts+1,expires_at=?,resend_after=? WHERE id=?",
                digest(invitation.id(),invitation.email(),secret),Timestamp.from(now.plusSeconds(86400)),
                Timestamp.from(now.plusSeconds(60)),invitation.id());
        return new Pending(invitation.id(),invitation.revision()+1,invitation.email(),secret);
    }
    private Prepared replay(UUID requestId,String canonical) {
        var found=jdbc.query("SELECT invitation_id,request_json FROM administrator_invitation_command WHERE id=?",
                (rs,n)->List.of(rs.getString(1),rs.getString(2)),requestId.toString());
        if(found.isEmpty())return null;
        if(!found.get(0).get(1).equals(canonical))conflict("IAM.REQUEST_REUSED","邀请请求标识已被另一内容使用");
        return new Prepared(found.get(0).get(0),null,true);
    }
    private void saveCommand(UUID request,String actor,String id,String canonical) {
        jdbc.update("INSERT INTO administrator_invitation_command(id,actor_id,invitation_id,request_json) VALUES(?,?,?,?)",request.toString(),actor,id,canonical);
    }
    /** 在ADMIN共同锁下检查历史身份；已存在任何ADMIN，包括封禁者，都不能借初始化绕过邀请/最后管理员规则。 */
    private void requireNoAdministrator() {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_role ur JOIN `role` r ON r.id=ur.role_id WHERE r.code='ADMIN'",Long.class)>0)
            conflict("IAM.BOOTSTRAP_FORBIDDEN","已有管理员事实，禁止首位管理员初始化");
    }
    private void unbound(String email) {
        if(users.findUserByIdentity("EMAIL",email)!=null)conflict("IAM.INVITATION_EMAIL_BOUND","该邮箱已绑定账号，不能转换为管理员");
    }
    private String digest(String id,String email,String secret) { return digests.digest("administrator-invitation",id+"\n"+email+"\n"+secret); }
    private String secret() { byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private Invitation find(String id,boolean lock) {
        return jdbc.query("SELECT * FROM administrator_invitation WHERE id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->map(rs),id).stream().findFirst().orElse(null);
    }
    private static Invitation map(ResultSet rs)throws SQLException {
        Timestamp accepted=rs.getTimestamp("accepted_at");
        return new Invitation(rs.getString("id"),rs.getString("email"),rs.getString("secret_digest"),rs.getString("invited_by"),
                rs.getBoolean("bootstrap"),rs.getString("delivery_status"),rs.getLong("revision"),rs.getInt("send_attempts"),
                rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("resend_after").toInstant(),
                accepted==null?null:accepted.toInstant());
    }
    private static Invitation required(Invitation invitation) {
        if(invitation==null)throw new AuthException(HttpStatus.NOT_FOUND,"IAM.NOT_FOUND","邀请不存在");
        return invitation;
    }
    private static AdminInvitationDtos.Invitation projection(Invitation invitation,boolean replay) {
        return new AdminInvitationDtos.Invitation(UUID.fromString(invitation.id()),IdentityNormalizer.mask("EMAIL",invitation.email()),
                invitation.delivery(),invitation.acceptedAt()!=null?"ACCEPTED":invitation.expires().isAfter(Instant.now())?"AWAITING":"EXPIRED",
                invitation.revision(),invitation.sends(),invitation.expires(),invitation.resendAfter(),replay);
    }
    /** 发送结果/接受/重发均在IAM审计；只记录邀请ID和用户原因，不记录邮件正文、邮箱或秘密。 */
    private void audit(String actor,String user,String action,String reason,String remote,String trace) {
        users.insertSecurityAudit(UUID.randomUUID().toString(),user,actor,action,reason,
                remote==null?"unknown":remote,trace==null?"unknown":trace,Instant.now());
    }
    private static void reason(String value) {
        if(value==null || value.trim().length()<10 || value.trim().length()>500)
            throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT,"IAM.VALIDATION_FAILED","原因需10至500字");
    }
    private static void invalid() { throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT,"IAM.INVITATION_INVALID","邀请凭证无效、已使用或已到期"); }
    private static void conflict(String code,String message) { throw new AuthException(HttpStatus.CONFLICT,code,message); }
    private record Invitation(String id,String email,String digest,String actor,boolean bootstrap,String delivery,long revision,
            int sends,Instant expires,Instant resendAfter,Instant acceptedAt) {}
    private record Pending(String id,long revision,String email,String secret) {
        @Override public String toString() { return "PendingInvitation[redacted]"; }
    }
    private record Prepared(String id,Pending pending,boolean replayed) {}
}
