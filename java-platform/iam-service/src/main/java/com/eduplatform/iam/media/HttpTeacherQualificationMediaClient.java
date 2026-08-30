package com.eduplatform.iam.media;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.service.AuthException;
import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 通过版本化 JSON 契约校验教师资质资产；不接触对象存储凭据或文件字节。 */
@Component
public class HttpTeacherQualificationMediaClient implements TeacherQualificationMediaClient {
    private final RestClient client;

    public HttpTeacherQualificationMediaClient(IamProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.media().requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.media().requestTimeout());
        this.client = RestClient.builder().baseUrl(properties.media().baseUrl()).requestFactory(factory).build();
    }

    @Override
    public void requireBindable(String mediaId, String userId, String traceId) {
        try {
            Binding result = client.post().uri("/api/v1/media/{mediaId}/binding-validations", mediaId)
                    .header("X-User-Id", userId).header("X-User-Roles", "STUDENT")
                    .header("X-Trace-Id", traceId == null ? "unknown" : traceId)
                    .body(Map.of("purpose", "TEACHER_QUALIFICATION")).retrieve().body(Binding.class);
            if (result == null || !result.bindable() || !mediaId.equals(result.mediaId())
                    || !"TEACHER_QUALIFICATION".equals(result.purpose()) || !"READY".equals(result.status())) {
                notBindable();
            }
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409
                    || exception.getStatusCode().value() == 422) notBindable();
            throw unavailable();
        } catch (AuthException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private static void notBindable() {
        throw new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "IAM.QUALIFICATION_MEDIA_NOT_BINDABLE",
                "教师资质材料不存在、用途不符或尚未就绪");
    }
    private static AuthException unavailable() { return new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
            "MEDIA.DEPENDENCY_UNAVAILABLE", "媒体服务暂时不可用，请稍后重试"); }
    private record Binding(String mediaId, String purpose, String status, boolean bindable) {}
}
