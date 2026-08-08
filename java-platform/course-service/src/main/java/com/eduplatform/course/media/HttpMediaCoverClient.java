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

/** 使用一次性服务令牌申请 COURSE_COVER 原件展示授权，不向浏览器暴露内部接口或令牌。 */
@Component
public class HttpMediaCoverClient implements MediaCoverClient {
    static final String ORIGINAL_VARIANT = "ORIGINAL";
    private final RestClient client;
    private final CourseMediaAuthorizationIssuer authorizationIssuer;

    public HttpMediaCoverClient(CourseMediaProperties properties,
            CourseMediaAuthorizationIssuer authorizationIssuer) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
        this.authorizationIssuer = authorizationIssuer;
    }

    /**
     * 申请只允许 GET/ORIGINAL/INLINE 的封面授权；自定义响应头无法由 img 安全附带，因此一律拒绝。
     *
     * @param mediaId 课程版本保存的稳定 COURSE_COVER 媒体 ID
     * @param viewerId 已通过课程可见性判断的教师 owner 或公开读取哨兵 UUID
     * @param traceId 当前 Gateway 请求的追踪 ID
     * @return 短期封面 URL，只交给课程响应投影
     * @throws CourseException 媒体未 READY 或媒体服务不可用时抛出，不会降级为存储地址
     */
    @Override
    public String issueCover(String mediaId, String viewerId, String traceId) {
        String token = authorizationIssuer.issueCover(viewerId, mediaId, traceId);
        try {
            CourseDtos.CourseMediaPlaybackGrant grant = client.post()
                    .uri("/internal/v1/media/{mediaId}/access-grants", mediaId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .header("X-Trace-Id", traceId)
                    .body(Map.of(
                            "visitorId", viewerId,
                            "purpose", "COURSE_COVER",
                            "accessType", "COURSE_COVER_DISPLAY",
                            "variant", ORIGINAL_VARIANT,
                            "disposition", "INLINE"))
                    .retrieve()
                    .body(CourseDtos.CourseMediaPlaybackGrant.class);
            if (grant == null || !"GET".equals(grant.method()) || grant.url() == null
                    || grant.expiresAt() == null || grant.headers() == null || !grant.headers().isEmpty()) {
                throw unavailable();
            }
            return grant.url();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 409) {
                throw new CourseException(HttpStatus.CONFLICT, "MEDIA.NOT_READY", "课程封面尚未处理完成");
            }
            throw unavailable();
        } catch (CourseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static CourseException unavailable() {
        return new CourseException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA.DEPENDENCY_UNAVAILABLE",
                "课程封面暂时无法加载，请稍后重试");
    }
}
