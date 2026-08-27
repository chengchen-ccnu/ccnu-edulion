package com.eduplatform.media.processing;

import com.eduplatform.media.config.MediaProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
