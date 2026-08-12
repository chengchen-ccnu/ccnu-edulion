package com.eduplatform.media;

import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.processing.FfmpegCommandRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.eduplatform.media.service.ExternalImageDownloader;
import java.net.URI;

/** 在 H2 自动化测试中以内存 Fake 替代真实 OSS，不读取任何云凭据。 */
@TestConfiguration
class MediaTestConfiguration {
    @Bean
    @Primary
    FakeObjectStorage fakeObjectStorage() {
        return new FakeObjectStorage();
    }

    @Bean
    @Primary
    FakeFfmpegCommandRunner fakeFfmpegCommandRunner() {
        return new FakeFfmpegCommandRunner();
    }

    @Bean
    @Primary
    FakeExternalImageDownloader fakeExternalImageDownloader() {
        return new FakeExternalImageDownloader();
    }
}

/** 仅返回虚构图片字节，避免自动化测试访问网络或真实历史图片。 */
class FakeExternalImageDownloader implements ExternalImageDownloader {
    private int calls;
    @Override public DownloadedImage download(URI source) {
        calls++;
        if (!"images.fixture.test".equals(source.getHost())) throw new IllegalArgumentException("fixture host required");
        return new DownloadedImage(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1},
                "image/png", source);
    }
    int calls() { return calls; }
}
