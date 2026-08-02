package com.eduplatform.course.media;

import com.eduplatform.course.config.CourseMediaProperties;
import com.eduplatform.course.service.CourseException;
import com.eduplatform.shared.api.FieldError;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 使用版本化 JSON API 验证媒体绑定，不读取 media-service 数据库，也不接触 OSS SDK。
 */
@Component
public class HttpMediaBindingClient implements MediaBindingClient {
    private final RestClient client;

    public HttpMediaBindingClient(CourseMediaProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public void requireBindable(String mediaId, String purpose, String teacherId, String traceId) {
        try {
            BindingValidation response = client.post()
                    .uri("/api/v1/media/{mediaId}/binding-validations", mediaId)
                    .header("X-User-Id", teacherId)
                    .header("X-User-Roles", "TEACHER")
                    .header("X-Trace-Id", traceId)
                    .body(Map.of("purpose", purpose))
                    .retrieve()
                    .body(BindingValidation.class);
            if (response == null || !response.bindable() || !mediaId.equals(response.mediaId())
                    || !purpose.equals(response.purpose()) || !"READY".equals(response.status())) {
                throw notBindable(mediaId, "媒体验证响应与请求不一致");
            }
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw notBindable(mediaId, "媒体不存在或不属于当前教师");
            }
            if (exception.getStatusCode().value() == 409 || exception.getStatusCode().value() == 422) {
                throw notBindable(mediaId, "媒体用途不匹配或尚未处理完成");
            }
            throw unavailable(exception);
        } catch (CourseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private static CourseException notBindable(String mediaId, String reason) {
        return new CourseException(HttpStatus.UNPROCESSABLE_CONTENT, "COURSE.MEDIA_NOT_BINDABLE", "媒体不可绑定",
                List.of(new FieldError("mediaId", mediaId + ": " + reason)));
    }

    private static CourseException unavailable(RuntimeException cause) {
        return new CourseException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA.DEPENDENCY_UNAVAILABLE",
                "媒体服务暂时不可用，请稍后重试", List.of());
    }

    private record BindingValidation(String mediaId, String purpose, String status, boolean bindable) {}
}
