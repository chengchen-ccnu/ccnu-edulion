package com.eduplatform.agentaccess.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eduplatform.agentaccess.config.AgentProperties;
import com.eduplatform.agentaccess.web.AgentDtos;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 构建期验证帮助版本选择、规则直答，以及重复/混版/内部术语/危险路由的失败语义。 */
class UserHelpKnowledgeBaseTest {
    private UserHelpKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        knowledgeBase = new UserHelpKnowledgeBase(JsonMapper.builder().build());
        knowledgeBase.load();
    }

    @Test
    void manifestSelectsCurrentVersionAndExactRuleIsDirect() {
        var direct = knowledgeBase.search("怎么登录", 5);
        assertThat(direct.version()).isEqualTo("2026.09.1");
        assertThat(direct.evidence()).hasSize(1);
        assertThat(direct.evidence().get(0).directAnswer()).isTrue();
        assertThat(direct.evidence().get(0).appRoute()).isEqualTo("/login");

        var complex = knowledgeBase.search("课程视频看完以后，学习进度和成绩什么时候显示", 5);
        assertThat(complex.evidence()).hasSizeBetween(2, 5).allSatisfy(item ->
                assertThat(item.version()).isEqualTo(direct.version()));
    }

    @Test
    void duplicateIdAndRuleAreRejected() {
        String first = article("help_first", "2026.09.9", "怎么登录", "/login", safeBody());
        String duplicateId = "{\"version\":\"2026.09.9\",\"locale\":\"zh-CN\",\"articles\":["
                + first + "," + first + "]}";
        assertThatThrownBy(() -> knowledgeBase.validateCandidateJson(duplicateId, "2026.09.9"))
                .isInstanceOf(IllegalStateException.class);

        String duplicateRule = "{\"version\":\"2026.09.9\",\"locale\":\"zh-CN\",\"articles\":["
                + first + "," + article("help_second", "2026.09.9", " 怎 么 登 录 ", "/courses", safeBody())
                + "]}";
        assertThatThrownBy(() -> knowledgeBase.validateCandidateJson(duplicateRule, "2026.09.9"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mixedVersionDangerousRouteAndInternalBodyAreRejected() {
        assertRejected(article("help_mixed", "2026.09.8", "如何注册", "/register", safeBody()));
        assertRejected(article("help_route", "2026.09.9", "如何注册", "/internal/admin", safeBody()));
        assertRejected(article("help_body", "2026.09.9", "如何注册", "/register",
                "请查看 T15A 和内部数据库环境变量后再执行维护指令，这段文字不允许面向学生发布。"));
    }

    @Test
    void independentStudentContextReceivesOnlyJavaHelpGrant() {
        MemoryContextProvider memories = mock(MemoryContextProvider.class);
        UUID owner = UUID.randomUUID();
        when(memories.preferences(owner)).thenReturn(List.of());
        when(memories.summaries(owner, null, null, null)).thenReturn(List.of());
        AgentProperties properties = new AgentProperties(true, "http://127.0.0.1:65530",
                Duration.ofSeconds(2), "http://127.0.0.1:65531", "http://127.0.0.1:65532",
                Duration.ofSeconds(1), "1", "test-only-runtime-callback-token-32-bytes",
                "test-only-agent-domain-token-32-bytes", 6, 16_000);
        var context = new HttpAuthorizedContextResolver(properties, memories, mock(AgentDomainClient.class)).resolve(owner,
                AgentDtos.EntryPoint.INDEPENDENT_CHAT,
                new AgentDtos.MessageContextRequest(null, null, null, List.of(), List.of()),
                "trace-help-grant");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) context.trustedContext().get("toolGrants");
        assertThat(grants).anySatisfy(grant -> {
            assertThat(grant.get("toolName")).isEqualTo("USER_HELP_SEARCH");
            assertThat(grant.get("resourceScopes")).isEqualTo(List.of(Map.of("scopeType", "HELP_KB")));
        });
    }

    private void assertRejected(String article) {
        String file = "{\"version\":\"2026.09.9\",\"locale\":\"zh-CN\",\"articles\":[" + article + "]}";
        assertThatThrownBy(() -> knowledgeBase.validateCandidateJson(file, "2026.09.9"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String article(String id, String version, String rule, String route, String body) {
        return "{\"articleId\":\"" + id + "\",\"topic\":\"AUTH\",\"title\":\"登录帮助\","
                + "\"keywords\":[\"登录账号\",\"登录密码\"],\"highPrecisionRules\":[\"" + rule + "\"],"
                + "\"body\":\"" + body + "\",\"version\":\"" + version + "\",\"appRoute\":\""
                + route + "\"}";
    }

    private static String safeBody() {
        return "请在登录页面填写已经验证的邮箱或中国大陆手机号，再输入正确密码完成登录。";
    }
}
