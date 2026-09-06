package com.eduplatform.learning.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.learning.config.LearningProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * T11C.1 合并后真实 IAM HTTP 验收。
 *
 * <p>成功用例由外部脚本先启动当前主线 IAM；超时用例使用本机延迟 HTTP 端点模拟 IAM
 * 无法在配置超时内返回。两种依赖失败都只能降级昵称，不得丢失成绩册的稳定编号事实。</p>
 */
class HttpLearnerDirectoryClientRealAcceptanceTest {
    private static final String KNOWN_LEARNER = "00000000-0000-4000-8000-000000000101";
    private static final String UNKNOWN_LEARNER = "00000000-0000-4000-8000-000000000999";

    /** 通过真实 IAM 服务的 HTTP 接口验证批量昵称和部分缺失降级。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "T11C1_REAL_IAM_HTTP_BASE_URL", matches = "http://.+")
    void realIamHttpReturnsKnownNicknameAndOmitsUnknownId() {
        String baseUrl = System.getenv("T11C1_REAL_IAM_HTTP_BASE_URL");
        String token = System.getenv("IAM_INTERNAL_SERVICE_TOKEN");
        HttpLearnerDirectoryClient client = new HttpLearnerDirectoryClient(properties(baseUrl,
                Duration.ofSeconds(2), token));

        LearnerDirectoryClient.NicknameLookup result = client.nicknames(
                List.of(KNOWN_LEARNER, UNKNOWN_LEARNER), "t11c1-real-iam-client");

        assertThat(result.dependencyFailed()).isFalse();
        assertThat(result.values()).containsEntry(KNOWN_LEARNER, "测试学生");
        assertThat(result.values()).doesNotContainKey(UNKNOWN_LEARNER);
    }

    /** 超时返回部分降级标记和空昵称，且调用在有限时间内结束。 */
    @Test
    void iamTimeoutDegradesWithoutBlockingGradebook() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.createContext("/internal/v1/users/nicknames", exchange -> {
            try {
                Thread.sleep(500);
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            finally {
                exchange.close();
            }
        });
        server.setExecutor(executor);
        server.start();
        try {
            HttpLearnerDirectoryClient client = new HttpLearnerDirectoryClient(properties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(100), "test-token"));
            Instant startedAt = Instant.now();

            LearnerDirectoryClient.NicknameLookup result = client.nicknames(List.of(KNOWN_LEARNER),
                    "t11c1-real-iam-timeout");

            long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
            assertThat(result.dependencyFailed()).isTrue();
            assertThat(result.values()).isEmpty();
            assertThat(elapsedMillis).isLessThan(2_000L);
        }
        finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** 构造只包含 IAM HTTP 依赖的配置；不会连接数据库、Course、Media 或消息系统。 */
    private static LearningProperties properties(String baseUrl, Duration timeout, String token) {
        return new LearningProperties(
                new LearningProperties.Media("http://127.0.0.1:1", Duration.ofSeconds(1),
                        "test-learning", "test-media", Duration.ofSeconds(1),
                        "test-assessment-media-secret-32-bytes-minimum"),
                new LearningProperties.Course("http://127.0.0.1:1", Duration.ofSeconds(1), Duration.ZERO),
                new LearningProperties.Iam(baseUrl, timeout, token),
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), false,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
    }
}
