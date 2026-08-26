package com.eduplatform.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.course.messaging.CourseRabbitConfig;
import com.eduplatform.course.persistence.EnrollmentAccessRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * T11 在专用课程开发库和真实 RabbitMQ 上的显式验收；普通构建不会连接本地中间件。
 *
 * <p>用例只发布本轮随机 event/enrollment/learner/course ID，验证真实队列消费、
 * eventId 幂等、changedAt 乱序防护与 ACTIVE/CANCELED 投影；最后按精确 ID 删除消费记录和投影，
 * 不清空队列、课程或任何存量数据。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "platform.course.enrollment-messaging.enabled=true",
        "platform.course.media.messaging.enabled=false",
        "platform.course.seed.enabled=false"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T11_REAL_ACCEPTANCE", matches = "true")
class CourseEnrollmentRealEnvironmentAcceptanceTest {
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired ObjectMapper mapper;
    @Autowired EnrollmentAccessRepository enrollmentAccess;
    @Autowired JdbcTemplate jdbc;

    /** Spring 建立连接前同时限定专用 Schema 与显式 RabbitMQ 开关。 */
    @DynamicPropertySource
    static void requireDedicatedEnvironment(DynamicPropertyRegistry ignored) {
        if (!"edu_platform_course_dev".equals(System.getenv("COURSE_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T11 acceptance requires edu_platform_course_dev");
        }
        if (!"true".equalsIgnoreCase(System.getenv("RABBITMQ_ENABLED"))) {
            throw new IllegalStateException("T11 acceptance requires explicit RabbitMQ enablement");
        }
    }

    /** 经持久 Exchange/队列发布报名变更，并验证 Course V3 投影真正支撑访问查询。 */
    @Test
    void realRabbitConsumerAppliesIdempotentOrderedEnrollmentProjectionAndCleansFixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_course_dev");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success=TRUE", String.class)).contains("3");
        assertThat(rabbitAdmin.getQueueProperties(CourseRabbitConfig.ENROLLMENT_QUEUE)).isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(CourseRabbitConfig.ENROLLMENT_DLQ)).isNotNull();

        String enrollmentId = UUID.randomUUID().toString();
        String learnerId = UUID.randomUUID().toString();
        String courseId = UUID.randomUUID().toString();
        String activeEventId = UUID.randomUUID().toString();
        String olderCancelEventId = UUID.randomUUID().toString();
        String cancelEventId = UUID.randomUUID().toString();
        Instant activeAt = Instant.now();
        try {
            publish(activeEventId, enrollmentId, learnerId, courseId, "ACTIVE", activeAt);
            awaitStatus(learnerId, courseId, true);

            // 同 eventId 即使 payload 冲突也不得覆盖已消费事实；新 eventId 的旧时间也不得使状态倒退。
            publish(activeEventId, enrollmentId, learnerId, courseId, "CANCELED", activeAt.plusSeconds(30));
            publish(olderCancelEventId, enrollmentId, learnerId, courseId, "CANCELED", activeAt.minusSeconds(1));
            Thread.sleep(1000);
            assertThat(enrollmentAccess.hasActive(learnerId, courseId)).isTrue();

            publish(cancelEventId, enrollmentId, learnerId, courseId, "CANCELED", activeAt.plusSeconds(1));
            awaitStatus(learnerId, courseId, false);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM consumed_learning_event WHERE event_id IN (?,?,?)",
                    Integer.class, activeEventId, olderCancelEventId, cancelEventId)).isEqualTo(3);
        } finally {
            jdbc.update("DELETE FROM course_enrollment_access WHERE learner_id=? AND course_id=? AND enrollment_id=?",
                    learnerId, courseId, enrollmentId);
            jdbc.update("DELETE FROM consumed_learning_event WHERE event_id IN (?,?,?)",
                    activeEventId, olderCancelEventId, cancelEventId);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_enrollment_access WHERE learner_id=? AND course_id=?",
                Integer.class, learnerId, courseId)).isZero();
    }

    private void publish(String eventId, String enrollmentId, String learnerId, String courseId,
            String status, Instant changedAt) throws Exception {
        Map<String, Object> payload = Map.of(
                "enrollmentId", enrollmentId,
                "learnerId", learnerId,
                "courseId", courseId,
                "status", status,
                "changedAt", changedAt,
                "courseVersionId", UUID.randomUUID().toString());
        Map<String, Object> envelope = Map.of(
                "eventId", eventId,
                "eventType", "EnrollmentAccessChanged",
                "eventVersion", 1,
                "aggregateId", enrollmentId,
                "occurredAt", changedAt,
                "traceId", "t11-real-acceptance",
                "payload", payload);
        rabbit.convertAndSend(CourseRabbitConfig.ENROLLMENT_EVENTS_EXCHANGE,
                CourseRabbitConfig.ENROLLMENT_ROUTING_KEY, mapper.writeValueAsBytes(envelope));
    }

    private void awaitStatus(String learnerId, String courseId, boolean expectedActive) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (enrollmentAccess.hasActive(learnerId, courseId) == expectedActive
                    && jdbc.queryForObject(
                    "SELECT COUNT(*) FROM course_enrollment_access WHERE learner_id=? AND course_id=?",
                    Integer.class, learnerId, courseId) == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Enrollment projection did not reach expected state before timeout");
    }
}
