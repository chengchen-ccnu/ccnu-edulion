package com.eduplatform.course;

import com.eduplatform.course.media.MediaBindingClient;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.shared.api.FieldError;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

/** 为课程集成测试提供不访问真实 media-service 的确定性绑定校验端口。 */
@TestConfiguration
class CourseMediaTestConfiguration {
    @Bean
    @Primary
    FakeMediaBindingClient fakeMediaBindingClient() {
        return new FakeMediaBindingClient();
    }

    /**
     * 模拟 media-service 的所有权、用途和 READY 校验；测试必须显式登记允许绑定的资产。
     */
    static final class FakeMediaBindingClient implements MediaBindingClient {
        private final Map<String, Asset> assets = new HashMap<>();

        void clear() {
            assets.clear();
        }

        void register(String mediaId, String ownerId, String purpose, String status) {
            assets.put(mediaId, new Asset(ownerId, purpose, status));
        }

        @Override
        public void requireBindable(String mediaId, String purpose, String teacherId, String traceId) {
            Asset asset = assets.get(mediaId);
            if (asset == null || !teacherId.equals(asset.ownerId()) || !purpose.equals(asset.purpose())
                    || !"READY".equals(asset.status())) {
                throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT, "COURSE.MEDIA_NOT_BINDABLE",
                        "媒体不可绑定", List.of(new FieldError("mediaId", mediaId)));
            }
        }

        private record Asset(String ownerId, String purpose, String status) {}
    }
}
