package com.eduplatform.agentaccess.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 对 Python 的九类公开流事件执行字段级白名单校验。
 *
 * <p>校验发生在 MySQL 持久化和浏览器发送之前；缺字段、错类型、额外字段、任意路由、秘密字段
 * 或不符合终态组合的 payload 均使本次运行安全失败，原始非法内容不会落库或回显。</p>
 */
final class AgentStreamEventValidator {
    private static final Set<String> EVENTS = Set.of("RUN_ACCEPTED", "STAGE_CHANGED", "TEXT_DELTA",
            "CLARIFICATION_REQUIRED", "REFERENCE", "PARTIAL_CHECKPOINT", "HEARTBEAT", "RUN_ERROR",
            "FINAL_RESULT");
    private static final Set<String> STAGES = Set.of("UNDERSTANDING", "RETRIEVING", "CHECKING_WORK",
            "PLANNING", "EXPLAINING", "FINALIZING");
    private static final Set<String> SLOTS = Set.of("problemText", "conceptName", "questionId", "courseId",
            "lessonId", "studentWork", "hintLevel", "wrongQuestionId", "practiceTopic", "planGoal",
            "difficulty", "questionCount", "platformTopic", "actionId");
    private static final Set<String> SOURCES = Set.of("QUESTION", "KNOWLEDGE_POINT", "COURSE_TEXT",
            "STUDENT_FACT", "USER_HELP");
    private static final Set<String> TERMINALS = Set.of("COMPLETED", "DEGRADED", "LIMIT_REACHED",
            "CANCELLED", "FAILED");
    private static final Pattern TRACE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern ERROR = Pattern.compile("^AGENT\\.[A-Z0-9_]{3,48}$");
    private static final Pattern ROUTE = Pattern.compile("^/[a-z0-9-]+(?:/[a-z0-9-]+)*$");

    private AgentStreamEventValidator() {}

    /** 校验完整事件信封与对应 payload；任何失败统一抛出公开协议违例。 */
    static void validate(JsonNode event, UUID expectedRunId, String expectedTraceId) {
        object(event, Set.of("eventType", "runId", "sequence", "traceId", "emittedAt", "payload"),
                Set.of("eventType", "runId", "sequence", "traceId", "emittedAt", "payload"));
        String eventType = text(event, "eventType", 1, 40);
        if (!EVENTS.contains(eventType) || !uuid(event, "runId").equals(expectedRunId)
                || integer(event, "sequence", 1) < 1
                || !text(event, "traceId", 8, 64).equals(expectedTraceId)
                || !TRACE.matcher(expectedTraceId).matches()) fail();
        try {
            Instant.parse(text(event, "emittedAt", 1, 80));
        } catch (RuntimeException invalid) {
            fail();
        }
        JsonNode payload = event.get("payload");
        switch (eventType) {
            case "RUN_ACCEPTED" -> accepted(payload);
            case "STAGE_CHANGED" -> stageChanged(payload);
            case "TEXT_DELTA" -> textDelta(payload);
            case "CLARIFICATION_REQUIRED" -> clarification(payload);
            case "REFERENCE" -> referencePayload(payload);
            case "PARTIAL_CHECKPOINT" -> checkpoint(payload);
            case "HEARTBEAT" -> heartbeat(payload);
            case "RUN_ERROR" -> runError(payload, expectedTraceId);
            case "FINAL_RESULT" -> finalResult(payload, expectedTraceId);
            default -> fail();
        }
    }

    private static void accepted(JsonNode node) {
        object(node, Set.of("conversationId", "messageId", "replayed"),
                Set.of("conversationId", "messageId", "replayed"));
        uuid(node, "conversationId"); uuid(node, "messageId"); bool(node, "replayed");
    }

    private static void stageChanged(JsonNode node) {
        object(node, Set.of("stage", "message"), Set.of("stage", "message"));
        if (!STAGES.contains(text(node, "stage", 1, 40))) fail();
        text(node, "message", 1, 120);
    }

    private static void textDelta(JsonNode node) {
        object(node, Set.of("delta"), Set.of("delta"));
        text(node, "delta", 1, 4000);
    }

    private static void clarification(JsonNode node) {
        object(node, Set.of("question", "missingSlots"), Set.of("question", "missingSlots"));
        text(node, "question", 1, 500);
        JsonNode slots = array(node, "missingSlots", 1, 8);
        Set<String> unique = new HashSet<>();
        slots.forEach(slot -> {
            if (!slot.isTextual() || !SLOTS.contains(slot.asText()) || !unique.add(slot.asText())) fail();
        });
    }

    private static void referencePayload(JsonNode node) {
        object(node, Set.of("reference"), Set.of("reference"));
        reference(node.get("reference"));
    }

    private static void checkpoint(JsonNode node) {
        object(node, Set.of("text", "complete", "persisted"), Set.of("text", "complete", "persisted"));
        text(node, "text", 1, 30000);
        if (bool(node, "complete") || !bool(node, "persisted")) fail();
    }

