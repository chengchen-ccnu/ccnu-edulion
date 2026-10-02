package com.eduplatform.operation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.eduplatform.operation.domain.NotificationDomains;
import com.eduplatform.operation.messaging.NotificationRabbitConfig;
import com.eduplatform.operation.messaging.OperationOutboxPublisher;
import com.eduplatform.operation.service.NotificationDelivery;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.*;

/**
 * 显式专用开发库长链验收：实际登录Gateway，再调用Operation和领域HTTP，使用真实MySQL/RabbitMQ。
 * Mockito仅选择故障发生点：坏Learning端口仍走真实HTTP，unroutable仍由真实Broker return；不伪造成功事实。
 * 随机Fixture、独立队列、精确ID清理；已有Outbox/人工课程只在内存比较，不输出业务正文或秘密。
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
        "server.port=18085","OPERATION_OUTBOX_POLL_DELAY=200ms","OPERATION_DELIVERY_POLL_DELAY=200ms",
        "OPERATION_OUTBOX_MAX_ATTEMPTS=3","platform.operation.messaging-enabled=true"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="T164_REAL_ACCEPTANCE_ENABLED",matches="true")
class T164NotificationsRealAcceptanceTest {
    @Autowired JdbcTemplate operation;
    @Autowired ObjectMapper json;
    @Autowired NotificationDelivery delivery;
    @Autowired(required=false) OperationOutboxPublisher publisher;
    @Autowired(required=false) org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @MockitoSpyBean NotificationDomains domains;
    @MockitoSpyBean RabbitTemplate rabbit;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String owner=id(),student=id(),late=id(),outsider=id(),admin=id(),course=id(),version=id(),chapter=id(),lesson=id();
    String application=id(),round=id(),fakeAsset=id();
    final List<String> users=new ArrayList<>(List.of(owner,student,late,outsider,admin));
    final List<String> learners=new ArrayList<>(),assessments=new ArrayList<>();
    final Set<String> events=new HashSet<>();
    final AtomicReference<String> brokenCourse=new AtomicReference<>(),returnedEvent=new AtomicReference<>();
    String ownerToken,studentToken,lateToken,outsiderToken,adminToken;
    JdbcTemplate iam,learning,courses;
    String queue=System.getenv("OPERATION_NOTIFICATION_QUEUE");
    Process restartedLearning;
    /** Cleanup仅连接本域数据库和RabbitAdmin，不启动投递/发布/消费者，避免清理时重放任何历史或中断工作。 */
    @org.springframework.test.context.DynamicPropertySource
    static void cleanupProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("platform.operation.messaging-enabled",()->!"true".equals(System.getenv("T164_CLEANUP_ONLY")));
    }

    /** 覆盖版本确认、并发冲突、触发时收件人、真实到时/暂停补时、故障预算、DLQ与精确清理。 */
    @Test void gatewayDomainsMysqlRabbitFaultsAndCleanup()throws Exception {
        iam=db("iam");learning=db("learning");courses=db("course");
        assertThat(operation.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("edu_platform_operation_dev");
        if("true".equals(System.getenv("T164_CLEANUP_ONLY"))) {
            recoverManifest();collectEvents();cleanup();cleanQueue();System.out.println("T16.4 interrupted exact-ID fixture cleanup complete.");return;
        }
        var historyLearning=learning.queryForList("SELECT * FROM learning_outbox_event");
        var historyCourse=courses.queryForList("SELECT * FROM outbox_event");
        var historyIam=iam.queryForList("SELECT * FROM iam_outbox_event");
        var manual=courses.queryForList("SELECT * FROM course WHERE id='5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057'");
        RabbitAdmin broker=new RabbitAdmin(rabbit);
        List<String> shared=List.of("edu.course.enrollment-access.v1","edu.learning.availability.v1","edu.iam.teacher-review.decisions.v1");
        for(String name:shared) {
            var state=broker.getQueueProperties(name);
            if(state!=null) {
                assertThat(((Number)state.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue()).as("shared queue must be empty before isolated acceptance").isZero();
                assertThat(((Number)state.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).intValue()).as("do not compete with an existing consumer").isZero();
            }
        }
        saveManifest();Throwable primary=null;
        try {
            fixture();login();faultSelectors();
            internalBoundary();
            String teach="/api/v1/teacher/courses/"+course+"/announcements",read="/api/v1/courses/"+course+"/announcements";
            status("GET",read,null,null,401);status("GET",read,null,outsiderToken,404);
            status("POST",teach,Map.of("clientRequestId",id(),"title","越权","body","测试"),studentToken,403);
            status("POST",teach,Map.of("clientRequestId",id(),"title","管理员代发","body","测试"),adminToken,403);
            status("POST",teach,Map.of("clientRequestId",id(),"title","非owner","body","测试"),outsiderToken,404);
            JsonNode validation=request("POST",teach,Map.of("clientRequestId",id(),"title","","body",""),ownerToken,422);
            assertThat(validation.path("fieldErrors").toString()).contains("不能为空白").doesNotContain("must not");
            JsonNode a=request("POST",teach,Map.of("clientRequestId",id(),"title","中文公告","body","中文纯文本\n第二行"),ownerToken,201);
            String ann=a.path("id").asString();
            assertThat(request("GET","/api/v1/courses/"+course,null,null,200).toString()).doesNotContain("中文公告","中文纯文本");
            await(()->messages(course,"COURSE_ANNOUNCEMENT")==103);
            assertThat(operation.queryForObject("SELECT COUNT(*) FROM notification_delivery_work WHERE course_id=? AND status='COMPLETE'",Long.class,course)).isPositive();
            assertThat(counts().path("unreadCount").asInt()).isEqualTo(1);
            request("POST","/api/v1/notifications/read-all",null,studentToken,200);
            assertThat(counts().path("unreadCount").asInt()).isEqualTo(1);
            String message=operation.queryForObject("SELECT id FROM notification_message WHERE recipient_id=? AND target_id=?",String.class,student,ann);
            status("POST","/api/v1/notifications/"+message+"/read",Map.of("expectedRevision",1),studentToken,409);
            request("POST",read+"/"+ann+"/confirm",Map.of("clientRequestId",id(),"expectedRevision",1),studentToken,200);
            assertThat(counts().path("unreadCount").asInt()).isZero();
            // 相同旧版的两个并发编辑只允许一个提交；真实MySQL锁保护审计、消息和Outbox。
            var pool=Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> edit=()->send("PUT",teach+"/"+ann,Map.of("clientRequestId",id(),"expectedRevision",1,"title","并发新版","body","新正文"),ownerToken).statusCode();
                var results=pool.invokeAll(List.of(edit,edit));assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(200,409);
            } finally { pool.shutdownNow(); }
            status("POST",read+"/"+ann+"/confirm",Map.of("clientRequestId",id(),"expectedRevision",1),studentToken,409);
            await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_delivery_work WHERE course_id=? AND revision=2 AND status='COMPLETE'",Long.class,course)>0);
            request("POST",read+"/"+ann+"/confirm",Map.of("clientRequestId",id(),"expectedRevision",2),studentToken,200);
            replayOld(ann);assertThat(counts().path("unreadCount").asInt()).isZero();
            request("POST","/api/v1/learning/enrollments",Map.of("courseId",course),lateToken,201);
            request("GET",read+"/"+ann,null,lateToken,200);
            assertThat(request("GET","/api/v1/notifications",null,lateToken,200).path("total").asInt()).isZero();
            String enrollment=learning.queryForObject("SELECT id FROM enrollment WHERE learner_id=? AND course_id=?",String.class,student,course);
            request("DELETE","/api/v1/learning/enrollments/"+enrollment,null,studentToken,204);
            status("GET",read+"/"+ann,null,studentToken,404);assertThat(counts().path("unreadCount").asInt()).isZero();
            request("PUT",teach+"/"+ann,Map.of("clientRequestId",id(),"expectedRevision",2,"title","退课时改版","body","第三版"),ownerToken,200);
            request("POST","/api/v1/learning/enrollments",Map.of("courseId",course),studentToken,200);
            assertThat(counts().path("unreadCount").asInt()).isZero();
            request("POST",teach+"/"+ann+"/withdraw",Map.of("clientRequestId",id(),"expectedRevision",3),ownerToken,200);
            status("GET",read+"/"+ann,null,studentToken,404);status("GET","/api/v1/notifications/"+message+"/target",null,studentToken,404);
            taskScenarios();reviewScenario();failureScenarios(teach,read);
            System.out.println("T16.4 real Gateway/Operation/Course/Learning/IAM + MySQL/RabbitMQ assertions passed.");
        } catch(Exception | AssertionError failure) {primary=failure;throw failure;} finally {
            try {
            brokenCourse.set(null);returnedEvent.set(null);
            // 只收集本轮精确聚合的事件，ACK共享队列中的本轮副本；任何其他事件保持requeue并停止清理。
            try {collectEvents();for(String name:shared) ackOwn(name);cleanup();cleanQueue();}
            catch(RuntimeException | AssertionError cleanupFailure){if(primary!=null){primary.addSuppressed(cleanupFailure);}else throw cleanupFailure;}
            assertThat(learning.queryForList("SELECT * FROM learning_outbox_event").equals(historyLearning)).as("all historical Learning events preserved").isTrue();
            assertThat(courses.queryForList("SELECT * FROM outbox_event").equals(historyCourse)).as("all historical Course events preserved").isTrue();
            assertThat(iam.queryForList("SELECT * FROM iam_outbox_event").equals(historyIam)).as("all historical IAM events preserved").isTrue();
            assertThat(courses.queryForList("SELECT * FROM course WHERE id='5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057'").equals(manual)).as("manual linear algebra course unchanged").isTrue();
            System.out.println("T16.4 exact-ID fixture cleanup complete; histories and manual course preserved.");
            } finally {if(restartedLearning!=null && restartedLearning.isAlive()){restartedLearning.destroy();if(!restartedLearning.waitFor(10,TimeUnit.SECONDS))restartedLearning.destroyForcibly();}}
        }
    }

    /** 仅测试Fixture直写各域各自开发库；正式业务协作仍仅经API/事件，无跨Schema查询。 */
    private void fixture() {
        String hash=new BCryptPasswordEncoder().encode(password);
        for(String u:users) {
            iam.update("INSERT INTO `user`(id,account,display_name,password_hash,status) VALUES(?,?,?,?,'ACTIVE')",u,"t164_"+u.substring(0,12),"合成通知测试",hash);
            iam.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM `role` WHERE code=?",u,u.equals(admin)?"ADMIN":"STUDENT");
            if(u.equals(owner)||u.equals(outsider))iam.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM `role` WHERE code='TEACHER'",u);
            iam.update("INSERT INTO user_identity(id,user_id,identity_type,normalized_value,verified_at,created_at) VALUES(?,?,'EMAIL',?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",id(),u,email(u));
        }
        String category=courses.queryForObject("SELECT id FROM category WHERE status='ACTIVE' ORDER BY id LIMIT 1",String.class);
        courses.update("INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status,published_at) VALUES(?,?,?,'PUBLISHED',CURRENT_TIMESTAMP(6))",course,id(),owner);
        courses.update("INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,description,cover_url,instructor_display_name,category_id,level,access_type) VALUES(?,?,1,'v1','PUBLISHED','t164_course_test','test','test','','teacher_test_164',?,'BEGINNER','PUBLIC')",version,course,category);
        courses.update("UPDATE course SET current_draft_version_id=?,published_version_id=? WHERE id=?",version,version,course);
        courses.update("INSERT INTO chapter(id,course_version_id,title,position) VALUES(?,?,'test',1)",chapter,version);
        courses.update("INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,content_revision_id) VALUES(?,?,'test_metadata','VIDEO',1,FALSE,?)",lesson,chapter,lesson);
        learners.add(student);for(int i=0;i<102;i++)learners.add(id());
        for(String u:learners) {
            String enrollment=id();
            learning.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",enrollment,u,course,version,Timestamp.from(Instant.now().minusSeconds(30)));
            learning.update("INSERT INTO notification_enrollment_interval(id,enrollment_id,course_id,learner_id,starts_at) VALUES(?,?,?,?,?)",id(),enrollment,course,u,Timestamp.from(Instant.now().minusSeconds(30)));
        }
    }
    final String password=UUID.randomUUID().toString();
    private String email(String u){return "t164-"+u+"@example.invalid";}
    private void login()throws Exception {
        ownerToken=token(owner);studentToken=token(student);lateToken=token(late);outsiderToken=token(outsider);adminToken=token(admin);
    }
    private String token(String u)throws Exception {return request("POST","/api/v1/auth/login",Map.of("identity",email(u),"password",password),null,200).path("accessToken").asString();}

    /** 坏端口和错误路由仅选中本轮精确ID；故障最终由HTTP连接失败和Broker mandatory return产生。 */
    private void faultSelectors() {
        NotificationDomains bad=new NotificationDomains(json,"http://127.0.0.1:18082","http://127.0.0.1:1","http://127.0.0.1:18081",System.getenv("OPERATION_INTERNAL_SERVICE_TOKEN"));
        doAnswer(inv->brokenCourse.get()!=null && ((List<?>)inv.getArgument(1)).contains(brokenCourse.get())?bad.access(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2),inv.getArgument(3)):inv.callRealMethod()).when(domains).access(anyString(),anyList(),anyList(),anyList());
        doAnswer(inv->Objects.equals(brokenCourse.get(),inv.getArgument(0))?bad.recipients(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2)):inv.callRealMethod()).when(domains).recipients(anyString(),any(),anyString());
        doAnswer(inv->{Message m=inv.getArgument(2);if(Objects.equals(returnedEvent.get(),m.getMessageProperties().getMessageId()) && "operation.announcement.changed.v1".equals(inv.getArgument(1))) {rabbit.send(inv.getArgument(0),"t164.unroutable",m,inv.getArgument(3));return null;}return inv.callRealMethod();}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    private long messages(String c,String kind){return operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE course_id=? AND kind=?",Long.class,c,kind);}
    private JsonNode counts(){try{return request("GET","/api/v1/notifications/unread-count",null,studentToken,200);}catch(Exception e){throw new RuntimeException(e);}}

    /** 重投同ID及新ID旧版经过真实MQ；不会覆盖新版确认。 */
    private void replayOld(String ann)throws Exception {
        String old=operation.queryForObject("SELECT payload_json FROM operation_outbox_event WHERE aggregate_id=? AND JSON_EXTRACT(payload_json,'$.payload.revision')=1 LIMIT 1",String.class,ann);
        rabbit.convertAndSend(NotificationRabbitConfig.EXCHANGE,"operation.announcement.changed.v1",old);
        var node=(tools.jackson.databind.node.ObjectNode)json.readTree(old);String replay=id();events.add(replay);node.put("eventId",replay);
        rabbit.convertAndSend(NotificationRabbitConfig.EXCHANGE,"operation.announcement.changed.v1",json.writeValueAsString(node));
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_delivery_work WHERE event_id=? AND status='SUPPRESSED'",Long.class,replay)==1);
    }

    /** 未来发布不通知；真实服务器时间到期、暂停阻止触发、恢复沿用原CourseTaskClock顺延。 */
    private void taskScenarios()throws Exception {
        brokenCourse.set(course);
        String task=task("HOMEWORK",Instant.now().plusSeconds(8));
        assertThat(learning.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=? AND event_type='CourseTaskNotificationChanged'",Long.class,task)).isZero();
        restartLearning();
        await(()->learning.queryForObject("SELECT status FROM assessment_notification_opening WHERE assessment_id=?",String.class,task).equals("OPENED"));
        request("POST","/api/v1/learning/enrollments",Map.of("courseId",course),outsiderToken,201);
        brokenCourse.set(null);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=?",Long.class,task)>0);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_delivery_work WHERE target_id=? AND action='OPENED' AND status='COMPLETE'",Long.class,task)==1);
        assertThat(operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=? AND recipient_id=?",Long.class,task,outsider)).isZero();
        String taskMessage=operation.queryForObject("SELECT id FROM notification_message WHERE recipient_id=? AND target_id=? LIMIT 1",String.class,student,task);
        long rev=operation.queryForObject("SELECT revision FROM notification_message WHERE id=?",Long.class,taskMessage);
        request("GET","/api/v1/notifications/"+taskMessage+"/target",null,studentToken,200);
        long before=counts().path("unreadCount").asLong();request("POST","/api/v1/notifications/"+taskMessage+"/read",Map.of("expectedRevision",rev),studentToken,200);
        assertThat(counts().path("unreadCount").asLong()).isEqualTo(before-1);
        long revision=learning.queryForObject("SELECT revision FROM assessment WHERE id=?",Long.class,task);
        var deadline=new LinkedHashMap<String,Object>();deadline.put("expectedRevision",revision);deadline.put("closesAt",Instant.now().plusSeconds(7200));deadline.put("lateUntil",null);deadline.put("reason","合成截止变更验收");
        request("PUT","/api/v1/teacher/assessments/"+task+"/deadline",deadline,ownerToken,200);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=? AND action='DEADLINE_CHANGED'",Long.class,task)>0);
        revision=learning.queryForObject("SELECT revision FROM assessment WHERE id=?",Long.class,task);
        request("POST","/api/v1/teacher/assessments/"+task+"/closure",Map.of("clientRequestId",id(),"expectedRevision",revision),ownerToken,200);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=? AND action='ENDED'",Long.class,task)>0);
        long openedCount=operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=? AND action='OPENED'",Long.class,task);
        String opened=learning.queryForObject("SELECT payload_json FROM learning_outbox_event WHERE aggregate_id=? AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.payload.action'))='OPENED'",String.class,task);
        var delayed=(tools.jackson.databind.node.ObjectNode)json.readTree(opened);String delayedId=id();events.add(delayedId);delayed.put("eventId",delayedId);
        rabbit.convertAndSend("edu.learning.task-notifications.events.v1","learning.task.notification.changed.v1",json.writeValueAsString(delayed));
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_delivery_work WHERE event_id=? AND status='SUPPRESSED'",Long.class,delayedId)==1);
        assertThat(operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=? AND action='OPENED'",Long.class,task)).isEqualTo(openedCount);
        String practice=task("PRACTICE",null);
        assertThat(learning.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=? AND event_type='CourseTaskNotificationChanged'",Long.class,practice)).isZero();
        String exam=task("EXAM",Instant.now().plusSeconds(5));
        Instant original=learning.queryForObject("SELECT opens_at FROM assessment WHERE id=?",Timestamp.class,exam).toInstant();
        String cycle=id();availability(cycle,1,true);TimeUnit.SECONDS.sleep(6);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE course_id=? AND recipient_id=? AND action='PAUSED'",Long.class,course,student)==1);
        String pauseMessage=operation.queryForObject("SELECT id FROM notification_message WHERE course_id=? AND recipient_id=? AND action='PAUSED'",String.class,course,student);
        assertThat(request("GET","/api/v1/notifications/"+pauseMessage+"/target",null,studentToken,200).path("paused").asBoolean()).isTrue();
        assertThat(learning.queryForObject("SELECT status FROM assessment_notification_opening WHERE assessment_id=?",String.class,exam)).isEqualTo("PENDING");
        status("GET","/api/v1/courses/"+course+"/announcements",null,studentToken,404);
        availability(cycle,2,false);
        await(()->learning.queryForObject("SELECT status FROM assessment_notification_opening WHERE assessment_id=?",String.class,exam).equals("OPENED"));
        assertThat(learning.queryForObject("SELECT opens_at FROM assessment WHERE id=?",Timestamp.class,exam).toInstant()).isAfter(original.plusSeconds(5));
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE course_id=? AND kind='COURSE_AVAILABILITY'",Long.class,course)>0);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_delivery_work WHERE target_id=? AND action='OPENED' AND status='COMPLETE'",Long.class,exam)==1);
    }
    /** 三域独立秘密/固定caller/上限100均由真实HTTP验证；Gateway不得暴露内部名单。 */
    private void internalBoundary()throws Exception {
        for(int port:List.of(18081,18082,18084)) {
            String path=port==18081?"/internal/v1/notification-review-access":port==18082?"/internal/v1/notification-courses":"/internal/v1/notification-current-recipients";
            Object body=port==18081?Map.of("userId",student,"applicationIds",List.of(application)):port==18082?Map.of("ids",List.of(course)):Map.of("courseId",course,"learnerIds",List.of(student));
            var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("X-Internal-Caller","iam-service").header("X-Internal-Service-Token",System.getenv("OPERATION_INTERNAL_SERVICE_TOKEN")).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            assertThat(http.send(b.build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
        }
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082/internal/v1/notification-courses")).header("Content-Type","application/json").header("X-Internal-Caller","operation-service").header("X-Internal-Service-Token",System.getenv("OPERATION_INTERNAL_SERVICE_TOKEN")).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("ids",java.util.Collections.nCopies(101,course)))));
        assertThat(http.send(b.build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(422);
        status("POST","/internal/v1/notification-courses",Map.of("ids",List.of(course)),ownerToken,404);
    }
    /** 销毁本轮脚本启动的Learning JVM并重启同一服务；到期工作必须从MySQL恢复，不丢触发或补后来报名者。 */
    private void restartLearning()throws Exception {
        long pid=Long.parseLong(System.getenv("T164_LEARNING_PID"));
        ProcessHandle old=ProcessHandle.of(pid).orElseThrow();
        // Windows ProcessHandle不保证提供commandLine；仅输出退出码核对本轮脚本PID对应的实际jar，不打印参数。
        var check=new ProcessBuilder("pwsh","-NoProfile","-Command","$taskProcess=Get-CimInstance Win32_Process -Filter 'ProcessId="+pid+"'; if($taskProcess.CommandLine -notlike '*learning-service-0.1.0-SNAPSHOT.jar*'){exit 1}").start();
        if(!check.waitFor(10,TimeUnit.SECONDS)||check.exitValue()!=0)throw new IllegalStateException("Not acceptance Learning process");
        old.destroy();old.onExit().get(10,TimeUnit.SECONDS);
        var root=java.nio.file.Path.of("../..").toAbsolutePath().normalize();
        var command=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-jar",root.resolve("java-platform/learning-service/target/learning-service-0.1.0-SNAPSHOT.jar").toString(),"--spring.profiles.active=local","--server.port=18084","--platform.learning.import.consumer-enabled=false","--platform.learning.assessment-settlement-poll-delay=1h");
        command.directory(root.toFile());command.redirectErrorStream(true).redirectOutput(root.resolve(".m2/t16-4-acceptance/learning-restarted.log").toFile());restartedLearning=command.start();
        await(()->{try{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18084/actuator/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception e){return false;}},40000);
    }
    private String task(String type,Instant opens)throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("clientRequestId",id());body.put("courseId",course);body.put("lessonId",null);body.put("title","t164_task_test");body.put("description","test");body.put("type",type);body.put("gradePublicationPolicy","MANUAL");body.put("answerPublicationPolicy","NEVER");body.put("opensAt",opens);body.put("closesAt",type.equals("PRACTICE")?null:Instant.now().plusSeconds(1800));body.put("latePolicy","DISALLOW");body.put("lateUntil",null);body.put("maxAttempts",1);body.put("timeLimitSeconds",type.equals("EXAM")?600:null);
        JsonNode draft=request("POST","/api/v1/teacher/assessments",body,ownerToken,201);String task=draft.path("id").asString();assessments.add(task);
        String question=learning.queryForObject("SELECT q.current_version_id FROM question_aggregate q WHERE q.scope='SYSTEM' AND q.status='ACTIVE' AND q.current_version_id IS NOT NULL ORDER BY q.id LIMIT 1",String.class);
        JsonNode replaced=request("PUT","/api/v1/teacher/assessments/"+task+"/questions",Map.of("expectedRevision",draft.path("revision").asLong(),"questions",List.of(Map.of("questionVersionId",question,"sequence",1,"points",1))),ownerToken,200);
        request("PUT","/api/v1/teacher/assessments/"+task+"/publication",Map.of("expectedRevision",replaced.path("revision").asLong(),"status","PUBLISHED"),ownerToken,200);return task;
    }
    private void availability(String cycle,long revision,boolean paused)throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082/internal/v1/teachers/"+owner+"/course-availability")).header("X-Internal-Caller","iam-service").header("X-Internal-Service-Token",System.getenv("COURSE_SUSPENSION_IAM_TOKEN")).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("suspensionId",cycle,"accessRevision",revision,"paused",paused))));
        assertThat(http.send(req.build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
    /** 最小申请Fixture不申请素材预览或伪造批准；真实IAM驳回命令产生结果事件，消息不可包含完整理由/材料ID。 */
    private void reviewScenario()throws Exception {
        iam.update("INSERT INTO teacher_application(id,user_id,qualification_media_id,statement,status,submitted_at,updated_at) VALUES(?,?,?,'合成申请','UNDER_REVIEW',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",application,student,fakeAsset);
        iam.update("INSERT INTO teacher_application_round(id,application_id,round_number,qualification_media_id,statement,status,submitted_at,first_viewed_by,first_viewed_at,bound_enqueued_at) VALUES(?,?,1,?,'合成申请','UNDER_REVIEW',CURRENT_TIMESTAMP(6),?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",round,application,fakeAsset,admin);
        request("POST","/api/v1/admin/teacher-applications/"+application+"/decision",Map.of("clientRequestId",id(),"expectedRevision",0,"decision","REJECTED","materialConfirmed",false,"rejectionReason","合成驳回理由仅本人审核详情可见"),adminToken,200);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=?",Long.class,application)==1);
        String result=request("GET","/api/v1/notifications",null,studentToken,200).toString();assertThat(result).doesNotContain(fakeAsset,"合成驳回理由","qualificationMediaId","rejectionReason");
    }
    /** 真实HTTP故障不得返回假零/已读；持久fan-out耗尽5次、生产Outbox真实return耗尽3次；毒消息进入独立DLQ。 */
    private void failureScenarios(String teach,String read)throws Exception {
        brokenCourse.set(course);
        JsonNode failed=request("POST",teach,Map.of("clientRequestId",id(),"title","依赖失败测试","body","test"),ownerToken,201);
        String ann=failed.path("id").asString();
        status("GET","/api/v1/notifications/unread-count",null,studentToken,503);status("POST","/api/v1/notifications/read-all",null,studentToken,503);
        await(()->operation.queryForObject("SELECT COUNT(*) FROM notification_delivery_work WHERE target_id=? AND status='FAILED' AND attempts=5",Long.class,ann)==1,55000);
        brokenCourse.set(null);TimeUnit.MILLISECONDS.sleep(600);
        assertThat(operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=?",Long.class,ann)).isZero();
        String outbound=id();events.add(outbound);returnedEvent.set(outbound);
        // 只创建本轮专用失败运输Fixture，不改任何历史FAILED；实际业务事件已由此前场景验证同事务提交。
        String payload=json.writeValueAsString(Map.of("eventId",outbound,"eventType","CourseAnnouncementChanged","eventVersion",1,"aggregateId",ann,"occurredAt",Instant.now(),"traceId","t164-fault-trace","payload",Map.of("announcementId",ann,"courseId",course,"revision",1,"action","ANNOUNCEMENT","triggeredAt",Instant.now())));
        operation.update("INSERT INTO operation_outbox_event(event_id,aggregate_id,payload_json,next_attempt_at) VALUES(?,?,?,?)",outbound,ann,payload,Timestamp.from(Instant.now()));
        await(()->operation.queryForObject("SELECT status FROM operation_outbox_event WHERE event_id=?",String.class,outbound).equals("FAILED"));
        assertThat(operation.queryForObject("SELECT attempts FROM operation_outbox_event WHERE event_id=?",Integer.class,outbound)).isEqualTo(3);returnedEvent.set(null);publisher.publishPending();
        assertThat(operation.queryForObject("SELECT status FROM operation_outbox_event WHERE event_id=?",String.class,outbound)).isEqualTo("FAILED");
        String poison=id();events.add(poison);var bad=(tools.jackson.databind.node.ObjectNode)json.readTree(payload);bad.put("eventId",poison).put("eventVersion",2);
        rabbit.convertAndSend(NotificationRabbitConfig.EXCHANGE,"operation.announcement.changed.v1",json.writeValueAsString(bad));
        await(()->{var q=new RabbitAdmin(rabbit).getQueueProperties(queue+".dlq");return q!=null && ((Number)q.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue()==1;});ackOwn(queue+".dlq");
    }

    private JdbcTemplate db(String domain) {
        String schema="edu_platform_"+domain+"_dev";assertThat(System.getenv(domain.toUpperCase()+"_MYSQL_DATABASE")).isEqualTo(schema);
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://"+System.getenv("MYSQL_HOST")+":"+System.getenv("MYSQL_PORT")+"/"+schema+"?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true",System.getenv("MYSQL_USERNAME"),System.getenv("MYSQL_PASSWORD")));
    }
    private HttpResponse<String> send(String method,String path,Object body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080"+path)).timeout(Duration.ofSeconds(12));if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode request(String m,String p,Object b,String t,int expected)throws Exception {var r=send(m,p,b,t);assertThat(r.statusCode()).as(m+" "+p).isEqualTo(expected);return r.body().isBlank()?json.nullNode():json.readTree(r.body());}
    private void status(String m,String p,Object b,String t,int expected)throws Exception {request(m,p,b,t,expected);}
    private void await(BooleanSupplier ready)throws Exception {await(ready,30000);}
    private void await(BooleanSupplier ready,long ms)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);while(System.nanoTime()<end){if(ready.getAsBoolean())return;TimeUnit.MILLISECONDS.sleep(200);}throw new AssertionError("bounded real acceptance condition timed out");}
    private static String id(){return UUID.randomUUID().toString();}
    private void collectEvents() {
        events.addAll(operation.queryForList("SELECT event_id FROM operation_outbox_event WHERE aggregate_id IN (SELECT id FROM course_announcement WHERE course_id=?)",String.class,course));
        events.addAll(courses.queryForList("SELECT event_id FROM outbox_event WHERE aggregate_id=?",String.class,course));
        events.addAll(iam.queryForList("SELECT event_id FROM iam_outbox_event WHERE aggregate_id=?",String.class,application));
        for(String task:assessments)events.addAll(learning.queryForList("SELECT event_id FROM learning_outbox_event WHERE aggregate_id=?",String.class,task));
        var enrolled=learning.queryForList("SELECT id FROM enrollment WHERE course_id=?",String.class,course);for(String e:enrolled)events.addAll(learning.queryForList("SELECT event_id FROM learning_outbox_event WHERE aggregate_id=?",String.class,e));
    }
    /** 不purge公共队列；仅basicGet后核对eventId，ACK本轮副本，外来消息requeue并明确失败。 */
    private void ackOwn(String name) {
        if(new RabbitAdmin(rabbit).getQueueProperties(name)==null)return;
        rabbit.execute(channel->{for(int n=0;n<1000;n++){var response=channel.basicGet(name,false);if(response==null)return null;String event=json.readTree(new String(response.getBody(),StandardCharsets.UTF_8)).path("eventId").asString();if(!events.contains(event)){channel.basicNack(response.getEnvelope().getDeliveryTag(),false,true);throw new IllegalStateException("foreign event preserved; exact cleanup stopped");}channel.basicAck(response.getEnvelope().getDeliveryTag(),false);}throw new IllegalStateException("bounded queue cleanup exceeded");});
    }
    /** 所有DELETE都限定本轮精确UUID；迁移/表/Schema保留供后续启动，既有账号分类题目和历史事件保留。 */
    private void cleanup() {
        operation.update("DELETE FROM notification_message WHERE course_id=? OR target_id=?",course,application);
        operation.update("DELETE FROM notification_delivery_work WHERE course_id=? OR target_id=?",course,application);
        for(String e:events)operation.update("DELETE FROM operation_consumed_event WHERE event_id=?",e);
        operation.update("DELETE FROM operation_outbox_event WHERE aggregate_id IN (SELECT id FROM course_announcement WHERE course_id=?)",course);
        for(String u:users){operation.update("DELETE FROM operation_command WHERE actor_id=?",u);operation.update("DELETE FROM operation_actor_lock WHERE actor_id=?",u);}
        operation.update("DELETE FROM course_announcement_confirmation WHERE announcement_id IN (SELECT id FROM course_announcement WHERE course_id=?)",course);
        operation.update("DELETE FROM course_announcement_revision WHERE announcement_id IN (SELECT id FROM course_announcement WHERE course_id=?)",course);operation.update("DELETE FROM course_announcement WHERE course_id=?",course);
        for(String e:events)learning.update("DELETE FROM learning_outbox_event WHERE event_id=?",e);
        for(String task:assessments){
            for(String table:List.of("assessment_notification_opening","assessment_deadline_audit","assessment_question","assessment_participant","assessment_assignment"))learning.update("DELETE FROM "+table+" WHERE assessment_id=?",task);
            learning.update("DELETE FROM learning_idempotency_request WHERE resource_id=?",task);
            learning.update("DELETE FROM assessment WHERE id=?",task);
        }
        learning.update("DELETE FROM course_student_number WHERE course_id=?",course);learning.update("DELETE FROM course_student_number_counter WHERE course_id=?",course);
        learning.update("DELETE FROM published_lesson_content_projection WHERE course_id=?",course);learning.update("DELETE FROM published_course_content_projection WHERE course_id=?",course);
        learning.update("DELETE FROM learning_activity_day WHERE course_id=?",course);learning.update("DELETE FROM lesson_learning_day WHERE course_id=?",course);
        learning.update("DELETE FROM learning_idempotency_request WHERE owner_id=?",owner);
        learning.update("DELETE FROM learning_course_clock_interval WHERE course_id=?",course);learning.update("DELETE FROM learning_course_clock WHERE course_id=?",course);learning.update("DELETE FROM enrollment WHERE course_id=?",course);
        courses.update("DELETE FROM outbox_event WHERE aggregate_id=?",course);courses.update("DELETE FROM course_suspension_interval WHERE course_id=?",course);courses.update("DELETE FROM teacher_course_availability WHERE teacher_id=?",owner);
        courses.update("DELETE FROM lesson WHERE id=?",lesson);courses.update("DELETE FROM chapter WHERE id=?",chapter);courses.update("UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id=?",course);courses.update("DELETE FROM course_version WHERE id=?",version);courses.update("DELETE FROM course WHERE id=?",course);
        iam.update("DELETE FROM iam_outbox_event WHERE aggregate_id=?",application);iam.update("DELETE FROM teacher_review_command WHERE application_id=?",application);iam.update("DELETE FROM teacher_review_decision WHERE application_id=?",application);iam.update("DELETE FROM teacher_application_round WHERE application_id=?",application);iam.update("DELETE FROM teacher_application WHERE id=?",application);
        for(String u:users){iam.update("DELETE FROM account_security_audit WHERE user_id=? OR actor_id=?",u,u);iam.update("DELETE FROM refresh_session WHERE user_id=? AND parent_id IS NOT NULL",u);iam.update("DELETE FROM refresh_session WHERE user_id=?",u);iam.update("DELETE FROM login_audit WHERE user_id=?",u);iam.update("DELETE FROM user_identity WHERE user_id=?",u);iam.update("DELETE FROM user_role WHERE user_id=?",u);iam.update("DELETE FROM `user` WHERE id=?",u);}
        cleanRedis();
        assertThat(operation.queryForObject("SELECT COUNT(*) FROM notification_message WHERE course_id=?",Long.class,course)).isZero();
    }
    /** 无秘密manifest只记录本轮UUID/独立队列，异常退出后显式Cleanup可重复恢复；不按名称批量删除。 */
    private void saveManifest()throws Exception {
        var ids=new LinkedHashMap<String,Object>();ids.put("owner",owner);ids.put("student",student);ids.put("late",late);ids.put("outsider",outsider);ids.put("admin",admin);ids.put("course",course);ids.put("version",version);ids.put("chapter",chapter);ids.put("lesson",lesson);ids.put("application",application);ids.put("round",round);ids.put("fakeAsset",fakeAsset);ids.put("queue",queue);
        java.nio.file.Files.writeString(manifest(),json.writeValueAsString(ids),StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(manifest().resolveSibling("fixture-"+course+".json"),json.writeValueAsString(ids),StandardCharsets.UTF_8);
    }
    private java.nio.file.Path manifest(){return java.nio.file.Path.of("../../.m2/t16-4-acceptance/fixture.json").toAbsolutePath().normalize();}
    private void recoverManifest()throws Exception {
        JsonNode m=json.readTree(java.nio.file.Files.readString(manifest()));
        owner=UUID.fromString(m.path("owner").asString()).toString();student=UUID.fromString(m.path("student").asString()).toString();late=UUID.fromString(m.path("late").asString()).toString();outsider=UUID.fromString(m.path("outsider").asString()).toString();admin=UUID.fromString(m.path("admin").asString()).toString();course=UUID.fromString(m.path("course").asString()).toString();version=UUID.fromString(m.path("version").asString()).toString();chapter=UUID.fromString(m.path("chapter").asString()).toString();lesson=UUID.fromString(m.path("lesson").asString()).toString();application=UUID.fromString(m.path("application").asString()).toString();queue=m.path("queue").asString();
        if(!queue.matches("edu\\.operation\\.t164\\.[a-f0-9]{32}"))throw new IllegalStateException("Invalid isolated queue");
        users.clear();users.addAll(List.of(owner,student,late,outsider,admin));assessments.addAll(learning.queryForList("SELECT id FROM assessment WHERE course_id=?",String.class,course));
        events.addAll(operation.queryForList("SELECT event_id FROM notification_delivery_work WHERE course_id=? OR target_id=?",String.class,course,application));
    }
    /** 停止本轮唯一监听器，精确清空本轮副本后只删除随机私有队列/DLX；公共交换机保留。 */
    private void cleanQueue() {
        if(listeners!=null)listeners.stop();var admin=new RabbitAdmin(rabbit);
        for(String own:new HashSet<>(List.of(queue,System.getenv("OPERATION_NOTIFICATION_QUEUE")))) {
            ackOwn(own);ackOwn(own+".dlq");admin.deleteQueue(own);admin.deleteQueue(own+".dlq");admin.deleteExchange(own+".dlx");
        }
    }
    /** 仅删除本轮账号的会话版本、隔离namespace中的已知登录限流键；不扫描或清空Redis。 */
    private void cleanRedis() {
        var connection=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(System.getenv().getOrDefault("REDIS_HOST","127.0.0.1"),Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","6379")));
        connection.setDatabase(Integer.parseInt(System.getenv().getOrDefault("IAM_REDIS_DATABASE","1")));connection.afterPropertiesSet();connection.start();
        try {var cache=new org.springframework.data.redis.core.StringRedisTemplate(connection);String prefix=System.getenv().getOrDefault("IAM_SESSION_VERSION_KEY_PREFIX","edu:iam:session-version");for(String u:users)cache.delete(prefix+":"+u);}finally{connection.destroy();}
        var gateway=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(System.getenv().getOrDefault("REDIS_HOST","127.0.0.1"),Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT","6379")));
        gateway.setDatabase(Integer.parseInt(System.getenv().getOrDefault("GATEWAY_REDIS_DATABASE","0")));gateway.afterPropertiesSet();gateway.start();
        try {
            String prefix=System.getenv("GATEWAY_RATE_LIMIT_KEY_PREFIX");if(prefix==null || !prefix.startsWith("edu:gateway:rate:t164:"))return;
            var cache=new org.springframework.data.redis.core.StringRedisTemplate(gateway);
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            cache.delete(prefix+":login:"+java.util.HexFormat.of().formatHex(digest.digest("ip:127.0.0.1".getBytes(StandardCharsets.UTF_8))));
            for(String u:users)cache.delete(prefix+":login:"+java.util.HexFormat.of().formatHex(digest.digest(("user:"+u).getBytes(StandardCharsets.UTF_8))));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}finally{gateway.destroy();}
    }
}
