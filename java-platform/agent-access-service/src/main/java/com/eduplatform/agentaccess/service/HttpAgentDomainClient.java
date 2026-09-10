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
