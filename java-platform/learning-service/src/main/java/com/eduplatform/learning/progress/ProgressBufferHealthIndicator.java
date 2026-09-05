package com.eduplatform.learning.progress;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** 暴露进度缓冲连通性、积压数量和最老年龄；健康详情不包含学习者/课程/课时标识。 */
@Component("learningProgressBuffer")
@ConditionalOnBean(RedisProgressBufferStore.class)
public class ProgressBufferHealthIndicator implements HealthIndicator {
    private final RedisProgressBufferStore store;

    public ProgressBufferHealthIndicator(RedisProgressBufferStore store) { this.store = store; }

    @Override
    public Health health() {
        if (!store.ping()) return Health.down().withDetail("component", "progress-buffer").build();
        return Health.up().withDetail("component", "progress-buffer")
                .withDetail("worker", "learning-service-scheduled")
                .withDetail("dirtyCount", store.dirtyCount())
                .withDetail("oldestDirtyAgeSeconds", store.oldestDirtyAgeSeconds())
                .build();
    }
}
