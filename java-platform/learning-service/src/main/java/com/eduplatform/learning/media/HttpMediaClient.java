package com.eduplatform.learning.media;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.shared.api.FieldError;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 通过版本化媒体 API 校验资产并获取短期源文件流；授权 URL 仅存在于方法栈内且从不记录。 */
@Component
public class HttpMediaClient implements MediaClient {
    private final RestClient media;
    private final HttpClient downloads;
    private final LearningProperties properties;

    public HttpMediaClient(LearningProperties properties) {
        this.properties = properties;
        this.downloads = HttpClient.newBuilder().connectTimeout(properties.media().requestTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(downloads);
        requestFactory.setReadTimeout(properties.media().requestTimeout());
        this.media = RestClient.builder().baseUrl(properties.media().baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    /** 调用媒体绑定验证契约；404 隐藏他人资产，409/422 统一映射为不可绑定。 */
    public void requireBindable(String mediaId, String purpose, String teacherId, String traceId) {
        try {
            Binding response = media.post().uri("/api/v1/media/{mediaId}/binding-validations", mediaId)
                    .header("X-User-Id", teacherId).header("X-User-Roles", "TEACHER").header("X-Trace-Id", traceId)
                    .body(Map.of("purpose", purpose)).retrieve().body(Binding.class);
            if (response == null || !response.bindable() || !mediaId.equals(response.mediaId())
                    || !purpose.equals(response.purpose()) || !"READY".equals(response.status())) {
                throw notBindable(mediaId, "媒体验证响应不一致");
            }
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) throw notBindable(mediaId, "媒体不存在或不属于当前教师");
            if (exception.getStatusCode().value() == 409 || exception.getStatusCode().value() == 422) {
                throw notBindable(mediaId, "媒体用途不匹配或尚未 READY");
            }
            throw unavailable();
        } catch (QuestionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    /** 先申请 ORIGINAL/ATTACHMENT 短期授权再打开流；URL 仅保存在当前栈帧。 */
    public InputStream openOriginal(String mediaId, String teacherId, String traceId) {
        try {
            Grant grant = media.post().uri("/api/v1/media/{mediaId}/access-grants", mediaId)
                    .header("X-User-Id", teacherId).header("X-User-Roles", "TEACHER").header("X-Trace-Id", traceId)
                    .body(Map.of("variant", "ORIGINAL", "disposition", "ATTACHMENT"))
                    .retrieve().body(Grant.class);
            if (grant == null || !"GET".equals(grant.method())) throw unavailable();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(grant.url()))
                    .timeout(properties.media().requestTimeout()).GET();
            grant.headers().forEach(request::header);
            HttpResponse<InputStream> response = downloads.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw unavailable();
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (QuestionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private static QuestionException notBindable(String mediaId, String reason) {
        return new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "QUESTION.MEDIA_NOT_BINDABLE", "媒体不可绑定",
                List.of(new FieldError("mediaId", mediaId + ": " + reason)));
    }

    private static QuestionException unavailable() {
        return new QuestionException(HttpStatus.SERVICE_UNAVAILABLE, "QUESTION.MEDIA_UNAVAILABLE", "媒体服务暂时不可用，请稍后重试");
    }

    private record Binding(String mediaId, String purpose, String status, boolean bindable) {}
    private record Grant(String method, String url, Map<String, String> headers) {}
}
