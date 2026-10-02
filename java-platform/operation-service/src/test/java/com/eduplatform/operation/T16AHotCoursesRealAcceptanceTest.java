package com.eduplatform.operation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.eduplatform.operation.hot.*;
import com.eduplatform.operation.hot.HotCourseModels.*;
import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.*;

/** 显式专用MySQL/Redis/Rabbit/领域HTTP验收；故障选择仍使用真实坏端口，Fixture按UUID/trace/namespace清理。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"server.port=18085","platform.operation.messaging-enabled=false","platform.operation.hot.worker-enabled=false","platform.operation.hot.messaging-enabled=true"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="T16A_REAL_ACCEPTANCE_ENABLED",matches="true")
class T16AHotCoursesRealAcceptanceTest {
    @Autowired JdbcTemplate operation;
    @Autowired HotCourseWorker worker;
    @Autowired HotCourseEvents consumer;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;
    @MockitoSpyBean HotCourseSources sources;
    @MockitoSpyBean HotCourseCache cache;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final List<String> courses=new ArrayList<>(),versions=new ArrayList<>();
    final Set<String> eventIds=new HashSet<>();
    final String owner=id(),learner=id(),other=id(),enrollment=id();
    JdbcTemplate courseDb,learningDb,mediaDb;
    String namespace=System.getenv("OPERATION_HOT_NAMESPACE"),queue=System.getenv("OPERATION_HOT_QUEUE");

    /** 真实分页源/聚合与事件增量：去重、乱序、窗口过期、暂停、封面、故障与重建；所有清理在finally。 */
    @Test void actualDependenciesAndPreciseCleanup() throws Exception {
        courseDb=db("course");learningDb=db("learning");mediaDb=db("media");
        var historyCourse=courseDb.queryForList("SELECT * FROM outbox_event");var historyLearning=learningDb.queryForList("SELECT * FROM learning_outbox_event");
        var manual=courseDb.queryForList("SELECT * FROM course WHERE id='5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057'");
        var state=operation.queryForMap("SELECT * FROM hot_course_refresh_state WHERE id=1");
        assertThat(operation.queryForObject("SELECT COUNT(*) FROM hot_course_projection",Long.class)).as("isolated acceptance requires no prior hot projection").isZero();
        List<String> projectionIds=new ArrayList<>();
        RabbitAdmin broker=new RabbitAdmin(rabbit);
        try {
            fixture();worker.requestRebuild();long start=System.nanoTime();
            for(int batch=0;batch<5 && !"READY".equals(cache.read().status());batch++) worker.refresh();
            System.out.println("T16A source+MySQL+Redis rebuild milliseconds="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            projectionIds.addAll(operation.queryForList("SELECT course_id FROM hot_course_projection",String.class));
            JsonNode first=get();assertThat(first.path("status").asText()).isEqualTo("READY");assertThat(first.path("items").size()).isEqualTo(6);
            assertThat(card(courses.get(0)).path("learners7d").asLong()).isEqualTo(1);
            assertThat(card(courses.get(1)).path("label").asText()).isEqualTo("NEW");
            assertThat(sources.learning(courses.get(0)).learners7d()).isEqualTo(1);
            assertThat(sources.card(courses.get(8)).visible()).isFalse();
            assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082/internal/v1/hot-course-source")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
            assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/internal/v1/hot-course-source")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
            clearInvocations(sources);for(int n=0;n<10;n++)get();verifyNoInteractions(sources);
            // 源资格表跨日同一人只有一个人数；过窗的人不计，报名活动不能冒充学习。
            String repeated=signal(courses.get(0),true);await(()->consumed(repeated));long before=inbox();
            String repeatedJson=learningDb.queryForObject("SELECT payload_json FROM learning_outbox_event WHERE event_id=?",String.class,repeated);
            rabbit.convertAndSend("edu.learning.hot-course.events.v1","learning.course.day-qualified.v1",repeatedJson);await(()->inbox()==before);worker.refresh();
            assertThat(card(courses.get(0)).path("learners7d").asLong()).isEqualTo(1);
            // 取消再重报后重投旧取消信号，当前事实仍为ACTIVE，不做事件加减。
            learningDb.update("UPDATE enrollment SET status='CANCELED',canceled_at=CURRENT_TIMESTAMP WHERE id=?",enrollment);
            String cancel=enrollmentSignal("CANCELED");await(()->consumed(cancel));worker.refresh();assertThat(card(courses.get(0)).path("activeEnrollments").asLong()).isZero();
            learningDb.update("UPDATE enrollment SET status='ACTIVE',canceled_at=NULL WHERE id=?",enrollment);String reenroll=enrollmentSignal("ACTIVE");await(()->consumed(reenroll));worker.refresh();
            rabbit.convertAndSend("edu.learning.enrollment.events.v1","learning.enrollment.access-changed.v1",learningDb.queryForObject("SELECT payload_json FROM learning_outbox_event WHERE event_id=?",String.class,cancel));
            consumer.consume(learningDb.queryForObject("SELECT payload_json FROM learning_outbox_event WHERE event_id=?",String.class,cancel));worker.refresh();assertThat(card(courses.get(0)).path("activeEnrollments").asLong()).isEqualTo(1);
            learningDb.update("UPDATE hot_learning_day SET activity_date=? WHERE course_id=? AND activity_date=?",java.sql.Date.valueOf(today().minusDays(7)),courses.get(0),java.sql.Date.valueOf(today()));
            // 另一条同人第6天记录仍在窗内；移到第8天后才能归零。
            learningDb.update("DELETE FROM hot_learning_day WHERE course_id=? AND activity_date=?",courses.get(0),java.sql.Date.valueOf(today().minusDays(6)));
            String expire=signal(courses.get(0),true);await(()->consumed(expire));worker.refresh();assertThat(card(courses.get(0)).path("learners7d").asLong()).isZero();assertThat(card(courses.get(0)).path("label").asText()).isEqualTo("NEW");
            String suspension=id();availability(true,1,suspension);await(()->courseDb.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND status='PENDING'",Long.class,courses.get(0))==0);
            assertThat(sources.card(courses.get(0)).visible()).isFalse();assertThat(get().path("items").toString()).doesNotContain(courses.get(0));
            availability(false,2,suspension);assertThat(sources.card(courses.get(0)).visible()).isTrue();
            courseDb.update("UPDATE course SET publication_status='UNPUBLISHED' WHERE id=?",courses.get(1));String unpublished=signal(courses.get(1),false);await(()->consumed(unpublished));worker.refresh();assertThat(get().path("items").toString()).doesNotContain(courses.get(1));
            courseDb.update("UPDATE course SET publication_status='PUBLISHED' WHERE id=?",courses.get(1));String published=signal(courses.get(1),false);await(()->consumed(published));worker.refresh();
            courseDb.update("UPDATE course_version SET lifecycle_status='ARCHIVED' WHERE id=?",versions.get(1));String archived=signal(courses.get(1),false);await(()->consumed(archived));worker.refresh();assertThat(get().path("items").toString()).doesNotContain(courses.get(1));courseDb.update("UPDATE course_version SET lifecycle_status='PUBLISHED' WHERE id=?",versions.get(1));
            coversExpireAndRefresh();sourceFailureAndRebuildRace();redisFailure();
            rabbit.convertAndSend("edu.learning.hot-course.events.v1","learning.course.day-qualified.v1","{}");await(()->broker.getQueueProperties(queue+".dlq")!=null && ((Number)broker.getQueueProperties(queue+".dlq").get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue()==1);
            assertThat(((Number)broker.getQueueProperties(queue).get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue()).isZero();
            String failed=unroutable();await(()->"FAILED".equals(courseDb.queryForObject("SELECT status FROM outbox_event WHERE event_id=?",String.class,failed)),20000);
            assertThat(courseDb.queryForObject("SELECT attempts FROM outbox_event WHERE event_id=?",Integer.class,failed)).isEqualTo(3);
            System.out.println("T16A actual Redis/MySQL/Rabbit/Gateway/source/cover expiry/fault/rebuild assertions passed.");
        } finally {
            reset(sources,cache);listeners.stop();projectionIds.addAll(operation.queryForList("SELECT course_id FROM hot_course_projection",String.class));
            cleanup(projectionIds,broker);
            operation.update("UPDATE hot_course_refresh_state SET rebuild_after=?,last_completed_at=?,last_failure_at=? WHERE id=1",state.get("rebuild_after"),state.get("last_completed_at"),state.get("last_failure_at"));
            assertThat(courseDb.queryForList("SELECT * FROM outbox_event").equals(historyCourse)).isTrue();assertThat(learningDb.queryForList("SELECT * FROM learning_outbox_event").equals(historyLearning)).isTrue();
            assertThat(courseDb.queryForList("SELECT * FROM course WHERE id='5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057'").equals(manual)).isTrue();
            assertThat(redis.hasKey(namespace+":board")).isFalse();System.out.println("T16A exact UUID/eventId/trace/namespace cleanup passed; histories and linear algebra preserved.");
        }
    }
    /** 合成109课验证100项keyset上限、两个学生ID及最小报名；只读复用既有封面，不改人工课/媒体绑定。 */
    private void fixture() {
        String category=courseDb.queryForObject("SELECT id FROM category WHERE status='ACTIVE' ORDER BY id LIMIT 1",String.class);
        String cover=courseDb.queryForObject("SELECT v.cover_media_id FROM course c JOIN course_version v ON v.id=c.published_version_id WHERE c.publication_status='PUBLISHED' AND c.system_suspended=FALSE AND v.cover_media_id IS NOT NULL ORDER BY c.id LIMIT 1",String.class);
        for(int n=0;n<109;n++) {
            String c=id(),v=id();courses.add(c);versions.add(v);
            courseDb.update("INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status,published_at) VALUES(?,?,?, ?,?)",c,id(),owner,n==8?"DRAFT":"PUBLISHED",Timestamp.from(Instant.now().minusSeconds(n)));
            courseDb.update("INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,description,cover_url,cover_media_id,instructor_display_name,category_id,level,access_type) VALUES(?,?,1,'v1',?,'t16a_course_test','test','test','',?,'teacher_test_t16a',?,'BEGINNER','PUBLIC')",v,c,n==8?"DRAFT":"PUBLISHED",n==0?cover:null,category);
            courseDb.update("UPDATE course SET published_version_id=?,current_draft_version_id=? WHERE id=?",n==8?null:v,v,c);
        }
        for(LocalDate date:List.of(today(),today().minusDays(6))) learningDb.update("INSERT INTO hot_learning_day(course_id,learner_id,activity_date,event_id) VALUES(?,?,?,?)",courses.get(0),learner,java.sql.Date.valueOf(date),id());
        learningDb.update("INSERT INTO hot_learning_day(course_id,learner_id,activity_date,event_id) VALUES(?,?,?,?)",courses.get(0),other,java.sql.Date.valueOf(today().minusDays(7)),id());
        learningDb.update("INSERT INTO learning_activity_day(course_id,learner_id,activity_date,last_activity_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",courses.get(1),other,java.sql.Date.valueOf(today()));
        learningDb.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) VALUES(?,?,?,'v1','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",enrollment,learner,courses.get(0));
    }
    /** 保留实际20秒授权，等待真实过期后读模型不回传旧URL；再次后台刷新获得新有效期并真实GET OSS。 */
    private void coversExpireAndRefresh() throws Exception {
        String event=signal(courses.get(0),false);await(()->consumed(event));worker.refresh();
        JsonNode card=card(courses.get(0));assertThat(card.path("coverStatus").asText()).isEqualTo("READY");String url=card.path("coverUrl").asText();
        var original=http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.discarding());assertThat(original.statusCode()).isEqualTo(200);
        Instant serverDate=ZonedDateTime.parse(original.headers().firstValue("Date").orElseThrow(),java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        long clockLead=Math.max(0,Duration.between(serverDate,Instant.now()).getSeconds());
        System.out.println("T16A OSS observed local clock lead seconds="+clockLead);
        Instant expires=Instant.parse(card.path("coverExpiresAt").asText());while(Instant.now().isBefore(expires.plusSeconds(1)))Thread.sleep(200);
        assertThat(card(courses.get(0)).path("coverStatus").asText()).isEqualTo("UNAVAILABLE");assertThat(card(courses.get(0)).path("coverUrl").isNull()).isTrue();
        // HTTP Date可能与本机有秒级偏差；等服务端也跨过期限后验证真实签名拒绝，仍不改URL/签名。
        assertThat(clockLead).isLessThan(90);if(clockLead>0)Thread.sleep((clockLead+2)*1000);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(url)).header("Cache-Control","no-cache").GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        worker.refresh();assertThat(card(courses.get(0)).path("coverStatus").asText()).isEqualTo("READY");
    }
    /** 依赖坏端口是真HTTP失败；扫描旧快照时并发发布新事件，随后以dirty刷新最新标题。 */
    private void sourceFailureAndRebuildRace() throws Exception {
        HotCourseSources broken=new HotCourseSources(json,"http://127.0.0.1:18082","http://127.0.0.1:1",System.getenv("OPERATION_INTERNAL_SERVICE_TOKEN"));
        String signal=signal(courses.get(0),true);await(()->consumed(signal));doAnswer(inv->broken.learning(inv.getArgument(0))).when(sources).learning(courses.get(0));
        assertThatThrownBy(worker::refresh).isInstanceOf(IllegalStateException.class);assertThat(operation.queryForObject("SELECT COUNT(*) FROM hot_course_dirty WHERE course_id=?",Long.class,courses.get(0))).isEqualTo(1);
        assertThat(get().path("status").asText()).isEqualTo("UNINITIALIZED");doCallRealMethod().when(sources).learning(courses.get(0));worker.refresh();
        worker.requestRebuild();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(inv->{SourcePage snapshot=(SourcePage)inv.callRealMethod();started.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("race timeout");return snapshot;}).when(sources).page("");
        var executor=Executors.newSingleThreadExecutor();
        try {
            var rebuild=executor.submit(worker::refresh);assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            courseDb.update("UPDATE course_version SET title='t16a_new_publication_test' WHERE id=?",versions.get(0));String concurrent=signal(courses.get(0),false);release.countDown();rebuild.get(20,TimeUnit.SECONDS);
            await(()->consumed(concurrent));doCallRealMethod().when(sources).page("");refreshToReady();assertThat(card(courses.get(0)).path("title").asText()).isEqualTo("t16a_new_publication_test");
        }finally{release.countDown();executor.shutdownNow();doCallRealMethod().when(sources).page("");}
    }
    /** 只让本验收热点读连接坏端口；不停止共享Redis，Gateway热点200降级且普通课程列表仍200。 */
    private void redisFailure() throws Exception {
        var badFactory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",1));badFactory.afterPropertiesSet();badFactory.start();
        try {
            var badRedis=new StringRedisTemplate(badFactory);var beans=new StaticListableBeanFactory();beans.addBean("redis",badRedis);
            var broken=new HotCourseCache(beans.getBeanProvider(StringRedisTemplate.class),json,namespace);
            doAnswer(inv->broken.read()).when(cache).read();clearInvocations(sources);
            assertThat(get().path("status").asText()).isEqualTo("UNAVAILABLE");verifyNoInteractions(sources);
            assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/api/v1/courses")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            doCallRealMethod().when(cache).read();redis.delete(namespace+":board");assertThat(get().path("status").asText()).isEqualTo("UNINITIALIZED");worker.requestRebuild();refreshToReady();assertThat(get().path("status").asText()).isEqualTo("READY");
        }finally{doCallRealMethod().when(cache).read();badFactory.destroy();}
    }
    private String signal(String course,boolean learning) {
        return outbox(learning?learningDb:courseDb,learning?"learning_outbox_event":"outbox_event",course,learning?"CourseLearningDayQualified":"CoursePortalChanged",learning?"edu.learning.hot-course.events.v1":"edu.course.portal.events.v1",learning?"learning.course.day-qualified.v1":"course.portal.changed.v1",Map.of("courseId",course));
    }
    private String enrollmentSignal(String status) {return outbox(learningDb,"learning_outbox_event",enrollment,"EnrollmentAccessChanged","edu.learning.enrollment.events.v1","learning.enrollment.access-changed.v1",Map.of("courseId",courses.get(0),"learnerId",learner,"enrollmentId",enrollment,"status",status,"changedAt",Instant.now(),"courseVersionId","v1"));}
    private String unroutable() {return outbox(courseDb,"outbox_event",courses.get(0),"CoursePortalChanged","edu.course.portal.events.v1","unroutable.t16a."+id(),Map.of("courseId",courses.get(0)));}
    /** Fixture模拟已提交本域失效事实，实际发布/confirm/return由正在运行的生产Outbox执行。 */
    private String outbox(JdbcTemplate db,String table,String aggregate,String type,String exchange,String routing,Object payload) {
        String event=id();eventIds.add(event);String envelope=json.writeValueAsString(Map.of("eventId",event,"eventType",type,"eventVersion",1,"aggregateId",aggregate,"occurredAt",Instant.now(),"traceId",event,"payload",payload));
        String time=table.equals("outbox_event")?"created_at":"occurred_at";
        db.update("INSERT INTO "+table+"(event_id,event_type,event_version,aggregate_id,exchange_name,routing_key,trace_id,payload_json,status,attempts,next_attempt_at,"+time+",updated_at) VALUES(?,?,1,?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",event,type,aggregate,exchange,routing,event,envelope);return event;
    }
    private void availability(boolean paused,long revision,String suspension) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082/internal/v1/teachers/"+owner+"/course-availability"))
                .header("X-Internal-Caller","iam-service").header("X-Internal-Service-Token",System.getenv("COURSE_SUSPENSION_IAM_TOKEN"))
                .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("suspensionId",suspension,"accessRevision",revision,"paused",paused)))).build();
        assertThat(http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        // 暂停/恢复为整个教师批量发事件；等待全部可靠消费，再分批追平，不能只等待第一条dirty。
        List<String> batch=courseDb.queryForList("SELECT event_id FROM outbox_event WHERE aggregate_id IN (SELECT id FROM course WHERE owner_teacher_id=?)",String.class,owner);
        eventIds.addAll(batch);await(()->batch.stream().allMatch(this::consumed));
        for(int n=0;n<5 && operation.queryForObject("SELECT COUNT(*) FROM hot_course_dirty",Long.class)>0;n++)worker.refresh();
    }
    /** 精确删本轮ID/trace；只消费独立队列的毒消息，不purge，外域既有事实/Outbox全字段保持。 */
    private void cleanup(List<String> projections,RabbitAdmin broker) throws Exception {
        for(String course:courses) {
            eventIds.addAll(courseDb.queryForList("SELECT event_id FROM outbox_event WHERE aggregate_id=?",String.class,course));
            operation.update("DELETE FROM hot_course_consumed_event WHERE course_id=?",course);operation.update("DELETE FROM hot_course_dirty WHERE course_id=?",course);
            for(String table:List.of("hot_learning_day","learning_activity_day","enrollment"))learningDb.update("DELETE FROM "+table+" WHERE course_id=?",course);
            courseDb.update("DELETE FROM course_suspension_interval WHERE course_id=?",course);
            courseDb.update("UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id=?",course);
            courseDb.update("DELETE FROM course_version WHERE course_id=?",course);courseDb.update("DELETE FROM course WHERE id=?",course);
        }
        courseDb.update("DELETE FROM teacher_course_availability WHERE teacher_id=?",owner);
        // Course可用性副本只ACK本轮eventId，出现外来消息立即requeue并停止；不抢其他消费者。
        for(String name:List.of("edu.learning.availability.v1","edu.course.enrollment-access.v1")) {
            var properties=broker.getQueueProperties(name);if(properties==null)continue;assertThat(((Number)properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).longValue()).isZero();
            rabbit.execute(channel->{for(int n=0;n<eventIds.size()+1;n++){var message=channel.basicGet(name,false);if(message==null)break;String event=json.readTree(message.getBody()).path("eventId").asText();if(!eventIds.contains(event)){channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true);break;}channel.basicAck(message.getEnvelope().getDeliveryTag(),false);}return null;});
        }
        for(String event:eventIds){courseDb.update("DELETE FROM outbox_event WHERE event_id=?",event);learningDb.update("DELETE FROM learning_outbox_event WHERE event_id=?",event);}
        for(String id:new HashSet<>(projections))operation.update("DELETE FROM hot_course_projection WHERE course_id=?",id);
        String prefix=System.getenv("OPERATION_HOT_TRACE_PREFIX")+"_%";
        List<String> grants=mediaDb.queryForList("SELECT grant_id FROM access_grant_audit WHERE trace_id LIKE ?",String.class,prefix);
        for(String grant:grants){assertThat(mediaDb.queryForObject("SELECT COUNT(*) FROM temporary_access_object WHERE grant_id=?",Long.class,grant)).isZero();mediaDb.update("DELETE FROM access_grant_audit WHERE grant_id=?",grant);}
        redis.delete(namespace+":board");while(rabbit.receive(queue)!=null){}while(rabbit.receive(queue+".dlq")!=null){}
        broker.deleteQueue(queue);broker.deleteQueue(queue+".dlq");broker.deleteExchange(queue+".dlx");
        assertThat(operation.queryForObject("SELECT COUNT(*) FROM hot_course_projection",Long.class)).isZero();
        for(String course:courses) assertThat(learningDb.queryForObject("SELECT COUNT(*) FROM hot_learning_day WHERE course_id=?",Long.class,course)).isZero();
    }
    private JsonNode get() throws Exception {var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/api/v1/operations/hot-courses")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).isEqualTo(200);assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");return json.readTree(r.body());}
    private JsonNode card(String id) throws Exception {for(JsonNode card:get().path("items"))if(id.equals(card.path("courseId").asText()))return card;throw new AssertionError("Fixture card absent: "+id);}
    private boolean consumed(String event) {return operation.queryForObject("SELECT COUNT(*) FROM hot_course_consumed_event WHERE event_id=?",Long.class,event)>0;}
    private long inbox() {return operation.queryForObject("SELECT COUNT(*) FROM hot_course_consumed_event",Long.class);}
    /** 分批重建确实可能返回UNINITIALIZED；最多五轮追平本轮109课，不把首批当全量完成。 */
    private void refreshToReady() {for(int n=0;n<5 && !"READY".equals(cache.read().status());n++)worker.refresh();assertThat(cache.read().status()).isEqualTo("READY");}
    private void await(BooleanSupplier condition)throws Exception {await(condition,15000);}
    private void await(BooleanSupplier condition,long millis)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(100);}throw new AssertionError("Bounded acceptance wait timed out");}
    private JdbcTemplate db(String domain) {String schema="edu_platform_"+domain+"_dev";assertThat(System.getenv(domain.toUpperCase()+"_MYSQL_DATABASE")).isEqualTo(schema);return new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://"+System.getenv("MYSQL_HOST")+":"+System.getenv("MYSQL_PORT")+"/"+schema+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",System.getenv("MYSQL_USERNAME"),System.getenv("MYSQL_PASSWORD")));}
    private static String id() {return UUID.randomUUID().toString();}
    private LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
}
