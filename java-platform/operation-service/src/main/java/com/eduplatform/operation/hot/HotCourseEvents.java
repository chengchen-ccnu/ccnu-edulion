package com.eduplatform.operation.hot;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 热点失效信号收件箱；eventId与dirty同事务，成功只表示需求可靠登记，不表示排名已刷新。 */
@Service
@ConditionalOnProperty(name="spring.flyway.enabled",havingValue="true")
public class HotCourseEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HotCourseCache cache;
    public HotCourseEvents(JdbcTemplate jdbc,ObjectMapper mapper,HotCourseCache cache) {this.jdbc=jdbc;this.mapper=mapper;this.cache=cache;}
    /** 输入v1合法信封，输出持久失效需求；旧/乱序只重查最新事实，毒消息拒绝交独立DLQ。 */
    @Transactional
    public void consume(String json) {
        if(json.length()>65536) throw new IllegalArgumentException("热点信封过大");
        var event=mapper.readTree(json);
        if(event==null || !Set.of("CoursePortalChanged","CourseLearningDayQualified","CourseAvailabilityChanged","EnrollmentAccessChanged").contains(event.path("eventType").asText())
                || event.path("eventVersion").asInt()!=1) throw new IllegalArgumentException("热点事件版本/类型不合法");
        String eventId=UUID.fromString(event.path("eventId").asText()).toString();
        UUID.fromString(event.path("aggregateId").asText());Instant.parse(event.path("occurredAt").asText());
        String trace=event.path("traceId").asText();if(trace.length()<8 || trace.length()>64) throw new IllegalArgumentException("热点事件Trace不合法");
        String courseId=UUID.fromString(event.path("payload").path("courseId").asText()).toString();
        // 与刷新/重建共享锁；刷新结束前来的新信号在其后登记，不能被旧批次DELETE掉。
        jdbc.queryForObject("SELECT id FROM hot_course_refresh_state WHERE id=1 FOR UPDATE",Integer.class);
        var previous=jdbc.queryForList("SELECT course_id FROM hot_course_consumed_event WHERE event_id=?",String.class,eventId);
        if(!previous.isEmpty()) {
            if(!courseId.equals(previous.get(0))) throw new IllegalArgumentException("热点事件身份冲突");
            return;
        }
        jdbc.update("INSERT INTO hot_course_consumed_event(event_id,course_id,consumed_at) VALUES(?,?,?)",eventId,courseId,Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO hot_course_dirty(course_id) VALUES(?) ON DUPLICATE KEY UPDATE course_id=course_id",courseId);
        cache.invalidate();
    }
}
