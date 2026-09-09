package com.eduplatform.learning.media;

import com.eduplatform.learning.config.LearningProperties;
import com.eduplatform.learning.service.QuestionException;
import com.eduplatform.learning.web.LearningDtos;
import com.eduplatform.shared.api.FieldError;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.time.Instant;
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
    private final AssessmentMediaAuthorizationIssuer authorizationIssuer;

    public HttpMediaClient(LearningProperties properties, AssessmentMediaAuthorizationIssuer authorizationIssuer) {
        this.properties = properties;
        this.authorizationIssuer = authorizationIssuer;
        this.downloads = HttpClient.newBuilder().connectTimeout(properties.media().requestTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(downloads);
        requestFactory.setReadTimeout(properties.media().requestTimeout());
        this.media = RestClient.builder().baseUrl(properties.media().baseUrl()).requestFactory(requestFactory).build();
    }

    /** 调用媒体绑定验证契约；404 隐藏他人资产，409/422 统一映射为不可绑定。 */
    @Override
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

    /** 先申请 ORIGINAL/ATTACHMENT 短期授权再打开流；URL 仅保存在当前栈帧。 */
    @Override
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

    /** 调用媒体域受限转存端点；响应只接受稳定 mediaId，不保存或记录 sourceUrl。 */
    @Override
    public String transferExternalImage(String clientRequestId, String sourceUrl, String ownerId,
            String scope, String traceId) {
        try {
            Asset response = media.post().uri("/api/v1/media/external-image-transfers")
                    .header("X-User-Id", ownerId)
                    .header("X-User-Roles", "SYSTEM".equals(scope) ? "ADMIN" : "TEACHER")
                    .header("X-User-Permissions", "SYSTEM".equals(scope) ? "administration:access" : "teaching:access")
                    .header("X-Trace-Id", traceId)
                    .body(Map.of("clientRequestId", clientRequestId, "sourceUrl", sourceUrl, "scope", scope))
                    .retrieve().body(Asset.class);
            if (response == null || response.mediaId() == null || !"QUESTION_IMAGE".equals(response.purpose())
                    || !"READY".equals(response.status())) throw unavailable();
            return response.mediaId();
        } catch (QuestionException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 422 || exception.getStatusCode().value() == 409) {
                throw new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "QUESTION.MEDIA_NOT_BINDABLE",
                        "历史图片无法安全转存");
            }
            throw unavailable();
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 将学习域已裁决的测评访问事实换成单资产短期授权。
     * 服务 JWT 只在方法栈内存在，不返回浏览器、不记日志也不入库。
     */
    @Override
    public LearningDtos.MediaAccessGrant issueAssessmentAccess(String mediaId, String learnerId, String purpose,
            LearningDtos.MediaDisposition disposition, String traceId) {
        return issueQuestionAccess(mediaId, learnerId, purpose, disposition, traceId, "ASSESSMENT_QUESTION");
    }

    /** 公共题正文授权复用已有短期令牌传输和错误映射，令牌明确区分正文来源。 */
    @Override
    public LearningDtos.MediaAccessGrant issueSystemQuestionAccess(String mediaId, String learnerId, String purpose,
            LearningDtos.MediaDisposition disposition, String traceId) {
        return issueQuestionAccess(mediaId, learnerId, purpose, disposition, traceId, "SYSTEM_QUESTION_CONTENT");
    }

    /** 已裁决的单资产经一次性 JWT 换取短期 URL；内部认证失败按依赖故障处理，不输出服务令牌。 */
    private LearningDtos.MediaAccessGrant issueQuestionAccess(String mediaId, String learnerId, String purpose,
            LearningDtos.MediaDisposition disposition, String traceId, String accessType) {
        try {
            String token = authorizationIssuer.issue(learnerId, mediaId, purpose, disposition.name(), traceId, accessType);
            Grant grant = media.post().uri("/internal/v1/media/{mediaId}/access-grants", mediaId)
                    .header("Authorization", "Bearer " + token)
                    .header("X-Trace-Id", traceId)
                    .body(Map.of("visitorId", learnerId, "purpose", purpose,
                            "accessType", accessType, "variant", "ORIGINAL",
                            "disposition", disposition.name()))
                    .retrieve().body(Grant.class);
            if (grant == null || !"GET".equals(grant.method()) || grant.expiresAt() == null) throw unavailable();
            return new LearningDtos.MediaAccessGrant(grant.mediaId(), grant.mimeType(), grant.sizeBytes(),
                    grant.method(), grant.url(), grant.headers(), grant.expiresAt());
        } catch (QuestionException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new QuestionException(HttpStatus.NOT_FOUND, "LEARNING.NOT_FOUND", "学习资源不存在或不可见");
            }
            if (exception.getStatusCode().value() == 409) {
                throw new QuestionException(HttpStatus.CONFLICT, "MEDIA.NOT_READY", "媒体尚未就绪");
            }
            throw unavailable();
        } catch (RuntimeException exception) {
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
    private record Grant(String mediaId, String mimeType, long sizeBytes,
            String method, String url, Map<String, String> headers, Instant expiresAt) {}
    private record Asset(String mediaId, String purpose, String status) {}
}