    private static void heartbeat(JsonNode node) {
        object(node, Set.of("stage"), Set.of("stage"));
        if (!STAGES.contains(text(node, "stage", 1, 40))) fail();
    }

    private static void runError(JsonNode node, String traceId) {
        object(node, Set.of("error", "terminal"), Set.of("error", "terminal"));
        if (bool(node, "terminal")) fail();
        safeError(node.get("error"), traceId);
    }

    private static void finalResult(JsonNode node, String traceId) {
        Set<String> allowed = Set.of("terminal", "terminalStatus", "finalAnswer", "answerCompleteness",
                "interruptionNotice", "savedCheckpointSequence", "references", "practiceProposal",
                "actionControl", "pendingPracticeAction", "skillOffer", "error");
        object(node, Set.of("terminal", "terminalStatus", "answerCompleteness"), allowed);
        if (!bool(node, "terminal")) fail();
        String status = text(node, "terminalStatus", 1, 32);
        String completeness = text(node, "answerCompleteness", 1, 20);
        if (!TERMINALS.contains(status)) fail();
        if (Set.of("COMPLETED", "DEGRADED", "LIMIT_REACHED").contains(status)) {
            text(node, "finalAnswer", 1, 60000);
            String expected = "COMPLETED".equals(status) ? "COMPLETE" : "SIMPLIFIED";
            if (!expected.equals(completeness) || node.has("interruptionNotice")) fail();
        } else if (!"INTERRUPTED".equals(completeness)
                || !"回答已中断".equals(text(node, "interruptionNotice", 1, 20))) {
            fail();
        }
        if (node.has("savedCheckpointSequence") && integer(node, "savedCheckpointSequence", 1) < 1) fail();
        if (node.has("references")) array(node, "references", 0, 20).forEach(AgentStreamEventValidator::reference);
        if (node.has("practiceProposal")) practiceProposal(node.get("practiceProposal"));
        if (node.has("actionControl") && !Set.of("CONFIRM_CURRENT", "CANCEL_CURRENT")
                .contains(text(node, "actionControl", 1, 30))) fail();
        if (node.has("pendingPracticeAction")) pendingPracticeAction(node.get("pendingPracticeAction"));
        if (node.has("skillOffer")) skillOffer(node.get("skillOffer"));
        if (node.has("skillOffer") && (node.has("practiceProposal") || node.has("pendingPracticeAction"))) fail();
        if (node.has("error")) safeError(node.get("error"), traceId);
    }

    /** 同一校验器先检查 Python 私有名称，再检查 Java 投影后的公开能力枚举。 */
    private static void skillOffer(JsonNode node) {
        Set<String> common = Set.of("offerId", "topic", "difficulty", "questionCount", "prompt");
        Set<String> names = new HashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        boolean internal = names.contains("skillName");
        Set<String> allowed = new HashSet<>(common);
        allowed.add(internal ? "skillName" : "capability");
        object(node, Set.of("offerId", internal ? "skillName" : "capability", "topic", "prompt"), allowed);
        uuid(node, "offerId"); text(node, "topic", 1, 200); text(node, "prompt", 1, 200);
        String identity = text(node, internal ? "skillName" : "capability", 1, 80);
        if (!(internal ? "public-practice-generation" : "GENERATE_AUTONOMOUS_PRACTICE").equals(identity)) fail();
        if (node.has("difficulty")) difficulty(node, "difficulty");
        if (node.has("questionCount")) {
            long count = integer(node, "questionCount", 1); if (count > 20) fail();
        }
    }

    private static void practiceProposal(JsonNode node) {
        object(node, Set.of("skillName", "skillVersion", "topic", "difficulty", "requestedCount", "candidates"),
                Set.of("skillName", "skillVersion", "topic", "difficulty", "requestedCount", "candidates", "allowDifficultyFallback"));
        if (!"public-practice-generation".equals(text(node, "skillName", 1, 40))
                || !Set.of("1.0.0", "1.1.0").contains(text(node, "skillVersion", 1, 20))) fail();
        text(node, "topic", 1, 200); difficulty(node, "difficulty");
        long requested = integer(node, "requestedCount", 1); if (requested > 20) fail();
        if (node.has("allowDifficultyFallback") && !node.get("allowDifficultyFallback").isBoolean()) fail();
        Set<UUID> versions = new HashSet<>();
        array(node, "candidates", 1, 20).forEach(value -> {
            object(value, Set.of("questionId", "questionVersionId"), Set.of("questionId", "questionVersionId"));
            uuid(value, "questionId"); if (!versions.add(uuid(value, "questionVersionId"))) fail();
        });
    }

