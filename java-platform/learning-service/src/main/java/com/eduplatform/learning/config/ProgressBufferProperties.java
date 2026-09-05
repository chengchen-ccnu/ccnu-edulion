package com.eduplatform.learning.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 普通播放心跳的 Redis 缓冲容量保护和 Worker 租约参数；强制检查点不依赖这些参数。 */
@ConfigurationProperties("platform.learning.progress-buffer")
public record ProgressBufferProperties(boolean enabled, Duration workerDelay, int batchSize,
        int workerConcurrency, Duration leaseDuration, long backlogThreshold) {
    public ProgressBufferProperties {
        if (workerDelay == null) workerDelay = Duration.ofSeconds(5);
        if (batchSize <= 0) batchSize = 1_000;
        if (workerConcurrency <= 0) workerConcurrency = 16;
        if (leaseDuration == null) leaseDuration = Duration.ofSeconds(15);
        if (backlogThreshold <= 0) backlogThreshold = 10_000;
    }
}
