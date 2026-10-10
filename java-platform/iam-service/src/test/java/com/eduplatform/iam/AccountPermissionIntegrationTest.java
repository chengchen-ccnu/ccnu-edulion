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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:t163_permissions;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class AccountPermissionIntegrationTest {
    @Autowired AccountPermissionService permissions;
    @Autowired TeacherApplicationService applications;
    @Autowired TeacherReviewService reviews;
    @Autowired JdbcTemplate jdbc;
    @Autowired IamMapper users;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
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

    /** 丢弃提交返回值后另一管理员只凭userId恢复；读取不改变命令、审计、Outbox或调用Course。 */
    @Test void latestRecoversLostResponseAcrossAdministratorsWithoutSideEffects() throws Exception {
        String successor=user("ADMIN");
        change(student,Action.BAN);
        var commands=jdbc.queryForList("SELECT * FROM account_permission_command");
        var audits=jdbc.queryForList("SELECT * FROM account_security_audit");
        var outbox=jdbc.queryForList("SELECT * FROM iam_outbox_event");
        clearInvocations(course);
        var recovered=permissions.latest(successor,student).orElseThrow();
        assertThat(recovered).isEqualTo(permissions.get(successor,recovered.commandId().toString()));
        assertThat(recovered.coordinationStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(recovered.replayed()).isFalse();
        var http=latestHttp(student,token(successor,"administration:access",0));
        assertThat(http.statusCode()).isEqualTo(200);
        assertThat(http.body()).contains(recovered.commandId().toString())
                .doesNotContain("reason","request_json","requestJson","actorId","email","password","trace-t163","权限测试明确");
        assertThat(jdbc.queryForList("SELECT * FROM account_permission_command")).isEqualTo(commands);
        assertThat(jdbc.queryForList("SELECT * FROM account_security_audit")).isEqualTo(audits);
        assertThat(jdbc.queryForList("SELECT * FROM iam_outbox_event")).isEqualTo(outbox);
        verifyNoInteractions(course);
    }

    /** 后提交的其他账号不能串入；revision优先于时间，同revision以ID形成稳定次序。仅H2构造相同revision边界。 */
    @Test void latestOrdersByDecisionRevisionThenIdAndIsolatesTarget() {
        var first=change(student,Action.BAN);
        var latest=change(student,Action.UNBAN);
        String other=user("STUDENT");change(other,Action.BAN);
        jdbc.update("UPDATE account_permission_command SET created_at=? WHERE id=?",
                Timestamp.from(Instant.now().plusSeconds(100)),first.commandId().toString());
        assertThat(permissions.latest(admin,student).orElseThrow().commandId()).isEqualTo(latest.commandId());
        jdbc.update("UPDATE account_permission_command SET decided_revision=? WHERE id=?",
                latest.decidedRevision(),first.commandId().toString());
        String expected=java.util.stream.Stream.of(first.commandId().toString(),latest.commandId().toString()).max(String::compareTo).orElseThrow();
        for(int i=0;i<3;i++)assertThat(permissions.latest(admin,student).orElseThrow().commandId().toString()).isEqualTo(expected);
        assertThat(permissions.latest(admin,other).orElseThrow().userId().toString()).isEqualTo(other);
    }

    /** 四态沿既有协调器推进；每次GET前后比较原始行，失败后由另一管理员以原ID有限补偿。 */
    @Test void latestReturnsPendingFailedAppliedWithoutConsumingBudget() {
        users.assignRole(student,"TEACHER");
        doThrow(new IllegalStateException("test-timeout")).when(course).apply(anyString(),anyString(),anyLong(),anyBoolean(),anyString());
        var command=change(student,Action.REVOKE_TEACHING);
        assertLatestUnchanged("PENDING");
        for(int i=0;i<3;i++)attempt(command.commandId());
        assertLatestUnchanged("FAILED");
        String successor=user("ADMIN");
        var recovered=permissions.latest(successor,student).orElseThrow();
        assertThatThrownBy(()->permissions.retry(successor,recovered.commandId().toString(),
                new AccountPermissionDtos.RetryRequest(UUID.randomUUID(),4)))
                .isInstanceOfSatisfying(AuthException.class,e->assertThat(e.code()).isEqualTo("IAM.COORDINATION_RETRY_CONFLICT"));
        permissions.retry(successor,recovered.commandId().toString(),new AccountPermissionDtos.RetryRequest(UUID.randomUUID(),3));
        doReturn(new AccountCourseClient.Applied(student,recovered.suspensionId().toString(),recovered.accessRevision(),true,0))
                .when(course).apply(anyString(),anyString(),anyLong(),anyBoolean(),anyString());
        attempt(recovered.commandId());assertLatestUnchanged("APPLIED");
    }

    /** 两管理员争抢同一FAILED命令；一次预算成功，另一次409，原水位与周期保持不变。 */
    @Test void recoveredCommandKeepsConcurrentRetryConflict() throws Exception {
        users.assignRole(student,"TEACHER");
        doThrow(new IllegalStateException("test-timeout")).when(course).apply(anyString(),anyString(),anyLong(),anyBoolean(),anyString());
        var command=change(student,Action.REVOKE_TEACHING);
        for(int i=0;i<3;i++)attempt(command.commandId());
        String successor=user("ADMIN");var id=permissions.latest(successor,student).orElseThrow().commandId().toString();
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var results=new java.util.ArrayList<Future<Integer>>();
            for(String actor:List.of(admin,successor))results.add(pool.submit(()->{
                start.await();
                try {permissions.retry(actor,id,new AccountPermissionDtos.RetryRequest(UUID.randomUUID(),3));return 202;}
                catch(AuthException conflict){return conflict.status().value();}
            }));
            start.countDown();assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(202,409);
            var after=permissions.latest(successor,student).orElseThrow();
            assertThat(after.attempts()).isEqualTo(3);assertThat(after.accessRevision()).isEqualTo(command.accessRevision());
            assertThat(after.suspensionId()).isEqualTo(command.suspensionId());
        }finally {pool.shutdownNow();}
    }

    /** 真实IAM HTTP过滤链：空账号204、未知404、非法UUID422；匿名/旧会话/禁用401，当前非管理员或过时角色403。 */
    @Test void latestHttpUsesCurrentAuthorityAndDistinguishesEmptyFromUnknown() throws Exception {
        String valid=token(admin,"administration:access",0);
        var empty=latestHttp(student,valid);assertThat(empty.statusCode()).isEqualTo(204);assertThat(empty.body()).isEmpty();
        var unknown=latestHttp(UUID.randomUUID().toString(),valid);assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("code","message","traceId","timestamp","path","fieldErrors");
        assertThat(latestHttp("invalid",valid).statusCode()).isEqualTo(422);
        assertThat(latestHttp(student,null).statusCode()).isEqualTo(401);
        assertThat(latestHttp(student,token(student,"learning:access",0)).statusCode()).isEqualTo(403);
        jdbc.update("DELETE FROM user_role WHERE user_id=?",admin);users.assignRole(admin,"STUDENT");
        assertThat(latestHttp(student,valid).statusCode()).isEqualTo(403);
        jdbc.update("DELETE FROM user_role WHERE user_id=?",admin);users.assignRole(admin,"ADMIN");
        jdbc.update("UPDATE `user` SET session_version=1 WHERE id=?",admin);
        assertThat(latestHttp(student,valid).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE `user` SET status='DISABLED' WHERE id=?",admin);
        assertThat(latestHttp(student,token(admin,"administration:access",1)).statusCode()).isEqualTo(401);
    }

    /** 每种协调状态在两次读取间必须保留所有原始字段，GET不能调用Course或消耗尝试。 */
    private void assertLatestUnchanged(String status) {
        var before=jdbc.queryForList("SELECT * FROM account_permission_command WHERE user_id=?",student);
        clearInvocations(course);
        assertThat(permissions.latest(admin,student).orElseThrow().coordinationStatus()).isEqualTo(status);
        assertThat(jdbc.queryForList("SELECT * FROM account_permission_command WHERE user_id=?",student)).isEqualTo(before);
        verifyNoInteractions(course);
    }
    /** 仅H2 HTTP测试签发已知测试密钥JWT；真实验收通过正式登录，不以此替代真实授权。 */
    private String token(String actor,String permission,long version) {
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("edu-platform-iam-test")
                .audience(List.of("edu-platform-gateway-test")).subject(actor).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120))
                .claim("roles",List.of("ADMIN")).claim("permissions",List.of(permission)).claim("sessionVersion",version).build();
        return jwtEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    private java.net.http.HttpResponse<String> latestHttp(String target,String token) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/admin/users/"+target+"/permission-commands/latest")).GET();
        if(token!=null)request.header("Authorization","Bearer "+token);
        return java.net.http.HttpClient.newHttpClient().send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
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
