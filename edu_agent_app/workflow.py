"""构建“检索—分步讲解—反馈判断—补救/移交”的数学教学状态机。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langgraph.graph import END, START, StateGraph

from .config import Settings
from .policy import (
    BudgetExceeded,
    CallBudget,
    remediation_strategy,
    should_handoff_after_feedback,
)
from .retrieval import (
    RetrievalServices,
    extract_knowledge_names,
    retrieval_trace,
)
from .state import (
    AgentState,
    DomainDecision,
    FeedbackDecision,
    KnowledgeHypotheses,
    KnowledgeSelection,
    ProblemPlan,
)


@dataclass
class WorkflowServices:
    """工作流运行期依赖；包含模型、只读检索服务和不可变配置快照。"""
    llm: Any
    retrieval: RetrievalServices
    settings: Settings


def _last_human_text(state: AgentState) -> str:
    """读取会话中最近一条学生消息；没有学生消息时返回空字符串。"""
    for message in reversed(state.get("messages", [])):
        if isinstance(message, HumanMessage):
            return str(message.content)
    return ""


async def _select_knowledge(
    services: WorkflowServices,
    budget: CallBudget,
    question: str,
    candidates: list[str],
) -> list[str]:
    """让模型只从候选集合中选择至多五个知识点，并丢弃越界输出。"""
    candidates = list(dict.fromkeys(candidates))[:40]
    if not candidates:
        return []
    model = services.llm.with_structured_output(KnowledgeSelection)
    result = await budget.invoke(
        model,
        [
            SystemMessage(content="你只负责从候选中选择与初中数学问题直接相关的知识点。"),
            HumanMessage(
                content=(
                    f"问题：{question}\n候选知识点：{candidates}\n"
                    "最多选择5个；只能原样选择候选项。若候选均不相关，返回空列表。"
                )
            ),
        ],
    )
    allowed = set(candidates)
    return [name for name in result.knowledge_points if name in allowed][:5]


async def _hypothesize_knowledge(
    services: WorkflowServices,
    budget: CallBudget,
    question: str,
) -> list[str]:
    """题库无可靠命中时生成第二阶段知识点检索词，不直接作为最终结论。"""
    model = services.llm.with_structured_output(KnowledgeHypotheses)
    result = await budget.invoke(
        model,
        [
            SystemMessage(content="你是初中数学知识点分析器。不要解题。"),
            HumanMessage(
                content=(
                    "题库没有可靠命中。若内容可能是数学问题，请预估3到8个规范知识点名称；"
                    f"若明显不是数学问题，请返回空列表：\n{question}"
                )
            ),
        ],
    )
    return list(dict.fromkeys(result.knowledge_points))[:8]


async def _classify_domain(
    services: WorkflowServices,
    budget: CallBudget,
    question: str,
) -> DomainDecision:
    """在两阶段检索均失败后判断是否为数学问题，避免把新题型误判成闲聊。"""
    model = services.llm.with_structured_output(DomainDecision)
    return await budget.invoke(
        model,
        [
            SystemMessage(
                content=(
                    "判断问题是否属于数学学习。检索失败不能作为非数学的证据；"
                    "文字题、证明题、符号表达式和超出题库的新题型仍可能是数学问题。"
                )
            ),
            HumanMessage(content=question),
        ],
    )


async def _plan_problem(
    services: WorkflowServices,
    budget: CallBudget,
    question: str,
    image_url: str | None,
    knowledge_points: list[str],
    prerequisites: list[str],
    context: str,
) -> ProblemPlan:
    """结合题目、可选图片、知识点与检索上下文生成单步拆分的解题计划。"""
    model = services.llm.with_structured_output(ProblemPlan)
    prompt_text = (
        f"学生问题：{question}\n"
        f"已识别知识点：{knowledge_points or ['未可靠识别']}\n"
        f"前置知识：{prerequisites or ['暂无']}\n"
        f"检索上下文：{context or '无可靠检索上下文'}\n\n"
        "请核对题意并生成初中生可理解的分步解法。每个列表元素只能包含一个步骤，"
        "不要把多个步骤塞进同一元素；公式使用Markdown/LaTeX。"
    )
    content: Any = prompt_text
    if image_url:
        content = [
            {"type": "text", "text": prompt_text},
            {"type": "image_url", "image_url": {"url": image_url}},
        ]
    return await budget.invoke(
        model,
        [
            SystemMessage(content="你是严谨、循序渐进的初中数学教师。"),
            HumanMessage(content=content),
        ],
    )


def _context_from_hits(hits: list, limit: int = 5) -> str:
    """截取有限候选内容供模型参考，控制提示词长度且不修改原始文档。"""
    snippets = []
    for hit in hits[:limit]:
        content = str(getattr(hit.document, "page_content", ""))
        snippets.append(content[:700])
    return "\n---\n".join(snippets)


def create_workflow(services: WorkflowServices):
    """创建尚未编译的 LangGraph 教学状态机。

    图首先完成双路检索与题目规划，再逐步展示并在每步等待学生反馈。未理解时按固定顺序
    补救；超过步骤重试或全局 LLM 预算后移交人工。节点只写回检查点状态并调用模型/检索，
    不写 Java 业务数据库；调用方负责选择内存或 PostgreSQL 检查点并设置中断点。
    """
    async def prepare_problem(state: AgentState) -> dict:
        """准备题目与解题计划；检索失败可降级讲解，预算或异常则写入终止原因。"""
        budget = CallBudget(state, services.settings.max_llm_calls)
        question = _last_human_text(state)
        image_url = state.get("user_image_url")
        trace: list[dict] = []
        status: Literal["question_hit", "knowledge_hit", "math_fallback", "non_math"]
        selected: list[str] = []
        chosen_hits: list = []

        try:
            question_hits = services.retrieval.question.search(question)
            trace.append(retrieval_trace("question", question_hits))
            selected = await _select_knowledge(
                services,
                budget,
                question,
                extract_knowledge_names(question_hits),
            )
            if selected:
                status = "question_hit"
                chosen_hits = question_hits
            else:
                hypotheses = await _hypothesize_knowledge(services, budget, question)
                knowledge_query = f"{question}\n可能知识点：{'、'.join(hypotheses)}"
                knowledge_hits = services.retrieval.knowledge.search(knowledge_query)
                trace.append(retrieval_trace("knowledge", knowledge_hits))
                selected = await _select_knowledge(
                    services,
                    budget,
                    question,
                    extract_knowledge_names(knowledge_hits),
                )
                if selected:
                    status = "knowledge_hit"
                    chosen_hits = knowledge_hits
                else:
                    domain = await _classify_domain(services, budget, question)
                    if not domain.is_math and domain.confidence >= 0.70:
                        return {
                            **budget.update(),
                            "user_question": question,
                            "retrieval_status": "non_math",
                            "retrieval_trace": trace,
                            "stop_reason": "non_math",
                            "terminal_message": (
                                "这个问题看起来不属于数学学习范围。"
                                "你可以换一道数学题，或补充公式、图形和已知条件。"
                            ),
                        }
                    # 检索失败但仍是数学问题：允许低置信度直接讲解，不能误判为闲聊。
                    status = "math_fallback"

            prerequisites = await services.retrieval.graph.prerequisites(selected)
            plan = await _plan_problem(
                services,
                budget,
                question,
                image_url,
                selected,
                prerequisites,
                _context_from_hits(chosen_hits),
            )
            return {
                **budget.update(),
                "user_question": question,
                "retrieval_status": status,
                "retrieval_trace": trace,
                "identified_knowledge_points": selected,
                "prerequisites": prerequisites,
                "main_question": plan.main_question,
                "solution_steps": plan.steps,
                "current_step_index": 0,
                "step_retry_count": 0,
                "max_step_retries": int(
                    state.get("max_step_retries", services.settings.max_step_retries)
                ),
                "stop_reason": "",
            }
        except BudgetExceeded:
            return {
                **budget.update(),
                "stop_reason": "llm_budget",
                "terminal_message": "本会话已达到模型调用上限，建议稍后继续或请老师协助。",
            }
        except Exception as exc:
            return {
                **budget.update(),
                "stop_reason": "prepare_failed",
                "terminal_message": f"题目分析暂时失败：{exc}",
            }

    def route_after_prepare(state: AgentState) -> str:
        """有终止原因时进入统一终点，否则展示首个讲解步骤。"""
        return "terminal" if state.get("stop_reason") else "present_step"

    async def present_step(state: AgentState) -> dict:
        """展示当前步骤并请求反馈；索引越界表示全部步骤已经完成。"""
        index = int(state.get("current_step_index", 0))
        steps = state.get("solution_steps", [])
        if index >= len(steps):
            return {"stop_reason": "complete"}
        step = steps[index]
        return {
            "current_step_text": step,
            "messages": [
                AIMessage(
                    content=(
                        f"### 第 {index + 1} 步\n\n{step}\n\n"
                        "**你理解这一步了吗？如果不理解，请告诉我具体卡在哪里。**"
                    )
                )
            ],
        }

    async def assess_feedback(state: AgentState) -> dict:
        """调用结构化模型判断学生是否理解，并记录具体疑问和累计预算。"""
        budget = CallBudget(state, services.settings.max_llm_calls)
        feedback = _last_human_text(state)
        try:
            model = services.llm.with_structured_output(FeedbackDecision)
            decision = await budget.invoke(
                model,
                [
                    SystemMessage(
                        content=(
                            "判断学生是否真正理解当前数学步骤。学生仍有疑问或只部分理解时，"
                            "understood应为false；不要把无关回答当作理解。"
                        )
                    ),
                    HumanMessage(
                        content=(
                            f"当前步骤：{state.get('current_step_text', '')}\n"
                            f"学生反馈：{feedback}"
                        )
                    ),
                ],
            )
            return {
                **budget.update(),
                "feedback": feedback,
                "feedback_understood": decision.understood,
                "feedback_question": decision.question if decision.has_question else "",
            }
        except BudgetExceeded:
            return {
                **budget.update(),
                "feedback_understood": False,
                "stop_reason": "llm_budget",
            }

    def route_after_feedback(state: AgentState) -> str:
        """按理解状态、模型预算和步骤重试上限选择前进、补救或人工移交。"""
        if state.get("stop_reason") == "llm_budget":
            return "handoff"
        if state.get("feedback_understood"):
            return "advance_step"
        if int(state.get("llm_call_count", 0)) >= int(
            state.get("max_llm_calls", services.settings.max_llm_calls)
        ):
            return "handoff"
        if should_handoff_after_feedback(state):
            return "handoff"
        return "remediate"

    async def remediate(state: AgentState) -> dict:
        """只针对当前障碍执行一次补救讲解；无论成功与否都受全局调用预算约束。"""
        budget = CallBudget(state, services.settings.max_llm_calls)
        retry_count = int(state.get("step_retry_count", 0))
        strategy_name, strategy_instruction = remediation_strategy(
            retry_count,
            state.get("prerequisites", []),
        )
        try:
            result = await budget.invoke(
                services.llm,
                [
                    SystemMessage(content="你是耐心但不啰嗦的初中数学教师。"),
                    HumanMessage(
                        content=(
                            f"题目：{state.get('main_question', '')}\n"
                            f"当前步骤：{state.get('current_step_text', '')}\n"
                            f"学生反馈：{state.get('feedback', '')}\n"
                            f"学生具体问题：{state.get('feedback_question', '')}\n"
                            f"本次教学策略：{strategy_instruction}\n"
                            "只讲当前障碍，控制在300字内，结尾询问学生是否理解。"
                        )
                    ),
                ],
            )
            return {
                **budget.update(),
                "step_retry_count": retry_count + 1,
                "messages": [
                    AIMessage(
                        content=(
                            f"_本次调整：{strategy_name}_\n\n{result.content}\n\n"
                            "**现在理解了吗？**"
                        )
                    )
                ],
            }
        except BudgetExceeded:
            return {
                **budget.update(),
                "stop_reason": "llm_budget",
                "messages": [AIMessage(content="本会话已达到模型调用上限。")],
            }

    async def advance_step(state: AgentState) -> dict:
        """推进到下一讲解步骤，并清空只属于上一阶段的反馈与重试状态。"""
        return {
            "current_step_index": int(state.get("current_step_index", 0)) + 1,
            "step_retry_count": 0,
            "feedback_question": "",
        }

    def route_after_advance(state: AgentState) -> str:
        """最后一步之后进入完成节点，否则继续展示下一步。"""
        return (
            "complete"
            if int(state.get("current_step_index", 0)) >= len(state.get("solution_steps", []))
            else "present_step"
        )

    async def handoff(state: AgentState) -> dict:
        """以稳定提示结束自动教学；不会自动联系教师或继续调用模型。"""
        if state.get("stop_reason") == "llm_budget":
            message = "本会话已达到模型调用上限，建议稍后继续，或把当前步骤交给老师协助。"
        else:
            message = (
                "我们已经依次尝试了换种表述、补充例子和回退前置知识，"
                "但这一步仍然没有讲清。建议先暂停复习，或请老师结合你的具体作答进行指导。"
            )
        return {"stop_reason": "handoff", "messages": [AIMessage(content=message)]}

    async def complete(_state: AgentState) -> dict:
        """写入正常完成终态和学生可见提示。"""
        return {
            "stop_reason": "complete",
            "messages": [AIMessage(content="你已经完成了本题的全部步骤，做得很好！")],
        }

    async def terminal(state: AgentState) -> dict:
        """把准备阶段产生的非数学、失败或预算提示转换成最终 AI 消息。"""
        return {
            "messages": [
                AIMessage(content=state.get("terminal_message", "本轮学习已结束。"))
            ]
        }

    graph = StateGraph(AgentState)
    graph.add_node("prepare_problem", prepare_problem)
    graph.add_node("present_step", present_step)
    graph.add_node("assess_feedback", assess_feedback)
    graph.add_node("remediate", remediate)
    graph.add_node("advance_step", advance_step)
    graph.add_node("handoff", handoff)
    graph.add_node("complete", complete)
    graph.add_node("terminal", terminal)

    graph.add_edge(START, "prepare_problem")
    graph.add_conditional_edges(
        "prepare_problem",
        route_after_prepare,
        {"terminal": "terminal", "present_step": "present_step"},
    )
    graph.add_edge("present_step", "assess_feedback")
    graph.add_conditional_edges(
        "assess_feedback",
        route_after_feedback,
        {
            "advance_step": "advance_step",
            "remediate": "remediate",
            "handoff": "handoff",
        },
    )
    graph.add_edge("remediate", "assess_feedback")
    graph.add_conditional_edges(
        "advance_step",
        route_after_advance,
        {"present_step": "present_step", "complete": "complete"},
    )
    graph.add_edge("handoff", END)
    graph.add_edge("complete", END)
    graph.add_edge("terminal", END)
    return graph
