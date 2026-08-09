package com.eduplatform.media.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.config.MediaProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 HLS 命令的音视频流选择边界，不启动本机 FFmpeg 子进程。 */
class FfmpegCommandRunnerTest {
    @Test
    void keepsOptionalFirstAudioStreamAndEncodesItAsAac() {
        MediaProperties.Worker properties = new MediaProperties.Worker(
                true, "ffmpeg", Duration.ofMinutes(2), 1, Duration.ofSeconds(6));

        List<String> command = new FfmpegCommandRunner().buildCommand(
                Path.of("input.mp4"), Path.of("index.m3u8"), Path.of("segment-%05d.ts"), properties);

        assertThat(command).doesNotContain("-an");
        assertThat(command).containsSubsequence("-map", "0:v:0", "-map", "0:a:0?");
        assertThat(command).containsSubsequence("-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2");
        assertThat(command).containsSubsequence("-f", "hls", "-hls_segment_type", "mpegts");
    }
}
