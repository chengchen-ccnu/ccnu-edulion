package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.security.IdentitySecurityStore;
import com.eduplatform.iam.service.*;
import com.eduplatform.iam.media.TeacherQualificationMediaClient;
import com.eduplatform.iam.web.*;
import com.eduplatform.iam.web.AccountPermissionDtos.Action;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 专用H2权限事务测试；Media/Course端口明确Mock，不冒充真实跨服务验收；批准由既有审核服务产生而非伪造决定行。 */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:t163_permissions;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class AccountPermissionIntegrationTest {
    @Autowired AccountPermissionService permissions;
    @Autowired TeacherApplicationService applications;
    @Autowired TeacherReviewService reviews;
    @Autowired JdbcTemplate jdbc;
    @Autowired IamMapper users;
    @MockitoBean AccountCourseClient course;
    @MockitoBean TeacherQualificationMediaClient media;
    @MockitoSpyBean IdentitySecurityStore security;
    String admin,student;

    @BeforeEach void setup() {
        for(String role:List.of("ADMIN","STUDENT","TEACHER"))users.upsertRole(role,role);
        for(String p:List.of("administration:access","learning:access","teaching:access"))users.upsertPermission(p,p);
        users.assignPermission("ADMIN","administration:access");users.assignPermission("STUDENT","learning:access");users.assignPermission("TEACHER","teaching:access");
        admin=user("ADMIN");student=user("STUDENT");
        when(course.impact(anyString(),anyBoolean(),nullable(String.class))).thenReturn(new AccountCourseClient.Impact(List.of(),0));
        when(course.apply(anyString(),anyString(),anyLong(),anyBoolean(),anyString())).thenAnswer(call ->
                new AccountCourseClient.Applied(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),0));
    }
    @AfterEach void cleanup() {
        // 仅独立内存库；逐个本例UUID清理，不触碰开发库或其他任务事实。
        for(String id:jdbc.queryForList("SELECT id FROM `user`",String.class)) {
            jdbc.update("DELETE FROM iam_outbox_event WHERE aggregate_id=? OR aggregate_id IN(SELECT id FROM teacher_application WHERE user_id=?)",id,id);
            jdbc.update("DELETE FROM teacher_review_command WHERE application_id IN(SELECT id FROM teacher_application WHERE user_id=?)",id);
            jdbc.update("DELETE FROM teacher_review_decision WHERE application_id IN(SELECT id FROM teacher_application WHERE user_id=?)",id);
            jdbc.update("DELETE FROM teacher_application_round WHERE application_id IN(SELECT id FROM teacher_application WHERE user_id=?)",id);
            jdbc.update("DELETE FROM teacher_application_command WHERE application_id IN(SELECT id FROM teacher_application WHERE user_id=?)",id);
            jdbc.update("DELETE FROM teacher_application WHERE user_id=?",id);
            jdbc.update("DELETE FROM account_permission_retry WHERE command_id IN(SELECT id FROM account_permission_command WHERE user_id=?)",id);
            jdbc.update("DELETE FROM account_permission_command WHERE user_id=?",id);
            jdbc.update("DELETE FROM account_permission_preview WHERE user_id=?",id);
            jdbc.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?",id,id);
            jdbc.update("DELETE FROM user_role WHERE user_id=?",id);
            jdbc.update("DELETE FROM `user` WHERE id=?",id);
        }
    }
    @Test void ordinaryBanUnbanRetainsIdentityAndDecisionReplay() {
        var request=request(preview(student,Action.BAN));
        var command=permissions.change(admin,student,request,"test","trace-t163");
        assertThat(command.coordinationStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(permissions.change(admin,student,request,"test","trace-t163").replayed()).isTrue();
        assertThat(users.findUserById(student).getStatus()).isEqualTo("DISABLED");
        assertThat(users.findRoles(student)).containsExactly("STUDENT");
        assertThatThrownBy(()->permissions.change(admin,student,new AccountPermissionDtos.CommandRequest(request.clientRequestId(),
                request.previewId(),request.expectedRevision(),Action.BAN,"另一个封禁原因足够明确",true),"test","trace-t163"))
                .isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.REQUEST_REUSED"));
        change(student,Action.UNBAN);
        assertThat(users.findRoles(student)).containsExactly("STUDENT");
        assertThat(users.findUserById(student).getSessionVersion()).isEqualTo(2);
    }
    @Test void previewExpiryRevisionAndExplicitConfirmationRejectWrites() {
        var p=preview(student,Action.BAN);
        var good=request(p);
        assertThatThrownBy(()->permissions.change(admin,student,new AccountPermissionDtos.CommandRequest(good.clientRequestId(),
                good.previewId(),good.expectedRevision(),Action.BAN,good.reason(),false),"test","trace-t163")).isInstanceOf(AuthException.class);
        jdbc.update("UPDATE account_permission_preview SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),p.previewId().toString());
        assertThatThrownBy(()->permissions.change(admin,student,good,"test","trace-t163")).isInstanceOf(AuthException.class);
        var current=request(preview(student,Action.BAN));
        users.touchUserRevision(student,0);
        assertThatThrownBy(()->permissions.change(admin,student,current,"test","trace-t163")).isInstanceOf(AuthException.class);
        assertThat(users.findUserById(student).getStatus()).isEqualTo("ACTIVE");
    }
    @Test void ordinaryCannotAdministerAndTeacherOnlyIsForbidden() {
        assertThatThrownBy(()->permissions.preview(student,admin,Action.BAN)).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.status().value()).isEqualTo(403));
        jdbc.update("DELETE FROM user_role WHERE user_id=?",student);users.assignRole(student,"TEACHER");
        assertThatThrownBy(()->permissions.preview(admin,student,Action.BAN)).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.ILLEGAL_ACCOUNT_CAPABILITIES"));
        assertThatThrownBy(()->permissions.preview(admin,admin,Action.RESTORE_TEACHING)).isInstanceOf(AuthException.class);
    }
    @Test void firstGrantCannotUseRestoreAndSeedRoleDoesNotInventApproval() {
        assertThatThrownBy(()->preview(student,Action.RESTORE_TEACHING)).isInstanceOf(AuthException.class);
        users.assignRole(student,"TEACHER");
        var revoke=change(student,Action.REVOKE_TEACHING);permissions.coordinate(revoke.commandId().toString());
        assertThatThrownBy(()->preview(student,Action.RESTORE_TEACHING)).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.TEACHING_RESTORE_FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_review_decision",Long.class)).isZero();
    }
    @Test void approvedRestoreWaitsForCourseAndUsesOriginalCycle() {
        approve();
        var revoke=change(student,Action.REVOKE_TEACHING);
        assertThat(users.findRoles(student)).containsExactly("STUDENT");
        assertThatThrownBy(()->preview(student,Action.BAN)).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.COORDINATION_PENDING"));
        permissions.coordinate(revoke.commandId().toString());
        var restore=change(student,Action.RESTORE_TEACHING);
        assertThat(restore.suspensionId()).isEqualTo(revoke.suspensionId());
        assertThat(restore.accessRevision()).isEqualTo(revoke.accessRevision()+1);
        assertThat(users.findRoles(student)).containsExactly("STUDENT");
        permissions.coordinate(restore.commandId().toString());
        assertThat(permissions.get(admin,restore.commandId().toString()).coordinationStatus()).isEqualTo("APPLIED");
        assertThat(users.findRoles(student)).containsExactly("STUDENT","TEACHER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iam_outbox_event WHERE aggregate_id=? AND event_type='AccountPermissionChanged'",Long.class,student)).isEqualTo(4);
    }
    @Test void banTeacherThenUnbanRestoresOnlyStudentAndKeepsCycle() {
        approve();var ban=change(student,Action.BAN);permissions.coordinate(ban.commandId().toString());
        change(student,Action.UNBAN);
        assertThat(users.findRoles(student)).containsExactly("STUDENT");
        var restore=change(student,Action.RESTORE_TEACHING);
        assertThat(restore.suspensionId()).isEqualTo(ban.suspensionId());
    }
    @Test void impactChangeAndRedisFailureRollBackWholeDecision() {
        users.assignRole(student,"TEACHER");
        var p=preview(student,Action.REVOKE_TEACHING);
        when(course.impact(eq(student),eq(true),nullable(String.class))).thenReturn(new AccountCourseClient.Impact(List.of(UUID.randomUUID().toString()),2));
        assertThatThrownBy(()->permissions.change(admin,student,request(p),"test","trace-t163"))
                .isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.IMPACT_CHANGED"));
        when(course.impact(anyString(),anyBoolean(),nullable(String.class))).thenReturn(new AccountCourseClient.Impact(List.of(),0));
        doThrow(new IllegalStateException("test-unavailable")).when(security).invalidateSessionVersion(student);
        assertThatThrownBy(()->change(student,Action.REVOKE_TEACHING)).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.status().value()).isEqualTo(503));
        assertThat(users.findRoles(student)).containsExactly("STUDENT","TEACHER");
        assertThat(users.findUserById(student).getRevision()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_permission_command WHERE user_id=?",Long.class,student)).isZero();
    }
    @Test void coordinationHasFiniteAutomaticAndManualBudgetAndReplay() {
        users.assignRole(student,"TEACHER");
        doThrow(new IllegalStateException("test-timeout-after-commit")).when(course).apply(anyString(),anyString(),anyLong(),anyBoolean(),anyString());
        var command=change(student,Action.REVOKE_TEACHING);
        for(int i=0;i<3;i++)attempt(command.commandId());
        var failed=permissions.get(admin,command.commandId().toString());
        assertThat(failed.coordinationStatus()).isEqualTo("FAILED");assertThat(failed.attempts()).isEqualTo(3);
        for(int count=3;count<6;count++) {
            var request=new AccountPermissionDtos.RetryRequest(UUID.randomUUID(),count);
            permissions.retry(admin,command.commandId().toString(),request);
            assertThat(permissions.retry(admin,command.commandId().toString(),request).replayed()).isTrue();
            attempt(command.commandId());
        }
        assertThat(permissions.get(admin,command.commandId().toString()).attempts()).isEqualTo(6);
        assertThatThrownBy(()->permissions.retry(admin,command.commandId().toString(),new AccountPermissionDtos.RetryRequest(UUID.randomUUID(),5))).isInstanceOf(AuthException.class);
        verify(course,times(6)).apply(eq(student),eq(command.suspensionId().toString()),eq(command.accessRevision()),eq(true),anyString());
        assertThat(users.findRoles(student)).containsExactly("STUDENT");
    }
    /** 两个当前管理员同时封禁对方：共同角色锁之后复核操作者，最终恰好一人可用，不产生零管理员。 */
    @Test void concurrentAdministratorsCannotDisableEachOtherToZero()throws Exception {
        String other=user("ADMIN");
        var pa=permissions.preview(admin,other,Action.BAN);var pb=permissions.preview(other,admin,Action.BAN);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Boolean> a=pool.submit(()->{start.await();return tryChange(admin,other,pa);});
            Future<Boolean> b=pool.submit(()->{start.await();return tryChange(other,admin,pb);});
            start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id IN (?,?) AND status='ACTIVE'",Long.class,admin,other)).isEqualTo(1);
        }finally { pool.shutdownNow(); }
    }
    @Test void selfBanIsForbiddenAndLastAdministratorIsNeverLost() {
        assertThatThrownBy(()->preview(admin,Action.BAN)).isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.SELF_DISABLE_FORBIDDEN"));
        String ordinaryActor=student;
        assertThatThrownBy(()->permissions.preview(ordinaryActor,admin,Action.BAN)).isInstanceOf(AuthException.class);
    }
    private boolean tryChange(String actor,String target,AccountPermissionDtos.Preview preview) {
        try {permissions.change(actor,target,request(preview),"test","trace-t163");return true;}
        catch(AuthException forbidden) {return false;}
    }
    private void attempt(UUID id) {
        jdbc.update("UPDATE account_permission_command SET next_attempt_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),id.toString());
        permissions.coordinate(id.toString());
    }
    private String user(String role) {
        String id=UUID.randomUUID().toString();users.insertUser(id,"t163_"+id.replace("-",""),"权限测试","not-a-login-password-hash");
        users.assignRole(id,role);return id;
    }
    private AccountPermissionDtos.Preview preview(String target,Action action) {return permissions.preview(admin,target,action);}
    private AccountPermissionDtos.CommandRequest request(AccountPermissionDtos.Preview p) {
        return new AccountPermissionDtos.CommandRequest(UUID.randomUUID(),p.previewId(),p.expectedRevision(),p.action(),"权限测试明确确认影响范围和原因",true);
    }
    private AccountPermissionDtos.Command change(String target,Action action) {
        return permissions.change(admin,target,request(preview(target,action)),"test","trace-t163");
    }
    /** 专用测试端口模拟可读资质，但不可变批准行由生产审核服务产生；真实验收另验证真实Media。 */
    private void approve() {
        UUID asset=UUID.randomUUID();
        var application=applications.submit(student,new AccountLifecycleDtos.SubmitTeacherApplicationRequest(UUID.randomUUID(),null,asset,"合成测试资格：本人具有数学教学经验"),"test","trace-t163").application();
        var opened=reviews.open(admin,application.id().toString(),"test","trace-t163");
        when(media.grant(asset.toString(),admin,false,"trace-t163")).thenReturn(new TeacherQualificationMediaClient.Grant(
                asset.toString(),"image/png",1,"GET","https://example.invalid/test",Map.of(),Instant.now().plusSeconds(60)));
        reviews.decide(admin,application.id().toString(),new TeacherReviewDtos.DecisionRequest(UUID.randomUUID(),opened.revision(),
                TeacherReviewDtos.Decision.APPROVED,true,null),"test","trace-t163");
    }
}
