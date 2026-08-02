"""定义模型结构化输出以及贯穿 LangGraph 各节点的会话状态契约。"""

from __future__ import annotations

from typing import Annotated, Literal, NotRequired, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages
from pydantic import BaseModel, Field


class KnowledgeSelection(BaseModel):
    """模型从检索候选中确认的规范知识点名称。"""
    knowledge_points: list[str] = Field(default_factory=list)


class KnowledgeHypotheses(BaseModel):
    """可靠检索缺失时由模型提出、供第二阶段检索使用的知识点假设。"""
    knowledge_points: list[str] = Field(
        default_factory=list,
        description="若可能是数学问题则预估3到8个规范知识点名称；明显非数学则为空列表",
    )


class DomainDecision(BaseModel):
    """检索无命中时对输入是否仍属数学学习问题的结构化判断。"""
    is_math: bool
    confidence: float = Field(ge=0, le=1)
    reason: str = ""


class ProblemPlan(BaseModel):
    """面向学生展示的标准化题意与有序讲解步骤。"""
    main_question: str
    steps: list[str] = Field(min_length=1)


class FeedbackDecision(BaseModel):
    """学生是否理解当前步骤，以及仍待回答的具体疑问。"""
    understood: bool
    has_question: bool = False
    question: str = ""


class AgentState(TypedDict):
    """教学会话状态。

    ``messages`` 通过 LangGraph reducer 追加；其余字段由工作流节点增量写回。``stop_reason``
    是终止、预算耗尽或人工移交的统一路由依据，调用方恢复检查点时必须保留这些计数与状态。
    """
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
