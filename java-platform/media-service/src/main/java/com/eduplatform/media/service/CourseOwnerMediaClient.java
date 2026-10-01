package com.eduplatform.media.service;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/** 通用教学文件预览交给 Course 权威裁决；只走受保护 HTTP，故障关闭，不能由 Media 推测课程可见性。 */
@Component
public class CourseOwnerMediaClient {
    private final RestClient course;
    private final String token;

    public CourseOwnerMediaClient(@Value("${platform.media.course.base-url:http://127.0.0.1:8082}") String baseUrl,
            @Value("${platform.media.course.request-timeout:3s}") Duration timeout,
            @Value("${platform.media.service-authorization.secret:}") String token) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.course = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.token = token;
    }

    /** 输入为已验证的上传者 UUID 和 Trace；204 才继续签发，409 返回配置邮箱，其他响应/超时统一 503。 */
    public void requireAccess(String teacherId, String traceId) {
        try {
            var response = course.get().uri("/internal/v1/teachers/{teacherId}/course-media-access", teacherId)
                    .header("X-Internal-Caller", "media-service").header("X-Internal-Service-Token", token)
                    .header("X-Trace-Id", traceId).retrieve().toBodilessEntity();
            if (response.getStatusCode().value() != 204) throw unavailable();
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 409) {
                try {
                    var error = new ObjectMapper().readTree(failure.getResponseBodyAsString());
                    if ("COURSE.SUSPENDED".equals(error.path("code").asText())
                            && error.path("supportEmail").isTextual() && !error.path("supportEmail").asText().isBlank()) {
                        throw new MediaCourseSuspendedException(error.path("supportEmail").asText());
                    }
                } catch (MediaCourseSuspendedException suspended) { throw suspended; }
                catch (RuntimeException ignored) { /* 不输出依赖错误正文。 */ }
            }
            throw unavailable();
        } catch (MediaException failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    private static MediaException unavailable() {
        return new MediaException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA.COURSE_DEPENDENCY_UNAVAILABLE", "课程服务暂时不可用，请稍后重试");
    }
}
