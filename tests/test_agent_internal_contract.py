"""验证 T13.1 内部 HTTP、流、工具、意图和索引事件契约。"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path
import unittest

from tests.contract_schema_support import (
    event_envelope_schema,
    index_version_errors,
    intent_semantic_errors,
    load_yaml,
    local_ref_errors,
    openapi_schema,
    schema_errors,
    stream_semantic_errors,
    tool_authorization_errors,
)


ROOT = Path(__file__).resolve().parents[1]
HTTP_CONTRACT_PATH = ROOT / "docs" / "api" / "agent-internal.yaml"
HTTP_EXAMPLES_PATH = ROOT / "docs" / "api" / "examples" / "agent-internal.v1.yaml"
INDEX_CONTRACT_PATH = ROOT / "docs" / "events" / "agent-index-events.v1.yaml"
INDEX_EXAMPLES_PATH = ROOT / "docs" / "events" / "examples" / "agent-index-events.v1.yaml"
INDEX_SOURCE_CONTRACT_PATH = ROOT / "docs" / "api" / "agent-index-source-internal.yaml"


class AgentInternalContractTests(unittest.TestCase):
    """以正式 Schema 和确定性语义检查覆盖 T13.1 正反例。"""

    @classmethod
    def setUpClass(cls):
        """一次读取版本化契约；不访问模型、索引、数据库或网络。"""
        cls.http_contract = load_yaml(HTTP_CONTRACT_PATH)
        cls.http_examples = load_yaml(HTTP_EXAMPLES_PATH)
        cls.index_contract = load_yaml(INDEX_CONTRACT_PATH)
        cls.index_examples = load_yaml(INDEX_EXAMPLES_PATH)
        cls.index_source_contract = load_yaml(INDEX_SOURCE_CONTRACT_PATH)

    def assertSchemaValid(self, schema_name, value):
        """断言一个示例符合指定内部 HTTP 组件 Schema。"""
        errors = schema_errors(openapi_schema(self.http_contract, schema_name), value)
        self.assertEqual(errors, [], errors)

    def test_valid_text_and_question_requests(self):
        """合法纯文本及题目快照＋学生主动作答均能通过可信上下文 Schema。"""
        for value in self.http_examples["validRequests"].values():
            self.assertSchemaValid("AgentRunRequest", value)
        self.assertSchemaValid("AgentRunRequest", self.http_examples["retryOfTextQuestion"])

    def test_all_local_schema_references_resolve(self):
        """内部 HTTP 与索引事件契约的每个本地引用都必须指向现存节点。"""
        self.assertEqual(local_ref_errors(self.http_contract), [])
        self.assertEqual(local_ref_errors(self.index_contract), [])
        self.assertEqual(local_ref_errors(self.index_source_contract), [])

    def test_index_source_api_is_internal_paginated_and_excludes_hidden_facts(self):
        """Java 索引源必须使用专用调用身份与稳定游标，题目类型层面不含答案/解析。"""
        paths = self.index_source_contract["paths"]
        self.assertEqual(
            set(paths),
            {
                "/internal/v1/agent-index/student-memories",
                "/internal/v1/agent-index/questions",
                "/internal/v1/agent-index/knowledge-points",
                "/internal/v1/agent-index/course-texts",
                "/internal/v1/agent-access/course-candidates:verify",
                "/internal/v1/agent-access/student-facts:query",
            },
        )
        parameters = self.index_source_contract["components"]["parameters"]
        self.assertEqual(parameters["InternalCaller"]["schema"]["const"], "agent-index-worker")
        self.assertGreaterEqual(parameters["InternalToken"]["schema"]["minLength"], 32)
        question = self.index_source_contract["components"]["schemas"]["QuestionIndexProjection"]
        self.assertTrue({"entityId", "questionVersionId", "sourceRevision"}.issubset(question["required"]))
        for forbidden in ("answers", "analysis", "url", "token"):
            self.assertNotIn(forbidden, question["properties"])
        self.assertEqual(parameters["AgentAccessCaller"]["schema"]["const"], "agent-access-service")
        safe_fact = self.index_source_contract["components"]["schemas"]["SafeStudentFact"]
        for forbidden in ("answer", "analysis", "userId", "url", "token"):
            self.assertNotIn(forbidden, safe_fact["properties"])
        memory = self.index_source_contract["components"]["schemas"]["StudentMemoryIndexProjection"]
        self.assertTrue({"subjectUserId", "sourceRevision", "text", "visibility"}.issubset(memory["required"]))
        for forbidden in ("answer", "analysis", "score", "evidence", "hiddenProfile", "token", "url"):
            self.assertNotIn(forbidden, memory["properties"])

    def test_t1421_v1_compatibility_recent_window_and_private_callbacks(self):
        """旧 v1 请求仍合法；新增最近窗口和三个无 userId 的私有回调具有严格 Schema。"""
        old_request = deepcopy(self.http_examples["validRequests"]["textQuestion"])
        old_request.pop("recentMessages", None)
        self.assertSchemaValid("AgentRunRequest", old_request)
        recent = deepcopy(old_request)
        recent["recentMessages"] = [{
            "messageId": "00000000-0000-4000-8000-000000000111",
            "role": "ASSISTANT", "content": "上轮保留的部分回答", "completeness": "PARTIAL",
            "sequenceNo": 2,
        }]
        self.assertSchemaValid("AgentRunRequest", recent)
        course = {"candidates": [{
            "entityId": "00000000-0000-4000-8000-000000000201",
            "courseId": "00000000-0000-4000-8000-000000000202",
            "courseVersionId": "v2", "lessonId": "00000000-0000-4000-8000-000000000201",
            "sourceRevision": 3,
        }]}
        self.assertSchemaValid("VerifyCourseCandidatesRequest", course)
        facts = {"toolName": "STUDENT_PROGRESS", "resourceIds": [], "limit": 8}
        self.assertSchemaValid("StudentFactsRequest", facts)
        self.assertNotIn("userId", course)
        self.assertNotIn("userId", facts)
        report = {
            "reportId": "00000000-0000-4000-8000-000000000203", "model": "test-model",
            "modelCalls": 2, "visionCalls": 0, "toolAttempts": 1,
            "promptTokens": 10, "completionTokens": 5,
            "phaseDurationsMs": {"MODEL_MATH_TUTOR": 20}, "terminationReason": "COMPLETED",
            "memoryCandidates": ["明确偏好：分步讲解"],
        }
        self.assertSchemaValid("RunResultReportRequest", report)

    def test_skill_execution_audit_contract_is_private_and_bounded(self):
        """Skill 审计只接受 Runtime 身份、相对路径和安全终态，不承载正文或输入输出。"""
        start = {
            "executionId": "00000000-0000-4000-8000-000000000204",
            "sequence": 1,
            "skillName": "test-skill",
            "skillVersion": "1.2.3",
            "selectionSource": "LLM",
        }
        finish = {
            "status": "FAILED",
            "referencePaths": ["references/rules.md"],
            "script": "scripts/check.py",
            "failureCode": "SKILL_SCRIPT_NON_ZERO",
        }
        self.assertSchemaValid("StartSkillExecutionRequest", start)
        self.assertSchemaValid("FinishSkillExecutionRequest", finish)
        invalid = deepcopy(finish)
        invalid["script"] = "../other.py"
        self.assertTrue(schema_errors(openapi_schema(self.http_contract, "FinishSkillExecutionRequest"), invalid))
        for forbidden in ("prompt", "input", "output", "answer", "environment"):
            self.assertNotIn(forbidden, self.http_contract["components"]["schemas"]["FinishSkillExecutionRequest"]["properties"])
        paths = self.http_contract["paths"]
        self.assertIn("/internal/v1/agent/runs/{runId}/skill-executions", paths)
        self.assertIn("/internal/v1/agent/runs/{runId}/skill-executions/{executionId}", paths)

    def test_retry_keeps_all_logical_and_execution_ids(self):
        """相同逻辑请求重试必须沿用会话、客户端、消息和 run 身份。"""
        original = self.http_examples["validRequests"]["textQuestion"]
        retry = self.http_examples["retryOfTextQuestion"]
        for key in ("conversationId", "clientRequestId", "messageId", "runId"):
            self.assertEqual(original[key], retry[key])
        semantics = self.http_contract["paths"]["/internal/v1/agent/runs"]["post"]["x-idempotency"]
        self.assertFalse(semantics["retryCreatesMessage"])
        self.assertFalse(semantics["retryStartsExecution"])

    def test_concurrent_run_conflict_is_structured_and_not_queued(self):
        """同会话并发 run 使用可重试 409 安全错误，且协议明确不排队。"""
        for value in self.http_examples["validHttpErrors"].values():
            self.assertSchemaValid("SafeError", value)
        concurrency = self.http_contract["paths"]["/internal/v1/agent/runs"]["post"]["x-concurrency"]
        self.assertEqual(concurrency["maxActiveRuns"], 1)
        self.assertEqual(concurrency["conflictStatus"], 409)
        self.assertFalse(concurrency["queueOnConflict"])

    def test_cancel_is_idempotent_and_has_one_cancelled_terminal(self):
        """取消请求与重放响应合法；取消流只能以一个 CANCELLED 终态结束。"""
        for value in self.http_examples["validCancelRequests"].values():
            self.assertSchemaValid("CancelRunRequest", value)
        for value in self.http_examples["validCancelResponses"].values():
            self.assertSchemaValid("CancelRunResponse", value)
        events = self.http_examples["validStreams"]["cancelledWithPartial"]
        for event in events:
            self.assertSchemaValid("StreamEvent", event)
        self.assertEqual(stream_semantic_errors(events), [])
        terminals = [event for event in events if event["eventType"] == "FINAL_RESULT"]
        self.assertEqual(len(terminals), 1)
        self.assertEqual(terminals[0]["payload"]["terminalStatus"], "CANCELLED")

    def test_streams_cover_normal_partial_and_limit_terminal_semantics(self):
        """正常、部分回答中断与超预算流都通过 Schema 和连续序号检查。"""
        for name, events in self.http_examples["validStreams"].items():
            for event in events:
                self.assertSchemaValid("StreamEvent", event)
            self.assertEqual(stream_semantic_errors(events), [], name)
        partial = self.http_examples["validStreams"]["cancelledWithPartial"][-1]["payload"]
        self.assertEqual(partial["answerCompleteness"], "INTERRUPTED")
        self.assertEqual(partial["interruptionNotice"], "回答已中断")
        limit = self.http_examples["validStreams"]["limitReached"][-1]["payload"]
        self.assertEqual(limit["terminalStatus"], "LIMIT_REACHED")
        self.assertEqual(limit["answerCompleteness"], "SIMPLIFIED")

    def test_stream_event_and_terminal_sets_are_frozen(self):
        """事件类型、唯一终态集合和超时映射不能因样例覆盖不足而缩减。"""
        schemas = self.http_contract["components"]["schemas"]
        self.assertEqual(
            set(schemas["StreamEvent"]["properties"]["eventType"]["enum"]),
            {
                "RUN_ACCEPTED",
                "STAGE_CHANGED",
                "TEXT_DELTA",
                "CLARIFICATION_REQUIRED",
                "REFERENCE",
                "PARTIAL_CHECKPOINT",
                "HEARTBEAT",
                "RUN_ERROR",
                "FINAL_RESULT",
            },
        )
        self.assertEqual(
            set(schemas["TerminalStatus"]["enum"]),
            {"COMPLETED", "DEGRADED", "LIMIT_REACHED", "CANCELLED", "FAILED"},
        )
        self.assertEqual(schemas["RunBudget"]["properties"]["totalTimeMs"]["default"], 90000)
        timeout_termination = self.http_contract["paths"]["/internal/v1/agent/runs"]["post"][
            "x-stream-semantics"
        ]["timeoutTermination"]
        self.assertEqual(timeout_termination["SLOW_CLIENT"], "FAILED")

    def test_invalid_stream_order_and_duplicate_terminal_are_rejected(self):
        """序号缺口或第二个终态即使单事件合法，也不能成为合法 run 流。"""
        for events in self.http_examples["invalidStreamSequences"].values():
            self.assertTrue(stream_semantic_errors(events))

    def test_intent_roles_slots_and_two_subrequest_limit(self):
        """合法意图使用固定专家、受约束槽位和最多两个有序子请求。"""
        for decision in self.http_examples["validIntentDecisions"].values():
            self.assertSchemaValid("IntentDecision", decision)
            self.assertEqual(intent_semantic_errors(self.http_contract, decision), [])

        missing_work = deepcopy(self.http_examples["validIntentDecisions"]["checkWork"])
        del missing_work["slots"]["studentWork"]
        self.assertTrue(intent_semantic_errors(self.http_contract, missing_work))

    def test_tool_calls_and_safe_results_validate(self):
        """只读工具调用具有 toolCallId/超时，结果只能使用受控安全证据。"""
        for value in self.http_examples["validToolCalls"].values():
            self.assertSchemaValid("ToolCallRequest", value)
        for value in self.http_examples["validToolResults"].values():
            self.assertSchemaValid("ToolResult", value)

    def test_tool_resource_scope_cannot_be_elevated_by_model(self):
        """模型把参数换成白名单外资源时，语义门禁必须拒绝。"""
        request = self.http_examples["validRequests"]["questionWithStudentWork"]
        tool_call = deepcopy(self.http_examples["validToolCalls"]["questionSearch"])
        grants = request["trustedContext"]["toolGrants"]
        self.assertEqual(tool_authorization_errors(tool_call, grants), [])
        tool_call["arguments"]["resourceIds"] = ["ffffffff-ffff-4fff-8fff-ffffffffffff"]
        self.assertTrue(tool_authorization_errors(tool_call, grants))

    def test_all_schema_negative_examples_really_fail(self):
        """伪造权限、越界预算、非法角色/意图/工具/事件和秘密字段均不能过 Schema。"""
        for case in self.http_examples["invalidSchemaCases"]:
            errors = schema_errors(
                openapi_schema(self.http_contract, case["schema"]), case["value"]
            )
            self.assertTrue(errors, case["name"])

    def test_default_budget_is_configurable_but_matches_first_release(self):
        """首版预算是请求中的可配置值，同时固定三步教学补救顺序。"""
        budget = self.http_examples["validRequests"]["textQuestion"]["budget"]
        self.assertEqual(budget["maxOrchestrationSteps"], 12)
        self.assertEqual(budget["maxLlmCalls"], 8)
        self.assertEqual(budget["reservedFinalLlmCalls"], 1)
        self.assertEqual(budget["maxToolAttempts"], 10)
        self.assertEqual(budget["maxExpertTransfers"], 2)
        self.assertEqual(budget["maxSameToolArgumentsAttempts"], 2)
        self.assertEqual(
            budget["teachingRemediationOrder"],
            ["REPHRASE", "SIMPLE_EXAMPLE", "PREREQUISITE_FALLBACK"],
        )

    def test_private_state_and_external_api_boundaries_are_explicit(self):
        """内部协议不把 Python 私有状态变成请求或 SSE 字段，也不修改外部 OpenAPI。"""
        boundary = self.http_contract["x-private-state-boundary"]
        self.assertFalse(boundary["serializedByJava"])
        self.assertFalse(boundary["returnedInSse"])
        request_properties = self.http_contract["components"]["schemas"]["AgentRunRequest"]["properties"]
        stream_properties = self.http_contract["components"]["schemas"]["StreamEvent"]["properties"]
        for name in boundary["forbiddenPublicFields"]:
            self.assertNotIn(name, request_properties)
            self.assertNotIn(name, stream_properties)

    def test_valid_index_events_and_privacy_negative_examples(self):
        """四类索引事件通过正式 Schema，答案、签名 URL、隐私和错误归档操作被拒绝。"""
        schema = event_envelope_schema(self.index_contract)
        for name, event in self.index_examples["validEvents"].items():
            self.assertEqual(schema_errors(schema, event), [], name)
        for case in self.index_examples["invalidSchemaCases"]:
            self.assertTrue(schema_errors(schema, case["value"]), case["name"])

    def test_index_event_idempotency_and_revision_order(self):
        """重复 eventId 为 no-op，独立旧 revision 不能覆盖较新投影。"""
        cases = self.index_examples["versionSequences"]
        self.assertEqual(
            index_version_errors(cases["acceptsIncreasingAndIgnoresDuplicateEventId"]), []
        )
        self.assertTrue(index_version_errors(cases["rejectsOlderRevisionOverwrite"]))
        semantics = self.index_contract["x-consumer-semantics"]
        self.assertEqual(semantics["idempotencyKey"], "eventId")
        self.assertEqual(semantics["staleEventRule"], "IGNORE_WHEN_REVISION_NOT_GREATER")


if __name__ == "__main__":
    unittest.main()
