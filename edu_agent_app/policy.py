from __future__ import annotations

from typing import Any, Mapping


class BudgetExceeded(RuntimeError):
    pass


class CallBudget:
    """一次节点执行期间的 LLM 调用预算，调用失败也计入预算。"""

    def __init__(self, state: Mapping[str, Any], default_limit: int):
        self.count = int(state.get("llm_call_count", 0))
        self.limit = int(state.get("max_llm_calls", default_limit))

    async def invoke(self, runnable: Any, value: Any) -> Any:
        if self.count >= self.limit:
            raise BudgetExceeded("本会话已达到模型调用上限")
        self.count += 1
        return await runnable.ainvoke(value)

    def update(self) -> dict:
        return {"llm_call_count": self.count, "max_llm_calls": self.limit}


def remediation_strategy(retry_count: int, prerequisites: list[str]) -> tuple[str, str]:
    """retry_count 是已经执行过的补救次数。"""
    if retry_count == 0:
        return "rephrase", "换一种更直白、更短的表述，避免重复原句。"
    if retry_count == 1:
        return "example", "补充一个数字简单、紧扣当前步骤的小例子或类比。"
    prerequisite_text = "、".join(prerequisites) if prerequisites else "当前步骤所需的最基础概念"
    return (
        "prerequisite",
        f"暂停当前推导，先回退讲解前置知识：{prerequisite_text}，再说明它如何连接到当前步骤。",
    )


def should_handoff_after_feedback(state: Mapping[str, Any]) -> bool:
    if state.get("feedback_understood"):
        return False
    return int(state.get("step_retry_count", 0)) >= int(
        state.get("max_step_retries", 3)
    )
