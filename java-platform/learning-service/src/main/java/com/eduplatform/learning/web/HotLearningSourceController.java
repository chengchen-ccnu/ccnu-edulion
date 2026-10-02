package com.eduplatform.learning.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.eduplatform.learning.service.QuestionException;

/** 热点专用本域可信聚合；输入稳定课程ID，输出去重学习/当前报名，不公开学生名单。 */
@RestController
public class HotLearningSourceController {
    private final JdbcTemplate jdbc;
    private final String secret;
    public HotLearningSourceController(JdbcTemplate jdbc,@Value("${OPERATION_INTERNAL_SERVICE_TOKEN:}") String secret) {
        this.jdbc=jdbc; this.secret=secret;
    }
    /** 同一只读一致快照内按七个上海日合并稳定学生ID；取消/重报只读当前ACTIVE，不依赖事件顺序。 */
    @GetMapping("/internal/v1/hot-learning-source/{courseId}")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Fact source(@PathVariable UUID courseId,
            @RequestHeader(value="X-Internal-Caller",defaultValue="") String caller,
            @RequestHeader(value="X-Internal-Service-Token",defaultValue="") String token) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32 || !"operation-service".equals(caller)
                || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            throw new QuestionException(HttpStatus.UNAUTHORIZED,"LEARNING.INTERNAL_UNAUTHORIZED","内部调用身份无效");
        LocalDate end=LocalDate.now(ZoneId.of("Asia/Shanghai")),start=end.minusDays(6);
        String id=courseId.toString();
        // UNION按学生去重：内容改版/同人跨日/跨课时均不重复；旧累计进度没有日事实时不伪造历史。
        long learners=jdbc.queryForObject("SELECT COUNT(DISTINCT learner_id) FROM ("
                +"SELECT learner_id FROM hot_learning_day WHERE course_id=? AND activity_date BETWEEN ? AND ? "
                +"UNION SELECT learner_id FROM lesson_learning_day WHERE course_id=? AND activity_date BETWEEN ? AND ? AND (watched_seconds>0 OR first_completed=TRUE) "
                +"UNION SELECT t.learner_id FROM assessment_attempt t JOIN assessment a ON a.id=t.assessment_id WHERE a.course_id=? AND a.ownership_type='COURSE' AND t.status<>'DRAFT' AND t.auto_settled=FALSE AND t.submission_day BETWEEN ? AND ?) genuine",Long.class,
                id,java.sql.Date.valueOf(start),java.sql.Date.valueOf(end),id,java.sql.Date.valueOf(start),java.sql.Date.valueOf(end),id,java.sql.Date.valueOf(start),java.sql.Date.valueOf(end));
        long enrolled=jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE course_id=? AND status='ACTIVE'",Long.class,id);
        return new Fact(id,learners,enrolled,start,end);
    }
    public record Fact(String courseId,long learners7d,long activeEnrollments,LocalDate windowStart,LocalDate windowEnd) {}
}
