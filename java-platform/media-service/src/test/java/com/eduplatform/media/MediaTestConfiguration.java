package com.eduplatform.media;

import com.eduplatform.media.storage.ObjectStorage;
import com.eduplatform.media.processing.FfmpegCommandRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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
}
