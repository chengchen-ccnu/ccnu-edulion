package com.eduplatform.learning.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.importing.QuestionImportParser;
import com.eduplatform.learning.importing.QuestionImportProcessor;
import com.eduplatform.learning.media.MediaClient;
import com.eduplatform.learning.persistence.QuestionRepository;
import com.eduplatform.learning.persistence.QuestionRepository.Outbox;
import com.eduplatform.learning.service.QuestionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * T16.4.0 维护后的真实 MySQL/RabbitMQ 发布回归。包装器显式开启且确认窗口才运行；
 * 不启动 Spring 调度器、不访问其他域、不连接公共队列。五条随机合法事件只经生产
 * publishPending 查询发布；finally 按本轮 UUID 清理，历史正文仅在内存比较，不输出。
 */
@EnabledIfEnvironmentVariable(named = "T1640_REAL_ACCEPTANCE", matches = "true")
class T1640LearningOutboxRealAcceptanceTest {
    /**
     * 验证同批成功与失败隔离、真实 ACK/return/NACK、2/4 秒退避及三次耗尽、未来事件不领取。
     * 已完成导入任务用于生产消费者 eventId 幂等分支，不下载媒体或重做历史导入；重复投递走真实 Broker。
     */
    @Test
    void productionBatchPublishesConfirmsRetriesAndConsumesIdempotently() throws Exception {
        assertThat(System.getenv("T1640_MAINTENANCE_CONFIRMED")).isEqualTo("true");
        assertThat(System.getenv("LEARNING_MYSQL_DATABASE")).isEqualTo("edu_platform_learning_dev");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://" + env("MYSQL_HOST", "127.0.0.1") + ":" + env("MYSQL_PORT", "3306")
                        + "/edu_platform_learning_dev?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("edu_platform_learning_dev");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM learning_outbox_event FORCE INDEX(PRIMARY) WHERE status='PENDING'"))
                .as("No historical pending events may be published by acceptance").isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM learning_outbox_event FORCE INDEX(idx_learning_outbox_pending) WHERE status='PENDING'"))
                .as("Secondary index must already be repaired").isZero();
        List<List<byte[]>> historical = snapshot(jdbc, "learning_outbox_event", "event_id");
        List<List<byte[]>> tasks = snapshot(jdbc, "question_import_task", "id");
        List<List<byte[]>> consumed = snapshot(jdbc, "learning_consumed_event", "event_id");
        String run = UUID.randomUUID().toString();
        String exchange = "t1640." + run;
        String queue = exchange + ".success", nackQueue = exchange + ".nack";
        String task = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString(), media = UUID.randomUUID().toString();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(UUID.randomUUID().toString());
        // 仅保存随机ID/拓扑名称，强制中断时可据此精确清理；不保存正文、凭据或历史事实。
        Path fixture = Path.of(System.getenv("T1640_FIXTURE_DIRECTORY"), "fixture-" + run + ".txt");
        Files.writeString(fixture, "run=" + run + "\ntask=" + task + "\nowner=" + owner
                + "\nevents=" + String.join(",", ids) + "\nexchange=" + exchange + "\nqueues=" + queue + "," + nackQueue + "\n");
        CachingConnectionFactory connection = new CachingConnectionFactory(env("RABBITMQ_HOST", "127.0.0.1"),
                Integer.parseInt(env("RABBITMQ_PORT", "5672")));
        connection.setUsername(System.getenv("RABBITMQ_USERNAME"));
        connection.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        connection.setVirtualHost(env("RABBITMQ_VHOST", "/"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        RabbitAdmin admin = new RabbitAdmin(connection);
        RabbitTemplate rabbit = new RabbitTemplate(connection);
        AtomicInteger nacks = new AtomicInteger();
        rabbit.setConfirmCallback((correlation, ack, cause) -> { if (!ack) nacks.incrementAndGet(); });
        rabbit.setReceiveTimeout(3000);
        JsonMapper json = JsonMapper.builder().build();
        QuestionRepository repository = new QuestionRepository(jdbc, json);
        LearningProperties properties = new LearningProperties(null, null,
                new LearningProperties.ImportSettings(1024, Duration.ofHours(1), true,
                        Duration.ofSeconds(2), Duration.ofSeconds(5), 3));
        LearningOutboxPublisher publisher = new LearningOutboxPublisher(repository, rabbit, properties);
        SimpleMessageListenerContainer container = null;
        try {
            admin.declareExchange(new DirectExchange(exchange, true, false));
            admin.declareQueue(new Queue(queue, true, false, false));
            admin.declareQueue(new Queue(nackQueue, true, false, false,
                    Map.of("x-max-length", 1, "x-overflow", "reject-publish")));
            bind(admin, queue, exchange, "success");
            bind(admin, nackQueue, exchange, "nack");
            // 独占测试队列先填满一条本轮消息，真实 Broker reject-publish 产生 publisher NACK。
            rabbit.convertAndSend(exchange, "nack", "t1640-capacity-fixture");
            await(() -> admin.getQueueInfo(nackQueue).getMessageCount() == 1);
            Instant now = Instant.now().minusSeconds(1);
            jdbc.update("INSERT INTO question_import_task(id,owner_id,client_request_id,request_fingerprint,source_media_id,source_format,status,retain_until,created_at,updated_at) VALUES(?,?,?,?,?,'CSV','COMPLETED',?,?,?)",
                    task, owner, run, "t1640-terminal-fixture", media, Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now), Timestamp.from(now));
            String[] routes = {"success", "recover", "return", "nack", "future"};
            for (int i = 0; i < ids.size(); i++) {
                String body = json.writeValueAsString(new QuestionImportProcessor.Event(ids.get(i), "QuestionImportRequested", 1,
                        task, now, "t1640-" + run, new QuestionImportProcessor.Payload(task, media,
                                com.eduplatform.learning.domain.QuestionModels.ImportFormat.CSV)));
                repository.insertOutbox(new Outbox(ids.get(i), "QuestionImportRequested", 1, task, exchange, routes[i],
                        "t1640-" + run, body, "PENDING", 0, i == 4 ? now.plusSeconds(3600) : now, null, now, null, now));
            }
            publisher.publishPending();
            assertState(jdbc, ids.get(0), "PUBLISHED", 0);
            for (int i = 1; i <= 3; i++) {
                assertState(jdbc, ids.get(i), "PENDING", 1);
                assertThat(count(jdbc, "SELECT COUNT(*) FROM learning_outbox_event WHERE event_id=? AND TIMESTAMPDIFF(MICROSECOND,updated_at,next_attempt_at) BETWEEN 1900000 AND 2000000", ids.get(i))).isEqualTo(1);
            }
            assertState(jdbc, ids.get(4), "PENDING", 0);
            publisher.publishPending(); // 尚未到期不能跳过退避；不通过修改 next_attempt_at 加速测试。
            assertState(jdbc, ids.get(2), "PENDING", 1);
            bind(admin, queue, exchange, "recover");
            await(() -> due(jdbc, ids.get(1)) && due(jdbc, ids.get(2)) && due(jdbc, ids.get(3)));
            publisher.publishPending();
            assertState(jdbc, ids.get(1), "PUBLISHED", 1);
            assertState(jdbc, ids.get(2), "PENDING", 2);
            assertState(jdbc, ids.get(3), "PENDING", 2);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM learning_outbox_event WHERE event_id=? AND TIMESTAMPDIFF(MICROSECOND,updated_at,next_attempt_at) BETWEEN 3900000 AND 4000000", ids.get(2))).isEqualTo(1);
            await(() -> due(jdbc, ids.get(2)) && due(jdbc, ids.get(3)));
            publisher.publishPending();
            assertState(jdbc, ids.get(2), "FAILED", 3);
            assertState(jdbc, ids.get(3), "FAILED", 3);
            await(() -> nacks.get() >= 3);
            for (int i = 2; i <= 3; i++) {
                assertThat(count(jdbc, "SELECT COUNT(*) FROM learning_outbox_event WHERE event_id=? AND published_at IS NULL AND last_error='IllegalStateException'", ids.get(i))).isEqualTo(1);
            }
            publisher.publishPending();
            assertState(jdbc, ids.get(2), "FAILED", 3);
            assertState(jdbc, ids.get(3), "FAILED", 3);
            assertState(jdbc, ids.get(4), "PENDING", 0);
            Message first = rabbit.receive(queue);
            Message second = rabbit.receive(queue);
            assertThat(first != null && second != null).as("Both successful batch events reached only the isolated queue").isTrue();
            assertThat(first.getMessageProperties().getMessageId()).isIn(ids.get(0), ids.get(1));
            assertThat(first.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(first.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            // 同一 eventId 两次真实运输，生产 AUTO ACK 容器调用生产 listener/processor 与 MySQL 唯一键。
            QuestionImportParser parser = mock(QuestionImportParser.class);
            QuestionService service = mock(QuestionService.class);
            MediaClient mediaClient = mock(MediaClient.class);
            QuestionImportProcessor processor = new QuestionImportProcessor(repository, parser, service, mediaClient, properties,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), json);
            QuestionImportListener listener = new QuestionImportListener(processor);
            AtomicInteger deliveries = new AtomicInteger();
            container = new LearningRabbitConfig().learningImportContainerFactory(connection, new LearningImportRecoverer(processor))
                    .createListenerContainer();
            container.setQueueNames(queue);
            container.setMessageListener((org.springframework.amqp.core.MessageListener) message -> {
                listener.consume(new String(message.getBody(), StandardCharsets.UTF_8));
                deliveries.incrementAndGet();
            });
            rabbit.send(exchange, "success", first);
            rabbit.send(exchange, "success", second);
            rabbit.send(exchange, "success", first);
            container.start();
            await(() -> deliveries.get() == 3);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM learning_consumed_event WHERE event_id IN (?,?)", ids.get(0), ids.get(1))).isEqualTo(2);
            verifyNoInteractions(parser, service, mediaClient);
            assertThat(count(jdbc, "SELECT COUNT(*) FROM question_import_task WHERE id=? AND status='COMPLETED' AND processed_rows=0", task)).isEqualTo(1);
            await(() -> admin.getQueueInfo(queue).getMessageCount() == 0);
            // 等待耗尽事件的 next_attempt_at 真正到期后再扫描，证明 FAILED 不会被自动重放。
            await(() -> due(jdbc, ids.get(2)) && due(jdbc, ids.get(3)));
            int exhaustedNacks = nacks.get();
            publisher.publishPending();
            assertState(jdbc, ids.get(2), "FAILED", 3);
            assertState(jdbc, ids.get(3), "FAILED", 3);
            assertThat(nacks.get()).isEqualTo(exhaustedNacks);
        } finally {
            if (container != null) container.stop();
            // 随机名称完全属于本轮；不 purge 共用队列，不按 owner/状态删除任何历史业务行。
            try {
                admin.deleteQueue(queue);
                admin.deleteQueue(nackQueue);
                admin.deleteExchange(exchange);
                assertThat(admin.getQueueInfo(queue)).isNull();
                assertThat(admin.getQueueInfo(nackQueue)).isNull();
            } finally {
                connection.destroy();
                for (String id : ids) {
                    jdbc.update("DELETE FROM learning_consumed_event WHERE event_id=?", id);
                    jdbc.update("DELETE FROM learning_outbox_event WHERE event_id=? AND aggregate_id=?", id, task);
                }
                jdbc.update("DELETE FROM question_import_task WHERE id=? AND owner_id=? AND client_request_id=?", task, owner, run);
                assertThat(sameRows(historical, snapshot(jdbc, "learning_outbox_event", "event_id"))).as("All historical Outbox bytes and IDs unchanged").isTrue();
                assertThat(sameRows(tasks, snapshot(jdbc, "question_import_task", "id"))).as("Original import tasks unchanged").isTrue();
                assertThat(sameRows(consumed, snapshot(jdbc, "learning_consumed_event", "event_id"))).as("Original consumed facts unchanged").isTrue();
                Files.writeString(fixture, "cleanup=verified\n", java.nio.file.StandardOpenOption.APPEND);
            }
        }
    }

    /** 只声明本轮随机 exchange 的精确 routing key，不触碰平台既有绑定。 */
    private static void bind(RabbitAdmin admin, String queue, String exchange, String key) {
        admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, exchange, key, null));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    private static void assertState(JdbcTemplate jdbc, String id, String state, int attempts) {
        assertThat(count(jdbc, "SELECT COUNT(*) FROM learning_outbox_event WHERE event_id=? AND status=? AND attempts=?", id, state, attempts)).isEqualTo(1);
    }

    private static boolean due(JdbcTemplate jdbc, String id) {
        return count(jdbc, "SELECT COUNT(*) FROM learning_outbox_event WHERE event_id=? AND next_attempt_at<=UTC_TIMESTAMP(6)", id) == 1;
    }

    /** 有界等待真实退避及 ACK，不改变时间或事件状态；超时断言仅输出安全场景名。 */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("Bounded live broker/database wait").isTrue();
    }

    /** 全字段原值只存 JVM 内存；断言只输出布尔结果，避免历史正文出现在测试日志。 */
    private static List<List<byte[]>> snapshot(JdbcTemplate jdbc, String table, String key) {
        return jdbc.query("SELECT * FROM " + table + " FORCE INDEX(PRIMARY) ORDER BY " + key, (rs, row) -> {
            List<byte[]> values = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) values.add(rs.getBytes(i));
            return values;
        });
    }

    /** 比较按主键排序的两组原始字段字节（包含null）；只返回一致性布尔值，避免断言日志泄露历史正文。 */
    private static boolean sameRows(List<List<byte[]>> before, List<List<byte[]>> after) {
        if (before.size() != after.size()) return false;
        for (int row = 0; row < before.size(); row++) {
            if (before.get(row).size() != after.get(row).size()) return false;
            for (int column = 0; column < before.get(row).size(); column++) {
                if (!Arrays.equals(before.get(row).get(column), after.get(row).get(column))) return false;
            }
        }
        return true;
    }
}
