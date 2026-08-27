package com.eduplatform.course.media;

import com.eduplatform.course.config.CourseMediaProperties;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.course.web.CourseDtos;
import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 使用带一次性服务令牌的内部 JSON API 向 media-service 申请授权。
 *
 * <p>调用不传课程可见性布尔值；媒体 ID、用途和访问者均来自 course-service 的可信决策，
 * 并同时写入请求体和签名令牌供 media-service 逐项比对。</p>
 */
@Component
public class HttpMediaPlaybackClient implements MediaPlaybackClient {
    static final String HLS_VARIANT = "hls-fmp4-v2";
    static final String LEGACY_HLS_VARIANT = "hls-v1";
    private final RestClient client;
    private final CourseMediaAuthorizationIssuer authorizationIssuer;

    public HttpMediaPlaybackClient(CourseMediaProperties properties,
            CourseMediaAuthorizationIssuer authorizationIssuer) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.authorizationIssuer = authorizationIssuer;
    }

    @Override
    public CourseDtos.CourseMediaPlaybackGrant issuePlayback(
            String mediaId, String purpose, String visitorId, String traceId) {
        try {
            return requestVariant(mediaId, purpose, visitorId, traceId, HLS_VARIANT);
        } catch (RestClientResponseException exception) {
            // 已发布旧视频尚未生成 v2 时只回退一次 v1；其他错误不扩大为第二轮媒体调用。
            if (exception.getStatusCode().value() == 404) {
                try {
                    return requestVariant(mediaId, purpose, visitorId, traceId, LEGACY_HLS_VARIANT);
                } catch (RestClientResponseException legacyFailure) {
                    throw mapFailure(legacyFailure);
                }
            }
            throw mapFailure(exception);
        } catch (CourseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /** 使用与变体精确绑定的一次性令牌请求媒体授权；每次调用都生成新的 jti，回退不会重放令牌。 */
    private CourseDtos.CourseMediaPlaybackGrant requestVariant(
            String mediaId, String purpose, String visitorId, String traceId, String variant) {
        String token = authorizationIssuer.issuePlayback(visitorId, mediaId, purpose, variant, traceId);
        CourseDtos.CourseMediaPlaybackGrant grant = client.post()
                .uri("/internal/v1/media/{mediaId}/access-grants", mediaId)
                .headers(headers -> headers.setBearerAuth(token))
                .header("X-Trace-Id", traceId)
                .body(Map.of(
                        "visitorId", visitorId,
                        "purpose", purpose,
                        "accessType", "COURSE_PLAYBACK",
                        "variant", variant,
                        "disposition", "INLINE"))
                .retrieve()
                .body(CourseDtos.CourseMediaPlaybackGrant.class);
        if (grant == null || !"GET".equals(grant.method()) || grant.url() == null
                || grant.expiresAt() == null) {
            throw unavailable();
        }
        return grant;
    }

    private static CourseException mapFailure(RestClientResponseException exception) {
        if (exception.getStatusCode().value() == 409) {
            return new CourseException(HttpStatus.CONFLICT, "MEDIA.NOT_READY", "媒体尚未处理完成");
        }
        if (exception.getStatusCode().value() == 404) {
            return new CourseException(HttpStatus.NOT_FOUND, "COURSE.NOT_FOUND", "课程不存在或不可见");
        }
        return unavailable();
    }

    private static CourseException unavailable() {
        return new CourseException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA.DEPENDENCY_UNAVAILABLE",
                "媒体服务暂时不可用，请稍后重试");
    }
}
