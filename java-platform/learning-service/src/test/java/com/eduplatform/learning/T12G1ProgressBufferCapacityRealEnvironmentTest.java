package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.progress.RedisProgressBufferStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * T12G.1 真实容量验收：对 progress-events HTTP 接口持续发送 1,000 个 UUID 学习者、约 200 req/s。
 *
 * <p>测试只访问学习进度接口，不访问 OSS/HLS；独立随机端口服务使用真实 MySQL/Redis DB 4，结束时按本轮
 * learner UUID 精确删除 MySQL 聚合与 Redis Hash/dirty 成员。失败请求仍计入延迟分布，结果输出 p50/p95/p99、
 * 成功失败数和监控到的最老 dirty 年龄。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "platform.learning.import.messaging-enabled=false",
        "platform.learning.progress-buffer.worker-delay=5s",
        "platform.learning.progress-buffer.backlog-threshold=10000",
        "spring.datasource.hikari.maximum-pool-size=32",
        "spring.datasource.hikari.minimum-idle=8",
        "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12G1_CAPACITY_ENABLED", matches = "true")
class T12G1ProgressBufferCapacityRealEnvironmentTest {
    private static final String COURSE = "5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057";
    private static final String LESSON = "70775426-c55f-4662-bbc7-14750ce183e6";
    private static final String DATABASE = "edu_platform_learning_dev";
    private static final String REDIS_DATABASE = "4";
    private static final String DIRTY_KEY = "edu:learning:progress:dirty";
    private static final int LEARNERS = 1_000;
    private static final int REQUESTS_PER_SECOND = 200;
    private static final int DURATION_SECONDS = 10 * 60;
    private static final int TOTAL_REQUESTS = REQUESTS_PER_SECOND * DURATION_SECONDS;

    @LocalServerPort
    private int port;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisProgressBufferStore store;

    @Test
    void sustainsTwoHundredHeartbeatsPerSecondForTenMinutes() throws Exception {
        assertThat(DATABASE).isEqualTo(System.getenv("LEARNING_MYSQL_DATABASE"));
        assertThat(REDIS_DATABASE).isEqualTo(System.getenv("LEARNING_REDIS_DATABASE"));
        assertThat(redis.opsForZSet().zCard(DIRTY_KEY)).as("shared Redis DB 4 must start clean").isZero();

        String runId = System.getenv("T12G1_CAPACITY_RUN_ID");
        if (runId == null || runId.isBlank()) throw new IllegalStateException("T12G1_CAPACITY_RUN_ID is required");
        List<String> learners = new ArrayList<>(LEARNERS);
        for (int i = 0; i < LEARNERS; i++) {
            learners.add(UUID.nameUUIDFromBytes((runId + "-learner-" + i).getBytes(StandardCharsets.UTF_8)).toString());
        }
        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicLong maxOldestDirtyMillis = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(128);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .executor(executor).build();
        AtomicReference<Throwable> monitorFailure = new AtomicReference<>();
        Thread monitor = new Thread(() -> monitorDirtyAge(maxOldestDirtyMillis, monitorFailure), "t12g1-capacity-monitor");
        monitor.start();
        long dispatchStart = System.nanoTime();
        try {
            for (int index = 0; index < TOTAL_REQUESTS; index++) {
                long target = dispatchStart + index * 5_000_000L;
                waitUntil(target);
                String learner = learners.get(index % LEARNERS);
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/api/v1/learning/courses/" + COURSE + "/lessons/" + LESSON + "/progress-events"))
                        .header("Content-Type", "application/json")
                        .header("X-User-Id", learner)
                        .header("X-User-Roles", "STUDENT")
                        .header("X-User-Permissions", "learning:access")
                        .header("X-Trace-Id", runId)
                        .POST(HttpRequest.BodyPublishers.ofString(body(index)))
                        .build();
                long sentAt = System.nanoTime();
                try {
                    CompletableFuture<HttpResponse<Void>> future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
                    future.whenComplete((response, error) -> {
                        latenciesNanos.add(System.nanoTime() - sentAt);
                        if (error == null && response.statusCode() == HttpStatus.OK.value()) successes.incrementAndGet();
                        else failures.incrementAndGet();
                        completed.incrementAndGet();
                    });
                } catch (RuntimeException rejected) {
                    latenciesNanos.add(System.nanoTime() - sentAt);
                    failures.incrementAndGet();
                    completed.incrementAndGet();
                }
            }
            int completedRequests = awaitCompleted(completed, TOTAL_REQUESTS, Duration.ofSeconds(300));
            if (monitorFailure.get() != null) throw new AssertionError("dirty age monitor failed", monitorFailure.get());
            awaitDirtyDrain(Duration.ofSeconds(30));
            List<Long> ordered = new ArrayList<>(latenciesNanos);
            ordered.sort(Comparator.naturalOrder());
            double p50 = percentileMillis(ordered, 0.50);
            double p95 = percentileMillis(ordered, 0.95);
            double p99 = percentileMillis(ordered, 0.99);
            double oldest = maxOldestDirtyMillis.get() / 1000.0;
            System.out.printf("T12G.1 capacity result durationSeconds=%d learners=%d requests=%d success=%d failure=%d p50Ms=%.2f p95Ms=%.2f p99Ms=%.2f maxOldestDirtyAgeSeconds=%.3f finalDirtyCount=%.0f%n",
                    DURATION_SECONDS, LEARNERS, TOTAL_REQUESTS, successes.get(), failures.get(), p50, p95, p99,
                    oldest, store.dirtyCount());
            assertThat(completedRequests).as("all HTTP futures must complete").isEqualTo(TOTAL_REQUESTS);
            assertThat(failures).as("progress heartbeat HTTP failures").hasValue(0);
            assertThat(p95).as("ordinary heartbeat p95 must stay below 200ms").isLessThan(200.0);
            assertThat(oldest).as("oldest dirty age must remain near the 5s worker period").isLessThanOrEqualTo(6.0);
            assertThat(store.dirtyCount()).isZero();
        } finally {
            monitor.interrupt();
            monitor.join(2_000);
            executor.shutdownNow();
        }
    }

    private String body(int index) {
        return "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\""
                + Instant.now() + "\",\"positionSeconds\":" + (index % 600)
                + ",\"watchedSeconds\":1,\"completed\":false,\"eventType\":\"HEARTBEAT\"}";
    }

    private void monitorDirtyAge(AtomicLong maxAgeMillis, AtomicReference<Throwable> failure) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                maxAgeMillis.accumulateAndGet((long) (store.oldestDirtyAgeSeconds() * 1_000), Math::max);
                Thread.sleep(1_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            failure.set(error);
        }
    }

    private int awaitCompleted(AtomicInteger completed, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (completed.get() < expected && System.nanoTime() < deadline) Thread.sleep(200);
        return completed.get();
    }

    private void awaitDirtyDrain(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (store.dirtyCount() > 0 && System.nanoTime() < deadline) Thread.sleep(500);
    }

    private static void waitUntil(long targetNanos) {
        while (true) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) return;
            if (remaining > 2_000_000L) {
                try { Thread.sleep(Math.max(1, remaining / 1_000_000L - 1)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
            } else Thread.onSpinWait();
        }
    }

    private static double percentileMillis(List<Long> orderedNanos, double percentile) {
        if (orderedNanos.isEmpty()) return Double.NaN;
        int index = Math.min(orderedNanos.size() - 1, Math.max(0, (int) Math.ceil(orderedNanos.size() * percentile) - 1));
        return orderedNanos.get(index) / 1_000_000.0;
    }

}
