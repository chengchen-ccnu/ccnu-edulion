package com.eduplatform.media.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.eduplatform.media.config.MediaProperties;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * 使用本机真实 FFmpeg 验收 poster-v1：候选关键帧必须跳过开头黑场，并输出不放大的 WebP。
 * 该测试只写 JUnit 临时目录且离开用例后自动清理，不访问数据库、RabbitMQ 或 OSS。
 */
@EnabledIfEnvironmentVariable(named = "T12F1_FFMPEG_REAL_ACCEPTANCE", matches = "true")
class T12F1FfmpegPosterRealAcceptanceTest {
    @TempDir Path directory;

    @Test
    /** 输入一秒黑场和四秒测试画面；结果应选择第二个关键帧并保持 640x360。 */
    void skipsBlackKeyframeAndProducesBoundedWebpPoster() throws Exception {
        Path input = directory.resolve("synthetic.mp4");
        Path output = directory.resolve("poster-v1.webp");
        run(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=black:s=640x360:d=1:r=25",
                "-f", "lavfi", "-i", "testsrc2=s=640x360:d=4:r=25",
                "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]", "-map", "[v]",
                "-c:v", "libx264", "-g", "25", "-keyint_min", "25", "-sc_threshold", "0",
                "-pix_fmt", "yuv420p", input.toString()));

        MediaProperties.Worker worker = new MediaProperties.Worker(
                true, "ffmpeg", Duration.ofMinutes(1), 1, Duration.ofSeconds(2));
        new FfmpegCommandRunner().generatePoster(input, output, worker);

        assertThat(Files.readAllBytes(output)).startsWith("RIFF".getBytes(StandardCharsets.US_ASCII));
        assertThat(Files.readString(output, StandardCharsets.ISO_8859_1)).contains("WEBP");
        assertThat(probeDimensions(output)).isEqualTo("640,360");
        assertThat(meanBrightness(ImageIO.read(directory.resolve("poster-candidate-0.png").toFile()))).isLessThan(5);
        assertThat(meanBrightness(ImageIO.read(directory.resolve("poster-candidate-1.png").toFile()))).isBetween(8.0, 247.0);
    }

    /** 启动无凭据的本机媒体命令，失败时把有限 stderr 带入断言便于定位开发环境问题。 */
    private void run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
    }

    /** 通过配套 ffprobe 读取最终像素尺寸，确认最长边限制没有导致形变或上采样。 */
    private String probeDimensions(Path poster) throws Exception {
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height", "-of", "csv=p=0", poster.toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        return output;
    }

    /** 采样候选 PNG 的平均亮度，只用于证明首个黑场候选确实被跳过。 */
    private double meanBrightness(BufferedImage image) {
        assertThat(image).isNotNull();
        long brightness = 0;
        long samples = 0;
        for (int y = 0; y < image.getHeight(); y += 8) {
            for (int x = 0; x < image.getWidth(); x += 8) {
                int rgb = image.getRGB(x, y);
                brightness += (((rgb >> 16) & 0xff) * 299
                        + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
                samples++;
            }
        }
        return (double) brightness / samples;
    }
}
