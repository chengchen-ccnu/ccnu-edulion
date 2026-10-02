package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.eduplatform.iam.IamServiceApplication;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.service.*;
import com.eduplatform.iam.verification.AdminInvitationDelivery;
import com.eduplatform.learning.web.LearningDtos;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

/** 真正Gateway→IAM→Course→Learning/MySQL/Redis/RabbitMQ/SMTP验收；资质由T16.2真实Media/OSS审核产生，Spy只注入未知结果或事务故障。 */
@EnabledIfEnvironmentVariable(named="T163_REAL_ACCEPTANCE_ENABLED",matches="true")
class T163AccountPermissionsRealAcceptanceTest {
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final ObjectMapper json=new ObjectMapper();
    final String trace="t163-real-"+UUID.randomUUID().toString().substring(0,12);
    final List<String> invitations=new ArrayList<>();
    final List<String> invitedUsers=new ArrayList<>();
    final List<String> eventIds=new ArrayList<>();
    final Set<String> acknowledged=new HashSet<>();
    final Set<String> rateSubjects=new HashSet<>();
    ConfigurableApplicationContext iamContext;
    // Media测试类路径含Gateway测试依赖；IAM servlet上下文排除Gateway自动配置，真实Gateway仍是独立JAR。
    private static final String GATEWAY_EXCLUSIONS=String.join(",",List.of(
            "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayResilience4JCircuitBreakerAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayFunctionAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayStreamAutoConfiguration",
            "org.springframework.cloud.gateway.discovery.GatewayDiscoveryClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.SimpleUrlHandlerMappingGlobalCorsAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayReactiveLoadBalancerClientAutoConfiguration",
            "org.springframework.cloud.gateway.config.LocalResponseCacheAutoConfiguration",
            "org.springframework.cloud.gateway.config.GatewayTracingAutoConfiguration"));

    /** 受控独立IAM Web上下文使用仓库自己的配置/迁移；秘密全部继承包装器环境，不拼到启动参数。 */
    @Test void permissionsAndInvitationsUseRealInfrastructure()throws Exception {
        assertThat(System.getenv("IAM_MYSQL_DATABASE")).isEqualTo("edu_platform_iam_dev");
        assertThat(System.getenv("IAM_VERIFICATION_DELIVERY_MODE")).isEqualTo("smtp");
        Path root=Path.of("../..").toAbsolutePath().normalize();
        String config=root.resolve("java-platform/iam-service/src/main/resources/application.yml").toUri()+","
                +root.resolve("java-platform/iam-service/src/main/resources/application-local.yml").toUri();
        try {
            iamContext=new SpringApplicationBuilder(IamServiceApplication.class,Spies.class).profiles("local")
                    .run("--spring.config.location="+config,"--server.port=18081",
                            "--spring.cloud.gateway.server.webflux.enabled=false",
                            "--spring.cloud.gateway.enabled=false",
                            "--spring.autoconfigure.exclude="+GATEWAY_EXCLUSIONS,
                            "--spring.flyway.locations=filesystem:"+root.resolve("java-platform/iam-service/src/main/resources/db/migration"),
                            "--platform.iam.seed.enabled=false","--platform.iam.review.retention-enabled=false",
                            "--platform.iam.review.messaging-enabled=true","--platform.iam.account-permissions.poll-delay=200ms");
            assertThat(iamContext.getBean(JdbcTemplate.class).queryForObject("SELECT DATABASE()",String.class)).isEqualTo("edu_platform_iam_dev");
            var fixture=new T162TeacherReviewRealAcceptanceTest();
            fixture.withApprovedScenario(this::scenario);
            fixture.realQualificationReviewAndReferenceChain();
        }finally {
            if(iamContext!=null) {
                cleanupInvitations(iamContext.getBean(JdbcTemplate.class));
                clearRateKeys(iamContext.getBean(StringRedisTemplate.class));
                iamContext.close();
            }
        }
    }

