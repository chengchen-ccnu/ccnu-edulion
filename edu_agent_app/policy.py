"""集中定义模型调用预算、教学补救顺序和人工移交判定。"""

from __future__ import annotations

from typing import Any, Mapping


class BudgetExceeded(RuntimeError):
    """会话达到 LLM 调用上限；调用方应终止或移交，而不是无界重试。"""
    pass


class CallBudget:
    """跨节点累计的 LLM 调用预算，调用失败也计数以阻止故障重试失控。"""

    def __init__(self, state: Mapping[str, Any], default_limit: int):
        self.count = int(state.get("llm_call_count", 0))
        self.limit = int(state.get("max_llm_calls", default_limit))

    async def invoke(self, runnable: Any, value: Any) -> Any:
        """在尚有额度时调用模型，并在发起调用前计数；超限不会触发模型请求。"""
        if self.count >= self.limit:
            raise BudgetExceeded("本会话已达到模型调用上限")
        self.count += 1
        return await runnable.ainvoke(value)

    def update(self) -> dict:
        """返回应写回 AgentState 的累计次数和固定上限。"""
        return {"llm_call_count": self.count, "max_llm_calls": self.limit}


def remediation_strategy(retry_count: int, prerequisites: list[str]) -> tuple[str, str]:
    """按已执行补救次数选择重述、举例或回退前置知识，返回策略名和提示指令。"""
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
    """判断未理解的当前步骤是否已用尽补救次数；已理解时始终继续下一步。"""
    if state.get("feedback_understood"):
        return False
    return int(state.get("step_retry_count", 0)) >= int(
        state.get("max_step_retries", 3)
    )
