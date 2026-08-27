package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.messaging.MediaEventFactory;
import com.eduplatform.media.messaging.MediaRabbitTopology;
import com.eduplatform.media.messaging.OutboxPublisher;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.processing.FfmpegCommandRunner;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "MEDIA_REAL_ACCEPTANCE", matches = "true")
/**
 * 只在显式开启时运行的 T07/T08A/T08A.1 真实环境验收。
 *
 * <p>测试先强制确认目标为 {@code edu_platform_media_dev}，OSS 只使用本轮 UUID 前缀并只删除已记录精确键。
 * 它覆盖单对象/Multipart、HEAD、owner/服务令牌 HLS 播放授权、FFmpeg、confirm/return、有限重试/DLQ、幂等、未 ACK 重投和 MQ 故障 Outbox。</p>
 */
class MediaRealEnvironmentAcceptanceTest {
    private static final String OWNER = "00000000-0000-4000-8000-000000007901";
    private static final String VISITOR = "00000000-0000-4000-8000-000000007902";
    private static final String SERVICE_SECRET = "test-only-real-media-auth-secret-32-bytes";
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    /** 执行真实端到端链路，无论成功或失败都在 finally 中清理本轮 OSS 精确键和固定测试 owner 数据。 */
    void validatesMysqlOssRabbitAndFfmpegWithoutListingBucket() throws Exception {
        assertThat(System.getenv("MEDIA_MYSQL_DATABASE")).isEqualTo("edu_platform_media_dev");
        String runId = UUID.randomUUID().toString();
        String prefix = "t07-acceptance/" + runId;
        try (ConfigurableApplicationContext raw = new SpringApplicationBuilder(MediaServiceApplication.class)
                // test 只提供测试 classpath 隔离；local 后加载，保持真实 MySQL/Rabbit/OSS 配置优先。
                .profiles("test", "local")
                .run("--server.port=0",
                        // 验收 classpath 含下游服务，只允许 Media 迁移进入专用 schema。
                        "--spring.flyway.locations=filesystem:src/main/resources/db/migration",
                        "--platform.media.messaging.enabled=true",
                        "--platform.media.messaging.outbox-poll-delay=1h",
                        "--platform.media.oss.enabled=true",
                        "--platform.media.oss.key-prefix=" + prefix,
                        "--platform.media.worker.enabled=true",
                        "--platform.media.worker.ffmpeg-path=ffmpeg",
                        "--platform.media.maintenance.enabled=false",
                        "--platform.media.service-authorization.issuer=edu-platform-course-service",
                        "--platform.media.service-authorization.audience=edu-platform-media-service",
                        "--platform.media.service-authorization.secret=" + SERVICE_SECRET,
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
            MediaProperties runtimeProperties = context.getBean(MediaProperties.class);
            assertThat(runtimeProperties.serviceAuthorization().issuer()).isEqualTo("edu-platform-course-service");
            assertThat(runtimeProperties.serviceAuthorization().audience()).isEqualTo("edu-platform-media-service");
            RabbitAdmin admin = new RabbitAdmin(rabbit);
            drainStaleAcceptanceDeadLetters(mapper, rabbit);
            assertAcceptanceQueuesEmpty(admin);
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

                Path video = createSyntheticVideo(true);
                JsonNode videoAsset;
                try {
                    assertSourceStreams(mapper, video, true);
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
                        assertThat(node.get("variant").asString()).isEqualTo("hls-fmp4-v2"));
                JsonNode hlsGrant = mapper.readTree(send(port, "POST", "/api/v1/media/" + videoId
                        + "/access-grants", "{\"variant\":\"hls-fmp4-v2\",\"disposition\":\"INLINE\"}").body());
                HttpResponse<String> playlist = http.send(HttpRequest.newBuilder(
                                URI.create(hlsGrant.get("url").asString())).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(playlist.statusCode()).isEqualTo(200);
                Matcher initMatcher = Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"").matcher(playlist.body());
                assertThat(initMatcher.find()).isTrue();
                String segmentUrl = playlist.body().lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .findFirst().orElseThrow();
                HttpResponse<byte[]> init = http.send(HttpRequest.newBuilder(URI.create(initMatcher.group(1))).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                HttpResponse<byte[]> segment = http.send(HttpRequest.newBuilder(URI.create(segmentUrl)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(init.statusCode()).isEqualTo(200);
                assertThat(segment.statusCode()).isEqualTo(200);
                assertThat(init.body()).isNotEmpty();
                assertThat(segment.body()).isNotEmpty();
                assertFmp4Streams(mapper, init.body(), segment.body());

                String serviceToken = serviceToken(VISITOR, videoId);
                HttpResponse<String> internalGrant = sendInternal(port, serviceToken, videoId, """
                        {"visitorId":"%s","purpose":"LESSON_VIDEO","accessType":"COURSE_PLAYBACK",
                         "variant":"hls-fmp4-v2","disposition":"INLINE"}
                        """.formatted(VISITOR));
                assertThat(internalGrant.statusCode()).withFailMessage("internal media grant returned %s: %s",
                        internalGrant.statusCode(), internalGrant.body()).isEqualTo(201);
                JsonNode serviceGrant = mapper.readTree(internalGrant.body());
                HttpResponse<String> servicePlaylist = http.send(HttpRequest.newBuilder(
                                URI.create(serviceGrant.get("url").asString())).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(servicePlaylist.statusCode()).isEqualTo(200);
                assertThat(servicePlaylist.body()).contains("#EXTM3U");

                verifySilentVideoStillTranscodes(mapper, context.getBean(FfmpegCommandRunner.class),
                        context.getBean(MediaProperties.class).worker());

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
                assertAcceptanceQueuesEmpty(admin);
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
        // basicGet 的 ACK 与 Broker 队列计数更新存在短窗口；只吸收本轮同 eventId 的可能重复死信。
        for (int attempt = 0; attempt < 8; attempt++) {
            TimeUnit.MILLISECONDS.sleep(100);
            Message duplicate = rabbit.receive(MediaRabbitTopology.DEAD_QUEUE);
            if (duplicate == null) continue;
            assertThat(mapper.readTree(new String(duplicate.getBody(), StandardCharsets.UTF_8))
                    .get("eventId").asString()).isEqualTo(eventId);
        }
    }

    /**
     * 仅清理上次被中断验收留下的固定 poison 死信；遇到任何非本验收消息立即失败。
     */
    private void drainStaleAcceptanceDeadLetters(ObjectMapper mapper, RabbitTemplate rabbit) throws Exception {
        rabbit.execute(channel -> {
            com.rabbitmq.client.GetResponse stale;
            while ((stale = channel.basicGet(MediaRabbitTopology.DEAD_QUEUE, false)) != null) {
                long deliveryTag = stale.getEnvelope().getDeliveryTag();
                JsonNode event = mapper.readTree(new String(stale.getBody(), StandardCharsets.UTF_8));
                boolean acceptanceTrace = Set.of("trace-t07-poison", "trace-t07-acceptance")
                        .contains(event.path("traceId").asString());
                if (!"MediaUploaded".equals(event.path("eventType").asString()) || !acceptanceTrace
                        || !OWNER.equals(event.path("payload").path("ownerId").asString())) {
                    channel.basicNack(deliveryTag, false, true);
                    throw new AssertionError("DLQ contains a message not owned by media real acceptance: type="
                            + event.path("eventType").asString() + ", traceId=" + event.path("traceId").asString());
                }
                channel.basicAck(deliveryTag, false);
            }
            return null;
        });
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

    /** 使用 lavfi 合成确定性画面，并按需加入 1 kHz 正弦波音轨供真实 HLS 流断言。 */
    private Path createSyntheticVideo(boolean withAudio) throws Exception {
        Path output = Files.createTempFile("t07-synthetic-", ".mp4");
        List<String> command = new ArrayList<>(List.of(ffmpegExecutable(), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=64x64:d=1"));
        if (withAudio) {
            command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000:duration=1",
                    "-map", "0:v:0", "-map", "1:a:0"));
        } else {
            command.addAll(List.of("-map", "0:v:0"));
        }
        command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p"));
        if (withAudio) command.addAll(List.of("-c:a", "aac", "-b:a", "96k", "-shortest"));
        command.add(output.toString());
        Process process = new ProcessBuilder(command).inheritIO().start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(Files.size(output)).isPositive();
        return output;
    }

    /** 断言合成原件确实包含预期流，防止“带声音验收”退化成只生成画面的假阳性。 */
    private void assertSourceStreams(ObjectMapper mapper, Path source, boolean expectAudio) throws Exception {
        List<JsonNode> streams = probeStreams(mapper, source);
        assertThat(streams).anySatisfy(stream -> assertThat(stream.path("codec_type").asString()).isEqualTo("video"));
        if (expectAudio) {
            assertThat(streams).anySatisfy(stream ->
                    assertThat(stream.path("codec_type").asString()).isEqualTo("audio"));
            assertAudioHasSignal(source);
        } else {
            assertThat(streams).noneSatisfy(stream ->
                    assertThat(stream.path("codec_type").asString()).isEqualTo("audio"));
        }
    }

    /** 下载的真实 OSS init 与 m4s 合并后必须同时含视频/AAC，证明 fMP4 授权链路没有丢声。 */
    private void assertFmp4Streams(ObjectMapper mapper, byte[] init, byte[] segment) throws Exception {
        Path localSegment = Files.createTempFile("t08-hls-audio-", ".mp4");
        try {
            byte[] media = new byte[init.length + segment.length];
            System.arraycopy(init, 0, media, 0, init.length);
            System.arraycopy(segment, 0, media, init.length, segment.length);
            Files.write(localSegment, media);
            List<JsonNode> streams = probeStreams(mapper, localSegment);
            assertThat(streams).anySatisfy(stream ->
                    assertThat(stream.path("codec_type").asString()).isEqualTo("video"));
            assertThat(streams).anySatisfy(stream -> {
                assertThat(stream.path("codec_type").asString()).isEqualTo("audio");
                assertThat(stream.path("codec_name").asString()).isEqualTo("aac");
            });
            assertAudioHasSignal(localSegment);
        } finally {
            Files.deleteIfExists(localSegment);
        }
    }

    /** 解码第一条音轨并验证平均音量，避免仅有 AAC 流结构、实际样本却全静音的假阳性。 */
    private void assertAudioHasSignal(Path media) throws Exception {
        Path diagnosticFile = Files.createTempFile("t08-audio-signal-", ".log");
        try {
            Process process = new ProcessBuilder(ffmpegExecutable(), "-hide_banner", "-nostdin", "-i",
                    media.toString(), "-map", "0:a:0", "-af", "volumedetect", "-f", "null", "-")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(diagnosticFile.toFile()).start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new AssertionError("audio signal inspection timed out");
            }
            String diagnostic = Files.readString(diagnosticFile, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).withFailMessage("audio signal inspection failed").isZero();
            Matcher meanVolume = Pattern.compile("mean_volume:\\s*(-?(?:\\d+(?:\\.\\d+)?|inf))\\s*dB")
                    .matcher(diagnostic);
            assertThat(meanVolume.find()).as("FFmpeg volumedetect mean_volume output").isTrue();
            assertThat(meanVolume.group(1)).as("audio stream must not be silent").isNotEqualTo("-inf");
            assertThat(Double.parseDouble(meanVolume.group(1))).as("audible mean volume in dB")
                    .isGreaterThan(-60.0);
        } finally {
            Files.deleteIfExists(diagnosticFile);
        }
    }

    /** optional audio map 必须允许无音轨 MP4 生成可探测的视频 HLS，临时输入与产物始终精确删除。 */
    private void verifySilentVideoStillTranscodes(ObjectMapper mapper, FfmpegCommandRunner runner,
            MediaProperties.Worker properties) throws Exception {
        Path source = createSyntheticVideo(false);
        Path scratch = Files.createTempDirectory("t08-silent-hls-");
        try {
            assertSourceStreams(mapper, source, false);
            Path playlist = scratch.resolve("index.m3u8");
            runner.run(source, playlist, scratch.resolve("segment-%05d.m4s"), properties);
            assertThat(Files.readString(playlist, StandardCharsets.UTF_8)).contains("#EXTM3U");
            assertSourceStreams(mapper, playlist, false);
        } finally {
            Files.deleteIfExists(source);
            deleteRecursively(scratch);
        }
    }

    /** 使用与 FFmpeg 同目录的 ffprobe 读取 codec_type/codec_name，不依赖文件扩展名猜测媒体流。 */
    private List<JsonNode> probeStreams(ObjectMapper mapper, Path media) throws Exception {
        Path output = Files.createTempFile("t08-ffprobe-", ".json");
        Path error = Files.createTempFile("t08-ffprobe-", ".log");
        try {
            Process process = new ProcessBuilder(ffprobeExecutable(), "-v", "error", "-show_entries",
                    "stream=codec_type,codec_name", "-of", "json", media.toString())
                    .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new AssertionError("ffprobe timed out");
            }
            String diagnostic = Files.readString(error, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ");
            assertThat(process.exitValue()).withFailMessage("ffprobe failed: %s", diagnostic).isZero();
            List<JsonNode> streams = new ArrayList<>();
            mapper.readTree(Files.readString(output, StandardCharsets.UTF_8)).path("streams").forEach(streams::add);
            return streams;
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(error);
        }
    }

    private static String ffmpegExecutable() {
        String ffmpeg = System.getenv("FFMPEG_PATH");
        return ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg;
    }

    private static String ffprobeExecutable() {
        Path ffmpeg = Path.of(ffmpegExecutable());
        String probeName = ffmpeg.getFileName().toString().toLowerCase().endsWith(".exe")
                ? "ffprobe.exe" : "ffprobe";
        return ffmpeg.getParent() == null ? probeName : ffmpeg.resolveSibling(probeName).toString();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.toList();
        }
        for (int index = paths.size() - 1; index >= 0; index--) Files.deleteIfExists(paths.get(index));
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

    /** 只删除本轮固定 owner/事件和从数据库取得的精确 OSS 键，不列举 Bucket，并逐键 HEAD 确认已删除。 */
    private void cleanupExact(ObjectStorage storage, MediaRepository repository, JdbcTemplate jdbc, List<String> eventIds) {
        List<MediaAsset> assets = repository.listAssetsOwned(OWNER, null, null, 0, 100);
        List<String> mediaIds = assets.stream().map(MediaAsset::mediaId).toList();
        List<String> uploadIds = jdbc.queryForList(
                "SELECT upload_id FROM upload_session WHERE owner_id = ?", String.class, OWNER);
        List<String> objectKeys = new ArrayList<>();
        for (MediaAsset asset : assets) {
            repository.listVariants(asset.mediaId()).forEach(variant -> {
                repository.listVariantComponents(variant.variantId())
                        .forEach(component -> objectKeys.add(component.objectKey()));
                objectKeys.add(variant.objectKey());
            });
            objectKeys.add(asset.objectKey());
        }
        jdbc.queryForList("SELECT object_key FROM temporary_access_object WHERE media_id IN "
                        + "(SELECT media_id FROM media_asset WHERE owner_id = ?)", String.class, OWNER)
                .forEach(objectKeys::add);
        objectKeys.stream().distinct().forEach(storage::delete);
        objectKeys.stream().distinct().forEach(key -> assertThatThrownBy(() -> storage.head(key))
                .as("acceptance OSS object %s must be deleted", key)
                .isInstanceOf(com.aliyun.oss.OSSException.class));
        jdbc.update("DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event "
                + "WHERE aggregate_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?))", OWNER);
        for (String eventId : eventIds) jdbc.update("DELETE FROM consumed_event WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM temporary_access_object WHERE media_id IN "
                + "(SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM access_grant_audit WHERE actor_id = ?", OWNER);
        jdbc.update("DELETE FROM access_grant_audit WHERE actor_id = ?", VISITOR);
        jdbc.update("DELETE FROM consumed_service_authorization WHERE visitor_id = ?", VISITOR);
        jdbc.update("DELETE FROM media_audit_record WHERE actor_id = ? OR media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER, OWNER);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM media_reference WHERE media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM processing_job WHERE media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM media_variant_component WHERE media_id IN "
                + "(SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM media_variant WHERE media_id IN (SELECT media_id FROM media_asset WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM media_asset WHERE owner_id = ?", OWNER);
        jdbc.update("DELETE FROM upload_part WHERE upload_id IN (SELECT upload_id FROM upload_session WHERE owner_id = ?)", OWNER);
        jdbc.update("DELETE FROM upload_session WHERE owner_id = ?", OWNER);

        // 开发 Schema 可能保留其他显式测试 owner 的数据；这里只验证本轮 owner、媒体和事件，避免误删或误报无关记录。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_session WHERE owner_id = ?", Integer.class, OWNER))
                .as("acceptance upload sessions").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE owner_id = ?", Integer.class, OWNER))
                .as("acceptance media assets").isZero();
        for (String uploadId : uploadIds) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_part WHERE upload_id = ?", Integer.class, uploadId))
                    .as("acceptance upload parts for %s", uploadId).isZero();
        }
        for (String mediaId : mediaIds) {
            for (String table : List.of("media_variant", "media_variant_component", "temporary_access_object",
                    "processing_job", "media_reference")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE media_id = ?",
                                Integer.class, mediaId))
                        .as("acceptance cleanup table %s for media %s", table, mediaId).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                            Integer.class, mediaId))
                    .as("acceptance outbox for media %s", mediaId).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_audit_record WHERE media_id = ?",
                            Integer.class, mediaId))
                    .as("acceptance audit for media %s", mediaId).isZero();
        }
        for (String eventId : eventIds) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?",
                            Integer.class, eventId))
                    .as("acceptance consumed event %s", eventId).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media_audit_record WHERE actor_id = ?",
                        Integer.class, OWNER))
                .as("acceptance owner audit records").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_grant_audit WHERE actor_id IN (?, ?)",
                        Integer.class, OWNER, VISITOR))
                .as("acceptance access grant audit records").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_service_authorization WHERE visitor_id = ?",
                        Integer.class, VISITOR))
                .as("acceptance service authorization records").isZero();
    }

    /** 成功验收后四个持久队列都必须为空，确保本轮处理、引用、生命周期和死信消息没有残留。 */
    private void assertAcceptanceQueuesEmpty(RabbitAdmin admin) throws InterruptedException {
        for (String queue : List.of(MediaRabbitTopology.PROCESSING_QUEUE, MediaRabbitTopology.REFERENCES_QUEUE,
                MediaRabbitTopology.LIFECYCLE_QUEUE, MediaRabbitTopology.DEAD_QUEUE)) {
            org.springframework.amqp.core.QueueInformation information = null;
            for (int attempt = 0; attempt < 40; attempt++) {
                information = admin.getQueueInfo(queue);
                if (information != null && information.getMessageCount() == 0) break;
                TimeUnit.MILLISECONDS.sleep(100);
            }
            assertThat(information).as("RabbitMQ queue %s must exist", queue).isNotNull();
            assertThat(information.getMessageCount()).as("RabbitMQ queue %s cleanup", queue).isZero();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /** 用真实 OSS 验收所需的短时测试令牌；密钥只用于本轮测试进程，不是部署凭据。 */
    private static String serviceToken(String visitorId, String mediaId) {
        Instant now = Instant.now();
        SecretKey key = new SecretKeySpec(SERVICE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("edu-platform-course-service")
                .audience(List.of("edu-platform-media-service"))
                .subject(visitorId)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(30))
                .claim("mediaId", mediaId)
                .claim("purpose", "LESSON_VIDEO")
                .claim("accessType", "COURSE_PLAYBACK")
                .claim("variant", "hls-fmp4-v2")
                .claim("disposition", "INLINE")
                .claim("traceId", "real-service-authorization")
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> sendInternal(int port, String token, String mediaId, String body)
            throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/internal/v1/media/" + mediaId + "/access-grants"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
