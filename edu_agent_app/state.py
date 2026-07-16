from __future__ import annotations

from typing import Annotated, Literal, NotRequired, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages
from pydantic import BaseModel, Field


class KnowledgeSelection(BaseModel):
    knowledge_points: list[str] = Field(default_factory=list)


class KnowledgeHypotheses(BaseModel):
    knowledge_points: list[str] = Field(
        default_factory=list,
        description="若可能是数学问题则预估3到8个规范知识点名称；明显非数学则为空列表",
    )


class DomainDecision(BaseModel):
    is_math: bool
    confidence: float = Field(ge=0, le=1)
    reason: str = ""


class ProblemPlan(BaseModel):
    main_question: str
    steps: list[str] = Field(min_length=1)


class FeedbackDecision(BaseModel):
    understood: bool
    has_question: bool = False
    question: str = ""


class AgentState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    user_question: NotRequired[str]
    user_image_url: NotRequired[str | None]

    retrieval_status: NotRequired[
        Literal["question_hit", "knowledge_hit", "math_fallback", "non_math"]
    ]
    retrieval_trace: NotRequired[list[dict]]
    identified_knowledge_points: NotRequired[list[str]]
    prerequisites: NotRequired[list[str]]

    main_question: NotRequired[str]
    solution_steps: NotRequired[list[str]]
    current_step_index: NotRequired[int]
    current_step_text: NotRequired[str]

    feedback: NotRequired[str]
    feedback_understood: NotRequired[bool]
    feedback_question: NotRequired[str]
    step_retry_count: NotRequired[int]
    max_step_retries: NotRequired[int]

    llm_call_count: NotRequired[int]
    max_llm_calls: NotRequired[int]
    stop_reason: NotRequired[str]
    terminal_message: NotRequired[str]
