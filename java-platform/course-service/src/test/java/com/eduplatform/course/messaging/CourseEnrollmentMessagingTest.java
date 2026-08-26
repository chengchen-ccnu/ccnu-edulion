package com.eduplatform.course.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验证 T11 报名访问队列使用独立 DLX/DLQ 和冻结的 v1 路由，避免失败消息无限重入主队列。 */
class CourseEnrollmentMessagingTest {
    @Test
    void enrollmentQueueHasDedicatedDeadLetterTopology() {
        CourseRabbitConfig config = new CourseRabbitConfig();
        assertThat(config.enrollmentQueue().isDurable()).isTrue();
        assertThat(config.enrollmentQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", CourseRabbitConfig.ENROLLMENT_DLX)
                .containsEntry("x-dead-letter-routing-key", CourseRabbitConfig.ENROLLMENT_DEAD_ROUTING_KEY);
        assertThat(config.enrollmentDeadLetterQueue().getName()).isEqualTo(CourseRabbitConfig.ENROLLMENT_DLQ);
        assertThat(CourseRabbitConfig.ENROLLMENT_ROUTING_KEY)
                .isEqualTo("learning.enrollment.access-changed.v1");
    }
}
