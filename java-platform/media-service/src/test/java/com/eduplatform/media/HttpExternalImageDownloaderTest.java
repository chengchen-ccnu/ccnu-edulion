package com.eduplatform.media;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eduplatform.media.config.MediaProperties;
import com.eduplatform.media.service.HttpExternalImageDownloader;
import com.eduplatform.media.service.MediaException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 只验证 SSRF 前置拒绝，不发起网络请求或读取真实图片。 */
class HttpExternalImageDownloaderTest {
    @Test
    void rejectsPrivateAndNonAllowListedHostsBeforeNetwork() {
        var settings = new MediaProperties.ExternalImageImport(true, List.of("127.0.0.1"),
                1024, Duration.ofMillis(100), 0);
        var properties = new MediaProperties(null, null, null, null, null, null, settings);
        var downloader = new HttpExternalImageDownloader(properties);
        assertThatThrownBy(() -> downloader.download(URI.create("https://127.0.0.1/image.png")))
                .isInstanceOf(MediaException.class);
        assertThatThrownBy(() -> downloader.download(URI.create("https://unlisted.fixture.test/image.png")))
                .isInstanceOf(MediaException.class);
    }
}
