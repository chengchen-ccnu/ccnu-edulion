package com.eduplatform.operation.service;

import com.eduplatform.operation.domain.NotificationDomains;
import com.eduplatform.operation.web.NotificationDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 本人消息查询/计数/目标/已读共用实时授权；消息只保存必要标题和目标，不以缓存替代事实。 */
@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final NotificationDomains domains;
    public NotificationService(JdbcTemplate jdbc,NotificationDomains domains) { this.jdbc=jdbc; this.domains=domains; }
    /** 按时间分页已授权消息；数据库快照每批100，依赖失败503，退课/撤回不泄露旧标题。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public MessagePage list(String user,int page,int size) {
        var messages=scan(user); int from=(int)Math.min((long)(page-1)*size,messages.size());
        return new MessagePage(messages.subList(from,Math.min(from+size,messages.size())),page,size,messages.size());
    }
    /** 当前授权口径的真实数量；不依赖Redis角标，不返回假零。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Counts counts(String user) { return counts(scan(user)); }
    /** 普通目标页成功加载后显式请求；重新验权和版本校验，公告409要求独立我知道了。 */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Counts read(String user,String id,long revision) {
        Message message=owned(user,id,true);
        if(authorized(user,List.of(message)).isEmpty()) throw AnnouncementService.notFound();
        if(message.revision()!=revision || message.confirmationRequired()) throw AnnouncementService.conflict();
        jdbc.update("UPDATE notification_message SET unread=FALSE WHERE id=? AND recipient_id=? AND revision=?",id,user,revision);
        return counts(user);
    }
    /** 普通消息全部已读；所有依赖资格先成功，再写MySQL，跳过全部公告；异常整体回滚，返回剩余未确认数量。 */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Counts readAll(String user) {
        var messages=scan(user);
        for(Message message:messages) if(!message.confirmationRequired())
            jdbc.update("UPDATE notification_message SET unread=FALSE WHERE id=? AND recipient_id=? AND revision=?",message.id(),user,message.revision());
        return counts(user);
    }
    /** 返回重新授权的固定页面目标，不自动清未读；暂停/恢复仅通用状态和邮箱，不授权正文。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Target target(String user,String id) {
        Message message=owned(user,id,false);
        if(authorized(user,List.of(message)).isEmpty()) throw AnnouncementService.notFound();
        String type=message.kind(); boolean paused=false; String email=null;
        if("COURSE_AVAILABILITY".equals(type)) {
            type="COURSE_STATUS";
            var course=domains.courses(List.of(message.courseId())).stream().findFirst().orElseThrow(AnnouncementService::notFound);
            paused=course.paused(); email=course.supportEmail();
        }
        return new Target(type,message.targetId(),message.courseId(),message.revision(),paused,email);
    }
    /** 单页共享资格合并：Course当前发布/暂停、Learning当前报名/触发区间/任务目标、IAM本人申请。 */
    public List<Message> authorized(String user,List<Message> input) {
        if(input.isEmpty()) return List.of();
        List<String> courseIds=input.stream().map(Message::courseId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<String,NotificationDomains.Course> courses=domains.courses(courseIds).stream().collect(Collectors.toMap(NotificationDomains.Course::courseId,c->c));
        List<String> taskIds=input.stream().filter(m->"COURSE_TASK".equals(m.kind())).map(Message::targetId).distinct().toList();
        List<NotificationDomains.Check> checks=input.stream().filter(m->m.courseId()!=null).map(m->new NotificationDomains.Check(m.id(),m.courseId(),m.occurredAt())).toList();
        var access=courseIds.isEmpty()?new NotificationDomains.Access(List.of(),List.of(),List.of()):domains.access(user,courseIds,taskIds,checks);
        List<String> reviewIds=input.stream().filter(m->"TEACHER_REVIEW".equals(m.kind())).map(Message::targetId).distinct().toList();
        Set<String> reviews=Set.copyOf(domains.reviews(user,reviewIds));
        var result=new ArrayList<Message>();
        for(Message m:input) {
            if("TEACHER_REVIEW".equals(m.kind())) { if(reviews.contains(m.targetId())) result.add(m); continue; }
            var course=courses.get(m.courseId());
            if(course==null || course.ownerTeacherId().equals(user) || !access.activeCourseIds().contains(m.courseId()) || !access.eligibleKeys().contains(m.id())) continue;
            if("COURSE_AVAILABILITY".equals(m.kind())) { result.add(m); continue; }
            if(!course.readable()) continue;
            if("COURSE_TASK".equals(m.kind())) { if(access.accessibleAssessmentIds().contains(m.targetId())) result.add(m); continue; }
            if("COURSE_ANNOUNCEMENT".equals(m.kind())) {
                long active=jdbc.queryForObject("SELECT COUNT(*) FROM course_announcement WHERE id=? AND course_id=? AND status='ACTIVE' AND revision=?",Long.class,m.targetId(),m.courseId(),m.revision());
                if(active>0) result.add(m);
            }
        }
        return result;
    }
    private List<Message> scan(String user) {
        List<Message> result=new ArrayList<>();
        // 快照事务中按不变id推进，每个SQL/HTTP批次最多100；没有TTL或用户累计消息数上限。
        String after="";
        while(true) {
            var batch=jdbc.query("SELECT * FROM notification_message WHERE recipient_id=? AND id>? ORDER BY id LIMIT 100",this::row,user,after);
            if(batch.isEmpty())break;
            result.addAll(authorized(user,batch));
            after=batch.get(batch.size()-1).id();
        }
        result.sort(java.util.Comparator.comparing(Message::occurredAt).thenComparing(Message::id).reversed());
        return result;
    }
    private Message owned(String user,String id,boolean lock) {
        return jdbc.query("SELECT * FROM notification_message WHERE recipient_id=? AND id=?"+(lock?" FOR UPDATE":""),this::row,user,id).stream().findFirst().orElseThrow(AnnouncementService::notFound);
    }
    private Message row(ResultSet rs,int n)throws SQLException {
        String kind=rs.getString("kind");
        return new Message(rs.getString("id"),kind,rs.getString("action"),rs.getString("title"),rs.getString("target_id"),rs.getString("course_id"),rs.getLong("revision"),rs.getBoolean("unread"),"COURSE_ANNOUNCEMENT".equals(kind),rs.getTimestamp("occurred_at").toInstant());
    }
    private static Counts counts(List<Message> messages) {
        return new Counts(messages.stream().filter(Message::unread).count(),messages.stream().filter(m->m.confirmationRequired() && m.unread()).count());
    }
}
