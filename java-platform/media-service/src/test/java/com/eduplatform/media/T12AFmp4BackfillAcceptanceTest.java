package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.ProcessingStatus;
import com.eduplatform.media.messaging.MediaDomainEvent;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.processing.FfmpegMediaProcessingWorker;
import com.eduplatform.media.processing.MediaProcessingCoordinator;
import com.eduplatform.media.service.MediaAssetService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 为明确指定的专用开发视频幂等补产 fMP4 HLS v2，不修改课程绑定或旧 v1 对象。
 *
 * <p>只有同时显式设置 {@code T12A_FMP4_BACKFILL=true} 与 {@code T12A_MEDIA_ID} 才运行。
 * 目标必须存在于 {@code edu_platform_media_dev}、用途为 LESSON_VIDEO；重复执行会在 v2 已存在时直接返回。</p>
 */
@EnabledIfEnvironmentVariable(named = "T12A_FMP4_BACKFILL", matches = "true")
class T12AFmp4BackfillAcceptanceTest {
    @Test
    void backfillsOneExplicitDevelopmentAsset() {
        assertThat(System.getenv("MEDIA_MYSQL_DATABASE")).isEqualTo("edu_platform_media_dev");
        String mediaId = System.getenv("T12A_MEDIA_ID");
        assertThat(mediaId).isNotBlank();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MediaServiceApplication.class)
                .profiles("test", "local")
                .run("--spring.main.web-application-type=none",
                        "--spring.flyway.locations=filesystem:src/main/resources/db/migration",
                        "--platform.media.messaging.enabled=false",
                        "--platform.media.maintenance.enabled=false",
                        "--platform.media.oss.enabled=true",
                        "--platform.media.worker.enabled=true",
                        "--platform.media.worker.ffmpeg-path=ffmpeg")) {
            MediaRepository repository = context.getBean(MediaRepository.class);
            var asset = repository.findAsset(mediaId).orElseThrow();
            assertThat(asset.purpose()).isEqualTo(MediaPurpose.LESSON_VIDEO);
            assertThat(asset.deletedAt()).isNull();
            if (repository.findVariant(mediaId, FfmpegMediaProcessingWorker.HLS_VARIANT).isPresent()) return;

            Instant now = Instant.now();
            if (repository.findProcessingJob(mediaId, MediaAssetService.PROCESS_JOB,
                    MediaAssetService.PRESET_VERSION).isEmpty()) {
                repository.insertProcessingJob(new MediaRepository.ProcessingJob(
                        UUID.randomUUID().toString(), mediaId, MediaAssetService.PROCESS_JOB,
                        MediaAssetService.PRESET_VERSION, ProcessingStatus.PENDING, 0,
                        null, null, null, null, null, now, now));
            }
            // 固定事件 ID 让同一 mediaId 的补产与后续精确清理可复现，不留下无法定位的消费记录。
            String eventId = UUID.nameUUIDFromBytes(
                    ("t12a-fmp4-backfill:" + mediaId).getBytes(StandardCharsets.UTF_8)).toString();
            MediaDomainEvent event = new MediaDomainEvent(eventId, "MediaUploaded", 1, mediaId,
                    now.toString(), "trace-t12a-fmp4-backfill",
                    context.getBean(ObjectMapper.class).createObjectNode(), "{}");

            context.getBean(MediaProcessingCoordinator.class).process(event);

            var variant = repository.findVariant(mediaId, FfmpegMediaProcessingWorker.HLS_VARIANT).orElseThrow();
            assertThat(repository.listVariantComponents(variant.variantId()))
                    .anySatisfy(component -> assertThat(component.componentName()).isEqualTo("init.mp4"))
                    .anySatisfy(component -> assertThat(component.componentName()).endsWith(".m4s"));
            assertThat(repository.findProcessingJob(mediaId, MediaAssetService.PROCESS_JOB,
                    MediaAssetService.PRESET_VERSION).orElseThrow().status()).isEqualTo(ProcessingStatus.SUCCEEDED);
        }
    }
}
