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

/** 使用一次性服务 JWT 调用 media-service，不向浏览器暴露服务令牌。 */
@Component
public class HttpMediaAccessClient implements MediaAccessClient {
    private final RestClient client;
    private final CourseMediaAuthorizationIssuer issuer;

    public HttpMediaAccessClient(CourseMediaProperties properties, CourseMediaAuthorizationIssuer issuer) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.requestTimeout());
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.issuer = issuer;
    }

    @Override
    public CourseDtos.CourseMediaAccessGrant issue(String mediaId, String purpose, String visitorId,
            String accessType, String variant, String disposition, String traceId) {
        try {
            String token = issuer.issueAccess(visitorId, mediaId, purpose, accessType, variant, disposition, traceId);
            CourseDtos.CourseMediaAccessGrant grant = client.post()
                    .uri("/internal/v1/media/{mediaId}/access-grants", mediaId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .header("X-Trace-Id", traceId)
                    .body(Map.of("visitorId", visitorId, "purpose", purpose, "accessType", accessType,
                            "variant", variant, "disposition", disposition))
                    .retrieve().body(CourseDtos.CourseMediaAccessGrant.class);
            if (grant == null || !"GET".equals(grant.method()) || grant.url() == null || grant.expiresAt() == null) {
                throw unavailable();
            }
            return grant;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new CourseException(HttpStatus.NOT_FOUND, "COURSE.NOT_FOUND", "课程不存在或不可见");
            }
            if (exception.getStatusCode().value() == 409) {
                throw new CourseException(HttpStatus.CONFLICT, "MEDIA.NOT_READY", "媒体尚未处理完成");
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
                "媒体服务暂时不可用，请稍后重试");
    }
}
