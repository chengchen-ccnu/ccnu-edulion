package com.eduplatform.media.processing;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.domain.MediaAsset;
import com.eduplatform.media.domain.MediaPurpose;
import com.eduplatform.media.storage.ObjectKeyFactory;
import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.storage.StorageObjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * T07 本机 FFmpeg Worker：课程封面不生成派生文件，课时视频生成无音频 baseline MP4。
 *
 * <p>原始与输出只落在随机临时目录，成功或失败后均尝试清理；派生 Object Key 由
 * mediaId + preset 确定，重放会覆盖同一键而不断产生新对象。</p>
 */
@Component
public class FfmpegMediaProcessingWorker implements MediaProcessingWorker {
    public static final String BASELINE_VARIANT = "baseline-mp4-v1";
    private final ObjectStorage storage;
    private final ObjectKeyFactory keys;
    private final MediaProperties properties;

    public FfmpegMediaProcessingWorker(ObjectStorage storage, ObjectKeyFactory keys, MediaProperties properties) {
        this.storage = storage;
        this.keys = keys;
        this.properties = properties;
    }

    @Override
    /** 下载原始视频、在时间上限内转码并上传派生结果；不提交临时文件或 FFmpeg 产物。 */
    public ProcessingResult process(MediaAsset asset) {
        if (asset.purpose() == MediaPurpose.COURSE_COVER) {
            return new ProcessingResult(List.of());
        }
        if (asset.purpose() != MediaPurpose.LESSON_VIDEO) {
            throw new IllegalStateException("No processing policy for media purpose");
        }
        if (!properties.worker().enabled()) {
            throw new IllegalStateException("FFmpeg worker is disabled");
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("edu-media-worker-");
            Path input = scratch.resolve("source.bin");
            Path output = scratch.resolve("baseline.mp4");
            Path error = scratch.resolve("ffmpeg-error.log");
            storage.download(asset.objectKey(), input);
            Process process = new ProcessBuilder(properties.worker().ffmpegPath(), "-hide_banner", "-loglevel", "error",
                    "-y", "-i", input.toString(), "-map", "0:v:0", "-c:v", "libx264", "-preset", "veryfast",
                    "-movflags", "+faststart", "-an", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(error.toFile())
                    .start();
            boolean completed = process.waitFor(properties.worker().timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg processing timed out");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                String diagnostic = Files.exists(error)
                        ? Files.readString(error, java.nio.charset.StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ") : "";
                if (diagnostic.length() > 300) diagnostic = diagnostic.substring(0, 300);
                throw new IllegalStateException("FFmpeg processing failed: " + diagnostic);
            }
            String objectKey = keys.derived(asset.mediaId(), BASELINE_VARIANT, "mp4");
            StorageObjectMetadata stored = storage.put(objectKey, output, "video/mp4");
            return new ProcessingResult(List.of(new ProcessingResult.VariantResult(BASELINE_VARIANT, objectKey,
                    "video/mp4", stored.size(), stored.etag())));
        } catch (IOException exception) {
            throw new IllegalStateException("FFmpeg worker scratch space failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg worker interrupted", exception);
        } finally {
            if (scratch != null) deleteScratch(scratch);
        }
    }

    private static void deleteScratch(Path scratch) {
        try (var paths = Files.walk(scratch)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
