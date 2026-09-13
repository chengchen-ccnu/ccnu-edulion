package com.eduplatform.agentaccess.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 验证九类浏览器公开事件都有严格且互斥的字段结构，非法私有内容不会进入持久化层。 */
class AgentStreamEventValidatorTest {
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-4000-8000-000000001401");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-4000-8000-000000001402");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-4000-8000-000000001403");
    private static final String TRACE_ID = "trace-stream-contract";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    /** 每类合法事件均通过；同一 payload 缺字段、错类型或多字段都会被 Java 拒绝。 */
    void validatesEveryDiscriminatedPayloadAndRejectsShapeDrift() {
        validPayloads().forEach((type, payload) -> {
            JsonNode event = event(type, payload);
            AgentStreamEventValidator.validate(event, RUN_ID, TRACE_ID);

            String firstField = requiredField(type);
            ObjectNode missing = (ObjectNode) event(type, payload);
            ((ObjectNode) missing.get("payload")).remove(firstField);
            assertProtocolViolation(missing);

            ObjectNode wrongType = (ObjectNode) event(type, payload);
            ((ObjectNode) wrongType.get("payload")).putObject(firstField);
            assertProtocolViolation(wrongType);

            ObjectNode additional = (ObjectNode) event(type, payload);
            ((ObjectNode) additional.get("payload")).put("rawToolResult", "private");
            assertProtocolViolation(additional);
        });
    }

    @Test
    /** 参考来源只能携带 Java 已授权事实；应用路由只允许 USER_HELP 的安全相对路径。 */
    void referenceRejectsUnauthorizedSourceAndArbitraryRoute() {
        JsonNode authorized = event("REFERENCE", Map.of("reference", reference("USER_HELP", "/login")));
        AgentStreamEventValidator.validate(authorized, RUN_ID, TRACE_ID);

        assertProtocolViolation(event("REFERENCE", Map.of("reference", reference("MODEL_OUTPUT", "/login"))));
        assertProtocolViolation(event("REFERENCE", Map.of("reference", reference("QUESTION", "/login"))));
        assertProtocolViolation(event("REFERENCE", Map.of("reference", reference("USER_HELP", "/api/private"))));
        assertProtocolViolation(event("REFERENCE", Map.of("reference", reference("USER_HELP", "https://x"))));
    }

    @Test
    /** 提示词、推理、Token、工具原始返回和临时 URL 即使藏在合法字符串字段中也会被上层秘密扫描拒绝。 */
    void validatorRejectsEnvelopeMismatchAndUnsafeErrorShape() {
        ObjectNode event = (ObjectNode) event("TEXT_DELTA", Map.of("delta", "安全文本"));
        event.put("traceId", "trace-other-contract");
        assertProtocolViolation(event);

        assertProtocolViolation(event("RUN_ERROR", Map.of("terminal", false, "error", Map.of(
                "code", "INTERNAL", "message", "failure", "retryable", true, "traceId", TRACE_ID))));
        assertProtocolViolation(event("FINAL_RESULT", Map.of("terminal", true, "terminalStatus", "FAILED",
                "answerCompleteness", "COMPLETE", "interruptionNotice", "回答已中断")));
    }

    /** 滚动部署可读取旧 1.0.0，但当前标准 Skill 的 1.1.0 也必须通过，未知版本继续拒绝。 */
    @Test
    void practiceProposalAcceptsPublishedVersionsAndRejectsUnknownVersion() {
        for (String version : List.of("1.0.0", "1.1.0")) {
            AgentStreamEventValidator.validate(practiceEvent(version), RUN_ID, TRACE_ID);
        }
        assertProtocolViolation(practiceEvent("1.2.0"));
    }

    private LinkedHashMap<String, Map<String, Object>> validPayloads() {
        LinkedHashMap<String, Map<String, Object>> payloads = new LinkedHashMap<>();
        payloads.put("RUN_ACCEPTED", Map.of("conversationId", CONVERSATION_ID.toString(),
                "messageId", MESSAGE_ID.toString(), "replayed", false));
        payloads.put("STAGE_CHANGED", Map.of("stage", "UNDERSTANDING", "message", "正在理解"));
        payloads.put("TEXT_DELTA", Map.of("delta", "先移项"));
        payloads.put("CLARIFICATION_REQUIRED", Map.of("question", "请补充题目", "missingSlots", List.of("problemText")));
        payloads.put("REFERENCE", Map.of("reference", reference("QUESTION", null)));
        payloads.put("PARTIAL_CHECKPOINT", Map.of("text", "部分回答", "complete", false, "persisted", true));
        payloads.put("HEARTBEAT", Map.of("stage", "EXPLAINING"));
        payloads.put("RUN_ERROR", Map.of("terminal", false, "error", Map.of("code", "AGENT.RUNTIME_FAILED",
                "message", "暂时失败", "retryable", true, "traceId", TRACE_ID)));
        payloads.put("FINAL_RESULT", Map.of("terminal", true, "terminalStatus", "COMPLETED",
                "answerCompleteness", "COMPLETE", "finalAnswer", "答案"));
        return payloads;
    }

    private Map<String, Object> reference(String sourceType, String route) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("referenceId", "safe-reference");
        reference.put("sourceType", sourceType);
        reference.put("title", "授权来源");
        reference.put("accessDecision", "AUTHORIZED_BY_JAVA");
        if (route != null) reference.put("appRoute", route);
        return reference;
    }

    private JsonNode event(String type, Map<String, Object> payload) {
        return mapper.valueToTree(Map.of("eventType", type, "runId", RUN_ID.toString(), "sequence", 1,
                "traceId", TRACE_ID, "emittedAt", Instant.now().toString(), "payload", payload));
    }

    private JsonNode practiceEvent(String version) {
        return event("FINAL_RESULT", Map.of(
                "terminal", true,
                "terminalStatus", "COMPLETED",
                "answerCompleteness", "COMPLETE",
                "finalAnswer", "已准备候选",
                "practiceProposal", Map.of(
                        "skillName", "public-practice-generation",
                        "skillVersion", version,
                        "topic", "一元一次方程",
                        "difficulty", "MEDIUM",
                        "requestedCount", 5,
                        "candidates", List.of(Map.of(
                                "questionId", UUID.randomUUID().toString(),
                                "questionVersionId", UUID.randomUUID().toString())))));
    }

    private static String requiredField(String type) {
        return switch (type) {
            case "RUN_ACCEPTED" -> "conversationId";
            case "STAGE_CHANGED", "HEARTBEAT" -> "stage";
            case "TEXT_DELTA" -> "delta";
            case "CLARIFICATION_REQUIRED" -> "question";
            case "REFERENCE" -> "reference";
            case "PARTIAL_CHECKPOINT" -> "text";
            case "RUN_ERROR" -> "error";
            case "FINAL_RESULT" -> "terminalStatus";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private void assertProtocolViolation(JsonNode event) {
        assertThatThrownBy(() -> AgentStreamEventValidator.validate(event, RUN_ID, TRACE_ID))
                .isInstanceOfSatisfying(AgentException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo("AGENT.PROTOCOL_VIOLATION"));
    }
}
