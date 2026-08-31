package com.eduplatform.media;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.processing.FfmpegCommandRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 确定性生成最小 HLS 清单/切片，可按次数注入失败，用于验证重试且不调用真实 FFmpeg。 */
class FakeFfmpegCommandRunner extends FfmpegCommandRunner {
    private int failuresRemaining;
    private Path lastScratch;

    synchronized void failNext(int count) {
        failuresRemaining = count;
    }

    synchronized Path lastScratch() {
        return lastScratch;
    }

    @Override
    public synchronized void run(Path input, Path playlist, Path segmentPattern, MediaProperties.Worker properties) {
        lastScratch = playlist.getParent();
        if (failuresRemaining > 0) {
            failuresRemaining--;
            throw new IllegalStateException("deterministic ffmpeg failure");
        }
        try {
            Path init = playlist.getParent().resolve("init.mp4");
            Path segment = playlist.getParent().resolve("segment-00000.m4s");
            Files.writeString(playlist, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"" + init
                    + "\"\n#EXTINF:2.0,\n" + segment + "\n#EXT-X-ENDLIST\n", StandardCharsets.UTF_8);
            Files.write(init, new byte[] {0, 1, 2, 3});
            Files.write(segment, new byte[] {1, 2, 3, 4});
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public synchronized void generatePoster(Path input, Path output, MediaProperties.Worker properties) {
        try {
            Files.write(output, new byte[] {0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4});
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
