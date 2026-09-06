package com.eduplatform.learning.identity;

import com.eduplatform.learning.config.LearningProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 通过 IAM 内部批量接口读取昵称。超时或非成功响应只降级成绩册昵称，不影响 Learning 事实和编号。
 */
@Component
public class HttpLearnerDirectoryClient implements LearnerDirectoryClient {
    private static final Logger LOG = LoggerFactory.getLogger(HttpLearnerDirectoryClient.class);
    private final RestClient iam;
    private final String baseUrl;
    private final String serviceToken;

    public HttpLearnerDirectoryClient(LearningProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.iam().requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.iam().requestTimeout());
        this.baseUrl = properties.iam().baseUrl();
        this.iam = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.serviceToken = properties.iam().serviceToken();
    }

    @Override
    public NicknameLookup nicknames(List<String> learnerIds, String traceId) {
        if (learnerIds.isEmpty()) return new NicknameLookup(Map.of(), false);
        if (serviceToken == null || serviceToken.isBlank()) return new NicknameLookup(Map.of(), true);
        try {
            NicknameResponse response = iam.post().uri("/internal/v1/users/nicknames")
                    .header("X-Trace-Id", traceId)
                    .header("X-Internal-Caller", "learning-service")
                    .header("X-Internal-Service-Token", serviceToken)
                    .body(new NicknameRequest(learnerIds)).retrieve().body(NicknameResponse.class);
            if (response == null) return new NicknameLookup(Map.of(), true);
            return new NicknameLookup(response.nicknames(), false);
        } catch (RuntimeException unavailable) {
            LOG.warn("IAM nickname projection unavailable: traceId={}, baseUrl={}, failureType={}", traceId, baseUrl,
                    unavailable.getClass().getSimpleName());
            return new NicknameLookup(Map.of(), true);
        }
    }

    private record NicknameRequest(List<String> learnerIds) {}
    private record NicknameResponse(Map<String, String> nicknames) {}
}
