package com.eduplatform.agentaccess.service;

import com.eduplatform.agentaccess.config.AgentProperties;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 用服务令牌访问 Course/Learning 私有投影；不发送浏览器提供的身份，也不记录请求正文。 */
@Component
public class HttpAgentDomainClient implements AgentDomainClient {
    private final RestClient course;
    private final RestClient learning;
    private final AgentProperties properties;

    public HttpAgentDomainClient(AgentProperties properties) {
        this.properties = properties;
        this.course = client(properties.courseBaseUrl(), properties);
        this.learning = client(properties.learningBaseUrl(), properties);
    }

    @Override
    public Set<UUID> verifyCourseCandidates(UUID owner, List<AgentInternalDtos.CourseCandidate> candidates,
            String traceId) {
        try {
            AgentInternalDtos.VerifyCourseCandidatesResponse response = course.post()
                    .uri("/internal/v1/agent-access/course-candidates:verify")
                    .headers(headers -> internal(headers, traceId))
                    .body(Map.of("ownerUserId", owner, "candidates", candidates)).retrieve()
                    .body(AgentInternalDtos.VerifyCourseCandidatesResponse.class);
            if (response == null || response.allowedEntityIds() == null) throw unavailable();
            Set<UUID> requested = candidates.stream().map(AgentInternalDtos.CourseCandidate::entityId)
                    .collect(Collectors.toSet());
            if (!requested.containsAll(response.allowedEntityIds())) throw unavailable();
            return Set.copyOf(response.allowedEntityIds());
        } catch (AgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @Override
    public List<AgentInternalDtos.SafeFact> readStudentFacts(UUID owner,
            AgentInternalDtos.StudentFactsRequest request, String traceId) {
        try {
            AgentInternalDtos.StudentFactsResponse response = learning.post()
                    .uri("/internal/v1/agent-access/student-facts:query")
                    .headers(headers -> internal(headers, traceId))
                    .body(Map.of("ownerUserId", owner, "toolName", request.toolName(),
                            "resourceIds", request.resourceIds() == null ? List.of() : request.resourceIds(),
                            "limit", request.limit())).retrieve().body(AgentInternalDtos.StudentFactsResponse.class);
            if (response == null || response.facts() == null) throw unavailable();
            Set<UUID> requested = request.resourceIds() == null ? Set.of() : Set.copyOf(request.resourceIds());
            return response.facts().stream()
                    .filter(fact -> requested.isEmpty() || requested.contains(fact.evidenceId()))
                    .limit(request.limit()).toList();
        } catch (AgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /** 一次 HTTP 调用解析一页去重后的精确题目版本，避免逐消息请求 Learning。 */
    @Override
    public List<AgentInternalDtos.QuestionVersionProjection> projectQuestionVersions(UUID owner,
            List<AgentInternalDtos.QuestionVersionKey> versions, String traceId) {
        if (versions.isEmpty()) return List.of();
        try {
            var response = learning.post().uri("/internal/v1/agent-access/question-versions:project")
                    .headers(headers -> internal(headers, traceId))
                    .body(new AgentInternalDtos.QuestionVersionProjectionRequest(owner, versions)).retrieve()
                    .body(AgentInternalDtos.QuestionVersionProjectionResponse.class);
            if (response == null || response.items() == null) throw unavailable();
            Set<AgentInternalDtos.QuestionVersionKey> requested = versions.stream()
                    .map(value -> new AgentInternalDtos.QuestionVersionKey(value.questionId(), value.questionVersionId()))
                    .collect(Collectors.toSet());
            if (response.items().stream().anyMatch(value -> !requested.contains(
                    new AgentInternalDtos.QuestionVersionKey(value.questionId(), value.questionVersionId())))) {
                throw unavailable();
            }
            return List.copyOf(response.items());
        } catch (AgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    /** 题目与版本来自已保存消息；Learning 再校验历史内容绑定后才返回短期授权。 */
    @Override
    public AgentInternalDtos.MediaAccessGrant authorizeQuestionVersionMedia(UUID owner, UUID questionId,
            UUID questionVersionId, UUID mediaId, String variant, String disposition, String traceId) {
        try {
            var response = learning.post().uri("/internal/v1/agent-access/question-version-media:authorize")
                    .headers(headers -> internal(headers, traceId))
                    .body(new AgentInternalDtos.QuestionVersionMediaAccessRequest(owner, questionId,
                            questionVersionId, mediaId, variant, disposition)).retrieve()
                    .body(AgentInternalDtos.MediaAccessGrant.class);
            if (response == null || !mediaId.equals(response.mediaId()) || response.url() == null
                    || response.expiresAt() == null) throw unavailable();
            return response;
        } catch (org.springframework.web.client.RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 403 || failure.getStatusCode().value() == 404) {
                throw new AgentException(HttpStatus.NOT_FOUND, "AGENT.NOT_FOUND", "资源不存在或不可见");
            }
            if (failure.getStatusCode().value() == 422) {
                throw new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "COMMON.VALIDATION_FAILED", "请求参数不合法");
            }
            throw unavailable();
        } catch (AgentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private void internal(org.springframework.http.HttpHeaders headers, String traceId) {
        headers.set("X-Internal-Caller", "agent-access-service");
        headers.set("X-Internal-Service-Token", properties.domainServiceToken());
        headers.set("X-Trace-Id", traceId);
    }

    private static RestClient client(String baseUrl, AgentProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.domainRequestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.domainRequestTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static AgentException unavailable() {
        return new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT.DEPENDENCY_UNAVAILABLE",
                "AI 授权依赖暂时不可用");
    }
}
