"""平台维护的公共题库自主组题 Skill；只描述和编排受控只读能力。"""

from __future__ import annotations

from dataclasses import dataclass

from .protocol import IntentSlots, SafeToolEvidence


@dataclass(frozen=True)
class PracticeGenerationSkill:
    """定义 Practice Coach 的固定能力边界，输入是已识别槽位与只读题库证据。

    输出仅包含题目/版本稳定 ID 的候选提案；Java 仍须校验题目当前状态、范围、难度和
    自动评分能力并保存待确认动作。这里不执行数据库写入，也不允许模型补题。
    """

    name: str = "public-practice-generation"
    version: str = "1.0.0"
    role: str = "PRACTICE_COACH"
    intent: str = "RECOMMEND_PRACTICE"
    max_candidates: int = 20
    timeout_ms: int = 10_000

    def build_proposal(self, slots: IntentSlots, evidence: list[SafeToolEvidence]) -> dict:
        """按检索顺序去重候选；数量不足原样返回，绝不扩大主题或难度。"""

        if not slots.practiceTopic:
            raise ValueError("practiceTopic 是组题 Skill 的必需槽位")
        requested = slots.questionCount or 5
        difficulty = slots.difficulty or "MEDIUM"
        candidates: list[dict[str, str]] = []
        seen: set[tuple[str, str]] = set()
        for item in evidence:
            if item.evidenceType != "QUESTION" or item.questionId is None or item.questionVersionId is None:
                continue
            key = (str(item.questionId), str(item.questionVersionId))
            if key in seen:
                continue
            seen.add(key)
            candidates.append({"questionId": key[0], "questionVersionId": key[1]})
            if len(candidates) >= self.max_candidates:
                break
        return {
            "skillName": self.name,
            "skillVersion": self.version,
            "topic": slots.practiceTopic,
            "difficulty": difficulty,
            "requestedCount": requested,
            "candidates": candidates,
        }


PUBLIC_PRACTICE_GENERATION_SKILL = PracticeGenerationSkill()
