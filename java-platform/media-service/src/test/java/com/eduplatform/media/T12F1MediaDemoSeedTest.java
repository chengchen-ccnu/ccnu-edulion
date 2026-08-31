package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaAssetScope;
import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.domain.MediaStatus;
import com.eduplatform.media.domain.ProcessingStatus;
import com.eduplatform.media.domain.UploadMode;
import com.eduplatform.media.domain.UploadSession;
import com.eduplatform.media.domain.UploadStatus;
import com.eduplatform.media.messaging.MediaDomainEvent;
import com.eduplatform.media.persistence.MediaRepository;
import com.eduplatform.media.persistence.MediaRepository.ProcessingJob;
import com.eduplatform.media.processing.MediaProcessingCoordinator;
import com.eduplatform.media.service.MediaAssetService;
import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.storage.StorageObjectMetadata;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * T12F.1 媒体演示 Seed，只操作专用 media 开发库与固定 OSS Object Key。
 * 输入是 Initialize/Cleanup/Rebuild；输出是视频、自动/自定义海报、正文图和三类资料。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "platform.media.messaging.enabled=false", "platform.media.maintenance.enabled=false",
        "platform.media.oss.enabled=true", "platform.media.oss.key-prefix=t12f1-demo",
        "platform.media.worker.enabled=true",
        "spring.flyway.locations=filesystem:src/main/resources/db/migration"
})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "T12F1_DEMO_SEED_ENABLED", matches = "true")
class T12F1MediaDemoSeedTest {
    private static final String DATABASE = "edu_platform_media_dev";
    private static final String OWNER = "00000000-0000-4000-8000-000000000102";
    private static final String VIDEO = "00000000-0000-4000-8000-00000000f101";
    private static final String CUSTOM_POSTER = "00000000-0000-4000-8000-00000000f102";
    private static final String ARTICLE_IMAGE = "00000000-0000-4000-8000-00000000f103";
    private static final String PDF = "00000000-0000-4000-8000-00000000f104";
    private static final String TXT = "00000000-0000-4000-8000-00000000f105";
    private static final String PPTX = "00000000-0000-4000-8000-00000000f106";
    private static final List<String> IDS = List.of(VIDEO, CUSTOM_POSTER, ARTICLE_IMAGE, PDF, TXT, PPTX);

    @Autowired JdbcTemplate jdbc;
    @Autowired MediaRepository repository;
    @Autowired ObjectStorage storage;
    @Autowired MediaProcessingCoordinator coordinator;
    @Autowired ObjectMapper mapper;

    /** 在创建 Spring 连接前确认数据库用途，禁止误连生产或其他任务 Schema。 */
    @DynamicPropertySource
    static void requireDedicatedSchema(DynamicPropertyRegistry ignored) {
        if (!DATABASE.equals(System.getenv("MEDIA_MYSQL_DATABASE"))) {
            throw new IllegalStateException("T12F.1 media demo seed requires edu_platform_media_dev");
        }
    }