    private static void pendingPracticeAction(JsonNode node) {
        object(node, Set.of("actionId", "conversationId", "criteria", "requestedCount", "actualCount",
                        "candidateQuestions", "status", "revision", "expiresAt", "executionResult"),
                Set.of("actionId", "conversationId", "criteria", "requestedCount", "actualCount",
                        "candidateQuestions", "status", "revision", "expiresAt", "executionResult"));
        uuid(node, "actionId"); uuid(node, "conversationId");
        JsonNode criteria = node.get("criteria");
        object(criteria, Set.of("topic", "difficulty", "requestedCount"),
                Set.of("topic", "difficulty", "requestedCount", "allowDifficultyFallback"));
        text(criteria, "topic", 1, 200); difficulty(criteria, "difficulty");
        if (criteria.has("allowDifficultyFallback") && !criteria.get("allowDifficultyFallback").isBoolean()) fail();
        long requested = integer(node, "requestedCount", 1);
        long actual = integer(node, "actualCount", 1);
        if (requested > 20 || actual > requested || integer(criteria, "requestedCount", 1) != requested) fail();
        Set<UUID> versions = new HashSet<>();
        JsonNode candidates = array(node, "candidateQuestions", 1, 20);
        if (candidates.size() != actual) fail();
        candidates.forEach(value -> {
            object(value, Set.of("questionId", "questionVersionId", "questionType", "difficulty"),
                    Set.of("questionId", "questionVersionId", "questionType", "difficulty"));
            uuid(value, "questionId"); if (!versions.add(uuid(value, "questionVersionId"))) fail();
            if (!Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK", "COMPOSITE")
                    .contains(text(value, "questionType", 1, 30))) fail();
            difficulty(value, "difficulty");
        });
        if (!Set.of("PENDING", "CONFIRMED", "CANCELLED", "EXPIRED", "EXECUTING", "SUCCEEDED", "FAILED")
                .contains(text(node, "status", 1, 20)) || integer(node, "revision", 0) < 0) fail();
        try { Instant.parse(text(node, "expiresAt", 1, 80)); } catch (RuntimeException invalid) { fail(); }
        JsonNode execution = node.get("executionResult");
        if (!execution.isNull()) {
            object(execution, Set.of("autonomousPracticeId", "assessmentId", "startRoute"),
                    Set.of("autonomousPracticeId", "assessmentId", "startRoute"));
            uuid(execution, "autonomousPracticeId"); uuid(execution, "assessmentId");
            String route = text(execution, "startRoute", 1, 80);
            if (!route.startsWith("/practice/") || route.contains("?") || route.contains("#")) fail();
        }
    }

    private static void difficulty(JsonNode node, String field) {
        if (!Set.of("EASY", "MEDIUM", "HARD").contains(text(node, field, 1, 10))) fail();
    }

    private static void reference(JsonNode node) {
        object(node, Set.of("referenceId", "sourceType", "title", "accessDecision"),
                Set.of("referenceId", "sourceType", "title", "accessDecision", "courseId", "lessonId", "appRoute"));
        text(node, "referenceId", 1, 128); text(node, "title", 1, 200);
        String source = text(node, "sourceType", 1, 32);
        if (!SOURCES.contains(source) || !"AUTHORIZED_BY_JAVA".equals(text(node, "accessDecision", 1, 32))) fail();
        if (node.has("courseId")) uuid(node, "courseId");
        if (node.has("lessonId")) uuid(node, "lessonId");
        if (node.has("appRoute")) {
            String route = text(node, "appRoute", 1, 120);
            if (!"USER_HELP".equals(source) || !ROUTE.matcher(route).matches()
                    || route.startsWith("/internal") || route.startsWith("/api")) fail();
        }
    }

    private static void safeError(JsonNode node, String traceId) {
        object(node, Set.of("code", "message", "retryable", "traceId"),
                Set.of("code", "message", "retryable", "traceId"));
        if (!ERROR.matcher(text(node, "code", 1, 64)).matches()) fail();
        text(node, "message", 1, 300); bool(node, "retryable");
        if (!traceId.equals(text(node, "traceId", 8, 64))) fail();
    }

    private static void object(JsonNode node, Set<String> required, Set<String> allowed) {
        if (node == null || !node.isObject()) fail();
        Set<String> names = new HashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        if (!names.containsAll(required) || !allowed.containsAll(names)) fail();
    }

    private static String text(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().length() < min || value.asText().length() > max) fail();
        return value.asText();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) fail();
        return value.asBoolean();
    }

    private static long integer(JsonNode node, String field, long minimum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() < minimum) fail();
        return value.asLong();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field, 36, 36));
        } catch (IllegalArgumentException invalid) {
            fail();
            throw invalid;
        }
    }

    private static JsonNode array(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.size() < min || value.size() > max) fail();
        return value;
    }

    private static void fail() {
        throw new AgentException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "AGENT.PROTOCOL_VIOLATION", "AI 运行协议响应不合法");
    }
}
