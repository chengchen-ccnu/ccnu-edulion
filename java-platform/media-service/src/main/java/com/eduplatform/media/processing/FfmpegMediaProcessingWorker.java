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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * T08A 本机 FFmpeg Worker：普通课程文件不生成派生对象，课时视频生成 VOD HLS 清单与切片。
 *
 * <p>原始与输出只落在随机临时目录，成功或失败后均尝试清理；派生 Object Key 由
 * mediaId + preset 确定，重放会覆盖同一键而不断产生新对象。</p>
 */
@Component
public class FfmpegMediaProcessingWorker implements MediaProcessingWorker {
    public static final String HLS_VARIANT = "hls-v1";
    private final ObjectStorage storage;
    private final ObjectKeyFactory keys;
    private final MediaProperties properties;
    private final FfmpegCommandRunner runner;
    private final Semaphore permits;

    public FfmpegMediaProcessingWorker(ObjectStorage storage, ObjectKeyFactory keys, MediaProperties properties,
            FfmpegCommandRunner runner) {
        this.storage = storage;
        this.keys = keys;
        this.properties = properties;
        this.runner = runner;
        this.permits = new Semaphore(Math.max(1, properties.worker().maxConcurrentJobs()));
    }

    /** 下载原始视频、在时间上限内转码并上传派生结果；不提交临时文件或 FFmpeg 产物。 */
    @Override
    public ProcessingResult process(MediaAsset asset) {
        if (asset.purpose() != MediaPurpose.LESSON_VIDEO) {
            return new ProcessingResult(List.of());
        }
        if (!properties.worker().enabled()) {
            throw new IllegalStateException("FFmpeg worker is disabled");
        }
        boolean acquired = permits.tryAcquire();
        if (!acquired) throw new IllegalStateException("Media worker concurrency limit reached");
        Path scratch = null;
        List<String> uploadedKeys = new ArrayList<>();
        try {
            scratch = Files.createTempDirectory("edu-media-worker-");
            Path input = scratch.resolve("source.bin");
            Path playlist = scratch.resolve("index.m3u8");
            Path segmentPattern = scratch.resolve("segment-%05d.ts");
            storage.download(asset.objectKey(), input);
            runner.run(input, playlist, segmentPattern, properties.worker());
            List<Path> segments;
            try (var files = Files.list(scratch)) {
                segments = files.filter(path -> path.getFileName().toString().matches("segment-\\d{5}\\.ts"))
                        .sorted().toList();
            }
            if (segments.isEmpty()) throw new IllegalStateException("FFmpeg produced no HLS segments");
            normalizePlaylist(playlist, segments);
            List<ProcessingResult.ComponentResult> components = new ArrayList<>();
            for (Path segment : segments) {
                String name = segment.getFileName().toString();
                String componentKey = keys.derivedComponent(asset.mediaId(), HLS_VARIANT, name);
                StorageObjectMetadata stored = storage.put(componentKey, segment, "video/mp2t");
                uploadedKeys.add(componentKey);
                components.add(new ProcessingResult.ComponentResult(name, componentKey,
                        "video/mp2t", stored.size(), stored.etag()));
            }
            String playlistKey = keys.derived(asset.mediaId(), HLS_VARIANT, "m3u8");
            StorageObjectMetadata storedPlaylist = storage.put(
                    playlistKey, playlist, "application/vnd.apple.mpegurl");
            uploadedKeys.add(playlistKey);
            return new ProcessingResult(List.of(new ProcessingResult.VariantResult(HLS_VARIANT, playlistKey,
                    "application/vnd.apple.mpegurl", storedPlaylist.size(), storedPlaylist.etag(), components)));
        } catch (RuntimeException | IOException failure) {
            for (String objectKey : uploadedKeys) {
                try { storage.delete(objectKey); } catch (RuntimeException ignored) { }
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("FFmpeg worker scratch space failed", failure);
        } finally {
            if (scratch != null) deleteScratch(scratch);
            permits.release();
        }
    }

    /**
     * 将 FFmpeg 可能写入的本机绝对切片路径收口为已生成文件名，并拒绝清单引用未登记文件。
     * @param playlist 待上传的媒体播放清单
     * @param segments 当前随机临时目录内实际生成的切片
     * @throws IOException 清单无法读取或重写时交由处理失败重试
     */
    private static void normalizePlaylist(Path playlist, List<Path> segments) throws IOException {
        Set<String> names = new HashSet<>();
        segments.forEach(segment -> names.add(segment.getFileName().toString()));
        List<String> normalized = new ArrayList<>();
        for (String line : Files.readAllLines(playlist, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                normalized.add(line);
                continue;
            }
            String name = Path.of(value).getFileName().toString();
            if (!names.contains(name)) throw new IllegalStateException("HLS playlist references an unknown segment");
            normalized.add(name);
        }
        Files.write(playlist, normalized, StandardCharsets.UTF_8);
    }

    private static void deleteScratch(Path scratch) {
        try (var paths = Files.walk(scratch)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
