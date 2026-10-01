package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.messaging.MediaRabbitTopology;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.storage.ObjectStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T16.2 真实 Gateway→IAM→Media→OSS/RabbitMQ 验收。只在显式环境开关及两个精确开发 Schema 下运行；
 * 本轮随机账号、资产、Outbox 和对象 Key 均在 finally 精确清理，不枚举桶或修改课程数据。
 */
@EnabledIfEnvironmentVariable(named = "T162_REAL_ACCEPTANCE_ENABLED", matches = "true")
class T162TeacherReviewRealAcceptanceTest {
    private static final int GATEWAY_PORT = 18080;
    private static final int MEDIA_PORT = 18083;
    private static final String DECISION_QUEUE = "edu.iam.teacher-review.decisions.v1";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void realQualificationReviewAndReferenceChain() throws Exception {
        assertThat(System.getenv("IAM_MYSQL_DATABASE")).isEqualTo("edu_platform_iam_dev");
        assertThat(System.getenv("MEDIA_MYSQL_DATABASE")).isEqualTo("edu_platform_media_dev");
        assertThat(System.getenv("COURSE_MEDIA_AUTH_SECRET")).isNotBlank();
        String run = UUID.randomUUID().toString();
        String prefix = "t16-2-acceptance/" + run;
        String studentId = UUID.randomUUID().toString();
        String adminId = UUID.randomUUID().toString();
        String email = "t162-" + run.substring(0, 12) + "@example.invalid";
        String adminEmail = "t162-admin-" + run.substring(0, 12) + "@example.invalid";
        String password = UUID.randomUUID().toString();
        JdbcTemplate iam = new JdbcTemplate(iamDataSource());
        String mediaId = null;
        String uploadId = null;
        String applicationId = null;
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(MediaServiceApplication.class).profiles("test", "local")
                    // test classpath 测试配置带假服务密钥；使用内存优先属性覆盖，不把真实秘密拼进命令参数。
                    .initializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                            "t162-service-authorization", Map.of(
                                    "platform.media.service-authorization.secret",
                                    System.getenv("COURSE_MEDIA_AUTH_SECRET"),
                                    "platform.media.service-authorization.audience",
                                    System.getenv().getOrDefault("COURSE_MEDIA_AUTH_AUDIENCE", "edu-platform-media-service"),
                                    "platform.media.service-authorization.iam-issuer",
                                    System.getenv().getOrDefault("IAM_MEDIA_AUTH_ISSUER", "edu-platform-iam-service")))))
                    .run("--server.port=" + MEDIA_PORT,
                            "--spring.flyway.locations=filesystem:src/main/resources/db/migration",
                            "--platform.media.messaging.enabled=true",
                            "--platform.media.oss.enabled=true",
                            "--platform.media.oss.key-prefix=" + prefix,
                            "--platform.media.maintenance.enabled=false",
                            "--platform.media.worker.enabled=true");
            JdbcTemplate media = context.getBean(JdbcTemplate.class);
            MediaRepository mediaRepository = context.getBean(MediaRepository.class);
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            RabbitTemplate rabbit = context.getBean(RabbitTemplate.class);
            RabbitAdmin rabbitAdmin = new RabbitAdmin(rabbit);
            java.util.Properties queue = rabbitAdmin.getQueueProperties(DECISION_QUEUE);
            assertThat(queue).as("IAM durable decision queue must exist").isNotNull();
            assertThat(((Number) queue.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue())
                    .as("do not consume another task's decision messages").isZero();
            insertUsers(iam, studentId, adminId, email, adminEmail, password);

            HttpResponse<String> studentLogin = gateway("POST", "/api/v1/auth/login",
                    json.writeValueAsString(Map.of("identity", email, "password", password)), null, null);
            assertThat(studentLogin.statusCode()).isEqualTo(200);
            String studentToken = json.readTree(studentLogin.body()).get("accessToken").asString();
            String refreshCookie = studentLogin.headers().allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith("EDU_REFRESH=") && !value.contains("Max-Age=0"))
                    .findFirst().orElseThrow().split(";", 2)[0];
            HttpResponse<String> adminLogin = gateway("POST", "/api/v1/auth/login",
                    json.writeValueAsString(Map.of("identity", adminEmail, "password", password)), null, null);
            assertThat(adminLogin.statusCode()).isEqualTo(200);
            String adminToken = json.readTree(adminLogin.body()).get("accessToken").asString();

            byte[] image = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l5sAAAAASUVORK5CYII=");
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
            JsonNode session = body(gateway("POST", "/api/v1/media/uploads", json.writeValueAsString(Map.of(
                    "clientRequestId", UUID.randomUUID().toString(), "purpose", "TEACHER_QUALIFICATION",
                    "fileName", "synthetic-t162.png", "size", image.length, "mimeType", "image/png",
                    "sha256", sha)), studentToken, null), 201);
            uploadId = session.get("uploadId").asString();
            JsonNode uploadGrant = session.get("uploadGrant");
            HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(uploadGrant.get("url").asString()))
                    .header("Content-Type", "image/png").header("x-oss-meta-sha256", sha);
            HttpResponse<Void> putResult = http.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(image)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(putResult.statusCode()).isBetween(200, 299);
            JsonNode completed = body(gateway("POST", "/api/v1/media/uploads/" + uploadId + "/complete",
                    json.writeValueAsString(Map.of("sha256", sha, "parts", java.util.List.of())), studentToken, null), 202);
            mediaId = completed.get("mediaId").asString();
            awaitMediaStatus(mediaId, studentToken, "READY");

            Map<String, Object> submission = new java.util.LinkedHashMap<>();
            submission.put("clientRequestId", UUID.randomUUID().toString());
            submission.put("expectedRevision", null);
            submission.put("qualificationMediaId", mediaId);
            submission.put("statement", "合成测试申请：数学课程教学资质材料完整清晰");
            JsonNode submitted = body(gateway("POST", "/api/v1/teacher-applications",
                    json.writeValueAsString(submission), studentToken, null), 201);
            applicationId = submitted.get("id").asString();
            awaitReference(media, mediaId);
            JsonNode listing = body(gateway("GET", "/api/v1/admin/teacher-applications?status=SUBMITTED&page=1&size=20&exact="
                    + applicationId, null, adminToken, null), 200);
            assertThat(listing.get("items").size()).isEqualTo(1);
            assertThat(listing.toString()).doesNotContain("qualificationMediaId", "@example.invalid");
            assertThat(body(gateway("GET", "/api/v1/teacher-applications/me", null, studentToken, null), 200)
                    .get("id").asString()).isEqualTo(applicationId);
            JsonNode detail = body(gateway("GET", "/api/v1/admin/teacher-applications/" + applicationId,
                    null, adminToken, null), 200);
            assertThat(detail.get("status").asString()).isEqualTo("UNDER_REVIEW");
            String roundId = detail.get("rounds").get(0).get("id").asString();
            String accessPath = "/api/v1/admin/teacher-applications/" + applicationId + "/material-access";
            String accessBody = json.writeValueAsString(Map.of("roundId", roundId, "action", "PREVIEW"));
            assertThat(gateway("POST", accessPath, accessBody, studentToken, null).statusCode()).isEqualTo(403);
            JsonNode preview = body(gateway("POST", accessPath, accessBody, adminToken, null), 201);
            assertThat(preview.get("mediaId").asString()).isEqualTo(mediaId);
            HttpResponse<byte[]> downloaded = http.send(HttpRequest.newBuilder(URI.create(preview.get("url").asString()))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(image);

            String decisionId = UUID.randomUUID().toString();
            String decisionBody = json.writeValueAsString(Map.of("clientRequestId", decisionId,
                    "expectedRevision", detail.get("revision").asLong(), "decision", "APPROVED",
                    "materialConfirmed", true));
            JsonNode approved = body(gateway("POST", "/api/v1/admin/teacher-applications/" + applicationId + "/decision",
                    decisionBody, adminToken, null), 200);
            assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
            assertThat(body(gateway("POST", "/api/v1/admin/teacher-applications/" + applicationId + "/decision",
                    decisionBody, adminToken, null), 200).get("revision").asLong())
                    .isEqualTo(approved.get("revision").asLong());
            HttpResponse<String> refreshed = gateway("POST", "/api/v1/auth/refresh", "{}", null, refreshCookie);
            String freshToken = body(refreshed, 200).get("accessToken").asString();
            JsonNode learner = body(gateway("GET", "/api/v1/users/me", null, freshToken, null), 200);
            assertThat(learner.get("permissions").toString()).contains("learning:access", "teaching:access");
            awaitOutbox(iam, applicationId);
            consumeOwnDecision(rabbit, applicationId);
            assertThat(media.queryForObject("SELECT COUNT(*) FROM media_reference WHERE media_id=? AND owner_service='iam-service' AND reference_type='TEACHER_APPLICATION_ROUND' AND status='BOUND'",
                    Integer.class, mediaId)).isEqualTo(1);
            assertThat(mediaRepository.findAsset(mediaId).orElseThrow().status().name()).isEqualTo("READY");
            // 仅本轮已记录的精确对象和行由 finally 清理；不查询课程库或枚举 OSS Bucket。
            cleanupMedia(media, mediaRepository, storage, mediaId, uploadId, studentId, adminId, prefix);
        } finally {
            if (context != null) {
                JdbcTemplate media = context.getBean(JdbcTemplate.class);
                cleanupMedia(media, context.getBean(MediaRepository.class), context.getBean(ObjectStorage.class),
                        mediaId, uploadId, studentId, adminId, prefix);
                context.close();
            }
            cleanupIam(iam, studentId, adminId, applicationId, mediaId);
        }
    }

    private DataSource iamDataSource() {
        String url = "jdbc:mysql://" + System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1") + ":"
                + System.getenv().getOrDefault("MYSQL_PORT", "3306") + "/edu_platform_iam_dev"
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        return new DriverManagerDataSource(url, System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD"));
    }

    /** 本轮仅建立两个随机账号及已验证合成邮箱，密码随机生成且只保存 BCrypt 摘要。 */
    private void insertUsers(JdbcTemplate iam, String student, String admin,
            String studentEmail, String adminEmail, String password) {
        iam.update("INSERT IGNORE INTO `role` (code,name) VALUES ('STUDENT','STUDENT'),('ADMIN','ADMIN')");
        iam.update("INSERT IGNORE INTO permission (code,name) VALUES ('learning:access','learning:access'),('administration:access','administration:access')");
        iam.update("INSERT IGNORE INTO role_permission (role_id,permission_id) SELECT r.id,p.id FROM `role` r JOIN permission p ON (r.code='STUDENT' AND p.code='learning:access') OR (r.code='ADMIN' AND p.code='administration:access')");
        String hash = new BCryptPasswordEncoder().encode(password);
        for (String id : new String[]{student, admin}) {
            iam.update("INSERT INTO `user` (id,account,display_name,password_hash,status,created_at,updated_at) VALUES (?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                    id, "t162_" + id.substring(0, 12), "合成审核测试", hash);
            iam.update("INSERT INTO user_role (user_id,role_id) SELECT ?,id FROM `role` WHERE code=?",
                    id, id.equals(student) ? "STUDENT" : "ADMIN");
            iam.update("INSERT INTO user_identity (id,user_id,identity_type,normalized_value,verified_at,created_at) VALUES (?,?, 'EMAIL', ?, CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                    UUID.randomUUID().toString(), id, id.equals(student) ? studentEmail : adminEmail);
        }
    }

    private HttpResponse<String> gateway(String method, String path, String body, String bearer, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + GATEWAY_PORT + path));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (cookie != null) request.header("Cookie", cookie);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int... allowed) {
        assertThat(response.statusCode()).isIn(java.util.Arrays.stream(allowed).boxed().toArray());
        return json.readTree(response.body());
    }

    private void awaitMediaStatus(String mediaId, String token, String expected) throws Exception {
        for (int i = 0; i < 120; i++) {
            HttpResponse<String> response = gateway("GET", "/api/v1/media/" + mediaId, null, token, null);
            if (response.statusCode() == 200 && expected.equals(json.readTree(response.body()).path("status").asString())) return;
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new AssertionError("synthetic qualification did not become READY");
    }

    private void awaitReference(JdbcTemplate media, String mediaId) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Integer count = media.queryForObject("SELECT COUNT(*) FROM media_reference WHERE media_id=? AND owner_service='iam-service' AND status='BOUND'",
                    Integer.class, mediaId);
            if (count != null && count == 1) return;
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new AssertionError("IAM qualification reference was not consumed by Media");
    }

    private void awaitOutbox(JdbcTemplate iam, String applicationId) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            Integer count = iam.queryForObject("SELECT COUNT(*) FROM iam_outbox_event WHERE aggregate_id=? AND event_type='TeacherQualificationDecided' AND status='PUBLISHED'",
                    Integer.class, applicationId);
            if (count != null && count == 1) return;
            TimeUnit.MILLISECONDS.sleep(250);
        }
        throw new AssertionError("IAM decision Outbox not confirmed");
    }

    /** 仅 ACK 本轮决定消息；若队列混有别的消息则 requeue 并中止，不清空共享队列。 */
    private void consumeOwnDecision(RabbitTemplate rabbit, String applicationId) throws Exception {
        Boolean consumed = rabbit.execute(channel -> {
            com.rabbitmq.client.GetResponse delivery = channel.basicGet(DECISION_QUEUE, false);
            if (delivery == null) return false;
            JsonNode envelope = json.readTree(new String(delivery.getBody(), StandardCharsets.UTF_8));
            if (!applicationId.equals(envelope.path("aggregateId").asString())) {
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                throw new AssertionError("decision queue contains unrelated message");
            }
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            return true;
        });
        assertThat(consumed).isTrue();
    }

    /** 只按本轮 mediaId 和已记录对象 Key 删除；不会扫描整个 Bucket。 */
    private void cleanupMedia(JdbcTemplate media, MediaRepository repository, ObjectStorage storage,
            String mediaId, String uploadId, String student, String admin, String prefix) {
        if (mediaId == null && uploadId != null) {
            java.util.List<String> created = media.queryForList("SELECT media_id FROM media_asset WHERE upload_id=? AND owner_id=?",
                    String.class, uploadId, student);
            if (created.size() == 1) mediaId = created.get(0);
        }
        if (mediaId == null) {
            if (uploadId != null) {
                java.util.List<String> keys = media.queryForList("SELECT object_key FROM upload_session WHERE upload_id=? AND owner_id=?",
                        String.class, uploadId, student);
                for (String key : keys) {
                    assertThat(key).startsWith(prefix + "/");
                    storage.delete(key);
                }
                media.update("DELETE FROM media_audit_record WHERE upload_id=?", uploadId);
                media.update("DELETE FROM upload_part WHERE upload_id=?", uploadId);
                media.update("DELETE FROM upload_session WHERE upload_id=? AND owner_id=?", uploadId, student);
            }
            return;
        }
        if (uploadId == null) {
            java.util.List<String> uploads = media.queryForList("SELECT upload_id FROM media_asset WHERE media_id=? AND owner_id=?",
                    String.class, mediaId, student);
            if (uploads.size() == 1) uploadId = uploads.get(0);
        }
        MediaAsset asset = repository.findAsset(mediaId).orElse(null);
        if (asset != null) {
            assertThat(asset.objectKey()).startsWith(prefix + "/");
            storage.delete(asset.objectKey());
        }
        media.update("DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE aggregate_id=?)", mediaId);
        media.update("DELETE FROM consumed_event WHERE event_id IN (SELECT bound_event_id FROM media_reference WHERE media_id=?)", mediaId);
        media.update("DELETE FROM consumed_event WHERE event_id IN (SELECT released_event_id FROM media_reference WHERE media_id=? AND released_event_id IS NOT NULL)", mediaId);
        media.update("DELETE FROM consumed_service_authorization WHERE visitor_id=?", admin);
        media.update("DELETE FROM temporary_access_object WHERE media_id=?", mediaId);
        media.update("DELETE FROM access_grant_audit WHERE media_id=?", mediaId);
        media.update("DELETE FROM media_audit_record WHERE media_id=? OR upload_id=?", mediaId, uploadId);
        media.update("DELETE FROM outbox_event WHERE aggregate_id=?", mediaId);
        media.update("DELETE FROM media_reference WHERE media_id=?", mediaId);
        media.update("DELETE FROM processing_job WHERE media_id=?", mediaId);
        media.update("DELETE FROM media_variant_component WHERE media_id=?", mediaId);
        media.update("DELETE FROM media_variant WHERE media_id=?", mediaId);
        media.update("DELETE FROM media_asset WHERE media_id=?", mediaId);
        media.update("DELETE FROM upload_part WHERE upload_id=?", uploadId);
        media.update("DELETE FROM upload_session WHERE upload_id=? AND owner_id=?", uploadId, student);
    }

    private void cleanupIam(JdbcTemplate iam, String student, String admin, String applicationId, String mediaId) {
        if (mediaId != null) iam.update("DELETE FROM iam_outbox_event WHERE aggregate_id=?", mediaId);
        java.util.List<String> applications = iam.queryForList("SELECT id FROM teacher_application WHERE user_id=?", String.class, student);
        if (applicationId != null && !applications.contains(applicationId)) applications.add(applicationId);
        for (String id : applications) {
            java.util.List<String> linkedMedia = iam.queryForList("SELECT qualification_media_id FROM teacher_application WHERE id=?",
                    String.class, id);
            for (String linked : linkedMedia) iam.update("DELETE FROM iam_outbox_event WHERE aggregate_id=?", linked);
            iam.update("DELETE FROM iam_outbox_event WHERE aggregate_id=?", id);
            iam.update("DELETE FROM teacher_review_command WHERE application_id=?", id);
            iam.update("DELETE FROM teacher_review_decision WHERE application_id=?", id);
            iam.update("DELETE FROM teacher_application_round WHERE application_id=?", id);
            iam.update("DELETE FROM teacher_application_command WHERE application_id=?", id);
            iam.update("DELETE FROM teacher_application WHERE id=?", id);
        }
        iam.update("DELETE FROM account_security_audit WHERE user_id IN (?,?) OR actor_id IN (?,?)",
                student, admin, student, admin);
        iam.update("DELETE FROM refresh_session WHERE user_id IN (?,?) AND parent_id IS NOT NULL", student, admin);
        iam.update("DELETE FROM refresh_session WHERE user_id IN (?,?)", student, admin);
        iam.update("DELETE FROM login_audit WHERE user_id IN (?,?)", student, admin);
        iam.update("DELETE FROM user_identity WHERE user_id IN (?,?)", student, admin);
        iam.update("DELETE FROM user_role WHERE user_id IN (?,?)", student, admin);
        iam.update("DELETE FROM `user` WHERE id IN (?,?)", student, admin);
    }

}