    @Test
    void applyRequestedDemoSeedAction() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DATABASE);
        String action = System.getenv().getOrDefault("T12F1_DEMO_SEED_ACTION", "").toUpperCase(Locale.ROOT);
        switch (action) {
            case "INITIALIZE" -> initialize();
            case "CLEANUP" -> cleanup();
            case "REBUILD" -> { cleanup(); initialize(); }
            default -> throw new IllegalArgumentException("T12F1_DEMO_SEED_ACTION must be INITIALIZE, CLEANUP or REBUILD");
        }
        System.out.printf("T12F.1 media demo action %s completed; assets=%d%n", action, IDS.size());
    }

    /** 固定 ID 已完整存在时只复核；部分存在时拒绝覆盖，要求显式 Rebuild。 */
    private void initialize() throws Exception {
        int existing = countAssets();
        if (existing == IDS.size()) { validateSeed(); return; }
        if (existing != 0) throw new IllegalStateException("T12F.1 media demo seed is partial; run Rebuild");
        Path scratch = Files.createTempDirectory("t12f1-media-seed-");
        try {
            Path video = scratch.resolve("lesson.mp4");
            Path poster = scratch.resolve("custom-poster.webp");
            Path image = scratch.resolve("article-image.png");
            runFfmpeg("-f", "lavfi", "-i", "color=c=blue:s=640x360:d=2",
                    "-f", "lavfi", "-i", "sine=frequency=880:duration=2", "-shortest",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", video.toString());
            runFfmpeg("-f", "lavfi", "-i", "color=c=orange:s=640x360", "-frames:v", "1", poster.toString());
            runFfmpeg("-f", "lavfi", "-i", "color=c=green:s=320x180", "-frames:v", "1", image.toString());
            seed(VIDEO, MediaPurpose.LESSON_VIDEO, "t12f1-short-video.mp4", "video/mp4", video, false);
            seed(CUSTOM_POSTER, MediaPurpose.VIDEO_POSTER, "t12f1-custom-poster.webp", "image/webp", poster, true);
            seed(ARTICLE_IMAGE, MediaPurpose.ARTICLE_IMAGE, "t12f1-formula.png", "image/png", image, true);
            seedBytes(PDF, MediaPurpose.ARTICLE_ATTACHMENT, "t12f1-reading.pdf", "application/pdf",
                    minimalPdf());
            seedBytes(TXT, MediaPurpose.ARTICLE_ATTACHMENT, "t12f1-notes.txt", "text/plain",
                    "T12F.1 demo notes\n".getBytes(StandardCharsets.UTF_8));
            seedBytes(PPTX, MediaPurpose.COURSEWARE, "t12f1-slides.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    minimalPptx());
            coordinator.process(new MediaDomainEvent(id("video-upload-event"), "MediaUploaded", 1, VIDEO,
                    Instant.now().toString(), "trace-t12f1-demo", mapper.createObjectNode(), "{}"));
            validateSeed();
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private void seedBytes(String mediaId, MediaPurpose purpose, String filename, String mimeType, byte[] bytes) throws Exception {
        Path file = Files.createTempFile("t12f1-seed-", ".bin");
        try { Files.write(file, bytes); seed(mediaId, purpose, filename, mimeType, file, true); }
        finally { Files.deleteIfExists(file); }
    }

    /** 写入一条固定上传会话和资产；视频保留 PENDING 交给正式状态机生成 HLS 与 poster-v1。 */
    private void seed(String mediaId, MediaPurpose purpose, String filename, String mimeType, Path source,
            boolean ready) throws Exception {
        String uploadId = id("upload-" + mediaId);
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        String objectKey = "t12f1-demo/original/" + mediaId + "." + extension;
        String sha = sha256(Files.readAllBytes(source));
        StorageObjectMetadata stored = storage.put(objectKey, source, mimeType);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insertUpload(new UploadSession(uploadId, OWNER, id("request-" + mediaId), "0".repeat(64),
                purpose, UploadMode.SINGLE_PUT, ready ? UploadStatus.READY : UploadStatus.PROCESSING,
                filename, extension, mimeType, stored.size(), sha, objectKey, null, null, "1".repeat(64),
                mediaId, now.plus(3650, ChronoUnit.DAYS), now, now));
        repository.insertAsset(new MediaAsset(mediaId, uploadId, OWNER, MediaAssetScope.TEACHER, purpose,
                ready ? MediaStatus.READY : MediaStatus.PROCESSING,
                ready ? ProcessingStatus.SUCCEEDED : ProcessingStatus.PENDING,
                purpose == MediaPurpose.LESSON_VIDEO ? MediaAssetService.LESSON_VIDEO_V5 : null,
                objectKey, filename, mimeType, stored.size(), sha, stored.etag(), null, null,
                null, null, now, now));
        if (!ready) repository.insertProcessingJob(new ProcessingJob(id("job-" + mediaId), mediaId,
                MediaAssetService.PROCESS_JOB, MediaAssetService.PRESET_VERSION, ProcessingStatus.PENDING,
                0, null, null, null, null, null, now, now));
    }

    /** 精确删除固定 ID 关联的原件、派生对象和数据库行，不列举 Bucket 或触碰其他 owner 数据。 */
    private void cleanup() {
        List<String> keys = new ArrayList<>();
        for (String mediaId : IDS) repository.findAsset(mediaId).ifPresent(asset -> {
            keys.add(asset.objectKey());
            repository.listVariants(mediaId).forEach(variant -> {
                keys.add(variant.objectKey());
                repository.listVariantComponents(variant.variantId()).forEach(component -> keys.add(component.objectKey()));
            });
        });
        keys.stream().distinct().forEach(storage::delete);
        String in = String.join(",", java.util.Collections.nCopies(IDS.size(), "?"));
        Object[] ids = IDS.toArray();
        jdbc.update("DELETE FROM consumed_event WHERE event_id=?", id("video-upload-event"));
        jdbc.update("DELETE FROM temporary_access_object WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM access_grant_audit WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM media_audit_record WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM media_reference WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM processing_job WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM media_variant_component WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM media_variant WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM media_asset WHERE media_id IN (" + in + ")", ids);
        jdbc.update("DELETE FROM upload_part WHERE upload_id IN (SELECT upload_id FROM upload_session WHERE media_id IN (" + in + "))", ids);
        jdbc.update("DELETE FROM upload_session WHERE media_id IN (" + in + ")", ids);
        assertThat(countAssets()).isZero();
    }

    private void validateSeed() {
        assertThat(countAssets()).isEqualTo(IDS.size());
        assertThat(repository.findAsset(VIDEO).orElseThrow().status()).isEqualTo(MediaStatus.READY);
        assertThat(repository.listVariants(VIDEO)).extracting(value -> value.variantName())
                .containsExactlyInAnyOrder("hls-fmp4-v2", "poster-v1");
        assertThat(repository.findVariant(VIDEO, "poster-v1").orElseThrow().mimeType()).isEqualTo("image/webp");
    }

    private int countAssets() {
        String in = String.join(",", java.util.Collections.nCopies(IDS.size(), "?"));
        return jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE media_id IN (" + in + ")",
                Integer.class, IDS.toArray());
    }

    private static void runFfmpeg(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg"),
                "-hide_banner", "-loglevel", "error", "-y"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).inheritIO().start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 生成带正确 xref 的单页空白 PDF，保证演示的 PREVIEW 对象可被标准阅读器解析。 */
    private static byte[] minimalPdf() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 640 360] /Contents 4 0 R >>",
                "<< /Length 0 >>\nstream\n\nendstream"
        };
        for (int index = 0; index < objects.length; index++) {
            offsets.add(output.size());
            output.write((index + 1 + " 0 obj\n" + objects[index] + "\nendobj\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        int xref = output.size();
        output.write(("xref\n0 5\n0000000000 65535 f \n" + offsets.stream()
                .map(value -> "%010d 00000 n \n".formatted(value))
                .reduce("", String::concat)
                + "trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    /** 生成最小但结构合法的单页 PPTX ZIP，演示仅验证下载，不引入 Office 在线转换。 */
    private static byte[] minimalPptx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                      <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                    </Types>
                    """);
            zipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                    </Relationships>
                    """);
            zipEntry(zip, "ppt/presentation.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
                      <p:sldSz cx="12192000" cy="6858000" type="screen16x9"/>
                    </p:presentation>
                    """);
            zipEntry(zip, "ppt/_rels/presentation.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                    </Relationships>
                    """);
            zipEntry(zip, "ppt/slides/slide1.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                      <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm/></p:grpSpPr></p:spTree></p:cSld>
                      <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
                    </p:sld>
                    """);
        }
        return output.toByteArray();
    }

    private static void zipEntry(ZipOutputStream zip, String name, String contents) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String id(String seed) {
        return UUID.nameUUIDFromBytes(("t12f1-media-" + seed).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
