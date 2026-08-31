package com.eduplatform.media.processing;

import com.eduplatform.media.config.MediaProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * 执行单个 FFmpeg HLS 子进程的可替换边界，便于自动化测试不依赖真实 FFmpeg 或 OSS。
 */
@Component
public class FfmpegCommandRunner {
    /**
     * 把一个已下载视频转为 VOD HLS 清单与切片。
     *
     * @param input Worker 临时目录中的原始视频
     * @param playlist 输出 m3u8 文件
     * @param segmentPattern FFmpeg 使用的切片文件模板
     * @param properties 超时、可执行文件和切片时长配置
     * @throws RuntimeException 超时、退出码非零或输出缺失时抛出，交由 MQ 有限重试
     */
    public void run(Path input, Path playlist, Path segmentPattern, MediaProperties.Worker properties) {
        Path error = playlist.getParent().resolve("ffmpeg-error.log");
        List<String> command = buildCommand(input, playlist, segmentPattern, properties);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(error.toFile());
            // FFmpeg 会相对工作目录写 init.mp4；固定为 Worker 随机目录，避免泄漏到服务启动目录。
            if (playlist.getParent() != null) processBuilder.directory(playlist.getParent().toFile());
            Process process = processBuilder.start();
            boolean completed = process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg processing timed out");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(playlist) || Files.size(playlist) == 0) {
                String diagnostic = Files.exists(error)
                        ? Files.readString(error, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ") : "";
                if (diagnostic.length() > 300) diagnostic = diagnostic.substring(0, 300);
                throw new IllegalStateException("FFmpeg HLS processing failed: " + diagnostic);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("FFmpeg process could not start", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg worker interrupted", exception);
        }
    }

    /**
     * 在视频前 30% 的有限关键帧集合中选择非纯黑/纯白画面并输出 WebP；没有合格候选时固定回退 10%。
     * 输出保持宽高比、最长边不超过 1280 且不放大，临时候选只存在 Worker 随机目录。
     */
    public void generatePoster(Path input, Path output, MediaProperties.Worker properties) {
        Path directory = output.getParent();
        Path probeOutput = directory.resolve("ffprobe-output.txt");
        Path probeError = directory.resolve("ffprobe-error.log");
        String ffprobe = ffprobePath(properties.ffmpegPath());
        runProcess(List.of(ffprobe, "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", input.toString()),
                properties.timeout().toMillis(), probeOutput, probeError);
        double duration = parseDuration(probeOutput);
        runProcess(List.of(ffprobe, "-v", "error", "-skip_frame", "nokey", "-select_streams", "v:0",
                "-show_entries", "frame=best_effort_timestamp_time", "-of", "csv=p=0", input.toString()),
                properties.timeout().toMillis(), probeOutput, probeError);
        List<Double> candidates = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(probeOutput, StandardCharsets.UTF_8)) {
                try {
                    double timestamp = Double.parseDouble(line.trim().split(",")[0]);
                    if (timestamp >= 0 && timestamp <= duration * 0.30 && candidates.size() < 12) candidates.add(timestamp);
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("FFprobe output could not be read", exception);
        }
        double selected = duration * 0.10;
        for (int index = 0; index < candidates.size(); index++) {
            Path candidate = directory.resolve("poster-candidate-" + index + ".png");
            extractFrame(input, candidate, candidates.get(index), properties, "png");
            if (isUsable(candidate)) {
                selected = candidates.get(index);
                break;
            }
        }
        extractFrame(input, output, selected, properties, "webp");
        try {
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IllegalStateException("FFmpeg poster output is missing");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("FFmpeg poster output could not be inspected", exception);
        }
    }

    private void extractFrame(Path input, Path output, double timestamp,
            MediaProperties.Worker properties, String format) {
        List<String> command = new ArrayList<>(List.of(properties.ffmpegPath(), "-hide_banner", "-loglevel", "error",
                "-y", "-ss", String.format(java.util.Locale.ROOT, "%.3f", timestamp), "-i", input.toString(),
                "-frames:v", "1", "-vf",
                "scale=if(gt(iw\\,ih)\\,min(iw\\,1280)\\,-2):if(gt(iw\\,ih)\\,-2\\,min(ih\\,1280))"));
        if ("webp".equals(format)) command.addAll(List.of("-c:v", "libwebp", "-quality", "85"));
        command.add(output.toString());
        runProcess(command, properties.timeout().toMillis(), null, output.getParent().resolve("poster-error.log"));
    }

    private static boolean isUsable(Path candidate) {
        try {
            BufferedImage image = ImageIO.read(candidate.toFile());
            if (image == null) return false;
            long brightness = 0;
            long extreme = 0;
            long samples = 0;
            int stepX = Math.max(1, image.getWidth() / 64);
            int stepY = Math.max(1, image.getHeight() / 64);
            for (int y = 0; y < image.getHeight(); y += stepY) {
                for (int x = 0; x < image.getWidth(); x += stepX) {
                    int rgb = image.getRGB(x, y);
                    int value = (((rgb >> 16) & 0xff) * 299 + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
                    brightness += value;
                    if (value <= 4 || value >= 251) extreme++;
                    samples++;
                }
            }
            double mean = samples == 0 ? 0 : (double) brightness / samples;
            return mean >= 8 && mean <= 247 && extreme < samples * 0.98;
        } catch (IOException exception) {
            return false;
        }
    }

    private static double parseDuration(Path output) {
        try {
            double value = Double.parseDouble(Files.readString(output, StandardCharsets.UTF_8).trim());
            if (!Double.isFinite(value) || value <= 0) throw new IllegalStateException("Video duration is invalid");
            return value;
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("Video duration could not be probed", exception);
        }
    }

    private static String ffprobePath(String ffmpegPath) {
        Path ffmpeg = Path.of(ffmpegPath);
        String executable = ffmpeg.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")
                ? "ffprobe.exe" : "ffprobe";
        return ffmpeg.getParent() == null ? executable : ffmpeg.getParent().resolve(executable).toString();
    }

    private static void runProcess(List<String> command, long timeoutMillis, Path output, Path error) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(error.toFile());
            if (output == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            else builder.redirectOutput(output.toFile());
            if (error.getParent() != null) builder.directory(error.getParent().toFile());
            Process process = builder.start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Media command timed out");
            }
            if (process.exitValue() != 0) {
                String diagnostic = Files.exists(error)
                        ? Files.readString(error, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ") : "";
                if (diagnostic.length() > 300) diagnostic = diagnostic.substring(0, 300);
                throw new IllegalStateException("Media command failed: " + diagnostic);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Media command could not start", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Media command interrupted", exception);
        }
    }

    /**
     * 构造单清晰度 fMP4 HLS 命令；视频流必选，第一条音轨可选并在存在时编码为 AAC。
     *
     * <p>{@code 0:a:0?} 的问号是 FFmpeg optional map 语义，保证没有音轨的源视频仍可转码。
     * 返回参数不包含凭据或存储地址，仅包含 Worker 临时文件路径。</p>
     */
    List<String> buildCommand(Path input, Path playlist, Path segmentPattern,
            MediaProperties.Worker properties) {
        long segmentSeconds = Math.max(1, properties.hlsSegmentDuration().toSeconds());
        return List.of(properties.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-y",
                "-i", input.toString(), "-map", "0:v:0", "-map", "0:a:0?",
                "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-force_key_frames", "expr:gte(t,n_forced*" + segmentSeconds + ")",
                "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2",
                "-f", "hls", "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
                "-hls_flags", "independent_segments", "-hls_time", Long.toString(segmentSeconds),
                "-hls_playlist_type", "vod", "-hls_segment_filename", segmentPattern.toString(), playlist.toString());
    }
}
