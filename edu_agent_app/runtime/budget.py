"""每个 run 的所有步骤、模型、工具和转交共享同一有限预算。"""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass, field
from time import monotonic
from typing import Any

from .protocol import RunBudget, ToolName


class LimitReached(RuntimeError):
    """预算或总时限已经耗尽；后续不得再启动同类工作。"""


@dataclass
class Usage:
    """只供 Java/运行日志消费的脱敏用量，不进入学生 SSE。"""

    orchestration_steps: int = 0
    llm_calls: int = 0
    tool_attempts: int = 0
    expert_transfers: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    phase_latency_ms: dict[str, int] = field(default_factory=dict)
    termination_reason: str = ""


class RunLimits:
    """在发起外部副作用前计数，失败、重试、视觉、压缩和候选提取均不会逃逸预算。"""

    def __init__(self, policy: RunBudget):
        self.policy = policy
        self.usage = Usage()
        self.started = monotonic()
        self.same_tool_arguments: Counter[str] = Counter()

    def remaining_ms(self) -> int:
        return max(0, self.policy.totalTimeMs - int((monotonic() - self.started) * 1000))

    def check_time(self) -> None:
        if self.remaining_ms() <= 0:
            raise LimitReached("TOTAL_TIME_LIMIT")

    def step(self, phase: str) -> None:
        self.check_time()
        if self.usage.orchestration_steps >= self.policy.maxOrchestrationSteps:
            raise LimitReached("ORCHESTRATION_STEPS")
        self.usage.orchestration_steps += 1
        self.usage.phase_latency_ms.setdefault(phase, 0)

    def llm(self, *, allow_reserved: bool = False) -> None:
        self.check_time()
        available = self.policy.maxLlmCalls
        if not allow_reserved:
            available -= self.policy.reservedFinalLlmCalls
        if self.usage.llm_calls >= available:
            raise LimitReached("LLM_CALLS")
        self.usage.llm_calls += 1

    def tool(self, name: ToolName, arguments: dict[str, Any]) -> None:
        self.check_time()
        if self.usage.tool_attempts >= self.policy.maxToolAttempts:
            raise LimitReached("TOOL_ATTEMPTS")
        key = f"{name.value}:{json.dumps(arguments, sort_keys=True, ensure_ascii=False, default=str)}"
        if self.same_tool_arguments[key] >= self.policy.maxSameToolArgumentsAttempts:
            raise LimitReached("SAME_TOOL_ARGUMENTS")
        self.same_tool_arguments[key] += 1
        self.usage.tool_attempts += 1

    def transfer(self) -> None:
        self.check_time()
        if self.usage.expert_transfers >= self.policy.maxExpertTransfers:
            raise LimitReached("EXPERT_TRANSFERS")
        self.usage.expert_transfers += 1

    def add_tokens(self, prompt: int | None, completion: int | None) -> None:
        self.usage.prompt_tokens += int(prompt or 0)
        self.usage.completion_tokens += int(completion or 0)
