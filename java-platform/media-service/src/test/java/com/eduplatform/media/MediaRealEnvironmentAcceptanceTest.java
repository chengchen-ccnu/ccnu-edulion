package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.messaging.MediaEventFactory;
import com.eduplatform.media.messaging.MediaRabbitTopology;
import com.eduplatform.media.messaging.OutboxPublisher;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.storage.ObjectStorage;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "MEDIA_REAL_ACCEPTANCE", matches = "true")
/**
 * 只在显式开启时运行的 T07 真实环境验收。
 *
 * <p>测试先强制确认目标为 {@code edu_platform_media_dev}，OSS 只使用本轮 UUID 前缀并只删除已记录精确键。
 * 它覆盖单对象/Multipart、HEAD、读授权、FFmpeg、confirm/return、有限重试/DLQ、幂等、未 ACK 重投和 MQ 故障 Outbox。</p>
 */
class MediaRealEnvironmentAcceptanceTest {
    private static final String OWNER = "00000000-0000-4000-8000-000000007901";
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    /** 执行真实端到端链路，无论成功或失败都在 finally 中清理本轮 OSS 精确键和固定测试 owner 数据。 */
    void validatesMysqlOssRabbitAndFfmpegWithoutListingBucket() throws Exception {
        assertThat(System.getenv("MEDIA_MYSQL_DATABASE")).isEqualTo("edu_platform_media_dev");
        String runId = UUID.randomUUID().toString();
        String prefix = "t07-acceptance/" + runId;
        try (ConfigurableApplicationContext raw = new SpringApplicationBuilder(MediaServiceApplication.class)
                .profiles("local")
                .run("--server.port=0",
                        "--platform.media.messaging.enabled=true",
                        "--platform.media.messaging.outbox-poll-delay=1h",
                        "--platform.media.oss.enabled=true",
                        "--platform.media.oss.key-prefix=" + prefix,
                        "--platform.media.worker.enabled=true",
                        "--platform.media.maintenance.enabled=false",
                        "--platform.media.upload.multipart-threshold=1024",
                        "--platform.media.upload.part-size=5242880")) {
            ServletWebServerApplicationContext context = (ServletWebServerApplicationContext) raw;
            int port = context.getWebServer().getPort();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            MediaRepository repository = context.getBean(MediaRepository.class);
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            RabbitTemplate rabbit = context.getBean(RabbitTemplate.class);
            OutboxPublisher outbox = context.getBean(OutboxPublisher.class);
            RabbitAdmin admin = new RabbitAdmin(rabbit);
            admin.purgeQueue(MediaRabbitTopology.DEAD_QUEUE, false);
            List<String> eventIds = new ArrayList<>();
            try {
                JsonNode cover = uploadSingleCover(port, mapper, runId);
                String coverId = cover.get("mediaId").asString();
                MediaRepository.OutboxEvent coverUploaded = pendingFor(repository, coverId, "MediaUploaded");
                eventIds.add(coverUploaded.eventId());
                outbox.publish(coverUploaded);
                awaitStatus(port, mapper, coverId, "READY", 30);
                assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class,
                        coverUploaded.eventId())).isEqualTo("PUBLISHED");

                JsonNode grant = mapper.readTree(send(port, "POST", "/api/v1/media/" + coverId + "/access-grants",
                        "{\"variant\":\"ORIGINAL\",\"disposition\":\"INLINE\"}").body());
                HttpResponse<byte[]> downloaded = http.send(HttpRequest.newBuilder(URI.create(grant.get("url").asString()))
                        .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(downloaded.statusCode()).isEqualTo(200);
                assertThat(downloaded.body()).isEqualTo("t07-cover".getBytes(StandardCharsets.UTF_8));

                Path video = createSyntheticVideo();
                JsonNode videoAsset;
                try {
                    videoAsset = uploadMultipartVideo(port, mapper, runId, video);
                } finally {
                    Files.deleteIfExists(video);
                }
                String videoId = videoAsset.get("mediaId").asString();
                MediaRepository.OutboxEvent videoUploaded = pendingFor(repository, videoId, "MediaUploaded");
                eventIds.add(videoUploaded.eventId());
                outbox.publish(videoUploaded);
                JsonNode readyVideo = awaitStatus(port, mapper, videoId, "READY", 60);
                assertThat(readyVideo.get("processingStatus").asString()).isEqualTo("SUCCEEDED");
                assertThat(readyVideo.get("variants")).anySatisfy(node ->
                        assertThat(node.get("variant").asString()).isEqualTo("baseline-mp4-v1"));

                rabbit.convertAndSend(MediaRabbitTopology.EVENTS_EXCHANGE, "media.uploaded.v1", videoUploaded.payloadJson());
                TimeUnit.MILLISECONDS.sleep(800);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Integer.class,
                        videoUploaded.eventId())).isEqualTo(1);

