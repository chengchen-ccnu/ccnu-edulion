package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.messaging.MediaEventParser;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.processing.MediaProcessingCoordinator;
import com.eduplatform.media.storage.ObjectStorage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T14.2.4 真实链的受控 Media 辅助步骤。
 *
 * <p>PROCESS 只处理脚本已通过 Gateway/OSS 创建的精确 AI 图片，不向共享 RabbitMQ 发布；CLEANUP
 * 只删除显式 uploadId/mediaId 对应的 OSS Key 与数据库事实，并逐键确认对象不存在。</p>
 */
@EnabledIfEnvironmentVariable(named = "T1424_MEDIA_REAL_ACCEPTANCE", matches = "true")
class T1424AiImageRealAcceptanceSupportTest {
    @Test
    /** 根据动作处理或清理单个明确资产；缺少专用 dev Schema 或稳定 ID 时立即停止。 */
    void processOrCleanupExactAiImage() {
        assertThat(System.getenv("MEDIA_MYSQL_DATABASE")).isEqualTo("edu_platform_media_dev");
        String action = required("T1424_MEDIA_ACTION");
        String owner = required("T1424_MEDIA_OWNER_ID");
        String uploadId = required("T1424_MEDIA_UPLOAD_ID");
        String mediaId = required("T1424_MEDIA_ID");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MediaServiceApplication.class)
                // test 仅隔离 reactor 带入的 Gateway 自动配置；local 与环境变量仍提供真实 MySQL/OSS。
                .profiles("test", "local").run("--server.port=0", "--platform.media.messaging.enabled=false",
                        "--platform.media.maintenance.enabled=false",
                        "--spring.flyway.locations=filesystem:src/main/resources/db/migration")) {
            if ("PROCESS".equals(action)) {
                process(context, owner, uploadId, mediaId);
            } else if ("CLEANUP".equals(action)) {
                cleanup(context, owner, uploadId, mediaId);
            } else {
                throw new IllegalArgumentException("T1424_MEDIA_ACTION must be PROCESS or CLEANUP");
            }
        }
    }

    /** 复核 owner/用途/上传关系后执行既有幂等处理器，结果必须为 USER_PRIVATE READY。 */
    private static void process(ConfigurableApplicationContext context, String owner, String uploadId,
            String mediaId) {
        MediaRepository repository = context.getBean(MediaRepository.class);
        MediaAsset asset = repository.findAsset(mediaId).orElseThrow();
        assertThat(asset.ownerId()).isEqualTo(owner);
        assertThat(asset.uploadId()).isEqualTo(uploadId);
        assertThat(asset.purpose().name()).isEqualTo("AI_QUESTION_IMAGE");
        assertThat(asset.scope().name()).isEqualTo("USER_PRIVATE");
        MediaRepository.OutboxEvent uploaded = repository.pendingOutbox(Instant.now().plusSeconds(2), 100).stream()
                .filter(event -> mediaId.equals(event.aggregateId()) && "MediaUploaded".equals(event.eventType()))
                .findFirst().orElseThrow();
        context.getBean(MediaProcessingCoordinator.class)
                .process(context.getBean(MediaEventParser.class).parse(uploaded.payloadJson()));
        MediaAsset ready = repository.findAsset(mediaId).orElseThrow();
        assertThat(ready.status().name()).isEqualTo("READY");
        assertThat(ready.processingStatus().name()).isEqualTo("SUCCEEDED");
    }

    /** 精确删除一个验收上传的对象及关系；不扫描 Bucket、不清表、不触碰其他 owner。 */
    private static void cleanup(ConfigurableApplicationContext context, String owner, String uploadId,
            String mediaId) {
        MediaRepository repository = context.getBean(MediaRepository.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        ObjectStorage storage = context.getBean(ObjectStorage.class);
        List<String> objectKeys = new ArrayList<>();
        repository.findAsset(mediaId).ifPresent(asset -> {
            assertThat(asset.ownerId()).isEqualTo(owner);
            assertThat(asset.uploadId()).isEqualTo(uploadId);
            repository.listVariants(mediaId).forEach(variant -> {
                repository.listVariantComponents(variant.variantId())
                        .forEach(component -> objectKeys.add(component.objectKey()));
                objectKeys.add(variant.objectKey());
            });
            objectKeys.add(asset.objectKey());
        });
        jdbc.query("SELECT object_key FROM upload_session WHERE upload_id=? AND owner_id=?",
                (rs, ignored) -> rs.getString(1), uploadId, owner).forEach(objectKeys::add);
        jdbc.query("SELECT object_key FROM temporary_access_object WHERE media_id=?",
                (rs, ignored) -> rs.getString(1), mediaId).forEach(objectKeys::add);
        objectKeys.stream().distinct().forEach(storage::delete);
        objectKeys.stream().distinct().forEach(key -> assertThatThrownBy(() -> storage.head(key))
                .isInstanceOf(com.aliyun.oss.OSSException.class));

        jdbc.update("DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE aggregate_id=?)",
                mediaId);
        jdbc.update("DELETE FROM temporary_access_object WHERE media_id=?", mediaId);
        jdbc.update("DELETE FROM access_grant_audit WHERE media_id=?", mediaId);
        jdbc.update("DELETE FROM media_audit_record WHERE media_id=?", mediaId);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id=?", mediaId);
        jdbc.update("DELETE FROM media_reference WHERE media_id=?", mediaId);
        jdbc.update("DELETE FROM processing_job WHERE media_id=?", mediaId);
        jdbc.update("DELETE FROM media_variant_component WHERE media_id=?", mediaId);
        jdbc.update("DELETE FROM media_variant WHERE media_id=?", mediaId);
        jdbc.update("DELETE FROM media_asset WHERE media_id=? AND owner_id=?", mediaId, owner);
        jdbc.update("DELETE FROM upload_part WHERE upload_id=?", uploadId);
        jdbc.update("DELETE FROM upload_session WHERE upload_id=? AND owner_id=?", uploadId, owner);

        for (String table : List.of("upload_session", "upload_part")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE upload_id=?",
                    Integer.class, uploadId)).isZero();
        }
        for (String table : List.of("media_asset", "media_variant", "media_variant_component",
                "temporary_access_object", "access_grant_audit", "media_audit_record", "media_reference",
                "processing_job")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE media_id=?",
                    Integer.class, mediaId)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",
                Integer.class, mediaId)).isZero();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
