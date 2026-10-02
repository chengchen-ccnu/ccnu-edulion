package com.eduplatform.operation.service;

import com.eduplatform.operation.domain.NotificationDomains;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 持久分页fan-out；每事务最多100人、一个游标，重启/重复/乱序不重复发信，依赖失败有限预算。 */
@Service
public class NotificationDelivery {
    private final JdbcTemplate jdbc;
    private final NotificationDomains domains;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    public NotificationDelivery(JdbcTemplate jdbc,NotificationDomains domains,ObjectMapper mapper,
            PlatformTransactionManager manager,@Value("${platform.operation.messaging-enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.domains=domains; this.mapper=mapper; this.transactions=new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED); this.enabled=enabled;
    }
    /** 校验四类合法信封，eventId与待投递事实同事务；同ID异内容拒绝，毒消息交给容器有限重试/DLQ。 */
    public void consume(String json) {
        Work work=parse(json);
        transactions.executeWithoutResult(status->{
            try { jdbc.update("INSERT INTO operation_consumed_event(event_id,payload_json,consumed_at) VALUES(?,?,?)",work.eventId(),json,Timestamp.from(Instant.now())); }
            catch(DuplicateKeyException e) {
                String previous=jdbc.queryForObject("SELECT payload_json FROM operation_consumed_event WHERE event_id=?",String.class,work.eventId());
                if(!mapper.readTree(previous).equals(mapper.readTree(json))) throw new IllegalArgumentException("Conflicting event identity"); return;
            }
            jdbc.update("INSERT INTO notification_delivery_work(event_id,kind,action,target_id,course_id,recipient_id,revision,triggered_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    work.eventId(),work.kind(),work.action(),work.target(),work.course(),work.recipient(),work.revision(),Timestamp.from(work.at()),Timestamp.from(Instant.now()));
        });
    }
    /** 每轮最多10个工作页；失败不阻塞其他页，最多5次/页、指数退避；FAILED不自动重放。 */
    @Scheduled(fixedDelayString="${OPERATION_DELIVERY_POLL_DELAY:2s}")
    public void tick() {
        if(!enabled) return;
        var ids=jdbc.query("SELECT event_id FROM notification_delivery_work WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,event_id LIMIT 10",(rs,n)->rs.getString(1),Timestamp.from(Instant.now()));
        for(String id:ids) {
            try { page(id); } catch(RuntimeException failure) { fail(id,failure); }
        }
    }
    /** 工作行锁保护跨实例同页；课程/任务实时重验，消息与游标原子提交，完成后保留工作审计。 */
    public void page(String eventId) {
        transactions.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT * FROM notification_delivery_work WHERE event_id=? FOR UPDATE",eventId);
            if(rows.isEmpty()) return; var r=rows.get(0);
            if(!"PENDING".equals(r.get("status")) || ((Timestamp)r.get("next_attempt_at")).toInstant().isAfter(Instant.now())) return;
            Work work=new Work(eventId,(String)r.get("kind"),(String)r.get("action"),(String)r.get("target_id"),(String)r.get("course_id"),(String)r.get("recipient_id"),((Number)r.get("revision")).longValue(),((Timestamp)r.get("triggered_at")).toInstant());
            String cursor=(String)r.get("cursor_id"); String title;
            NotificationDomains.Course course=null;
            if(work.course()!=null) {
                course=domains.courses(List.of(work.course())).stream().findFirst().orElse(null);
                if(course==null) { suppress(eventId); return; }
            }
            if("COURSE_ANNOUNCEMENT".equals(work.kind())) {
                var announcements=jdbc.queryForList("SELECT revision,status,title FROM course_announcement WHERE id=? AND course_id=? FOR UPDATE",work.target(),work.course());
                if(announcements.isEmpty() || !"ACTIVE".equals(announcements.get(0).get("status")) || !"ANNOUNCEMENT".equals(work.action())
                        || work.revision()!=((Number)announcements.get(0).get("revision")).longValue() || !course.readable()) { suppress(eventId); return; }
                title=(String)announcements.get(0).get("title");
            } else if("COURSE_TASK".equals(work.kind())) {
                NotificationDomains.Task task;
                try { task=domains.task(work.target()); } catch(OperationException e) { if(e.status()==HttpStatus.NOT_FOUND) { suppress(eventId); return; } throw e; }
                if(!work.course().equals(task.courseId()) || !course.readable() || "OPENED".equals(work.action()) && !task.deliverable()) { suppress(eventId); return; }
                title=switch(work.action()) { case "OPENED"->"课程任务已开放"; case "DEADLINE_CHANGED"->"课程任务截止时间已调整"; default->"课程任务已结束"; };
            } else if("COURSE_AVAILABILITY".equals(work.kind())) {
                if(work.revision()!=course.availabilityRevision()) { suppress(eventId); return; }
                title="PAUSED".equals(work.action())?"课程暂不可用":"课程已恢复";
            } else {
                if(!domains.reviews(work.recipient(),List.of(work.target())).contains(work.target())) { suppress(eventId); return; }
                title="APPROVED".equals(work.action())?"教师资格审核已通过":"教师资格审核结果已更新";
            }
            NotificationDomains.Recipients recipients=work.recipient()==null?domains.recipients(work.course(),work.at(),cursor):new NotificationDomains.Recipients(List.of(work.recipient()),false);
            Set<String> current=work.recipient()==null?Set.copyOf(domains.currentRecipients(work.course(),recipients.learnerIds())):Set.of(work.recipient());
            for(String user:recipients.learnerIds()) {
                if(!current.contains(user) || course!=null && course.ownerTeacherId().equals(user)) continue;
                writeMessage(work,user,title);
            }
            String next=recipients.learnerIds().isEmpty()?cursor:recipients.learnerIds().get(recipients.learnerIds().size()-1);
            jdbc.update("UPDATE notification_delivery_work SET cursor_id=?,status=?,attempts=0,last_error=NULL,next_attempt_at=? WHERE event_id=?",next,recipients.hasMore()?"PENDING":"COMPLETE",Timestamp.from(Instant.now()),eventId);
        });
    }
    /** 公告唯一消息只允许更高revision更新；确认先于投递时按独立确认事实初始化已读。普通消息以eventId唯一。 */
    private void writeMessage(Work work,String user,String title) {
        boolean announcement="COURSE_ANNOUNCEMENT".equals(work.kind());
        String source=announcement?"announcement:"+work.target():"event:"+work.eventId();
        boolean unread=!announcement || jdbc.queryForObject("SELECT COUNT(*) FROM course_announcement_confirmation WHERE announcement_id=? AND learner_id=? AND revision=?",Long.class,work.target(),user,work.revision())==0;
        var prior=jdbc.queryForList("SELECT id,revision FROM notification_message WHERE recipient_id=? AND source_key=? FOR UPDATE",user,source);
        if(prior.isEmpty()) {
            jdbc.update("INSERT INTO notification_message(id,recipient_id,source_key,kind,action,title,target_id,course_id,revision,unread,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),user,source,work.kind(),work.action(),title,work.target(),work.course(),work.revision(),unread,Timestamp.from(work.at()));
        } else if(announcement && ((Number)prior.get(0).get("revision")).longValue()<work.revision()) {
            jdbc.update("UPDATE notification_message SET title=?,revision=?,unread=?,occurred_at=? WHERE id=?",title,work.revision(),unread,Timestamp.from(work.at()),prior.get(0).get("id"));
        }
    }
    private void suppress(String id) { jdbc.update("UPDATE notification_delivery_work SET status='SUPPRESSED' WHERE event_id=?",id); }
    /** 失败独立事务锁定工作，留安全类型；已完成/撤回不会被失败回调重新打开。 */
    private void fail(String id,RuntimeException failure) {
        transactions.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT attempts FROM notification_delivery_work WHERE event_id=? AND status='PENDING' FOR UPDATE",id);
            if(rows.isEmpty()) return; int attempts=((Number)rows.get(0).get("attempts")).intValue()+1;
            jdbc.update("UPDATE notification_delivery_work SET attempts=?,status=?,next_attempt_at=?,last_error=? WHERE event_id=?",attempts,attempts>=5?"FAILED":"PENDING",Timestamp.from(Instant.now().plusSeconds(1L<<Math.min(attempts,8))),failure.getClass().getSimpleName(),id);
        });
    }
    /** 不接受未知事件类型、版本、空值、非法UUID/时间/动作；不从未验证payload猜测领域事实。 */
    private Work parse(String json) {
        if(json.length()>65536) throw new IllegalArgumentException("Oversized event");
        JsonNode e=mapper.readTree(json),p=e.get("payload");
        fields(e,Set.of("eventId","eventType","eventVersion","aggregateId","occurredAt","traceId","payload"));
        String event=id(e,"eventId"),aggregate=id(e,"aggregateId"),type=text(e,"eventType");
        if(!e.path("eventVersion").isIntegralNumber() || e.path("eventVersion").asInt()!=1 || !text(e,"traceId").matches("[A-Za-z0-9_-]{8,64}") || p==null || !p.isObject()) throw new IllegalArgumentException("Invalid envelope");
        Instant occurred=Instant.parse(text(e,"occurredAt"));
        String kind,target,course=null,recipient=null,action; long revision=1; Instant at=occurred;
        switch(type) {
            case "CourseAnnouncementChanged" -> { kind="COURSE_ANNOUNCEMENT";target=id(p,"announcementId");course=id(p,"courseId");action=text(p,"action");revision=revision(p,"revision");at=Instant.parse(text(p,"triggeredAt"));if(!Set.of("ANNOUNCEMENT","WITHDRAWN").contains(action)) throw new IllegalArgumentException("Invalid action"); }
            case "CourseTaskNotificationChanged" -> { kind="COURSE_TASK";target=id(p,"assessmentId");course=id(p,"courseId");action=text(p,"action");revision=revision(p,"revision");at=Instant.parse(text(p,"triggeredAt"));if(!Set.of("OPENED","DEADLINE_CHANGED","ENDED").contains(action)) throw new IllegalArgumentException("Invalid action"); }
            case "CourseAvailabilityChanged" -> { kind="COURSE_AVAILABILITY";target=id(p,"courseId");course=target;revision=revision(p,"availabilityRevision");id(p,"suspensionId"); if(!p.path("paused").isBoolean()) throw new IllegalArgumentException("Invalid availability");boolean paused=p.path("paused").asBoolean();action=paused?"PAUSED":"RESUMED";Instant.parse(text(p,"suspendedAt"));if(paused) { if(!p.has("resumedAt") || !p.get("resumedAt").isNull()) throw new IllegalArgumentException("Invalid interval"); } else Instant.parse(text(p,"resumedAt")); }
            case "TeacherQualificationDecided" -> { kind="TEACHER_REVIEW";target=id(p,"applicationId");recipient=id(p,"userId");id(p,"roundId");action=text(p,"decision");if(!Set.of("APPROVED","REJECTED").contains(action)) throw new IllegalArgumentException("Invalid decision"); }
            default -> throw new IllegalArgumentException("Unsupported notification event");
        }
        fields(p,switch(type) {
            case "CourseAnnouncementChanged" -> Set.of("announcementId","courseId","revision","action","triggeredAt");
            case "CourseTaskNotificationChanged" -> Set.of("assessmentId","courseId","revision","action","triggeredAt");
            case "CourseAvailabilityChanged" -> Set.of("courseId","suspensionId","availabilityRevision","paused","suspendedAt","resumedAt");
            default -> Set.of("applicationId","roundId","userId","decision");
        });
        if(!aggregate.equals(target)) throw new IllegalArgumentException("Invalid aggregate");
        return new Work(event,kind,action,target,course,recipient,revision,at);
    }
    /** 信封/载荷字段必须与所属版本契约一致，额外正文或隐私字段不得进入消费台账。 */
    private static void fields(JsonNode value,Set<String> allowed) {
        if(value==null || !value.isObject() || value.size()!=allowed.size())throw new IllegalArgumentException("Invalid event fields");
        for(var property:value.properties())if(!allowed.contains(property.getKey()))throw new IllegalArgumentException("Invalid event fields");
    }
    private static String text(JsonNode n,String field) { JsonNode v=n.get(field); if(v==null || !v.isString() || v.asString().isBlank()) throw new IllegalArgumentException("Missing event field"); return v.asString(); }
    private static String id(JsonNode n,String field) { return UUID.fromString(text(n,field)).toString(); }
    private static long revision(JsonNode n,String field) { if(!n.path(field).isIntegralNumber() || n.path(field).asLong()<1) throw new IllegalArgumentException("Invalid revision");return n.path(field).asLong(); }
    private record Work(String eventId,String kind,String action,String target,String course,String recipient,long revision,Instant at) {}
}
