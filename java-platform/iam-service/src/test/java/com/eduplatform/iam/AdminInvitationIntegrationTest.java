package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.service.*;
import com.eduplatform.iam.verification.AdminInvitationDelivery;
import com.eduplatform.iam.web.AdminInvitationDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 专用H2邀请测试；SMTP端口明确Mock，真实SMTP另验收。临时用户/邀请仅按本例UUID清理，无秘密日志。 */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:t163_invitations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class AdminInvitationIntegrationTest {
    @Autowired AdminInvitationService invitations;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean IamMapper users;
    @MockitoBean AdminInvitationDelivery delivery;
    String admin;
    final List<String> userIds=new ArrayList<>();
    final List<String> invitationIds=new ArrayList<>();
    final AtomicReference<String> secret=new AtomicReference<>();
    @BeforeEach void setup() {
        users.upsertRole("ADMIN","ADMIN");users.upsertRole("STUDENT","STUDENT");
        users.upsertPermission("administration:access","administration:access");users.assignPermission("ADMIN","administration:access");
        users.upsertPermission("learning:access","learning:access");users.assignPermission("STUDENT","learning:access");
        admin=user("ADMIN","admin-"+UUID.randomUUID()+"@example.invalid");
        doAnswer(call->{secret.set(call.getArgument(2));return null;}).when(delivery).send(anyString(),anyString(),anyString());
    }
    @AfterEach void cleanup() {
        reset(users);
        for(String id:invitationIds) {
            jdbc.update("DELETE FROM administrator_invitation_command WHERE invitation_id=?",id);
            jdbc.update("DELETE FROM administrator_invitation WHERE id=?",id);
            jdbc.update("DELETE FROM account_security_audit WHERE reason LIKE ?","%"+id);
        }
        for(String id:userIds) {
            jdbc.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?",id,id);
            jdbc.update("DELETE FROM user_identity WHERE user_id=?",id);
            jdbc.update("DELETE FROM user_role WHERE user_id=?",id);
            jdbc.update("DELETE FROM `user` WHERE id=?",id);
        }
        userIds.clear();invitationIds.clear();secret.set(null);
    }
    @Test void exactReplayDoesNotResendAndAcceptedAdminIsIndependent() {
        var request=createRequest("invited@example.invalid");var invited=create(request);
        String credential=secret.get();
        assertThat(invited.deliveryStatus()).isEqualTo("SENT");
        assertThat(invitations.create(admin,request,"test","trace-t163").replayed()).isTrue();
        verify(delivery,times(1)).send(anyString(),anyString(),anyString());
        assertThat(jdbc.queryForObject("SELECT secret_digest FROM administrator_invitation WHERE id=?",String.class,invited.invitationId().toString()))
                .isNotEqualTo(credential).hasSize(64);
        var accepted=invitations.accept(accept(invited,"invited@example.invalid",credential),"test","trace-t163");userIds.add(accepted.userId().toString());
        assertThat(users.findRoles(accepted.userId().toString())).containsExactly("ADMIN");
        assertThat(users.findPermissions(accepted.userId().toString())).containsExactly("administration:access");
        assertThat(invitations.get(admin,invited.invitationId().toString()).acceptanceStatus()).isEqualTo("ACCEPTED");
        assertInvalid(()->invitations.accept(accept(invited,"invited@example.invalid",credential),"test","trace-t163"));
        assertThatThrownBy(()->invitations.create(admin,new AdminInvitationDtos.Create(request.clientRequestId(),"other@example.invalid",request.reason()),"test","trace-t163"))
                .isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.REQUEST_REUSED"));
    }
    @Test void boundOrdinaryAndAdminEmailsNeverConvert() {
        user("STUDENT","bound-student@example.invalid");user("ADMIN","bound-admin@example.invalid");
        for(String email:List.of("bound-student@example.invalid","bound-admin@example.invalid")) {
            assertThatThrownBy(()->create(createRequest(email))).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.INVITATION_EMAIL_BOUND"));
        }
        verifyNoInteractions(delivery);
    }
    @Test void ordinaryCannotInviteReadOrResendAndBootstrapRefusesExistingAdmin() {
        String ordinary=user("STUDENT","ordinary@example.invalid");
        assertThatThrownBy(()->invitations.create(ordinary,createRequest("new@example.invalid"),"test","trace-t163")).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->invitations.get(ordinary,UUID.randomUUID().toString())).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->invitations.bootstrap(UUID.randomUUID(),"bootstrap@example.invalid")).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.BOOTSTRAP_FORBIDDEN"));
    }
    @Test void wrongEmailSecretExpiredAndPendingReturnSameInvalidError() {
        var invited=create(createRequest("ownership@example.invalid"));String credential=secret.get();
        assertInvalid(()->invitations.accept(accept(invited,"wrong@example.invalid",credential),"test","trace-t163"));
        assertInvalid(()->invitations.accept(accept(invited,"ownership@example.invalid","A".repeat(43)),"test","trace-t163"));
        jdbc.update("UPDATE administrator_invitation SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),invited.invitationId().toString());
        assertInvalid(()->invitations.accept(accept(invited,"ownership@example.invalid",credential),"test","trace-t163"));
        assertThat(invitations.get(admin,invited.invitationId().toString()).acceptanceStatus()).isEqualTo("EXPIRED");
        jdbc.update("UPDATE administrator_invitation SET expires_at=?,delivery_status='PENDING' WHERE id=?",Timestamp.from(Instant.now().plusSeconds(3600)),invited.invitationId().toString());
        assertInvalid(()->invitations.accept(accept(invited,"ownership@example.invalid",credential),"test","trace-t163"));
    }
    @Test void sendFailureResendRotatesSecretAndHasCooldownAndRevision() {
        doThrow(new IllegalStateException("test-smtp-failure")).when(delivery).send(anyString(),anyString(),anyString());
        var invited=create(createRequest("retry@example.invalid"));
        assertThat(invited.deliveryStatus()).isEqualTo("FAILED");
        assertInvalid(()->invitations.accept(accept(invited,"retry@example.invalid","A".repeat(43)),"test","trace-t163"));
        var resend=new AdminInvitationDtos.Resend(UUID.randomUUID(),0L,"明确重发失败管理员邀请的原因");
        assertThatThrownBy(()->invitations.resend(admin,invited.invitationId().toString(),resend,"test","trace-t163"))
                .isInstanceOfSatisfying(AuthException.class,e->assertThat(e.status().value()).isEqualTo(429));
        jdbc.update("UPDATE administrator_invitation SET resend_after=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),invited.invitationId().toString());
        doAnswer(call->{secret.set(call.getArgument(2));return null;}).when(delivery).send(anyString(),anyString(),anyString());
        var resent=invitations.resend(admin,invited.invitationId().toString(),resend,"test","trace-t163");
        assertThat(resent.deliveryStatus()).isEqualTo("SENT");assertThat(resent.sendAttempts()).isEqualTo(2);
        assertThat(invitations.resend(admin,invited.invitationId().toString(),resend,"test","trace-t163").replayed()).isTrue();
        assertThatThrownBy(()->invitations.resend(admin,invited.invitationId().toString(),new AdminInvitationDtos.Resend(UUID.randomUUID(),0L,resend.reason()),"test","trace-t163"))
                .isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.REVISION_CONFLICT"));
    }
    @Test void resendInvalidatesOldCredentialAndStopsAtSixSends() {
        var invited=create(createRequest("rotate@example.invalid"));String old=secret.get();
        for(int revision=0;revision<5;revision++) {
            jdbc.update("UPDATE administrator_invitation SET resend_after=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),invited.invitationId().toString());
            invitations.resend(admin,invited.invitationId().toString(),new AdminInvitationDtos.Resend(UUID.randomUUID(),(long)revision,"有限重发管理员邀请确认原因"),"test","trace-t163");
        }
        assertInvalid(()->invitations.accept(accept(invited,"rotate@example.invalid",old),"test","trace-t163"));
        assertThatThrownBy(()->invitations.resend(admin,invited.invitationId().toString(),new AdminInvitationDtos.Resend(UUID.randomUUID(),5L,"有限重发管理员邀请确认原因"),"test","trace-t163"))
                .isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.INVITATION_SEND_LIMIT"));
        var accepted=invitations.accept(accept(invited,"rotate@example.invalid",secret.get()),"test","trace-t163");userIds.add(accepted.userId().toString());
    }
    /** 两个相同接受请求争用邀请及ADMIN行锁，最终只有一个账号，另一个统一422，密码不会被覆盖。 */
    @Test void concurrentAcceptanceIsSingleUse()throws Exception {
        var invited=create(createRequest("concurrent@example.invalid"));var request=accept(invited,"concurrent@example.invalid",secret.get());
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        Callable<String> accept=()-> {start.await();try{return invitations.accept(request,"test","trace-t163").userId().toString();}catch(AuthException e){assertThat(e.code()).isEqualTo("IAM.INVITATION_INVALID");return "invalid";}};
        try {
            var first=pool.submit(accept);var second=pool.submit(accept);start.countDown();
            List<String> results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter("invalid"::equals).count()).isEqualTo(1);
            results.stream().filter(value->!value.equals("invalid")).forEach(userIds::add);
        }finally {pool.shutdownNow();}
    }
    /** 插入账号/身份后注入审计失败，整个接受事务回滚：无账号、无能力、凭证仍未消费。 */
    @Test void acceptanceTransactionFailureDoesNotCreateAccount() {
        var invited=create(createRequest("rollback@example.invalid"));String credential=secret.get();
        doThrow(new IllegalStateException("test-audit-write-failure")).when(users).insertSecurityAudit(anyString(),nullable(String.class),
                nullable(String.class),eq("ADMIN_INVITATION_ACCEPTED"),anyString(),anyString(),anyString(),any());
        assertThatThrownBy(()->invitations.accept(accept(invited,"rollback@example.invalid",credential),"test","trace-t163")).isInstanceOf(IllegalStateException.class);
        assertThat(users.findUserByIdentity("EMAIL","rollback@example.invalid")).isNull();
        assertThat(invitations.get(admin,invited.invitationId().toString()).acceptanceStatus()).isEqualTo("AWAITING");
    }
    @Test void firstAdministratorUsesVerifiedInvitationNotPasswordSeed() {
        jdbc.update("DELETE FROM user_role WHERE user_id=?",admin);
        var bootstrap=invitations.bootstrap(UUID.randomUUID(),"first@example.invalid");invitationIds.add(bootstrap.invitationId().toString());
        assertThat(users.findUserByIdentity("EMAIL","first@example.invalid")).isNull();
        var accepted=invitations.accept(accept(bootstrap,"first@example.invalid",secret.get()),"test","trace-t163");userIds.add(accepted.userId().toString());
        assertThat(users.findRoles(accepted.userId().toString())).containsExactly("ADMIN");
        assertThatThrownBy(()->invitations.bootstrap(UUID.randomUUID(),"other-first@example.invalid")).isInstanceOf(AuthException.class);
    }
    @Test void multiByteOverlongPasswordAndWhitespaceNameDoNotConsumeInvite() {
        var invited=create(createRequest("password@example.invalid"));
        assertInvalid(()->invitations.accept(new AdminInvitationDtos.Accept(invited.invitationId(),"password@example.invalid",secret.get(),"密".repeat(25),"测试"),"test","trace-t163"));
        assertInvalid(()->invitations.accept(new AdminInvitationDtos.Accept(invited.invitationId(),"password@example.invalid",secret.get(),"Valid-password-123","   "),"test","trace-t163"));
        assertThat(users.findUserByIdentity("EMAIL","password@example.invalid")).isNull();
    }
    private String user(String role,String email) {
        String id=UUID.randomUUID().toString();userIds.add(id);
        users.insertUser(id,"t163_"+id.replace("-",""),"邀请测试","not-a-login-hash");
        users.insertIdentity(UUID.randomUUID().toString(),id,"EMAIL",email,Instant.now());users.assignRole(id,role);return id;
    }
    private AdminInvitationDtos.Create createRequest(String email) {return new AdminInvitationDtos.Create(UUID.randomUUID(),email,"管理员邀请测试明确说明邀请原因");}
    private AdminInvitationDtos.Invitation create(AdminInvitationDtos.Create request) {
        var result=invitations.create(admin,request,"test","trace-t163");invitationIds.add(result.invitationId().toString());return result;
    }
    private AdminInvitationDtos.Accept accept(AdminInvitationDtos.Invitation invitation,String email,String value) {
        return new AdminInvitationDtos.Accept(invitation.invitationId(),email,value,"Invited-password-123","合成测试管理员");
    }
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AuthException.class,e->{assertThat(e.code()).isEqualTo("IAM.INVITATION_INVALID");assertThat(e.status().value()).isEqualTo(422);});
    }
}
