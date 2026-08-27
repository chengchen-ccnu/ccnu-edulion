package com.eduplatform.media.hls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证 fMP4 初始化段与媒体切片使用同一安全名称解析和 URL 重写规则。 */
class HlsPlaylistComponentTest {
    @Test
    void extractsAndRewritesInitAndMediaComponents() {
        assertThat(HlsPlaylistComponent.name("#EXT-X-MAP:URI=\"C:/scratch/init.mp4\""))
                .isEqualTo("init.mp4");
        assertThat(HlsPlaylistComponent.replace("#EXT-X-MAP:URI=\"init.mp4\"",
                "https://oss.test/init?signature=one"))
                .isEqualTo("#EXT-X-MAP:URI=\"https://oss.test/init?signature=one\"");
        assertThat(HlsPlaylistComponent.name("C:/scratch/segment-00000.m4s"))
                .isEqualTo("segment-00000.m4s");
        assertThat(HlsPlaylistComponent.replace("segment-00000.m4s", "https://oss.test/segment"))
                .isEqualTo("https://oss.test/segment");
        assertThat(HlsPlaylistComponent.name("#EXTINF:6.0,")).isNull();
    }

    @Test
    void rejectsMalformedInitAndUnsafeNames() {
        assertThatThrownBy(() -> HlsPlaylistComponent.name("#EXT-X-MAP:BYTERANGE=\"1@0\""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> HlsPlaylistComponent.name("segment name.m4s"))
                .isInstanceOf(IllegalStateException.class);
    }
}