                verifyFiniteRetryAndDeadLetter(mapper, rabbit, eventIds);
                verifyUnackedRedelivery(context.getBean(CachingConnectionFactory.class), rabbit, runId);
                verifyReturnAndUnavailableOutbox(repository, context.getBean(MediaEventFactory.class), outbox,
                        context.getBean(MediaProperties.class), jdbc, coverId, eventIds);

                outbox.publishPending();
                awaitPublishedEventsConsumed(jdbc);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id = ?", Integer.class, OWNER))
                        .isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_variant WHERE media_id = ?", Integer.class, videoId))
                        .isGreaterThanOrEqualTo(1);
            } finally {
                context.getBean(RabbitListenerEndpointRegistry.class).stop();
                cleanupExact(storage, repository, jdbc, eventIds);
            }
        }
    }

    private JsonNode uploadSingleCover(int port, ObjectMapper mapper, String runId) throws Exception {
        byte[] bytes = "t07-cover".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        JsonNode session = mapper.readTree(send(port, "POST", "/api/v1/media/uploads", """
                {"clientRequestId":"%s","purpose":"COURSE_COVER","fileName":"acceptance.png",
                 "size":%d,"mimeType":"image/png","sha256":"%s"}
                """.formatted(UUID.nameUUIDFromBytes((runId + "-cover").getBytes()), bytes.length, sha)).body());
        JsonNode grant = session.get("uploadGrant");
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(grant.get("url").asString()))
                .header("Content-Type", "image/png").header("x-oss-meta-sha256", sha)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isBetween(200, 299);
        return mapper.readTree(send(port, "POST", "/api/v1/media/uploads/" + session.get("uploadId").asString()
                + "/complete", "{\"sha256\":\"" + sha + "\",\"parts\":[]}").body());
    }

    private JsonNode uploadMultipartVideo(int port, ObjectMapper mapper, String runId, Path video) throws Exception {
        byte[] bytes = Files.readAllBytes(video);
        String sha = sha256(bytes);
        JsonNode session = mapper.readTree(send(port, "POST", "/api/v1/media/uploads", """
                {"clientRequestId":"%s","purpose":"LESSON_VIDEO","fileName":"acceptance.mp4",
                 "size":%d,"mimeType":"video/mp4","sha256":"%s"}
                """.formatted(UUID.nameUUIDFromBytes((runId + "-video").getBytes()), bytes.length, sha)).body());
        assertThat(session.get("mode").asString()).isEqualTo("MULTIPART");
        JsonNode grant = mapper.readTree(send(port, "POST", "/api/v1/media/uploads/"
                + session.get("uploadId").asString() + "/parts/authorize",
                "{\"partNumber\":1,\"contentLength\":" + bytes.length + "}").body());
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(grant.get("url").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isBetween(200, 299);
        String etag = put.headers().firstValue("ETag").orElseThrow().replace("\"", "");
        return mapper.readTree(send(port, "POST", "/api/v1/media/uploads/" + session.get("uploadId").asString()
                + "/complete", "{\"sha256\":\"" + sha + "\",\"parts\":[{\"partNumber\":1,\"etag\":\""
                + etag + "\"}]}").body());
    }

    private void verifyFiniteRetryAndDeadLetter(ObjectMapper mapper, RabbitTemplate rabbit, List<String> eventIds) throws Exception {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        String missingMedia = UUID.randomUUID().toString();
        String poison = """
                {"eventId":"%s","eventType":"MediaUploaded","eventVersion":1,"aggregateId":"%s",
                 "occurredAt":"%s","traceId":"trace-t07-poison","payload":{"mediaId":"%s",
                 "purpose":"COURSE_COVER","ownerId":"%s","mimeType":"image/png","size":1,
                 "sha256":"%s"}}
                """.formatted(eventId, missingMedia, Instant.now(), missingMedia, OWNER, "0".repeat(64));
        rabbit.convertAndSend(MediaRabbitTopology.EVENTS_EXCHANGE, "media.uploaded.v1", poison);
        Message dead = null;
        for (int i = 0; i < 40 && dead == null; i++) {
            TimeUnit.MILLISECONDS.sleep(250);
            dead = rabbit.receive(MediaRabbitTopology.DEAD_QUEUE);
        }
        assertThat(dead).isNotNull();
        assertThat(mapper.readTree(new String(dead.getBody(), StandardCharsets.UTF_8)).get("eventId").asString())
                .isEqualTo(eventId);
        assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    private void verifyUnackedRedelivery(CachingConnectionFactory springFactory, RabbitTemplate rabbit, String runId)
            throws Exception {
        String queue = "edu.media.acceptance.redelivery." + runId;
        String routing = "media.acceptance.redelivery." + runId;
        com.rabbitmq.client.ConnectionFactory rawFactory = springFactory.getRabbitConnectionFactory();
        try (Connection first = rawFactory.newConnection("t07-redelivery-first"); Channel channel = first.createChannel()) {
            channel.queueDeclare(queue, true, false, false, Map.of());
            channel.queueBind(queue, MediaRabbitTopology.EVENTS_EXCHANGE, routing);
            rabbit.convertAndSend(MediaRabbitTopology.EVENTS_EXCHANGE, routing, "redelivery-check");
            var delivery = channel.basicGet(queue, false);
            assertThat(delivery).isNotNull();
        }
        try (Connection second = rawFactory.newConnection("t07-redelivery-second"); Channel channel = second.createChannel()) {
            var redelivered = channel.basicGet(queue, false);
            assertThat(redelivered).isNotNull();
            assertThat(redelivered.getEnvelope().isRedeliver()).isTrue();
            channel.basicAck(redelivered.getEnvelope().getDeliveryTag(), false);
            channel.queueDelete(queue);
        }
    }

    private void verifyReturnAndUnavailableOutbox(MediaRepository repository, MediaEventFactory factory,
            OutboxPublisher publisher, MediaProperties properties, JdbcTemplate jdbc, String aggregateId,
            List<String> eventIds) {
        Instant now = Instant.now();
        MediaRepository.OutboxEvent returned = factory.create("MediaReady", aggregateId,
                "media.acceptance.unroutable." + UUID.randomUUID(), "trace-t07-return",
                Map.of("mediaId", aggregateId), now);
        repository.insertOutbox(returned);
        eventIds.add(returned.eventId());
        publisher.publish(returned);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class,
                returned.eventId())).isEqualTo("PENDING");

        MediaRepository.OutboxEvent unavailable = factory.create("MediaReady", aggregateId,
                "media.ready.v1", "trace-t07-unavailable", Map.of("mediaId", aggregateId), now);
        repository.insertOutbox(unavailable);
        eventIds.add(unavailable.eventId());
        CachingConnectionFactory badFactory = new CachingConnectionFactory("127.0.0.1", 1);
        badFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        badFactory.setPublisherReturns(true);
        try {
            new OutboxPublisher(repository, new RabbitTemplate(badFactory), properties).publish(unavailable);
        } finally {
            badFactory.destroy();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class,
                unavailable.eventId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE media_id = ?", Integer.class,
                aggregateId)).isEqualTo(1);
    }

    private Path createSyntheticVideo() throws Exception {
        Path output = Files.createTempFile("t07-synthetic-", ".mp4");
        String ffmpeg = System.getenv("FFMPEG_PATH");
        if (ffmpeg == null || ffmpeg.isBlank()) ffmpeg = "ffmpeg";
        Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=64x64:d=1", "-c:v", "libx264",
                "-pix_fmt", "yuv420p", output.toString()).inheritIO().start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(Files.size(output)).isPositive();
        return output;
    }

    private JsonNode awaitStatus(int port, ObjectMapper mapper, String mediaId, String expected, int seconds) throws Exception {
        JsonNode value = null;
        for (int i = 0; i < seconds * 4; i++) {
            HttpResponse<String> response = send(port, "GET", "/api/v1/media/" + mediaId, null);
            value = mapper.readTree(response.body());
            if (expected.equals(value.get("status").asString())) return value;
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new AssertionError("media did not reach expected state; last status=" + value.get("status").asString());
    }

    private MediaRepository.OutboxEvent pendingFor(MediaRepository repository, String aggregateId, String type) {
        return repository.pendingOutbox(Instant.now().plusSeconds(1), 100).stream()
                .filter(event -> aggregateId.equals(event.aggregateId()) && type.equals(event.eventType()))
                .findFirst().orElseThrow();
    }

    /** 等待本轮已发布 Outbox 全部留下持久化消费证据，避免清理与异步 ACK 竞态。 */
    private void awaitPublishedEventsConsumed(JdbcTemplate jdbc) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            Integer pending = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM outbox_event o
                    WHERE o.status = 'PUBLISHED'
                      AND o.aggregate_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)
                      AND NOT EXISTS (SELECT 1 FROM consumed_event c WHERE c.event_id = o.event_id)
                    """, Integer.class, OWNER);
            if (pending != null && pending == 0) return;
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("published T07 acceptance events were not consumed before cleanup");
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-User-Id", OWNER).header("X-User-Roles", "TEACHER")
                .header("X-Trace-Id", "trace-t07-acceptance");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage("%s %s returned %s: %s", method, path,
                response.statusCode(), response.body()).isBetween(200, 299);
        return response;
    }

    /** 只删除本轮固定 owner/事件和从数据库取得的精确 OSS 键，不列举 Bucket。 */
    private void cleanupExact(ObjectStorage storage, MediaRepository repository, JdbcTemplate jdbc, List<String> eventIds) {
        List<MediaAsset> assets = repository.listAssetsOwned(OWNER, null, null, 0, 100);
        for (MediaAsset asset : assets) {
            repository.listVariants(asset.mediaId()).forEach(variant -> storage.delete(variant.objectKey()));
            storage.delete(asset.objectKey());
        }
        jdbc.update("DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event "
                + "WHERE aggregate_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?))", OWNER);
        for (String eventId : eventIds) jdbc.update("DELETE FROM consumed_event WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM access_grant_audit WHERE actor_id = ?", OWNER);
        jdbc.update("DELETE FROM media_audit_record WHERE actor_id = ? OR media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER, OWNER);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM media_reference WHERE media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM processing_job WHERE media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM media_variant WHERE media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM media_asset WHERE owner_id = ?", OWNER);
        jdbc.update("DELETE FROM upload_part WHERE upload_id IN (SELECT upload_id FROM upload_session WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM upload_session WHERE owner_id = ?", OWNER);

        // 该专用 Schema 只允许承载 T07 验收数据；逐表归零断言可及时暴露异步消费或新增表遗漏的清理缺口。
        for (String table : List.of("upload_session", "upload_part", "media_asset", "media_variant",
                "processing_job", "media_reference", "outbox_event", "consumed_event",
                "media_audit_record", "access_grant_audit")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .as("T07 acceptance cleanup table %s", table)
                    .isZero();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
