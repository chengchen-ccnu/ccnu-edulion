package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import com.eduplatform.learning.web.HotLearningSourceController;

/** 本域真实SQL窗口/日资格事务验收；随机Fixture随事务回滚，不将报名活动或旧累计当学习。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HotLearningSourceIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LearningAssessmentRepository repository;
    String secret="test_hot_read_only_32_bytes_secret";
    @Test void crossDayDistinctAndSevenDayBoundary() {
        String course=id(),learner=id(),expired=id(),enrolled=id();LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        repository.qualifyHotLearningDay(course,learner,today,Instant.now());repository.qualifyHotLearningDay(course,learner,today,Instant.now());
        repository.qualifyHotLearningDay(course,learner,today.minusDays(6),Instant.now());repository.qualifyHotLearningDay(course,expired,today.minusDays(7),Instant.now());
        repository.markLearningActivity(course,enrolled,Instant.now());
        var source=new HotLearningSourceController(jdbc,secret);var fact=source.source(UUID.fromString(course),"operation-service",secret);
        assertThat(fact.learners7d()).isEqualTo(1);assertThat(fact.activeEnrollments()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hot_learning_day WHERE course_id=?",Long.class,course)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learning_outbox_event WHERE aggregate_id=? AND event_type='CourseLearningDayQualified'",Long.class,course)).isEqualTo(3);
    }
    @Test void cancellationReenrollmentAndPrivateIdentity() {
        String course=id(),learner=id(),enrollment=id();
        jdbc.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) VALUES(?,?,?,'v1','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",enrollment,learner,course);
        var source=new HotLearningSourceController(jdbc,secret);
        assertThat(source.source(UUID.fromString(course),"operation-service",secret).activeEnrollments()).isEqualTo(1);
        jdbc.update("UPDATE enrollment SET status='CANCELED' WHERE id=?",enrollment);
        assertThat(source.source(UUID.fromString(course),"operation-service",secret).activeEnrollments()).isZero();
        jdbc.update("UPDATE enrollment SET status='ACTIVE' WHERE id=?",enrollment);
        assertThat(source.source(UUID.fromString(course),"operation-service",secret).activeEnrollments()).isEqualTo(1);
        assertThatThrownBy(()->source.source(UUID.fromString(course),"iam-service",secret)).isInstanceOf(com.eduplatform.learning.service.QuestionException.class);
    }
    private String id() {return UUID.randomUUID().toString();}
}
