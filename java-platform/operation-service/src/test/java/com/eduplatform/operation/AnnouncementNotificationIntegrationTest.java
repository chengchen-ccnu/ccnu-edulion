package com.eduplatform.operation;

import com.eduplatform.operation.domain.NotificationDomains;
import com.eduplatform.operation.domain.NotificationDomains.*;
import com.eduplatform.operation.service.*;
import com.eduplatform.operation.web.NotificationDtos.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2领域集成覆盖授权/事务/乱序/并发；HTTP/MySQL/RabbitMQ实测另有显式专项，不冒充外部长链。 */
@SpringBootTest
@ActiveProfiles("test")
class AnnouncementNotificationIntegrationTest {
    @Autowired AnnouncementService announcements;
    @Autowired NotificationService notifications;
    @Autowired NotificationDelivery delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean NotificationDomains domains;
    String course,owner,student,late;
    AtomicBoolean active,eligible,paused;
    @BeforeEach void prepare() {
        for(String table:List.of("notification_message","notification_delivery_work","operation_consumed_event","operation_outbox_event","operation_command","course_announcement_confirmation","course_announcement_revision","course_announcement","operation_actor_lock")) jdbc.update("DELETE FROM "+table);
        reset(domains); course=id();owner=id();student=id();late=id();active=new AtomicBoolean(true);eligible=new AtomicBoolean(true);paused=new AtomicBoolean(false);
        when(domains.courses(anyList())).thenAnswer(inv->((List<String>)inv.getArgument(0)).contains(course)?List.of(new Course(course,owner,"PUBLISHED",paused.get(),1,"support@example.invalid")):List.of());
        when(domains.access(anyString(),anyList(),anyList(),anyList())).thenAnswer(inv->{
            String user=inv.getArgument(0); List<String> cs=inv.getArgument(1),tasks=inv.getArgument(2);List<Check> checks=inv.getArgument(3);
            boolean permitted=active.get() && (user.equals(student) || user.equals(late));
            return new Access(permitted?cs:List.of(),permitted?tasks:List.of(),permitted && eligible.get()?checks.stream().map(Check::key).toList():List.of());
        });
        when(domains.recipients(eq(course),any(),anyString())).thenReturn(new Recipients(List.of(student),false));
        when(domains.currentRecipients(eq(course),anyList())).thenAnswer(inv->active.get()?inv.getArgument(1):List.of());
        when(domains.reviews(anyString(),anyList())).thenAnswer(inv->inv.getArgument(1));
        when(domains.task(anyString())).thenAnswer(inv->new Task(inv.getArgument(0),course,"任务",1,true));
    }
    @Test void versionConfirmationWithdrawalAndSingleMessage() {
        Announcement a=create(); deliverAll();
        assertThat(notifications.counts(student)).isEqualTo(new Counts(1,1));
        assertThat(announcements.studentGet(student,course,a.id()).confirmed()).isFalse();
        assertThat(notifications.readAll(student)).isEqualTo(new Counts(1,1));
        assertThatThrownBy(()->notifications.read(student,messageId(),1)).isInstanceOf(OperationException.class);
        announcements.confirm(student,course,a.id(),new Revision(UUID.randomUUID(),1));
        assertThat(notifications.counts(student)).isEqualTo(new Counts(0,0));
        Announcement b=announcements.change(owner,course,a.id(),new Update(UUID.randomUUID(),1,"新版标题","新版\n纯文本"),false,"testtrace16");
        assertThatThrownBy(()->announcements.confirm(student,course,a.id(),new Revision(UUID.randomUUID(),1))).isInstanceOf(OperationException.class).extracting("code").isEqualTo("OPERATION.REVISION_CONFLICT");
        deliverAll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_message",Long.class)).isEqualTo(1);
        assertThat(notifications.list(student,1,20).items().get(0).title()).isEqualTo("新版标题");
        assertThat(notifications.counts(student)).isEqualTo(new Counts(1,1));
        announcements.change(owner,course,a.id(),new Update(UUID.randomUUID(),b.revision(),"",""),true,"testtrace16");
        assertThatThrownBy(()->announcements.studentGet(student,course,a.id())).isInstanceOf(OperationException.class).extracting("code").isEqualTo("OPERATION.NOT_FOUND");
        assertThat(notifications.counts(student)).isEqualTo(new Counts(0,0));
        assertThat(announcements.revisions(owner,course,a.id(),1,20).total()).isEqualTo(3);
    }
    @Test void ownerBoundaryAndNoPublicOrLateNotification() {
        assertThatThrownBy(()->announcements.create(student,course,new Create(UUID.randomUUID(),"公告","文本"),"testtrace16")).isInstanceOf(OperationException.class);
        Announcement a=create();deliverAll();
        var latePage=announcements.list(late,course,false,1,20);
        assertThat(latePage.total()).isEqualTo(1); assertThat(latePage.unconfirmedCount()).isZero();assertThat(latePage.latestUnconfirmed()).isNull();
        assertThat(notifications.list(late,1,20).items()).isEmpty();
        active.set(false);
        assertThatThrownBy(()->announcements.studentGet(student,course,a.id())).isInstanceOf(OperationException.class);
        assertThat(notifications.counts(student)).isEqualTo(new Counts(0,0));
        assertThatThrownBy(()->notifications.target(student,messageId())).isInstanceOf(OperationException.class);
        active.set(true); eligible.set(false);
        assertThat(announcements.studentGet(student,course,a.id()).confirmationRequired()).isFalse();
        assertThat(notifications.counts(student).unreadCount()).isZero();
    }
    @Test void pausedAvailabilityReadableButContentHidden() {
        Announcement a=create();deliverAll(); paused.set(true);
        assertThatThrownBy(()->announcements.studentGet(student,course,a.id())).isInstanceOf(OperationException.class);
        assertThatThrownBy(this::create).isInstanceOf(OperationException.class);
        String event=event("CourseAvailabilityChanged",course,Map.of("courseId",course,"suspensionId",id(),"availabilityRevision",1,"paused",true,"suspendedAt",Instant.now(),"resumedAt",mapper.nullNode()));
        delivery.consume(event);delivery.page(mapper.readTree(event).path("eventId").asString());
        var page=notifications.list(student,1,20);assertThat(page.total()).isEqualTo(1);
        var target=notifications.target(student,page.items().get(0).id()); assertThat(target.paused()).isTrue();assertThat(target.targetType()).isEqualTo("COURSE_STATUS");
        assertThat(notifications.read(student,page.items().get(0).id(),1)).isEqualTo(new Counts(0,0));
    }
    @Test void duplicateOldEventsCannotOverwriteNewConfirmation() {
        Announcement a=create();String old=payloads().get(0);deliverAll();
        Announcement updated=announcements.change(owner,course,a.id(),new Update(UUID.randomUUID(),1,"新","新正文"),false,"testtrace16");deliverAll();
        announcements.confirm(student,course,a.id(),new Revision(UUID.randomUUID(),updated.revision()));
        delivery.consume(old); delivery.page(mapper.readTree(old).path("eventId").asString());
        // 新eventId携带旧revision也只能登记抑制工作，不重置最新已确认消息。
        var node=mapper.readTree(old).deepCopy();((tools.jackson.databind.node.ObjectNode)node).put("eventId",id());
        delivery.consume(mapper.writeValueAsString(node));delivery.page(node.path("eventId").asString());
        assertThat(notifications.counts(student)).isEqualTo(new Counts(0,0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_message",Long.class)).isEqualTo(1);
    }
    @Test void failedAuditRollsBackAnnouncementAndOutbox() {
        // 新版审计插入被精确约束拒绝；公告/已有消息/Outbox必须全回滚。
        Announcement a=create();deliverAll();
        jdbc.update("INSERT INTO course_announcement_revision(announcement_id,revision,actor_id,status,title,body,changed_at) VALUES(?,2,?,'ACTIVE','预占','测试',?)",a.id(),owner,Timestamp.from(Instant.now()));
        assertThatThrownBy(()->announcements.change(owner,course,a.id(),new Update(UUID.randomUUID(),1,"不会保存","不会保存"),false,"testtrace16")).isInstanceOf(RuntimeException.class);
        assertThat(announcements.studentGet(student,course,a.id()).revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_outbox_event",Long.class)).isEqualTo(1);
    }
    @Test void preciseCommandReplayAndConcurrentRevision()throws Exception {
        Create command=new Create(UUID.randomUUID(),"并发公告","纯文本");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->announcements.create(owner,course,command,"testtrace16"));
            var two=pool.submit(()->announcements.create(owner,course,command,"testtrace16"));
            Announcement a=one.get(10,TimeUnit.SECONDS);assertThat(two.get(10,TimeUnit.SECONDS).id()).isEqualTo(a.id());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_outbox_event",Long.class)).isEqualTo(1);
            var first=pool.submit(()->changeResult(a.id()));var second=pool.submit(()->changeResult(a.id()));
            assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder("OK","CONFLICT");
            assertThatThrownBy(()->announcements.create(owner,course,new Create(command.clientRequestId(),"异载荷","其他"),"testtrace16")).isInstanceOf(OperationException.class);
        } finally { pool.shutdownNow(); }
    }
    private String changeResult(String a) { try { announcements.change(owner,course,a,new Update(UUID.randomUUID(),1,"新","新"),false,"testtrace16");return "OK"; }catch(OperationException e){return "CONFLICT";} }
    @Test void dependencyFailureIsNotZeroOrReadSuccess() {
        create();deliverAll(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_message",Long.class)).isEqualTo(1);
        when(domains.access(anyString(),anyList(),anyList(),anyList())).thenThrow(NotificationDomains.unavailable());
        assertThatThrownBy(()->notifications.counts(student)).isInstanceOf(OperationException.class);
        assertThatThrownBy(()->notifications.readAll(student)).isInstanceOf(OperationException.class);
        assertThat(jdbc.queryForObject("SELECT unread FROM notification_message",Boolean.class)).isTrue();
    }
    @Test void endedOpenReminderSuppressedAndReviewPrivacy() {
        String task=id();when(domains.task(task)).thenReturn(new Task(task,course,"不复制题目",2,false));
        String opened=event("CourseTaskNotificationChanged",task,Map.of("assessmentId",task,"courseId",course,"revision",1,"action","OPENED","triggeredAt",Instant.now()));
        delivery.consume(opened);delivery.page(mapper.readTree(opened).path("eventId").asString());
        assertThat(notifications.counts(student).unreadCount()).isZero();
        String review=id();String json=event("TeacherQualificationDecided",review,Map.of("applicationId",review,"roundId",id(),"userId",student,"decision","REJECTED"));
        delivery.consume(json);delivery.page(mapper.readTree(json).path("eventId").asString());
        String rendered=mapper.writeValueAsString(notifications.list(student,1,20));
        assertThat(rendered).contains("教师资格审核结果已更新").doesNotContain("qualificationMediaId","reason","body");
        notifications.target(student,messageId()); assertThat(notifications.counts(student).unreadCount()).isEqualTo(1);
        assertThat(notifications.readAll(student)).isEqualTo(new Counts(0,0));
    }
    @Test void invalidUnsupportedAndConflictingEventsFail() {
        assertThatThrownBy(()->delivery.consume("{}")).isInstanceOf(RuntimeException.class);
        String review=id();String json=event("TeacherQualificationDecided",review,Map.of("applicationId",review,"roundId",id(),"userId",student,"decision","APPROVED"));
        delivery.consume(json);delivery.consume(json);
        String conflict=json.replace("APPROVED","REJECTED"); assertThatThrownBy(()->delivery.consume(conflict)).isInstanceOf(IllegalArgumentException.class);
        var sensitive=(tools.jackson.databind.node.ObjectNode)mapper.readTree(json);((tools.jackson.databind.node.ObjectNode)sensitive.path("payload")).put("rejectionReason","不允许复制到通知台账");
        assertThatThrownBy(()->delivery.consume(mapper.writeValueAsString(sensitive))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_consumed_event",Long.class)).isEqualTo(1);
    }
    /** 第一页提交后发生真实语义的依赖故障；重建Worker从持久游标恢复第二页，不能重复前100人或推进失败游标。 */
    @Test void committedFanoutCursorSurvivesWorkerReconstruction() {
        var ids=java.util.stream.IntStream.range(0,105).mapToObj(n->id()).sorted().toList();
        when(domains.recipients(eq(course),any(),eq(""))).thenReturn(new Recipients(ids.subList(0,100),true));
        when(domains.recipients(eq(course),any(),eq(ids.get(99)))).thenThrow(NotificationDomains.unavailable());
        Announcement a=create();String payload=payloads().get(0);String event=mapper.readTree(payload).path("eventId").asString();delivery.consume(payload);
        delivery.page(event);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_message",Long.class)).isEqualTo(100);
        var worker=new NotificationDelivery(jdbc,domains,mapper,manager,true);worker.tick();
        assertThat(jdbc.queryForObject("SELECT cursor_id FROM notification_delivery_work WHERE event_id=?",String.class,event)).isEqualTo(ids.get(99));
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_delivery_work WHERE event_id=?",Integer.class,event)).isEqualTo(1);
        when(domains.recipients(eq(course),any(),eq(ids.get(99)))).thenReturn(new Recipients(ids.subList(100,105),false));
        jdbc.update("UPDATE notification_delivery_work SET next_attempt_at=? WHERE event_id=?",Timestamp.from(Instant.now().minusSeconds(1)),event);
        new NotificationDelivery(jdbc,domains,mapper,manager,true).tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_message WHERE target_id=?",Long.class,a.id())).isEqualTo(105);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery_work WHERE event_id=?",String.class,event)).isEqualTo("COMPLETE");
    }
    /** Broker NACK、确认超时都保留业务事实并耗有限发布预算；FAILED永不被publishPending重新领取。 */
    @Test void nackAndConfirmTimeoutLeaveFailedOutboxWithoutLosingBusinessFact() {
        create();String event=mapper.readTree(payloads().get(0)).path("eventId").asString();
        var rabbit=mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        doAnswer(inv->{org.springframework.amqp.rabbit.connection.CorrelationData c=inv.getArgument(3);c.getFuture().complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(false,"test nack"));return null;}).when(rabbit).send(anyString(),anyString(),any(org.springframework.amqp.core.Message.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        var publisher=new com.eduplatform.operation.messaging.OperationOutboxPublisher(jdbc,rabbit,manager,2,100);publisher.publishPending();
        assertThat(jdbc.queryForObject("SELECT attempts FROM operation_outbox_event",Integer.class)).isEqualTo(1);
        reset(rabbit);jdbc.update("UPDATE operation_outbox_event SET next_attempt_at=? WHERE event_id=?",Timestamp.from(Instant.now()),event);publisher.publishPending();
        assertThat(jdbc.queryForObject("SELECT status FROM operation_outbox_event",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_announcement",Long.class)).isEqualTo(1);
        publisher.publishPending();assertThat(jdbc.queryForObject("SELECT attempts FROM operation_outbox_event",Integer.class)).isEqualTo(2);
    }
    private Announcement create() { return announcements.create(owner,course,new Create(UUID.randomUUID(),"公告标题","纯文本\nhttps://example.invalid"),"testtrace16"); }
    private List<String> payloads() { return jdbc.query("SELECT payload_json FROM operation_outbox_event ORDER BY next_attempt_at,event_id",(rs,n)->rs.getString(1)); }
    private void deliverAll() { for(String json:payloads()) { delivery.consume(json);String event=mapper.readTree(json).path("eventId").asString();jdbc.update("UPDATE notification_delivery_work SET next_attempt_at=? WHERE event_id=?",Timestamp.from(Instant.now().minusSeconds(1)),event);delivery.page(event); } }
    private String messageId() { return jdbc.queryForObject("SELECT id FROM notification_message ORDER BY occurred_at DESC LIMIT 1",String.class); }
    private String event(String type,String aggregate,Map<String,Object> payload) { return mapper.writeValueAsString(Map.of("eventId",id(),"eventType",type,"eventVersion",1,"aggregateId",aggregate,"occurredAt",Instant.now(),"traceId","testtrace16","payload",payload)); }
    private static String id() { return UUID.randomUUID().toString(); }
}
