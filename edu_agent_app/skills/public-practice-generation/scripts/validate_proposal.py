"""校验公共题库候选并输出 Runtime 可封装的确定性组题结果。"""

from __future__ import annotations

import json
import sys
from typing import Any
from uuid import UUID


DIFFICULTIES = frozenset({"EASY", "MEDIUM", "HARD"})


def _uuid(value: Any, field: str) -> str:
    """验证稳定题目身份并返回规范 UUID；非法输入使脚本非零退出。"""

    if not isinstance(value, str):
        raise ValueError(f"{field} 必须是 UUID")
    return str(UUID(value))


def validate(value: Any) -> dict[str, Any]:
    """规范化条件并稳定筛选 QUESTION_SEARCH 候选，返回最小身份提案。"""

    if not isinstance(value, dict):
        raise ValueError("输入必须是 JSON 对象")
    topic_value = value.get("topic")
    topic = topic_value.strip() if isinstance(topic_value, str) else ""
    if not topic or len(topic) > 200:
        raise ValueError("topic 必须是 1..200 字符的非空文本")

    difficulty = value.get("difficulty") or "MEDIUM"
    if difficulty not in DIFFICULTIES:
        raise ValueError("difficulty 不合法")
    requested = value.get("requestedCount", 5)
    if requested is None:
        requested = 5
    if isinstance(requested, bool) or not isinstance(requested, int) or not 1 <= requested <= 20:
        raise ValueError("requestedCount 必须位于 1..20")

    candidates = value.get("candidates")
    if not isinstance(candidates, list):
        raise ValueError("candidates 必须是数组")
    recent_values = value.get("recentQuestionKeys", [])
    if not isinstance(recent_values, list):
        raise ValueError("recentQuestionKeys 必须是数组")
    recent: set[tuple[str, str]] = set()
    for item in recent_values:
        if not isinstance(item, dict):
            raise ValueError("近期题身份不合法")
        recent.add((_uuid(item.get("questionId"), "questionId"),
                    _uuid(item.get("questionVersionId"), "questionVersionId")))

    unique: list[tuple[tuple[str, str], dict[str, str]]] = []
    seen: set[tuple[str, str]] = set()
    for item in candidates:
        if not isinstance(item, dict) or item.get("evidenceType") != "QUESTION" \
                or item.get("accessDecision") != "AUTHORIZED_BY_JAVA":
            raise ValueError("候选必须是已授权 QUESTION 证据")
        key = (_uuid(item.get("questionId"), "questionId"),
               _uuid(item.get("questionVersionId"), "questionVersionId"))
        if key in seen:
            continue
        seen.add(key)
        unique.append((key, {"questionId": key[0], "questionVersionId": key[1]}))

    ordered = [item for key, item in unique if key not in recent]
    ordered.extend(item for key, item in unique if key in recent)
    # Learning 还要按难度、当前版本和自动评分能力做领域复核，因此这里保留最多 20 个
    # 检索候选作为复核池；requestedCount 只决定 Java 最终截取的主问题数量。
    selected = ordered[:20]
    if not selected:
        raise ValueError("零候选不能形成组题提案")
    fallback = value.get("allowDifficultyFallback", False)
    if not isinstance(fallback, bool):
        raise ValueError("allowDifficultyFallback 必须为布尔值")
    return {
        "resultType": "PRACTICE_PROPOSAL",
        "payload": {
            "topic": topic,
            "difficulty": difficulty,
            "requestedCount": requested,
            "candidates": selected,
            **({"allowDifficultyFallback": True} if fallback else {}),
        },
    }


def main() -> None:
    """从 stdin 读取一次 JSON 并向 stdout 写一次 JSON；失败不输出部分结果。"""

    value = json.load(sys.stdin)
    json.dump(validate(value), sys.stdout, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
