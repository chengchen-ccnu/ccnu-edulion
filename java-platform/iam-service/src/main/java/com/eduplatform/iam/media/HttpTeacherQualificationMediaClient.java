package com.eduplatform.iam.media;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.service.AuthException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 通过版本化 JSON 契约校验教师资质资产；不接触对象存储凭据或文件字节。 */
@Component
public class HttpTeacherQualificationMediaClient implements TeacherQualificationMediaClient {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HttpTeacherQualificationMediaClient.class);
    private final RestClient client;
    private final TeacherQualificationAuthorizationIssuer issuer;
    private final HttpClient probe;
    private final Duration timeout;

    public HttpTeacherQualificationMediaClient(IamProperties properties,
            TeacherQualificationAuthorizationIssuer issuer) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.media().requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.media().requestTimeout());
        this.client = RestClient.builder().baseUrl(properties.media().baseUrl()).requestFactory(factory).build();
        this.issuer = issuer;
        this.probe = http;
        this.timeout = properties.media().requestTimeout();
    }

    /** 用 IAM 一次性令牌请求 Media 原件，校验其确为资质允许的 PDF/图片，不接受任意 mediaId 外部调用。 */
    @Override
    public Grant grant(String mediaId, String administratorId, boolean download, String traceId) {
        try {
            String action = download ? "TEACHER_QUALIFICATION_DOWNLOAD" : "TEACHER_QUALIFICATION_PREVIEW";
            String disposition = download ? "ATTACHMENT" : "INLINE";
            Grant value = client.post().uri("/internal/v1/media/{mediaId}/access-grants", mediaId)
                    .header("Authorization", "Bearer " + issuer.issue(administratorId, mediaId, download, traceId))
                    .body(Map.of("visitorId", administratorId, "purpose", "TEACHER_QUALIFICATION",
                            "accessType", action, "variant", "ORIGINAL", "disposition", disposition))
                    .retrieve().body(Grant.class);
            if (value == null || !mediaId.equals(value.mediaId()) || !"GET".equals(value.method())
                    || value.url() == null || value.sizeBytes() < 1 || value.expiresAt() == null
                    || !Set.of("application/pdf", "image/jpeg", "image/png", "image/webp").contains(value.mimeType())) {
                notBindable();
            }
            return value;
        } catch (RestClientResponseException exception) {
            LOG.warn("Qualification Media grant returned status {}", exception.getStatusCode().value());
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409
                    || exception.getStatusCode().value() == 422) notBindable();
            throw unavailable();
        } catch (AuthException exception) { throw exception; }
        catch (RuntimeException exception) {
            LOG.warn("Qualification Media grant failed with {}", exception.getClass().getSimpleName());
            throw unavailable();
        }
    }

    /** 读取签名 GET 的首字节，确认真实原件仍可打开；流立即关闭，不把文件带入 IAM 内存或日志。 */
    @Override
    public void requireReadable(Grant grant) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(grant.url())).timeout(timeout).GET();
            if (grant.headers() != null) grant.headers().forEach(builder::header);
            HttpResponse<java.io.InputStream> response = probe.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300 || body.read() < 0) notBindable();
            }
        } catch (AuthException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
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
