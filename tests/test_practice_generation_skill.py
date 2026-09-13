"""T13.5.2 标准组题 Skill 的脚本、选择、审计与运行时接入回归。"""

from __future__ import annotations

import asyncio
import json
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

from edu_agent_app.runtime.skill_runtime import SkillEligibility, SkillRegistry, SkillRuntimeConfig, SkillScriptRunner
from edu_agent_app.runtime.service import DynamicAgentRuntime
from edu_agent_app.runtime.tools import ToolExecutor
from tests.test_dynamic_runtime import FakeModel, collect, run_request


ROOT = Path(__file__).resolve().parents[1]
SKILL_ROOT = ROOT / "edu_agent_app" / "skills"
SCRIPT = SKILL_ROOT / "public-practice-generation" / "scripts" / "validate_proposal.py"


class FakeAudit:
    """记录执行审计；可模拟开始失败以验证脚本前 fail closed。"""

    def __init__(self, fail_start: bool = False):
        self.fail_start = fail_start
        self.started: list[dict] = []
        self.finished: list[dict] = []

    async def start_skill_execution(self, _run_id, payload, _trace_id):
        if self.fail_start:
            raise RuntimeError("audit unavailable")
        self.started.append(dict(payload))

    async def finish_skill_execution(self, _run_id, _execution_id, payload, _trace_id):
        self.finished.append(dict(payload))


class QuestionSearch:
    """返回有当前版本 ID 的安全题目命中，顺序由测试输入固定。"""

    def __init__(self, identities):
        self.identities = identities
        self.requests = []

    def search(self, request):
        self.requests.append(request)
        return SimpleNamespace(hits=[SimpleNamespace(
            entity_id=question_id, title="候选题", summary="安全候选摘要", source_revision=1,
            payload={"question_version_id": version_id},
        ) for question_id, version_id in self.identities])


def run_script(value, *, raw: str | None = None):
    """独立执行正式脚本，验证 stdin/stdout 协议和非法 JSON 退出语义。"""

    return subprocess.run([sys.executable, str(SCRIPT)],
                          input=raw if raw is not None else json.dumps(value),
                          text=True, capture_output=True, check=False)


def candidate(question_id=None, version_id=None, **overrides):
    value = {"evidenceType": "QUESTION", "accessDecision": "AUTHORIZED_BY_JAVA",
             "questionId": str(question_id or uuid4()), "questionVersionId": str(version_id or uuid4())}
    value.update(overrides)
    return value


class PracticeProposalScriptTests(unittest.TestCase):
    """脚本只做确定性规范化和身份筛选，不依赖模型、索引或数据库。"""

    def test_defaults_boundaries_shortage_and_output_shape(self):
        for count in (1, 5, 20):
            with self.subTest(count=count):
                result = run_script({"topic": " 一元一次方程 ", "requestedCount": count,
                                     "candidates": [candidate() for _ in range(max(0, count - 1))]})
                self.assertEqual(0, result.returncode, result.stderr)
                value = json.loads(result.stdout)
                self.assertEqual({"resultType", "payload"}, set(value))
                self.assertEqual("PRACTICE_PROPOSAL", value["resultType"])
                self.assertEqual("MEDIUM", value["payload"]["difficulty"])
                self.assertEqual(count, value["payload"]["requestedCount"])
                self.assertEqual(max(0, count - 1), len(value["payload"]["candidates"]))

    def test_default_count_deduplication_order_and_recent_soft_avoidance(self):
        first, recent, last = candidate(), candidate(), candidate()
        result = run_script({"topic": "函数", "candidates": [first, recent, first, last],
                             "recentQuestionKeys": [{"questionId": recent["questionId"],
                                                     "questionVersionId": recent["questionVersionId"]}]})
        payload = json.loads(result.stdout)["payload"]
        self.assertEqual(5, payload["requestedCount"])
        self.assertEqual([first["questionId"], last["questionId"], recent["questionId"]],
                         [item["questionId"] for item in payload["candidates"]])

    def test_keeps_twenty_candidate_validation_pool_independent_of_requested_count(self):
        candidates = [candidate() for _ in range(21)]
        result = run_script({"topic": "函数", "requestedCount": 1, "candidates": candidates})
        self.assertEqual(0, result.returncode, result.stderr)
        payload = json.loads(result.stdout)["payload"]
        self.assertEqual(1, payload["requestedCount"])
        self.assertEqual([item["questionId"] for item in candidates[:20]],
                         [item["questionId"] for item in payload["candidates"]])

    def test_missing_topic_21_invalid_candidate_and_invalid_json_are_rejected(self):
        cases = [
            {"topic": "", "candidates": []},
            {"topic": "函数", "requestedCount": 21, "candidates": []},
            {"topic": "函数", "candidates": [candidate(evidenceType="KNOWLEDGE_POINT")]},
            {"topic": "函数", "candidates": [candidate(questionVersionId="bad")]},
        ]
        for value in cases:
            with self.subTest(value=value):
                self.assertNotEqual(0, run_script(value).returncode)
        self.assertNotEqual(0, run_script(None, raw="not-json").returncode)


