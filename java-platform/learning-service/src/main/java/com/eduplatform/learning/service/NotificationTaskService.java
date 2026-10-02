package com.eduplatform.learning.service;

import com.eduplatform.learning.course.CourseTaskClock;
import com.eduplatform.learning.messaging.LearningEvents;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.persistence.LearningAssessmentRepository.AssessmentRow;
import com.eduplatform.learning.web.LearningDtos;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真正开放的持久触发与安全任务事实；复用原Course时钟，不改变作答/暂停/结算规则。 */
@Service
public class NotificationTaskService {
    private final JdbcTemplate jdbc;
    private final LearningAssessmentRepository repository;
    private final LearningEvents events;
    private final CourseTaskClock clock;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    public NotificationTaskService(JdbcTemplate jdbc,LearningAssessmentRepository repository,LearningEvents events,
            CourseTaskClock clock,PlatformTransactionManager manager,
            @Value("${LEARNING_NOTIFICATION_OPENING_ENABLED:true}") boolean enabled) {
        this.jdbc=jdbc; this.repository=repository; this.events=events; this.clock=clock;
        this.transactions=new TransactionTemplate(manager); this.enabled=enabled;
    }
    /** 发布事务只为HOMEWORK/EXAM登记持久工作；已到真正开放点时同步写OPENED事实/Outbox，不以发布一概通知。 */
    public void schedule(String id) {
        AssessmentRow row=repository.assessment(id).orElseThrow(this::notFound);
        if(!supported(row)) return;
        jdbc.update("INSERT INTO assessment_notification_opening(assessment_id,next_attempt_at) VALUES(?,?) ON DUPLICATE KEY UPDATE assessment_id=assessment_id",id,Timestamp.from(opens(row)));
        open(id);
    }
    /** 每轮最多10场，宕机后按持久到期事实追平；暂停不耗失败预算，依赖故障最多5次且保留FAILED。 */
    @Scheduled(fixedDelayString="${LEARNING_NOTIFICATION_OPENING_POLL_DELAY:2s}")
    public void tick() {
        if(!enabled) return;
        List<String> ids=jdbc.query("SELECT assessment_id FROM assessment_notification_opening WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,assessment_id LIMIT 10",(rs,n)->rs.getString(1),Timestamp.from(Instant.now()));
        for(String id:ids) {
            try { open(id); }
            catch(RuntimeException failure) { fail(id,failure); }
        }
    }
    /** 课程→测评→触发行锁保护单次开放；恢复先应用原补时，真正开放时点确定历史收件人，过期/结束抑制。 */
    public void open(String id) {
        AssessmentRow initial=repository.assessment(id).orElseThrow(this::notFound);
        if(!supported(initial)) return;
        Boolean processed=clock.within(initial.courseId(),null,true,()->{
            AssessmentRow row=repository.lockOwnedAssessment(id,initial.ownerTeacherId());
            var work=jdbc.queryForList("SELECT status FROM assessment_notification_opening WHERE assessment_id=? FOR UPDATE",id);
            if(work.isEmpty() || !"PENDING".equals(work.get(0).get("status"))) return true;
            Instant now=Instant.now();
            if(row.status()!=LearningDtos.AssessmentStatus.PUBLISHED || ended(row,now)) {
                jdbc.update("UPDATE assessment_notification_opening SET status='SUPPRESSED' WHERE assessment_id=?",id); return true;
            }
            Instant at=opens(row);
            if(at.isAfter(now)) {
                jdbc.update("UPDATE assessment_notification_opening SET next_attempt_at=? WHERE assessment_id=?",Timestamp.from(at),id); return true;
            }
            emit(row,"OPENED",at,UUID.randomUUID().toString());
            jdbc.update("UPDATE assessment_notification_opening SET status='OPENED',opened_at=?,last_error=NULL WHERE assessment_id=?",Timestamp.from(at),id);
            return true;
        });
        if(processed==null) jdbc.update("UPDATE assessment_notification_opening SET next_attempt_at=? WHERE assessment_id=? AND status='PENDING'",Timestamp.from(Instant.now().plusSeconds(2)),id);
    }
    /** 教师截止/立即结束在已有领域事务调用；只发支持任务，失败回滚该领域事务，不把发布当开放。 */
    public void changed(String id,String action,Instant at,String traceId) {
        AssessmentRow row=repository.assessment(id).orElseThrow(this::notFound);
        if(supported(row)) emit(row,action,at,traceId);
    }
    /** 返回最新安全任务投影；暂停返回不可投递，恢复实时补时后再判断。未知/PRACTICE安全404，依赖故障传播503。 */
    public Fact fact(String id) {
        AssessmentRow initial=repository.assessment(id).orElseThrow(this::notFound);
        if(!supported(initial)) throw notFound();
        Fact fact=clock.within(initial.courseId(),null,true,()->toFact(repository.assessment(id).orElseThrow(this::notFound),true));
        return fact==null ? toFact(initial,false):fact;
    }
    private Fact toFact(AssessmentRow row,boolean active) {
        Instant now=Instant.now();
        return new Fact(row.id(),row.courseId(),row.title(),row.revision(),active && row.status()==LearningDtos.AssessmentStatus.PUBLISHED && !opens(row).isAfter(now) && !ended(row,now));
    }
    private static boolean supported(AssessmentRow row) {
        return row.courseId()!=null && (row.type()==LearningDtos.AssessmentType.HOMEWORK || row.type()==LearningDtos.AssessmentType.EXAM);
    }
    private static Instant opens(AssessmentRow row) {
        Instant published=row.publishedAt()==null ? Instant.now():row.publishedAt();
        return row.opensAt()==null || row.opensAt().isBefore(published) ? published:row.opensAt();
    }
    private static boolean ended(AssessmentRow row,Instant now) {
        Instant end=row.latePolicy()==LearningDtos.HomeworkLatePolicy.ALLOW_UNTIL ? row.lateUntil():row.closesAt();
        return row.closeStatus()==LearningDtos.AssessmentCloseStatus.CLOSED || end!=null && !end.isAfter(now);
    }
    private void emit(AssessmentRow row,String action,Instant at,String trace) {
        repository.insertOutbox(events.taskNotificationChanged(row.id(),row.courseId(),row.revision(),action,at,trace,Instant.now()));
    }
    /** 单场失败独立事务记录安全异常类型和指数退避；其他场继续，FAILED不自动重放。 */
    private void fail(String id,RuntimeException failure) {
        transactions.executeWithoutResult(status->{
            var work=jdbc.queryForList("SELECT attempts FROM assessment_notification_opening WHERE assessment_id=? AND status='PENDING' FOR UPDATE",id);
            if(work.isEmpty()) return;
            int attempts=((Number)work.get(0).get("attempts")).intValue()+1;
            jdbc.update("UPDATE assessment_notification_opening SET attempts=?,status=?,next_attempt_at=?,last_error=? WHERE assessment_id=?",attempts,attempts>=5?"FAILED":"PENDING",Timestamp.from(Instant.now().plusSeconds(1L<<Math.min(attempts,8))),failure.getClass().getSimpleName(),id);
        });
    }
    private QuestionException notFound() { return new QuestionException(HttpStatus.NOT_FOUND,"LEARNING.NOT_FOUND","学习资源不存在或不可见"); }
    public record Fact(String assessmentId,String courseId,String title,long revision,boolean deliverable) {}
}
