package com.eduplatform.operation.service;

import com.eduplatform.operation.domain.NotificationDomains;
import com.eduplatform.operation.web.NotificationDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 稳定公告/审计/显式确认事实；跨域仅HTTP授权，锁序actor→公告→消息，写失败整体回滚。 */
@Service
public class AnnouncementService {
    private final JdbcTemplate jdbc;
    private final NotificationDomains domains;
    private final ObjectMapper mapper;
    public AnnouncementService(JdbcTemplate jdbc,NotificationDomains domains,ObjectMapper mapper) {
        this.jdbc=jdbc; this.domains=domains; this.mapper=mapper;
    }
    /** owner在已发布非暂停课程发布纯文本；同请求精确重放，公告/版本/Outbox同事务，不逐学生跨域fan-out。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Announcement create(String actor,String course,Create input,String trace) {
        requireOwner(actor,course,true); lockActor(actor);
        Map<String,Object> command=Map.of("action","CREATE","courseId",course,"input",input);
        Announcement replay=replay(actor,input.clientRequestId(),command); if(replay!=null) return replay;
        validateText(input.title(),input.body());
        Instant now=now(); String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO course_announcement(id,course_id,revision,status,title,body,published_at,updated_at) VALUES(?,?,1,'ACTIVE',?,?,?,?)",id,course,input.title(),input.body(),ts(now),ts(now));
        Announcement row=getRaw(id,course,false); audit(actor,row); emit(row,"ANNOUNCEMENT",trace);
        save(actor,input.clientRequestId(),command,row); return row;
    }
    /** owner改版或撤回需当前revision；保存新审计，更新同一消息标题/未读，确认保留旧revision不能确认新版。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Announcement change(String actor,String course,String id,Update input,boolean withdraw,String trace) {
        requireOwner(actor,course,true); lockActor(actor);
        Map<String,Object> command=Map.of("action",withdraw?"WITHDRAW":"UPDATE","courseId",course,"id",id,"input",input);
        Announcement replay=replay(actor,input.clientRequestId(),command); if(replay!=null) return replay;
        Announcement current=getRaw(id,course,true);
        if(current.revision()!=input.expectedRevision() || !"ACTIVE".equals(current.status())) throw conflict();
        String title=withdraw?current.title():input.title(); String body=withdraw?current.body():input.body();
        validateText(title,body); Instant now=now();
        jdbc.update("UPDATE course_announcement SET revision=revision+1,status=?,title=?,body=?,updated_at=? WHERE id=?",withdraw?"WITHDRAWN":"ACTIVE",title,body,ts(now),id);
        Announcement row=getRaw(id,course,false); audit(actor,row);
        // 立即推进已有消息，异步投递只补当次合资格者；授权同时检查触发区间，退课期间改版不补提醒。
        jdbc.update("UPDATE notification_message SET title=?,revision=?,unread=TRUE,occurred_at=? WHERE kind='COURSE_ANNOUNCEMENT' AND target_id=?",title,row.revision(),ts(now),id);
        emit(row,withdraw?"WITHDRAWN":"ANNOUNCEMENT",trace); save(actor,input.clientRequestId(),command,row); return row;
    }
    /** 当前ACTIVE学生独立读取有效最新版；游客/试看/退课/撤回安全404，公共课程响应不携带正文。 */
    public Announcement studentGet(String user,String course,String id) {
        requireStudent(user,course);
        Announcement row=getRaw(id,course,false);
        if(!"ACTIVE".equals(row.status())) throw notFound();
        return decorated(user,row);
    }
    /** 学生/owner有界公告分页，学生未确认摘要只来自当次有资格消息，不为后报名者制造置顶。 */
    public AnnouncementPage list(String user,String course,boolean teacher,int page,int size) {
        if(teacher) requireOwner(user,course,false); else requireStudent(user,course);
        String where="course_id=?"+(teacher?"":" AND status='ACTIVE'");
        List<Announcement> rows=jdbc.query("SELECT * FROM course_announcement WHERE "+where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",this::row,course,size,(page-1)*size);
        var items=teacher?rows:decorated(user,rows);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM course_announcement WHERE "+where,Long.class,course);
        if(teacher) return new AnnouncementPage(items,page,size,total,null,0);
        long count=0; Announcement latest=null; String after="";
        // 摘要只扫描本课程现存消息；每批100且按稳定ID推进，不设累计公告数量上限。
        while(true) {
            var batch=jdbc.query("SELECT a.* FROM course_announcement a JOIN notification_message m ON m.target_id=a.id AND m.recipient_id=? AND m.kind='COURSE_ANNOUNCEMENT' WHERE a.course_id=? AND a.status='ACTIVE' AND a.id>? ORDER BY a.id LIMIT 100",this::row,user,course,after);
            if(batch.isEmpty()) break;
            for(Announcement view:decorated(user,batch)) {
                after=view.id();
                if(view.confirmationRequired() && !view.confirmed()) {
                    count++; if(latest==null || view.updatedAt().isAfter(latest.updatedAt())) latest=view;
                }
            }
        }
        return new AnnouncementPage(items,page,size,total,latest,count);
    }
    /** owner审计分页只读不可变版本；不提供个人确认名单或催读。 */
    public AuditPage revisions(String actor,String course,String id,int page,int size) {
        requireOwner(actor,course,false); getRaw(id,course,false);
        var rows=jdbc.query("SELECT revision,status,title,body,changed_at FROM course_announcement_revision WHERE announcement_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",(rs,n)->new Audit(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant()),id,size,(page-1)*size);
        return new AuditPage(rows,page,size,jdbc.queryForObject("SELECT COUNT(*) FROM course_announcement_revision WHERE announcement_id=?",Long.class,id));
    }
    /** 明确我知道了同时确认精确版本/清未读；与改版共用公告锁，旧版本409，消息未投递时确认仍独立保存。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Announcement confirm(String user,String course,String id,Revision input) {
        requireStudent(user,course); lockActor(user); Announcement row=getRaw(id,course,true);
        if(!"ACTIVE".equals(row.status())) throw notFound();
        if(row.revision()!=input.expectedRevision()) throw conflict();
        var command=Map.of("action","CONFIRM","courseId",course,"id",id,"input",input);
        Announcement replay=replay(user,input.clientRequestId(),command); if(replay!=null) return replay;
        jdbc.update("INSERT INTO course_announcement_confirmation(announcement_id,learner_id,revision,confirmed_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),confirmed_at=VALUES(confirmed_at)",id,user,row.revision(),ts(now()));
        jdbc.update("UPDATE notification_message SET unread=FALSE WHERE recipient_id=? AND kind='COURSE_ANNOUNCEMENT' AND target_id=? AND revision=?",user,id,row.revision());
        Announcement result=decorated(user,row); save(user,input.clientRequestId(),command,result); return result;
    }
    /** 最小Course事实决定owner写授权；只读历史允许owner查看撤回，任何写仅PUBLISHED且非暂停。 */
    public NotificationDomains.Course requireOwner(String actor,String course,boolean write) {
        var fact=domains.courses(List.of(course)).stream().findFirst().orElseThrow(AnnouncementService::notFound);
        if(!actor.equals(fact.ownerTeacherId())) throw notFound();
        if(write && (!"PUBLISHED".equals(fact.publicationStatus()) || fact.paused()))
            throw new OperationException(HttpStatus.CONFLICT,"OPERATION.COURSE_NOT_PUBLISHED","课程当前不可发布或修改公告");
        return fact;
    }
    /** ACTIVE报名和Course当前访问须同时允许；owner不作为学生收件人，不借缓存授权历史正文。 */
    public void requireStudent(String user,String course) {
        var fact=domains.courses(List.of(course)).stream().findFirst().orElseThrow(AnnouncementService::notFound);
        var access=domains.access(user,List.of(course),List.of(),List.of());
        if(!fact.readable() || fact.ownerTeacherId().equals(user) || !access.activeCourseIds().contains(course)) throw notFound();
    }
    private Announcement decorated(String user,Announcement a) {
        return decorated(user,List.of(a)).get(0);
    }
    /** 同课程单页最多100公告，单次Learning历史资格请求与本域批量确认；避免摘要逐公告HTTP放大依赖。 */
    private List<Announcement> decorated(String user,List<Announcement> announcements) {
        if(announcements.isEmpty()) return List.of();
        String marks=String.join(",",java.util.Collections.nCopies(announcements.size(),"?"));
        var args=new java.util.ArrayList<Object>();args.add(user);announcements.forEach(a->args.add(a.id()));
        var messages=jdbc.queryForList("SELECT id,target_id,revision,occurred_at FROM notification_message WHERE recipient_id=? AND kind='COURSE_ANNOUNCEMENT' AND target_id IN ("+marks+")",args.toArray());
        var revisions=announcements.stream().collect(java.util.stream.Collectors.toMap(Announcement::id,Announcement::revision));
        String course=announcements.get(0).courseId();
        var current=messages.stream().filter(m->revisions.get(m.get("target_id")).longValue()==((Number)m.get("revision")).longValue()).toList();
        var checks=current.stream().map(m->new NotificationDomains.Check((String)m.get("id"),course,((Timestamp)m.get("occurred_at")).toInstant())).toList();
        var eligible=checks.isEmpty()?List.<String>of():domains.access(user,List.of(course),List.of(),checks).eligibleKeys();
        var required=current.stream().filter(m->eligible.contains(m.get("id"))).map(m->(String)m.get("target_id")).collect(java.util.stream.Collectors.toSet());
        var confirmations=jdbc.queryForList("SELECT announcement_id,revision FROM course_announcement_confirmation WHERE learner_id=? AND announcement_id IN ("+marks+")",args.toArray());
        var confirmed=confirmations.stream().filter(c->revisions.get(c.get("announcement_id")).longValue()==((Number)c.get("revision")).longValue()).map(c->(String)c.get("announcement_id")).collect(java.util.stream.Collectors.toSet());
        return announcements.stream().map(a->new Announcement(a.id(),a.courseId(),a.revision(),a.status(),a.title(),a.body(),a.publishedAt(),a.updatedAt(),required.contains(a.id()),confirmed.contains(a.id()))).toList();
    }
    private Announcement getRaw(String id,String course,boolean lock) {
        return jdbc.query("SELECT * FROM course_announcement WHERE id=? AND course_id=?"+(lock?" FOR UPDATE":""),this::row,id,course).stream().findFirst().orElseThrow(AnnouncementService::notFound);
    }
    private Announcement row(ResultSet rs,int n)throws SQLException {
        return new Announcement(rs.getString("id"),rs.getString("course_id"),rs.getLong("revision"),rs.getString("status"),rs.getString("title"),rs.getString("body"),rs.getTimestamp("published_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),false,false);
    }
    private void audit(String actor,Announcement a) {
        jdbc.update("INSERT INTO course_announcement_revision(announcement_id,revision,actor_id,status,title,body,changed_at) VALUES(?,?,?,?,?,?,?)",a.id(),a.revision(),actor,a.status(),a.title(),a.body(),ts(a.updatedAt()));
    }
    private void emit(Announcement a,String action,String trace) {
        String id=UUID.randomUUID().toString();
        var payload=Map.of("announcementId",a.id(),"courseId",a.courseId(),"revision",a.revision(),"action",action,"triggeredAt",a.updatedAt());
        var event=Map.of("eventId",id,"eventType","CourseAnnouncementChanged","eventVersion",1,"aggregateId",a.id(),"occurredAt",a.updatedAt(),"traceId",trace,"payload",payload);
        jdbc.update("INSERT INTO operation_outbox_event(event_id,aggregate_id,payload_json,next_attempt_at) VALUES(?,?,?,?)",id,a.id(),mapper.writeValueAsString(event),ts(a.updatedAt()));
    }
    private void lockActor(String actor) {
        jdbc.update("INSERT INTO operation_actor_lock(actor_id) VALUES(?) ON DUPLICATE KEY UPDATE actor_id=actor_id",actor);
        jdbc.queryForObject("SELECT actor_id FROM operation_actor_lock WHERE actor_id=? FOR UPDATE",String.class,actor);
    }
    private Announcement replay(String actor,UUID request,Object input) {
        var commands=jdbc.queryForList("SELECT request_json,result_json FROM operation_command WHERE actor_id=? AND request_id=?",actor,request.toString());
        if(commands.isEmpty()) return null;
        var row=commands.get(0);
        if(!mapper.readTree(row.get("request_json").toString()).equals(mapper.valueToTree(input))) throw conflict();
        return mapper.readValue(row.get("result_json").toString(),Announcement.class);
    }
    private void save(String actor,UUID request,Object input,Announcement result) {
        jdbc.update("INSERT INTO operation_command(actor_id,request_id,request_json,result_json) VALUES(?,?,?,?)",actor,request.toString(),mapper.writeValueAsString(input),mapper.writeValueAsString(result));
    }
    private static void validateText(String title,String body) {
        if(title.isBlank() || title.length()>120 || body.isBlank() || body.length()>20000
                || title.chars().anyMatch(Character::isISOControl) || body.chars().anyMatch(c->Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t'))
            throw new OperationException(HttpStatus.UNPROCESSABLE_CONTENT,"OPERATION.VALIDATION_FAILED","标题或纯文本正文不合法");
    }
    public static OperationException notFound() { return new OperationException(HttpStatus.NOT_FOUND,"OPERATION.NOT_FOUND","资源不存在或不可见"); }
    public static OperationException conflict() { return new OperationException(HttpStatus.CONFLICT,"OPERATION.REVISION_CONFLICT","版本或状态已变化，请刷新后重试"); }
    static Timestamp ts(Instant at) { return Timestamp.from(at); }
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
