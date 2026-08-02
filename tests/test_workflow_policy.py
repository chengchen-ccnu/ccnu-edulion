import asyncio
import unittest

from edu_agent_app.policy import (
    BudgetExceeded,
    CallBudget,
    remediation_strategy,
    should_handoff_after_feedback,
)


class FakeRunnable:
    async def ainvoke(self, value):
        return value


class WorkflowPolicyTests(unittest.TestCase):
    def test_remediation_order(self):
        self.assertEqual(remediation_strategy(0, [])[0], "rephrase")
        self.assertEqual(remediation_strategy(1, [])[0], "example")
        name, instruction = remediation_strategy(2, ["有理数", "乘法法则"])
        self.assertEqual(name, "prerequisite")
        self.assertIn("有理数", instruction)

    def test_handoff_after_three_failed_remediations(self):
        state = {
            "feedback_understood": False,
            "step_retry_count": 3,
            "max_step_retries": 3,
        }
        self.assertTrue(should_handoff_after_feedback(state))

    def test_understood_never_handoffs(self):
        state = {
            "feedback_understood": True,
            "step_retry_count": 3,
            "max_step_retries": 3,
        }
        self.assertFalse(should_handoff_after_feedback(state))

    def test_llm_budget_is_hard_limit(self):
        """Given 仅余一次额度，When 连续调用两次，Then 第二次在模型调用前被拒绝。"""
        async def scenario():
            budget = CallBudget({"llm_call_count": 1, "max_llm_calls": 2}, 30)
            await budget.invoke(FakeRunnable(), "ok")
            with self.assertRaises(BudgetExceeded):
                await budget.invoke(FakeRunnable(), "blocked")
            self.assertEqual(budget.count, 2)

        asyncio.run(scenario())


if __name__ == "__main__":
    unittest.main()
