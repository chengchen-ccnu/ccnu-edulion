package com.eduplatform.agentaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eduplatform.agentaccess.config.AgentProperties;
import com.eduplatform.agentaccess.web.AgentDtos;
import com.eduplatform.agentaccess.web.AgentInternalDtos;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 验证重新生成只恢复消息保存的历史题目版本，不访问公共题目的当前版本。 */
class HttpAuthorizedContextResolverTest {

    @Test
    void currentQuestionMediaGrantDefaultsToQuestionImageWhenLearningOmitsPurpose() throws Exception {
        Class<?> grantType = Class.forName(
                "com.eduplatform.agentaccess.service.HttpAuthorizedContextResolver$MediaGrant");
        var constructor = grantType.getDeclaredConstructor(String.class, String.class, long.class, String.class,
                String.class, Map.class, Instant.class, String.class);
        constructor.setAccessible(true);
        Object grant = constructor.newInstance(UUID.randomUUID().toString(), "image/png", 128L, "GET",
                "https://fixture.invalid/image", null, Instant.now().plusSeconds(60), null);
        var purpose = grantType.getDeclaredMethod("purpose");
        var headers = grantType.getDeclaredMethod("headers");
        purpose.setAccessible(true);
        headers.setAccessible(true);

        assertThat(purpose.invoke(grant)).isEqualTo("QUESTION_IMAGE");
        assertThat(headers.invoke(grant)).isEqualTo(Map.of());
    }

    @Test
    void savedContextUsesExactHistoricalProjectionAndMessageMediaGrant() {
        UUID owner = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        AgentDomainClient domains = mock(AgentDomainClient.class);
        MemoryContextProvider memories = mock(MemoryContextProvider.class);
        when(memories.preferences(owner)).thenReturn(List.of());
        when(memories.summaries(owner, null, null, null)).thenReturn(List.of());
        var projection = new AgentInternalDtos.QuestionVersionProjection(questionId, versionId,
                "SINGLE_CHOICE", "EASY", "JUNIOR", "SEVEN", "MATHEMATICS", "历史版本题干",
                List.of(new AgentInternalDtos.QuestionOption("A", "历史选项")),
                List.of(new AgentInternalDtos.QuestionMedia(bindingId, mediaId, "QUESTION_IMAGE", 1)), List.of());
        when(domains.projectQuestionVersions(owner,
                List.of(new AgentInternalDtos.QuestionVersionKey(questionId, versionId)), "trace-history"))
                .thenReturn(List.of(projection));
        when(domains.authorizeQuestionVersionMedia(owner, questionId, versionId, mediaId,
                "ORIGINAL", "INLINE", "trace-history"))
                .thenReturn(new AgentInternalDtos.MediaAccessGrant(mediaId, "image/png", 128, "GET",
                        "https://fixture.invalid/short-lived", Map.of(), Instant.now().plusSeconds(60)));

        var stable = new AgentDtos.MessageContext(questionId, versionId, null, null, null, List.of(mediaId));
        var result = resolver(memories, domains).resolveSaved(owner, AgentDtos.EntryPoint.PRACTICE_FLOATING,
                stable, List.of(mediaId), List.of(), "trace-history");

        assertThat(result.stableContext().questionVersionId()).isEqualTo(versionId);
        assertThat(result.trustedContext().get("question").toString())
                .contains("历史版本题干", versionId.toString()).doesNotContain("answer", "analysis");
        assertThat(result.trustedContext().get("media").toString()).contains(mediaId.toString());
    }

    @Test
    void missingSavedVersionFailsClosedInsteadOfReadingCurrentQuestion() {
        UUID owner = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentDomainClient domains = mock(AgentDomainClient.class);
        MemoryContextProvider memories = mock(MemoryContextProvider.class);
        when(domains.projectQuestionVersions(owner,
                List.of(new AgentInternalDtos.QuestionVersionKey(questionId, versionId)), "trace-missing"))
                .thenReturn(List.of());
        var stable = new AgentDtos.MessageContext(questionId, versionId, null, null, null, List.of());
        assertThatThrownBy(() -> resolver(memories, domains).resolveSaved(owner,
                AgentDtos.EntryPoint.PRACTICE_FLOATING, stable, List.of(), List.of(), "trace-missing"))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
    }

    private static HttpAuthorizedContextResolver resolver(MemoryContextProvider memories,
            AgentDomainClient domains) {
        var properties = new AgentProperties(true, "http://127.0.0.1:65530", Duration.ofSeconds(2),
                "http://127.0.0.1:65531", "http://127.0.0.1:65532", Duration.ofSeconds(1), "1",
                "test-only-runtime-callback-token-32-bytes", "test-only-agent-domain-token-32-bytes", 6, 16_000);
        return new HttpAuthorizedContextResolver(properties, memories, domains);
    }
}
