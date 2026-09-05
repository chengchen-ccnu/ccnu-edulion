package com.eduplatform.learning.progress;

import com.eduplatform.learning.persistence.LearningAssessmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Learning 内部批量落库 Worker。
 *
 * <p>每轮领取到期 dirty Hash 后在受控并发度内并行处理不同学习者；租约避免多实例重复处理，
 * MySQL 事务成功后按 Hash bufferVersion 条件 ACK。MySQL 失败时不清理 Redis dirty，有限延后下一次领取，
 * 进程重启后仍可恢复。</p>
 */
@Component
@ConditionalOnBean(RedisProgressBufferStore.class)
@ConditionalOnProperty(prefix = "platform.learning.progress-buffer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ProgressBufferWorker {
    private final RedisProgressBufferStore store;
    private final LearningAssessmentRepository repository;
    private final MeterRegistry meterRegistry;
    private final Executor executor;
    private final ExecutorService ownedExecutor;

    /** Spring 生产实例使用受控并发，避免 1,000 个活跃学习者被单线程批次拖出过大的 dirty 年龄。 */
    @Autowired
    public ProgressBufferWorker(RedisProgressBufferStore store, LearningAssessmentRepository repository,
            MeterRegistry meterRegistry, com.eduplatform.learning.config.ProgressBufferProperties properties) {
        this(store, repository, meterRegistry, Executors.newFixedThreadPool(properties.workerConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "learning-progress-worker");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    /** 测试和手工故障验收使用同步执行器，调用返回前可以直接断言 MySQL/Redis 结果。 */
    public ProgressBufferWorker(RedisProgressBufferStore store, LearningAssessmentRepository repository,
            MeterRegistry meterRegistry) {
        this(store, repository, meterRegistry, Runnable::run, false);
    }

    private ProgressBufferWorker(RedisProgressBufferStore store, LearningAssessmentRepository repository,
            MeterRegistry meterRegistry, Executor executor, boolean ownsExecutor) {
        this.store = store; this.repository = repository; this.meterRegistry = meterRegistry;
        this.executor = executor;
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
    }

    @Scheduled(fixedDelayString = "${platform.learning.progress-buffer.worker-delay:5s}")
    public void flushDue() {
        Instant now = Instant.now();
        List<String> keys;
        try {
            keys = store.claimDue(now);
        } catch (RuntimeException unavailable) {
            return;
        }
        List<CompletableFuture<Void>> tasks = keys.stream()
                .map(key -> CompletableFuture.runAsync(() -> flushOne(key, now), executor)).toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        meterRegistry.counter("learning.progress.worker.batches").increment();
    }

    /** Spring 停止时释放生产 Worker 的线程；测试同步执行器无需清理。 */
    @jakarta.annotation.PreDestroy
    void stop() {
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }

    private void flushOne(String key, Instant now) {
        ProgressBufferStore.BufferedProgress snapshot;
        try {
            snapshot = store.leasedSnapshot(key).orElse(null);
            if (snapshot == null) return;
            String[] ids = RedisProgressBufferStore.ids(key);
            repository.applyBufferedProgress(ids[0], ids[1], ids[2], snapshot.positionSeconds(),
                    snapshot.watchedSeconds(), snapshot.completed(), snapshot.lastEventAt(), snapshot.version(), now);
            if (store.acknowledge(key, snapshot)) {
                meterRegistry.counter("learning.progress.worker.persisted").increment();
            } else {
                meterRegistry.counter("learning.progress.worker.conflicts").increment();
            }
        } catch (RuntimeException failure) {
            meterRegistry.counter("learning.progress.worker.failures").increment();
            try {
                store.release(key, now.plusSeconds(5));
            } catch (RuntimeException ignored) {
                // 租约自然过期后其他实例仍可重新领取；不吞掉 MySQL 失败的 dirty 事实。
            }
        }
    }
}
