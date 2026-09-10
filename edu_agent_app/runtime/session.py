"""进程内多轮短期状态、有限压缩与 T14.2 候选记忆生成。"""

from __future__ import annotations

import re
from collections import deque
from dataclasses import dataclass, field
from typing import Iterable

from .budget import RunLimits
from .protocol import AuthorizedMemorySummary, LearningPreference, RecentMessage


_URL = re.compile(r"https?://\S+", re.I)
_SIGNED = re.compile(
    r"(?i)(signature|token|accesskey|authorization|x-oss-[a-z-]+)\s*"
    r"(?:[=:]\s*|\s+)(?:bearer\s+)?[^\s,;]+"
)
_FORMULA = re.compile(r"(?:\$[^$]{1,300}\$|\\\([^)]{1,300}\\\)|[A-Za-z0-9()^+\-*/=]{3,})")


@dataclass
class ConversationState:
    """仅保存最近轮次、压缩摘要和当前教学补救，不充当长期事实数据库。"""

    recent_messages: deque[tuple[str, str]] = field(default_factory=lambda: deque(maxlen=12))
    summary: str = ""
    unresolved_problem: str = ""
    topic_key: str = ""
    remediation_count: int = 0
    source_titles: list[str] = field(default_factory=list)
    candidate_memories: list[str] = field(default_factory=list)


class ConversationStore:
    """按 conversationId 隔离短期状态；进程重启后由 Java 摘要重新注入。"""

    def __init__(self, compression_threshold_chars: int = 12_000):
        self._states: dict[str, ConversationState] = {}
        self.compression_threshold_chars = compression_threshold_chars

    def get(self, conversation_id: str) -> ConversationState:
        return self._states.setdefault(conversation_id, ConversationState())

    def context_text(
        self,
        conversation_id: str,
        java_summaries: Iterable[AuthorizedMemorySummary],
        preferences: Iterable[LearningPreference],
        recent_messages: Iterable[RecentMessage] | None = None,
    ) -> str:
        """合并 Java 已授权摘要与最近消息；秘密、工具原始结果和隐藏画像从不在输入结构中。"""

        state = self.get(conversation_id)
        sections = [item.text for item in java_summaries]
        java_recent = list(recent_messages or [])
        if state.summary and not java_recent:
            sections.append(state.summary)
        if java_recent:
            recent = "\n".join(
                f"{'学生' if item.role == 'USER' else '助手'}[{item.completeness}]: {item.content}"
                for item in java_recent
            )
        else:
            recent = "\n".join(f"{role}: {text}" for role, text in state.recent_messages)
        if recent:
            sections.append("最近对话：\n" + recent)
        explicit = [item.value for item in preferences if item.source == "EXPLICIT"]
        if explicit:
            sections.append("用户明确偏好：" + "；".join(explicit))
        return self._sanitize("\n\n".join(sections))[:16_000]

    def record_turn(
        self,
        conversation_id: str,
        user_text: str,
        answer: str,
        *,
        unresolved: bool,
        source_titles: Iterable[str],
        preferences: Iterable[LearningPreference],
        limits: RunLimits,
    ) -> None:
        """保存有界轮次并在阈值后做确定性压缩；压缩与候选提取均占编排步骤。"""

        state = self.get(conversation_id)
        state.recent_messages.append(("学生", self._sanitize(user_text)[:4_000]))
        state.recent_messages.append(("助手", self._sanitize(answer)[:6_000]))
        state.unresolved_problem = self._sanitize(user_text)[:2_000] if unresolved else ""
        state.source_titles = list(dict.fromkeys(self._sanitize(item)[:200] for item in source_titles))[:8]
        total = len(state.summary) + sum(len(text) for _, text in state.recent_messages)
        if total >= self.compression_threshold_chars:
            limits.step("CONTEXT_COMPRESSION")
            state.summary = self._compress(state, preferences)
            while len(state.recent_messages) > 6:
                state.recent_messages.popleft()
        limits.step("MEMORY_CANDIDATE_EXTRACTION")
        candidates = self._memory_candidates(state, user_text, preferences)
        state.candidate_memories = candidates

    def remediation(self, conversation_id: str, topic_key: str, says_not_understood: bool, maximum: int) -> int:
        """同主题连续“不懂”逐次推进；新主题立即重置，单次求助不会永久固化能力判断。"""

        state = self.get(conversation_id)
        if state.topic_key != topic_key:
            state.topic_key = topic_key
            state.remediation_count = 0
        if not says_not_understood:
            state.remediation_count = 0
            return 0
        state.remediation_count = min(maximum, state.remediation_count + 1)
        return state.remediation_count

    def _compress(self, state: ConversationState, preferences: Iterable[LearningPreference]) -> str:
        formulas: list[str] = []
        for _, text in state.recent_messages:
            formulas.extend(_FORMULA.findall(text))
        formulas = list(dict.fromkeys(formulas))[:12]
        explicit = [item.value for item in preferences if item.source == "EXPLICIT"][:8]
        parts = ["对话有限摘要："]
        if formulas:
            parts.append("保留公式：" + "；".join(formulas))
        if state.unresolved_problem:
            parts.append("未解决问题：" + state.unresolved_problem)
        if explicit:
            parts.append("明确偏好：" + "；".join(explicit))
        if state.source_titles:
            parts.append("必要来源：" + "；".join(state.source_titles))
        return self._sanitize("\n".join(parts))[:4_000]

    def _memory_candidates(
        self, state: ConversationState, user_text: str, preferences: Iterable[LearningPreference]
    ) -> list[str]:
        candidates = [f"明确偏好：{item.value}" for item in preferences if item.source == "EXPLICIT"]
        if state.unresolved_problem:
            candidates.append("未解决卡点：" + state.unresolved_problem)
        if re.search(r"我(?:总是|经常).{1,80}(?:错|不懂|忘)", user_text):
            candidates.append("用户自述的重复卡点：" + self._sanitize(user_text)[:300])
        return list(dict.fromkeys(candidates))[:8]

    @staticmethod
    def _sanitize(text: str) -> str:
        text = _URL.sub("[已移除短期地址]", text or "")
        text = _SIGNED.sub("[已移除秘密]", text)
        return text.replace("rawToolResult", "[已移除工具原始结果]")