class PracticeStandardSkillRuntimeTests(unittest.TestCase):
    def runtime(self, search, audit):
        config = SkillRuntimeConfig()
        return DynamicAgentRuntime(
            model=FakeModel(), tools=ToolExecutor(search_service=search),
            skill_registry=SkillRegistry.discover(SKILL_ROOT), skill_runner=SkillScriptRunner(config),
            skill_audit=audit,
            skill_policies={"public-practice-generation": SkillEligibility(
                frozenset({"PRACTICE_COACH"}), frozenset({"RECOMMEND_PRACTICE"}))},
        )

    def test_discovery_metadata_eligibility_unique_selection_and_envelope(self):
        async def scenario():
            registry = SkillRegistry.discover(SKILL_ROOT)
            descriptor = registry.get("public-practice-generation")
            self.assertIsNotNone(descriptor)
            self.assertEqual("1.1.0", descriptor.version)
            self.assertIn("主题", descriptor.description)

            identities = [(str(uuid4()), str(uuid4())) for _ in range(3)]
            audit = FakeAudit()
            runtime = self.runtime(QuestionSearch(identities), audit)
            events = await collect(runtime, run_request("推荐一元一次方程练习题"))
            proposal = events[-1]["payload"]["practiceProposal"]
            self.assertEqual("public-practice-generation", proposal["skillName"])
            self.assertEqual("1.1.0", proposal["skillVersion"])
            self.assertEqual(3, len(proposal["candidates"]))
            self.assertEqual(0, runtime.model.json_calls)
            self.assertEqual("RULE", audit.started[0]["selectionSource"])
            self.assertEqual("SUCCEEDED", audit.finished[0]["status"])
            self.assertEqual(["references/practice-rules.md"], audit.finished[0]["referencePaths"])

        asyncio.run(scenario())

    def test_missing_topic_clarifies_without_script_or_audit(self):
        async def scenario():
            audit, search = FakeAudit(), QuestionSearch([])
            events = await collect(self.runtime(search, audit), run_request("推荐练习题"))
            self.assertIn("想练习的数学主题", events[-1]["payload"]["finalAnswer"])
            self.assertFalse(audit.started)
            self.assertFalse(search.requests)

        asyncio.run(scenario())

    def test_audit_start_failure_fails_closed_without_search_or_proposal(self):
        async def scenario():
            audit = FakeAudit(fail_start=True)
            search = QuestionSearch([(str(uuid4()), str(uuid4()))])
            events = await collect(self.runtime(search, audit), run_request("推荐函数练习题"))
            final = events[-1]["payload"]
            self.assertEqual("本次练习准备失败，请稍后重试", final["finalAnswer"])
            self.assertNotIn("practiceProposal", final)
            self.assertFalse(search.requests)

        asyncio.run(scenario())


if __name__ == "__main__":
    unittest.main()
