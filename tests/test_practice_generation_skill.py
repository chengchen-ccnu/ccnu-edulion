"""T14.3 公共题库自主组题 Skill 的按需、边界和安全回归。"""

from __future__ import annotations

import asyncio
import unittest
from uuid import uuid4

from pydantic import ValidationError

from edu_agent_app.runtime.budget import RunLimits
from edu_agent_app.runtime.intent import IntentClassifier, latest_explicit_practice_topic
from edu_agent_app.runtime.practice_skill import PUBLIC_PRACTICE_GENERATION_SKILL
from edu_agent_app.runtime.protocol import IntentSlots, RecentMessage, RunBudget, SafeToolEvidence

from tests.test_dynamic_runtime import FakeModel, run_request


class PracticeGenerationSkillTests(unittest.TestCase):
    """只验证平台维护的确定性提案；数据库复核和写入由 Java 集成测试承担。"""

    def evidence(self, question_id=None, version_id=None):
        return SafeToolEvidence(
            evidenceId=str(version_id or uuid4()), evidenceType="QUESTION", title="候选题",
            summary="只读候选摘要", questionId=question_id or uuid4(),
            questionVersionId=version_id or uuid4(),
        )

    def test_defaults_count_boundaries_deduplicate_and_shortage(self):
        for count in (1, 5, 20):
            items = [self.evidence() for _ in range(count)]
            proposal = PUBLIC_PRACTICE_GENERATION_SKILL.build_proposal(
                IntentSlots(practiceTopic="一元一次方程", questionCount=count), items,
            )
            self.assertEqual(proposal["difficulty"], "MEDIUM")
            self.assertEqual(proposal["requestedCount"], count)
            self.assertEqual(len(proposal["candidates"]), count)

        duplicate = self.evidence()
        proposal = PUBLIC_PRACTICE_GENERATION_SKILL.build_proposal(
            IntentSlots(practiceTopic="三角形", questionCount=5), [duplicate, duplicate],
        )
        self.assertEqual(len(proposal["candidates"]), 1)

    def test_missing_topic_and_out_of_range_are_rejected(self):
        with self.assertRaises(ValueError):
            PUBLIC_PRACTICE_GENERATION_SKILL.build_proposal(IntentSlots(), [])
        for count in (0, 21):
            with self.assertRaises(ValidationError):
                IntentSlots(practiceTopic="函数", questionCount=count)

    def test_rule_intent_defaults_and_missing_topic_clarifies(self):
        async def scenario():
            classifier = IntentClassifier(FakeModel())
            request = run_request()
            routed = await classifier.classify(
                "推荐一元一次方程练习题", request.trustedContext, RunLimits(request.budget),
            )
            self.assertEqual(routed.decision.slots.practiceTopic, "一元一次方程")
            self.assertEqual(routed.decision.slots.difficulty, "MEDIUM")
            self.assertIsNone(routed.decision.slots.questionCount)
            missing = await classifier.classify(
                "推荐练习题", request.trustedContext, RunLimits(request.budget),
            )
            self.assertEqual(missing.decision.resolution, "NEEDS_CLARIFICATION")
            self.assertIn("practiceTopic", missing.decision.missingSlots)

        asyncio.run(scenario())

    def test_current_explicit_topic_and_natural_action_controls_are_bounded(self):
        async def scenario():
            classifier = IntentClassifier(FakeModel())
            request = run_request()
            messages = [RecentMessage(
                messageId=uuid4(), role="USER", content="请解释一元一次方程",
                completeness="COMPLETE", sequenceNo=1,
            )]
            self.assertEqual(
                latest_explicit_practice_topic(messages, request.trustedContext), "一元一次方程"
            )
            self.assertIsNone(latest_explicit_practice_topic([RecentMessage(
                messageId=uuid4(), role="USER", content="今天天气怎么样",
                completeness="COMPLETE", sequenceNo=2,
            )], request.trustedContext))
            confirmed = await classifier.classify(
                "好的", request.trustedContext, RunLimits(request.budget)
            )
            cancelled = await classifier.classify(
                "取消", request.trustedContext, RunLimits(request.budget)
            )
            self.assertEqual(confirmed.decision.secondaryIntent.value, "ACTION_CONFIRMATION")
            self.assertEqual(cancelled.decision.secondaryIntent.value, "ACTION_CANCELLATION")

        asyncio.run(scenario())


if __name__ == "__main__":
    unittest.main()