    /** 仅此受控验收上下文包裹真实端口；正常调用仍进真实HTTP/SMTP/DAO，不改变生产装配。 */
    static class Spies {
        @Bean static BeanPostProcessor controlledSpies() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(bean instanceof AccountCourseClient || bean instanceof AdminInvitationDelivery)return spy(bean);
                    if(bean instanceof IamMapper)return mock(IamMapper.class,withSettings().defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(bean)));
                    return bean;
                }
            };
        }
    }

    /** 在真实批准后创建精确课程/任务；未知HTTP结果重放同周期，不重复补时；失败补偿六次后停，SMTP秘密只在内存捕获不输出。 */
    private void scenario(JdbcTemplate iam,ConfigurableApplicationContext media,String student,String admin,
            String email,String adminEmail,String password,String teacherToken,String adminToken)throws Exception {
        AccountCourseClient client=iamContext.getBean(AccountCourseClient.class);
        StringRedisTemplate redis=iamContext.getBean(StringRedisTemplate.class);
        RabbitTemplate rabbit=media.getBean(RabbitTemplate.class);
        String queue="edu.iam.account-permissions.v1";
        Integer pending=rabbit.execute(channel->channel.queueDeclarePassive(queue).getMessageCount());
        assertThat(pending).as("不消费其他任务的权限消息").isZero();
        rateSubjects.addAll(List.of(student,admin));
        T163CrossDomainFixtures fixtures=new T163CrossDomainFixtures(student);
        try {
            var first=fixtures.create(true);var second=fixtures.create(true);var draft=fixtures.create(false);
            for(var course:List.of(first,second))body(send("POST","/api/v1/learning/enrollments",Map.of("courseId",course.id()),teacherToken),200,201);
            publishOwnEnrollments(fixtures,rabbit,student);
            String exam=fixtures.task(first,LearningDtos.AssessmentType.EXAM,student);
            String homework=fixtures.task(first,LearningDtos.AssessmentType.HOMEWORK,student);
            JsonNode examDraft=body(send("POST","/api/v1/learning/assessments/"+exam+"/attempts",Map.of("clientRequestId",UUID.randomUUID()),teacherToken),201,200);
            JsonNode homeworkDraft=body(send("POST","/api/v1/learning/assessments/"+homework+"/attempts",Map.of("clientRequestId",UUID.randomUUID()),teacherToken),201,200);
            String attempt=examDraft.get("id").asString();
            Instant deadline=fixtures.learning.queryForObject("SELECT deadline_at FROM assessment_attempt WHERE id=?",Timestamp.class,attempt).toInstant();
            Instant late=fixtures.tasks.assessment(homework).orElseThrow().lateUntil();
            assertThat(send("POST","/api/v1/admin/users/"+student+"/permission-previews",Map.of("action","REVOKE_TEACHING"),teacherToken).statusCode()).isEqualTo(403);
            assertThat(send("GET","/api/v1/admin/invitations/"+UUID.randomUUID(),null,null).statusCode()).isEqualTo(401);
            // 第一次Course已经提交却丢确认，下一次仍真实重放同ID，实际区间只有一段。
            AtomicBoolean loseAck=new AtomicBoolean(true);
            doAnswer(call->{Object applied=call.callRealMethod();if(loseAck.getAndSet(false))throw new IllegalStateException("controlled-lost-ack");return applied;})
                    .when(client).apply(eq(student),anyString(),anyLong(),eq(true),anyString());
            JsonNode preview=body(send("POST","/api/v1/admin/users/"+student+"/permission-previews",Map.of("action","REVOKE_TEACHING"),adminToken),201);
            assertThat(preview.path("affectedCourses").asInt()).isEqualTo(2);
            assertThat(preview.path("affectedStudents").asLong()).isEqualTo(1);
            Map<String,Object> command=request(preview,"REVOKE_TEACHING");
            JsonNode revoke=body(send("POST","/api/v1/admin/users/"+student+"/permission-commands",command,adminToken),202);
            assertThat(revoke.path("coordinationStatus").asString()).isEqualTo("PENDING");
            assertThat(body(send("POST","/api/v1/admin/users/"+student+"/permission-commands",command,adminToken),202).path("replayed").asBoolean()).isTrue();
            await(()->commandStatus(revoke,adminToken).equals("APPLIED"),20);
            JsonNode applied=body(send("GET","/api/v1/admin/permission-commands/"+revoke.path("commandId").asString(),null,adminToken),200);
            assertThat(applied.path("attempts").asInt()).isEqualTo(2);
            assertThat(fixtures.course.queryForObject("SELECT COUNT(*) FROM course_suspension_interval WHERE course_id=?",Long.class,first.id())).isEqualTo(1);
            assertThat(fixtures.course.queryForObject("SELECT system_suspended FROM course WHERE id=?",Boolean.class,draft.id())).isFalse();
            assertThat(send("GET","/api/v1/users/me",null,teacherToken).statusCode()).isIn(401,503);
            String learnerToken=login(email,password);
            assertThat(send("GET","/api/v1/users/me",null,teacherToken).statusCode()).isEqualTo(401);
            assertThat(send("GET","/api/v1/courses/"+first.id(),null,null).statusCode()).isEqualTo(409);
            assertThat(send("GET","/api/v1/learning/attempts/"+attempt+"/draft",null,learnerToken).statusCode()).isEqualTo(409);
            assertThat(send("PUT","/api/v1/learning/attempts/"+attempt+"/draft",Map.of("clientRequestId",UUID.randomUUID(),"expectedRevision",examDraft.path("revision").asLong(),"answers",List.of()),learnerToken).statusCode()).isEqualTo(409);
            assertThat(fixtures.learning.queryForObject("SELECT close_status FROM assessment WHERE id=?",String.class,exam)).isEqualTo("OPEN");
            TimeUnit.MILLISECONDS.sleep(250);
            reset(client);
            JsonNode restore=change(student,"RESTORE_TEACHING",adminToken);
            assertThat(restore.path("suspensionId").asString()).isEqualTo(revoke.path("suspensionId").asString());
            await(()->commandStatus(restore,adminToken).equals("APPLIED"),20);
            String restoredToken=login(email,password);
            await(()->fixtures.learning.queryForObject("SELECT COUNT(*) FROM course_availability_consumed_event WHERE course_id=?",Long.class,first.id())>=2,20);
            var interval=fixtures.course.queryForMap("SELECT suspended_at,resumed_at FROM course_suspension_interval WHERE course_id=?",first.id());
            Duration paused=Duration.between(((Timestamp)interval.get("suspended_at")).toInstant(),((Timestamp)interval.get("resumed_at")).toInstant());
            body(send("GET","/api/v1/learning/attempts/"+attempt+"/draft",null,restoredToken),200);
            assertThat(fixtures.learning.queryForObject("SELECT deadline_at FROM assessment_attempt WHERE id=?",Timestamp.class,attempt).toInstant()).isEqualTo(deadline.plus(paused));
            assertThat(fixtures.tasks.assessment(homework).orElseThrow().lateUntil()).isEqualTo(late.plus(paused));
            assertThat(fixtures.course.queryForObject("SELECT COUNT(*) FROM course_suspension_interval WHERE course_id=?",Long.class,draft.id())).isZero();
            // Broker重投同一事件并迟到旧暂停；Learning必须按eventId幂等且实时读Course，不能重复补时。
            String event=fixtures.course.queryForObject("SELECT payload_json FROM outbox_event WHERE aggregate_id=? AND event_type='CourseAvailabilityChanged' ORDER BY created_at LIMIT 1",String.class,first.id());
            rabbit.convertAndSend("edu.course.availability.events.v1","course.availability.changed.v1",event);
            rabbit.convertAndSend("edu.course.availability.events.v1","course.availability.changed.v1",event);
            TimeUnit.MILLISECONDS.sleep(500);
            assertThat(fixtures.learning.queryForObject("SELECT deadline_at FROM assessment_attempt WHERE id=?",Timestamp.class,attempt).toInstant()).isEqualTo(deadline.plus(paused));
            // Course无确认连续失败：真实持久命令及后台预算，不回滚撤权、不误标APPLIED。
            doThrow(new IllegalStateException("controlled-course-timeout")).when(client).apply(eq(student),anyString(),anyLong(),eq(true),anyString());
            JsonNode failed=change(student,"REVOKE_TEACHING",adminToken);
            await(()->commandStatus(failed,adminToken).equals("FAILED"),20);
            for(int attempts=3;attempts<6;attempts++) {
                var retry=Map.of("clientRequestId",UUID.randomUUID(),"expectedAttempts",attempts);
                String path="/api/v1/admin/permission-commands/"+failed.path("commandId").asString()+"/retry";
                body(send("POST",path,retry,adminToken),202);
                assertThat(body(send("POST",path,retry,adminToken),202).path("replayed").asBoolean()).isTrue();
                await(()->commandStatus(failed,adminToken).equals("FAILED"),15);
            }
            JsonNode exhausted=body(send("GET","/api/v1/admin/permission-commands/"+failed.path("commandId").asString(),null,adminToken),200);
            assertThat(exhausted.path("attempts").asInt()).isEqualTo(6);
            assertThat(exhausted.path("coordinationStatus").asString()).isEqualTo("FAILED");
            assertThat(send("POST","/api/v1/admin/permission-commands/"+failed.path("commandId").asString()+"/retry",Map.of("clientRequestId",UUID.randomUUID(),"expectedAttempts",5),adminToken).statusCode()).isEqualTo(409);
            reset(client);
            smtpAndTransactionScenario(iam,student,admin,adminToken,password);
            consumeOwnPermissionEvents(iam,rabbit,List.of(student,admin));
            await(()->fixtures.course.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN (?,?) AND status<>'PUBLISHED'",Long.class,first.id(),second.id())==0,20);
            await(()->fixtures.learning.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id IN(SELECT id FROM enrollment WHERE learner_id=?) AND status<>'PUBLISHED'",Long.class,student)==0,20);
        }finally {
            // 只停本轮未完成协调，等待已领取的有界HTTP结束，防止清理后Worker重新创建暂停事实。
            iam.update("UPDATE account_permission_command SET coordination_status='FAILED' WHERE user_id=? AND coordination_status='PENDING'",student);
            TimeUnit.MILLISECONDS.sleep(3500);
            consumeOwnPermissionEvents(iam,rabbit,List.of(student,admin));
            fixtures.close();
            reset(client);
            for(String user:List.of(student,admin))redis.delete(iamContext.getBean(com.eduplatform.iam.config.IamProperties.class).sessionCache().keyPrefix()+":"+user);
        }
        System.out.println("T16.3真实Gateway/IAM/Course/Learning/MySQL/Redis/RabbitMQ/SMTP验收及精确领域清理通过。");
    }

    /** 真实SMTP发给用户确认的受控发件邮箱；只捕获本轮邮件秘密用于HTTP接受，不读取/输出凭据，事务失败不能留下管理员。 */
    private void smtpAndTransactionScenario(JdbcTemplate iam,String student,String admin,String adminToken,String password)throws Exception {
        AdminInvitationDelivery delivery=iamContext.getBean(AdminInvitationDelivery.class);
        IamMapper mapper=iamContext.getBean(IamMapper.class);
        String email=iamContext.getBean(com.eduplatform.iam.config.IamProperties.class).verification().smtpFrom();
        assertThat(mapper.findUserByIdentity("EMAIL",IdentityNormalizer.normalize("EMAIL",email))==null).as("受控SMTP收件邮箱不能转换已有账号").isTrue();
        AtomicReference<String> credential=new AtomicReference<>();
        doAnswer(call->{call.callRealMethod();credential.set(call.getArgument(2));return null;})
                .when(delivery).send(eq(IdentityNormalizer.normalize("EMAIL",email)),anyString(),anyString());
        JsonNode invite=body(send("POST","/api/v1/admin/invitations",Map.of("clientRequestId",UUID.randomUUID(),"email",email,"reason","真实SMTP受控管理员邀请验收"),adminToken),202);
        String id=invite.path("invitationId").asString();invitations.add(id);
        assertThat(invite.path("deliveryStatus").asString()).isEqualTo("SENT");
        assertThat(credential.get()!=null && credential.get().length()==43).as("SMTP只在内存捕获本轮凭证").isTrue();
        String old=credential.get();
        Map<String,Object> oldAccept=Map.of("invitationId",id,"email",email,"secret",old,"password",password,"displayName","t163_受控邀请测试");
        // 只推进本轮邀请的测试时钟；真实重发再次走SMTP并立即作废旧凭证。
        iam.update("UPDATE administrator_invitation SET expires_at=?,resend_after=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),Timestamp.from(Instant.now().minusSeconds(1)),id);
        assertThat(send("POST","/api/v1/auth/admin-invitations/accept",oldAccept,null).statusCode()).isEqualTo(422);
        var resend=Map.of("clientRequestId",UUID.randomUUID(),"expectedRevision",0,"reason","过期受控管理员邀请真实SMTP重发验收");
        JsonNode resent=body(send("POST","/api/v1/admin/invitations/"+id+"/resend",resend,adminToken),202);
        assertThat(resent.path("deliveryStatus").asString()).isEqualTo("SENT");
        assertThat(resent.path("sendAttempts").asInt()).isEqualTo(2);
        assertThat(send("POST","/api/v1/auth/admin-invitations/accept",oldAccept,null).statusCode()).isEqualTo(422);
        Map<String,Object> accept=Map.of("invitationId",id,"email",email,"secret",credential.get(),"password",password,"displayName","t163_受控邀请测试");
        doThrow(new IllegalStateException("controlled-invitation-transaction-failure")).when(mapper).insertSecurityAudit(anyString(),nullable(String.class),
                nullable(String.class),eq("ADMIN_INVITATION_ACCEPTED"),anyString(),anyString(),anyString(),any());
        assertThat(send("POST","/api/v1/auth/admin-invitations/accept",accept,null).statusCode()).isEqualTo(500);
        assertThat(mapper.findUserByIdentity("EMAIL",IdentityNormalizer.normalize("EMAIL",email))==null).as("事务回滚不能创建账号").isTrue();
        reset(mapper);
        JsonNode accepted=body(send("POST","/api/v1/auth/admin-invitations/accept",accept,null),201);
        String invited=accepted.path("userId").asString();invitedUsers.add(invited);
        rateSubjects.add(invited);
        assertThat(send("POST","/api/v1/auth/admin-invitations/accept",accept,null).statusCode()).isEqualTo(422);
        assertThat(send("POST","/api/v1/auth/admin-invitations/accept",accept,null).statusCode()).isEqualTo(429);
        String invitedToken=login(email,password);
        assertThat(send("GET","/api/v1/learning/enrollments",null,invitedToken).statusCode()).isEqualTo(403);
        assertThat(send("GET","/api/v1/teacher-applications/me",null,invitedToken).statusCode()).isEqualTo(403);
        assertThat(mapper.findRoles(invited)).containsExactly("ADMIN");
        assertThat(mapper.findPermissions(invited)).containsExactly("administration:access");
        assertThat(send("POST","/api/v1/admin/invitations",Map.of("clientRequestId",UUID.randomUUID(),"email",email,"reason","禁止已有账号邮箱再次转换管理员"),adminToken).statusCode()).isEqualTo(409);
        // 有效管理员禁止自封禁；两管理员并发封禁对方，至少一方实时授权失败。
        assertThat(send("POST","/api/v1/admin/users/"+admin+"/permission-previews",Map.of("action","BAN"),adminToken).statusCode()).isEqualTo(409);
        JsonNode a=body(send("POST","/api/v1/admin/users/"+invited+"/permission-previews",Map.of("action","BAN"),adminToken),201);
        JsonNode b=body(send("POST","/api/v1/admin/users/"+admin+"/permission-previews",Map.of("action","BAN"),invitedToken),201);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return send("POST","/api/v1/admin/users/"+invited+"/permission-commands",request(a,"BAN"),adminToken).statusCode();});
            var second=pool.submit(()->{start.await();return send("POST","/api/v1/admin/users/"+admin+"/permission-commands",request(b,"BAN"),invitedToken).statusCode();});
            start.countDown();List<Integer> results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(value->value==202).count()).isEqualTo(1);
            assertThat(iam.queryForObject("SELECT COUNT(*) FROM `user` WHERE id IN(?,?) AND status='ACTIVE'",Long.class,admin,invited)).isEqualTo(1);
        }finally {pool.shutdownNow();credential.set(null);reset(delivery);}
    }

    private JsonNode change(String user,String action,String token)throws Exception {
        JsonNode preview=body(send("POST","/api/v1/admin/users/"+user+"/permission-previews",Map.of("action",action),token),201);
        return body(send("POST","/api/v1/admin/users/"+user+"/permission-commands",request(preview,action),token),202);
    }

    /** Learning全量发布器关闭以保护既有异常索引记录；通过既有生产发布器逐个发布本轮精确报名事件，真实等待mandatory/confirm。 */
    private void publishOwnEnrollments(T163CrossDomainFixtures fixtures,RabbitTemplate rabbit,String learner) {
        var repository=new com.eduplatform.learning.persistence.QuestionRepository(fixtures.learning,json);
        // 复用真实连接工厂但隔离发布回调，不能覆盖 Media 发布器已经注册的返回处理。
        var enrollmentRabbit=new RabbitTemplate(rabbit.getConnectionFactory());
        var publisher=new com.eduplatform.learning.messaging.LearningOutboxPublisher(repository,enrollmentRabbit,
                new com.eduplatform.learning.config.LearningProperties(null,null,null,null));
        for(var course:fixtures.courses) {
            for(String enrollment:fixtures.learning.queryForList("SELECT id FROM enrollment WHERE learner_id=? AND course_id=?",String.class,learner,course.id())) {
                var events=fixtures.learning.query("SELECT * FROM learning_outbox_event FORCE INDEX(PRIMARY) WHERE aggregate_id=? AND status='PENDING'",
                        (rs,n)->new com.eduplatform.learning.persistence.QuestionRepository.Outbox(rs.getString("event_id"),rs.getString("event_type"),
                                rs.getInt("event_version"),rs.getString("aggregate_id"),rs.getString("exchange_name"),rs.getString("routing_key"),
                                rs.getString("trace_id"),rs.getString("payload_json"),rs.getString("status"),rs.getInt("attempts"),
                                rs.getTimestamp("next_attempt_at").toInstant(),rs.getString("last_error"),rs.getTimestamp("occurred_at").toInstant(),
                                rs.getTimestamp("published_at")==null?null:rs.getTimestamp("published_at").toInstant(),rs.getTimestamp("updated_at").toInstant()),enrollment);
                for(var event:events)publisher.publish(event);
            }
        }
    }
    private Map<String,Object> request(JsonNode preview,String action) {
        return Map.of("clientRequestId",UUID.randomUUID(),"previewId",preview.path("previewId").asString(),
                "expectedRevision",preview.path("expectedRevision").asLong(),"action",action,"reason","真实验收明确确认影响和账号变更原因","confirmed",true);
    }
    private String login(String email,String password)throws Exception {return body(send("POST","/api/v1/auth/login",Map.of("identity",email,"password",password),null),200).path("accessToken").asString();}
    private HttpResponse<String> send(String method,String path,Object value,String token)throws Exception {
        // SMTP连接、TLS和提交各有既有5秒超时；验收客户端留出完整发送窗口，Course内部调用仍严格3秒。
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080"+path)).timeout(Duration.ofSeconds(45))
                .header("X-Trace-Id",trace).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.method(method,value==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response,int... allowed) {
        assertThat(response.statusCode()).as("真实HTTP状态；不打印凭证或响应正文").isIn(Arrays.stream(allowed).boxed().toArray());
        return json.readTree(response.body());
    }
    private String commandStatus(JsonNode command,String token) {
        try {return body(send("GET","/api/v1/admin/permission-commands/"+command.path("commandId").asString(),null,token),200).path("coordinationStatus").asString();}
        catch(Exception failure){throw new IllegalStateException("联动状态读取失败");}
    }
    private void await(java.util.function.BooleanSupplier condition,int seconds)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(!condition.getAsBoolean() && System.nanoTime()<until)TimeUnit.MILLISECONDS.sleep(100);
        assertThat(condition.getAsBoolean()).as("真实联动有界等待").isTrue();
    }

    /** 只ACK本轮已确认事件ID，遇到其他消息立刻requeue，不清共享队列。 */
    private void consumeOwnPermissionEvents(JdbcTemplate iam,RabbitTemplate rabbit,List<String> users)throws Exception {
        eventIds.clear();
        List<String> ids=new ArrayList<>(users);ids.addAll(invitedUsers);
        for(String user:ids) {
            await(()->iam.queryForObject("SELECT COUNT(*) FROM iam_outbox_event WHERE aggregate_id=? AND status<>'PUBLISHED'",Long.class,user)==0,20);
            eventIds.addAll(iam.queryForList("SELECT event_id FROM iam_outbox_event WHERE aggregate_id=? AND event_type='AccountPermissionChanged'",String.class,user)
                    .stream().filter(id->!acknowledged.contains(id)).toList());
        }
        Set<String> own=new HashSet<>(eventIds);
        for(int i=0;i<eventIds.size();i++) {
            Boolean consumed=rabbit.execute(channel->{
                var message=channel.basicGet("edu.iam.account-permissions.v1",false);
                if(message==null)return false;
                String id=json.readTree(new String(message.getBody(),StandardCharsets.UTF_8)).path("eventId").asString();
                if(!own.remove(id)){channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true);throw new IllegalStateException("不得消费无关权限事件");}
                channel.basicAck(message.getEnvelope().getDeliveryTag(),false);return true;
            });
            assertThat(consumed).isTrue();
        }
        assertThat(own).isEmpty();
        acknowledged.addAll(eventIds);
    }

    /** 精确清理本轮邀请、受邀账号及相关缓存；中途失败也按accepted_by读取本轮可能已提交的账号。 */
    private void cleanupInvitations(JdbcTemplate iam) {
        for(String id:invitations) {
            var accepted=iam.queryForList("SELECT accepted_by FROM administrator_invitation WHERE id=? AND accepted_by IS NOT NULL",String.class,id);
            for(String user:accepted)if(!invitedUsers.contains(user))invitedUsers.add(user);
            iam.update("DELETE FROM administrator_invitation_command WHERE invitation_id=?",id);
            iam.update("DELETE FROM administrator_invitation WHERE id=?",id);
            iam.update("DELETE FROM account_security_audit WHERE reason LIKE ?","%"+id);
        }
        for(String user:invitedUsers) {
            iam.update("DELETE FROM iam_outbox_event WHERE aggregate_id=?",user);
            iam.update("DELETE FROM account_permission_retry WHERE command_id IN(SELECT id FROM account_permission_command WHERE user_id=?)",user);
            iam.update("DELETE FROM account_permission_command WHERE user_id=?",user);
            iam.update("DELETE FROM account_permission_preview WHERE user_id=? OR actor_id=?",user,user);
            iam.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?",user,user);
            iam.update("DELETE FROM refresh_session WHERE user_id=? AND parent_id IS NOT NULL",user);
            iam.update("DELETE FROM refresh_session WHERE user_id=?",user);
            iam.update("DELETE FROM login_audit WHERE user_id=?",user);
            iam.update("DELETE FROM user_identity WHERE user_id=?",user);
            iam.update("DELETE FROM user_role WHERE user_id=?",user);
            iam.update("DELETE FROM `user` WHERE id=?",user);
            iamContext.getBean(StringRedisTemplate.class).delete(iamContext.getBean(com.eduplatform.iam.config.IamProperties.class).sessionCache().keyPrefix()+":"+user);
        }
    }

    /** 只删除验收专属限流namespace下的已知策略Key；不扫描/清空Redis或影响真实用户限流。 */
    private void clearRateKeys(StringRedisTemplate redis) {
        String prefix=System.getenv("GATEWAY_RATE_LIMIT_KEY_PREFIX");
        if(prefix==null || !prefix.startsWith("edu:gateway:rate:t163:"))throw new IllegalStateException("需要验收专属限流前缀");
        for(String policy:List.of("login","upload-credential","admin-invitation-accept","admin-invitation-send")) {
            redis.delete(prefix+":"+policy+":"+digest("ip:127.0.0.1"));
            for(String user:rateSubjects)redis.delete(prefix+":"+policy+":"+digest("user:"+user));
        }
        // 用户ID策略在本例已删除账号ID集合上精准计算，不按通配符批量清理。
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("摘要算法不可用");}
    }
}
