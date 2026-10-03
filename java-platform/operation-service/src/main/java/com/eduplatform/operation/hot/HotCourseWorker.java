package com.eduplatform.operation.hot;

import static com.eduplatform.operation.hot.HotCourseModels.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

/** 有界派生刷新/受控重建；持数据库协调行后读源再发布，旧扫描不能覆盖已完成的新刷新。 */
@Component
@ConditionalOnProperty(name="spring.flyway.enabled",havingValue="true")
public class HotCourseWorker {
    private final JdbcTemplate jdbc;
    private final HotCourseSources sources;
    private final HotCourseCache cache;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final boolean enabled;
    public HotCourseWorker(JdbcTemplate jdbc,HotCourseSources sources,HotCourseCache cache,ObjectMapper mapper,
            PlatformTransactionManager manager,MeterRegistry meters,@Value("${platform.operation.hot.worker-enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.sources=sources;this.cache=cache;this.mapper=mapper;this.meters=meters;this.enabled=enabled;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 每5秒最多50个失效ID和50个源卡片；故障留dirty/游标并计数，下轮自动补偿，不重放历史FAILED。 */
    @Scheduled(fixedDelayString="${platform.operation.hot.poll-delay:5s}")
    public void tick() {
        if(!enabled) return;
        try {refresh();}catch(RuntimeException failure) {
            meters.counter("operation.hot.refresh.failure").increment();
            jdbc.update("UPDATE hot_course_refresh_state SET last_failure_at=? WHERE id=1",Timestamp.from(Instant.now()));
        }
    }
    /** 显式后台刷新入口（非REST），每次只处理有界批次；成功才清dirty并推进重建游标，Redis失败回滚。 */
    public void refresh() {
        long started=System.nanoTime();
        tx.executeWithoutResult(status->{
            var state=jdbc.queryForMap("SELECT rebuild_after,last_completed_at FROM hot_course_refresh_state WHERE id=1 FOR UPDATE");
            Instant now=Instant.now();LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
            List<String> dirty=jdbc.queryForList("SELECT course_id FROM hot_course_dirty ORDER BY course_id LIMIT 50",String.class);
            for(String id:dirty) {replace(sources.card(id),now);jdbc.update("DELETE FROM hot_course_dirty WHERE course_id=?",id);}
            Timestamp completed=(Timestamp)state.get("last_completed_at");String after=(String)state.get("rebuild_after");
            if(completed==null || !after.isEmpty() || completed.toInstant().plusSeconds(30).isBefore(now)) {
                SourcePage page=sources.page(after);
                for(SourceCard card:page.items()) replace(card,now);
                if(page.hasMore()) {
                    jdbc.update("UPDATE hot_course_refresh_state SET rebuild_after=? WHERE id=1",page.items().get(page.items().size()-1).courseId());
                } else {
                    jdbc.update("UPDATE hot_course_refresh_state SET rebuild_after='',last_completed_at=?,last_failure_at=NULL WHERE id=1",Timestamp.from(now));
                    completed=Timestamp.from(now);
                }
            }
            // 首轮/零点尚未扫完不能冒充全量排名；老投影不重复续命，领域失联最长90秒后明确降级。
            Long stale=jdbc.queryForObject("SELECT COUNT(*) FROM hot_course_projection WHERE window_end<>? OR refreshed_at<?",Long.class,
                    java.sql.Date.valueOf(today),Timestamp.from(now.minusSeconds(90)));
            if(completed==null || stale>0) {cache.invalidate();return;}
            List<Card> ranked=jdbc.query("SELECT card_json,learners_7d,active_enrollments FROM hot_course_projection WHERE window_end=? AND (learners_7d>0 OR published_at>=?) ORDER BY CASE WHEN learners_7d>0 THEN 0 ELSE 1 END,learners_7d DESC,CASE WHEN learners_7d>0 THEN active_enrollments ELSE 0 END DESC,published_at DESC,course_id ASC LIMIT 6",
                    (rs,n)->publicCard(mapper.readValue(rs.getString(1),SourceCard.class),rs.getLong(2),rs.getLong(3)),
                    java.sql.Date.valueOf(today),Timestamp.from(now.minus(Duration.ofDays(30))));
            Timestamp oldest=jdbc.queryForObject("SELECT MIN(refreshed_at) FROM hot_course_projection",Timestamp.class);
            // 不能用每5秒重新SET把即将过期的源投影再延长90秒；缓存期限受最老可信刷新时点约束。
            Instant sourceUntil=oldest==null?now.plusSeconds(90):oldest.toInstant().plusSeconds(90);
            cache.publish(new Board(ranked.isEmpty()?"EMPTY":"READY",ranked,now,sourceUntil,today.minusDays(6),today));
        });
        meters.counter("operation.hot.refresh.success").increment();
        meters.timer("operation.hot.refresh.duration").record(Duration.ofNanos(System.nanoTime()-started));
    }
    /** 非公开事实删除旧卡片；公开卡片和人数只在本域派生表存储，窗口不一致拒绝不污染榜单。 */
    private void replace(SourceCard card,Instant now) {
        if(!card.visible()) {jdbc.update("DELETE FROM hot_course_projection WHERE course_id=?",card.courseId());return;}
        Learning fact=sources.learning(card.courseId());
        jdbc.update("INSERT INTO hot_course_projection(course_id,card_json,learners_7d,active_enrollments,window_end,published_at,refreshed_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE card_json=VALUES(card_json),learners_7d=VALUES(learners_7d),active_enrollments=VALUES(active_enrollments),window_end=VALUES(window_end),published_at=VALUES(published_at),refreshed_at=VALUES(refreshed_at)",
                card.courseId(),mapper.writeValueAsString(card),fact.learners7d(),fact.activeEnrollments(),java.sql.Date.valueOf(fact.windowEnd()),Timestamp.from(card.publishedAt()),Timestamp.from(now));
    }
    /** 将前六项授权封面投影到Redis，封面失败只返回UNAVAILABLE，永久URL和旧签名从不回退。 */
    private Card publicCard(SourceCard card,long learners,long active) {
        String url=null,coverStatus=card.coverMediaId()==null?"ABSENT":"UNAVAILABLE";Instant expires=null;
        if(card.coverMediaId()!=null) {
            try {Cover grant=sources.cover(card.courseId(),card.coverMediaId());url=grant.url();expires=grant.expiresAt();coverStatus="READY";}
            catch(RuntimeException failure) {meters.counter("operation.hot.cover.failure").increment();}
        }
        return new Card(card.courseId(),card.title(),card.summary(),card.instructorDisplayName(),card.categoryId(),card.level(),card.accessType(),card.publishedAt(),
                learners>0?"HOT":"NEW",learners,active,card.coverMediaId(),coverStatus,url,expires);
    }
    /** 受控运维重建请求只重置本任务游标并失效Redis；下一轮经同一序列化路径读取源，核心事实不变。 */
    public void requestRebuild() {
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT id FROM hot_course_refresh_state WHERE id=1 FOR UPDATE",Integer.class);
            jdbc.update("UPDATE hot_course_refresh_state SET rebuild_after='',last_completed_at=NULL WHERE id=1");cache.invalidate();
        });
    }
}
