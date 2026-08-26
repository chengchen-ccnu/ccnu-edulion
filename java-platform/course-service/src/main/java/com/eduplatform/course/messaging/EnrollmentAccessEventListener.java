package com.eduplatform.course.messaging;

import com.eduplatform.course.persistence.EnrollmentAccessRepository;
import java.time.Instant;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 将 learning-service 报名事件幂等投影到课程域，供报名制课时的本地播放授权查询。 */
@Component
@ConditionalOnProperty(prefix = "platform.course.enrollment-messaging", name = "enabled", havingValue = "true")
public class EnrollmentAccessEventListener {
    private final EnrollmentAccessRepository repository;
    private final ObjectMapper mapper;

    public EnrollmentAccessEventListener(EnrollmentAccessRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /**
     * 解析 v1 信封并写本地投影；格式错误或数据库失败向容器抛出，由三次有限重试后送入 DLQ。
     */
    @RabbitListener(queues = CourseRabbitConfig.ENROLLMENT_QUEUE,
            containerFactory = "courseEnrollmentRabbitListenerContainerFactory")
    public void consume(byte[] body) throws Exception {
        Envelope event = mapper.readValue(body, Envelope.class);
        if (!"EnrollmentAccessChanged".equals(event.eventType()) || event.eventVersion() != 1
                || event.payload() == null) {
            throw new IllegalArgumentException("Unsupported enrollment event");
        }
        Payload value = event.payload();
        if (!"ACTIVE".equals(value.status()) && !"CANCELED".equals(value.status())) {
            throw new IllegalArgumentException("Unsupported enrollment status");
        }
        repository.apply(event.eventId(), value.enrollmentId(), value.learnerId(), value.courseId(),
                value.status(), value.changedAt(), Instant.now());
    }

    private record Envelope(String eventId, String eventType, int eventVersion, Payload payload) {}
    private record Payload(String enrollmentId, String learnerId, String courseId, String status,
            Instant changedAt, String courseVersionId) {}
}
