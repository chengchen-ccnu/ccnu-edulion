package com.eduplatform.course;

import com.eduplatform.course.media.MediaAccessClient;
import com.eduplatform.course.media.MediaBindingClient;
import com.eduplatform.course.media.MediaCoverClient;
import com.eduplatform.course.media.MediaPlaybackClient;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.course.web.CourseDtos;
import com.eduplatform.shared.api.FieldError;
import java.time.Instant;
import java.util.ArrayList;
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

    @Bean
    @Primary
    FakeMediaPlaybackClient fakeMediaPlaybackClient() {
        return new FakeMediaPlaybackClient();
    }

    @Bean
    @Primary
    FakeMediaCoverClient fakeMediaCoverClient() {
        return new FakeMediaCoverClient();
    }

    @Bean
    @Primary
    FakeMediaAccessClient fakeMediaAccessClient() {
        return new FakeMediaAccessClient();
    }

    /**
     * 模拟领域服务授权调用，记录 course-service 自行解析出的全部字段，并可令指定媒体返回安全错误。
     */
    static final class FakeMediaAccessClient implements MediaAccessClient {
        private final List<AccessRequest> requests = new ArrayList<>();
        private final Map<String, CourseException> failures = new HashMap<>();

        void clear() {
            requests.clear();
            failures.clear();
        }

        void failNotReady(String mediaId) {
            failures.put(mediaId, new CourseException(HttpStatus.CONFLICT,
                    "MEDIA.NOT_READY", "媒体尚未处理完成"));
        }

        void failNotFound(String mediaId) {
            failures.put(mediaId, new CourseException(HttpStatus.NOT_FOUND,
                    "COURSE.NOT_FOUND", "课程不存在或不可见"));
        }

        List<AccessRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public CourseDtos.CourseMediaAccessGrant issue(String mediaId, String purpose, String visitorId,
                String accessType, String variant, String disposition, String traceId) {
            requests.add(new AccessRequest(mediaId, purpose, visitorId, accessType, variant, disposition, traceId));
            CourseException failure = failures.get(mediaId);
            if (failure != null) throw failure;
            int sequence = requests.size();
            return new CourseDtos.CourseMediaAccessGrant("GET",
                    "https://oss.example.test/temporary-content/" + mediaId + "?grant=" + sequence,
                    Map.of(), Instant.parse("2026-08-05T10:10:00Z").plusSeconds(sequence));
        }

        record AccessRequest(String mediaId, String purpose, String visitorId,
                String accessType, String variant, String disposition, String traceId) {}
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
            String mime = "LESSON_VIDEO".equals(purpose) ? "video/mp4"
                    : purpose.contains("IMAGE") || purpose.contains("POSTER") || purpose.contains("COVER")
                            ? "image/png" : "application/pdf";
            assets.put(mediaId, new Asset(ownerId, purpose, status, "test-resource.bin", mime, 1024));
        }

        void register(String mediaId, String ownerId, String purpose, String status,
                String filename, String mimeType, long size) {
            assets.put(mediaId, new Asset(ownerId, purpose, status, filename, mimeType, size));
        }

        @Override
        public BindingSnapshot requireBindable(String mediaId, String purpose, String teacherId, String traceId) {
            Asset asset = assets.get(mediaId);
            if (asset == null || !teacherId.equals(asset.ownerId()) || !purpose.equals(asset.purpose())
                    || !"READY".equals(asset.status())) {
                throw new CourseException(HttpStatus.UNPROCESSABLE_CONTENT, "COURSE.MEDIA_NOT_BINDABLE",
                        "媒体不可绑定", List.of(new FieldError("mediaId", mediaId)));
            }
            java.util.Set<String> variants = "LESSON_VIDEO".equals(purpose)
                    ? java.util.Set.of("hls-fmp4-v2", "poster-v1") : java.util.Set.of();
            return new BindingSnapshot(mediaId, purpose, asset.filename(), asset.mimeType(), asset.size(),
                    variants, "LESSON_VIDEO".equals(purpose) ? "LESSON_VIDEO_V5" : null);
        }

        private record Asset(String ownerId, String purpose, String status,
                String filename, String mimeType, long size) {}
    }

    /** 返回确定性的短期封面投影，并记录访问者以验证教师/公开读取边界。 */
    static final class FakeMediaCoverClient implements MediaCoverClient {
        private CoverRequest lastRequest;

        void clear() {
            lastRequest = null;
        }

        CoverRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public String issueCover(String mediaId, String viewerId, String traceId) {
            lastRequest = new CoverRequest(mediaId, viewerId, traceId);
            return "https://oss.example.test/temporary-cover/" + mediaId;
        }

        record CoverRequest(String mediaId, String viewerId, String traceId) {}
    }

    /** 记录课程域交给媒体域的可信事实，并返回不含真实 OSS 数据的确定性短期授权。 */
    static final class FakeMediaPlaybackClient implements MediaPlaybackClient {
        private PlaybackRequest lastRequest;

        void clear() {
            lastRequest = null;
        }

        PlaybackRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public CourseDtos.CourseMediaPlaybackGrant issuePlayback(
                String mediaId, String purpose, String visitorId, String traceId) {
            lastRequest = new PlaybackRequest(mediaId, purpose, visitorId, traceId);
            return new CourseDtos.CourseMediaPlaybackGrant(
                    "GET", "https://oss.example.test/temporary-playlist", Map.of(),
                    Instant.parse("2026-08-05T10:10:00Z"));
        }

        record PlaybackRequest(String mediaId, String purpose, String visitorId, String traceId) {}
    }
}
