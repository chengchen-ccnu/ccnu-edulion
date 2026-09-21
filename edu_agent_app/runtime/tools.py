"""冻结白名单之内的只读工具执行、范围校验、混合检索和多模态安全适配。"""

from __future__ import annotations

import asyncio
import base64
from dataclasses import dataclass
from time import perf_counter, time
from typing import Any, Awaitable, Callable, Mapping, Sequence
from uuid import uuid4

from .budget import LimitReached, RunLimits
from .protocol import (
    AgentRole,
    AgentRunRequest,
    SafeError,
    SafeToolEvidence,
    SecondaryIntent,
    ToolName,
    ToolResult,
)


ALLOWED_TOOLS: dict[SecondaryIntent, frozenset[ToolName]] = {
    SecondaryIntent.SOLVE_PROBLEM: frozenset({ToolName.QUESTION_SEARCH, ToolName.KNOWLEDGE_SEARCH, ToolName.PREREQUISITE_GRAPH, ToolName.AUTHORIZED_IMAGE_UNDERSTANDING}),
    SecondaryIntent.EXPLAIN_CONCEPT: frozenset({ToolName.KNOWLEDGE_SEARCH, ToolName.PREREQUISITE_GRAPH}),
    SecondaryIntent.COURSE_QUESTION: frozenset({ToolName.COURSE_TEXT_SEARCH, ToolName.KNOWLEDGE_SEARCH}),
    SecondaryIntent.CHECK_STUDENT_WORK: frozenset({ToolName.QUESTION_SEARCH, ToolName.KNOWLEDGE_SEARCH, ToolName.AUTHORIZED_IMAGE_UNDERSTANDING}),
    SecondaryIntent.GIVE_HINT: frozenset({ToolName.QUESTION_SEARCH, ToolName.KNOWLEDGE_SEARCH}),
    SecondaryIntent.REVIEW_WRONG_QUESTION: frozenset({ToolName.STUDENT_WRONG_QUESTIONS, ToolName.STUDENT_SUBMISSIONS, ToolName.KNOWLEDGE_SEARCH}),
    SecondaryIntent.RECOMMEND_PRACTICE: frozenset({ToolName.QUESTION_SEARCH, ToolName.KNOWLEDGE_SEARCH}),
    SecondaryIntent.CREATE_PLAN: frozenset({ToolName.STUDENT_PROGRESS, ToolName.STUDENT_PUBLISHED_GRADES}),
    SecondaryIntent.ADJUST_PLAN: frozenset({ToolName.STUDENT_PROGRESS, ToolName.STUDENT_PUBLISHED_GRADES}),
    SecondaryIntent.LEARNING_ADVICE: frozenset({ToolName.STUDENT_PROGRESS, ToolName.STUDENT_PUBLISHED_GRADES}),
    SecondaryIntent.PLATFORM_USAGE_HELP: frozenset({ToolName.USER_HELP_SEARCH}),
    SecondaryIntent.SMALL_TALK: frozenset(),
    SecondaryIntent.UNSUPPORTED: frozenset(),
    SecondaryIntent.ACTION_CONFIRMATION: frozenset(),
    SecondaryIntent.ACTION_CANCELLATION: frozenset(),
}

ROLE_TOOLS: dict[AgentRole, frozenset[ToolName]] = {
    AgentRole.COORDINATOR: frozenset(),
    AgentRole.MATH_TUTOR: frozenset().union(*(ALLOWED_TOOLS[item] for item in (
        SecondaryIntent.SOLVE_PROBLEM, SecondaryIntent.EXPLAIN_CONCEPT,
        SecondaryIntent.COURSE_QUESTION, SecondaryIntent.CHECK_STUDENT_WORK,
        SecondaryIntent.GIVE_HINT,
    ))),
    AgentRole.PRACTICE_COACH: frozenset().union(*(ALLOWED_TOOLS[item] for item in (
        SecondaryIntent.REVIEW_WRONG_QUESTION, SecondaryIntent.RECOMMEND_PRACTICE,
    ))),
    AgentRole.STUDY_PLANNER: frozenset().union(*(ALLOWED_TOOLS[item] for item in (
        SecondaryIntent.CREATE_PLAN, SecondaryIntent.ADJUST_PLAN, SecondaryIntent.LEARNING_ADVICE,
    ))),
    AgentRole.PLATFORM_HELP: frozenset({ToolName.USER_HELP_SEARCH}),
}


@dataclass(frozen=True)
class ToolInvocation:
    """运行时内部工具请求；字段与 T13.1 ToolCallRequest 一一对应。"""

    tool_name: ToolName
    arguments: dict[str, Any]
    timeout_ms: int = 5_000


CourseVerifier = Callable[[str, Sequence[Mapping[str, Any]], str], Awaitable[set[str]]]
StudentFactReader = Callable[[ToolName, str, Sequence[str], int, str], Awaitable[list[SafeToolEvidence]]]
UserHelpReader = Callable[[str, str, int, str], Awaitable[list[SafeToolEvidence]]]


class ImageUnclear(PermissionError):
    """视觉模型无法可靠读取题意；调用方必须澄清，不能把猜测作为证据。"""


class ToolExecutor:
    """执行只读工具并同时校验专家能力、二级意图、Java grant 与资源范围。"""

    def __init__(
        self,
        *,
        search_service: Any | None = None,
        graph: Any | None = None,
        model: Any | None = None,
        course_verifier: CourseVerifier | None = None,
        student_fact_reader: StudentFactReader | None = None,
        user_help_reader: UserHelpReader | None = None,
        http_client: Any | None = None,
    ):
        self.search_service = search_service
        self.graph = graph
        self.model = model
        self.course_verifier = course_verifier
        self.student_fact_reader = student_fact_reader
        self.user_help_reader = user_help_reader
        self.http_client = http_client

    async def execute(
        self,
        run: AgentRunRequest,
        role: AgentRole,
        intent: SecondaryIntent,
        invocation: ToolInvocation,
        limits: RunLimits,
    ) -> ToolResult:
        """权限拒绝不触达外部依赖；已发起的失败或超时照样计入工具预算。"""

        started = perf_counter()
        call_id = uuid4()
        name = invocation.tool_name
        trace_id = run.traceId
        try:
            self._authorize(run, role, intent, invocation)
            limits.step(f"TOOL_{name.value}")
            limits.tool(name, invocation.arguments)
            timeout = min(invocation.timeout_ms, 30_000, max(100, limits.remaining_ms())) / 1000
            evidence = await asyncio.wait_for(
                self._dispatch(run, intent, invocation, limits), timeout=timeout
            )
            status = "SUCCEEDED" if evidence else "EMPTY"
            result = ToolResult(
                toolCallId=call_id, toolName=name, status=status, traceId=trace_id,
                elapsedMs=min(30_000, int((perf_counter() - started) * 1000)), evidence=evidence,
            )
            limits.usage.phase_latency_ms[f"TOOL_{name.value}"] = result.elapsedMs
            return result
        except asyncio.TimeoutError:
            return self._failure(call_id, name, trace_id, started, "TIMEOUT", "AGENT.TOOL_TIMEOUT", "工具调用超时。", True)
        except ImageUnclear:
            return self._failure(call_id, name, trace_id, started, "REJECTED", "AGENT.IMAGE_UNCLEAR", "图片内容不够清晰，请补充清晰图片或文字题意。", False)
        except (PermissionError, ValueError) as exc:
            return self._failure(call_id, name, trace_id, started, "REJECTED", "AGENT.TOOL_REJECTED", str(exc)[:180], False)
        except LimitReached:
            raise
        except Exception as exc:
            return self._failure(call_id, name, trace_id, started, "FAILED", "AGENT.TOOL_FAILED", f"工具暂时不可用：{type(exc).__name__}", True)

    def _authorize(self, run: AgentRunRequest, role: AgentRole, intent: SecondaryIntent, invocation: ToolInvocation) -> None:
        name = invocation.tool_name
        if name not in ROLE_TOOLS[role] or name not in ALLOWED_TOOLS.get(intent, frozenset()):
            raise PermissionError("专家或二级意图不允许调用该工具")
        grants = [item for item in run.trustedContext.toolGrants if item.toolName == name]
        if not grants:
            raise PermissionError("Java 未授予该工具")
        requested_ids = {str(item) for item in invocation.arguments.get("resourceIds", [])}
        if name is ToolName.AUTHORIZED_IMAGE_UNDERSTANDING:
            requested_ids = {str(invocation.arguments.get("mediaId", ""))}
        elif name is ToolName.COURSE_TEXT_SEARCH:
            requested_ids = {str(invocation.arguments.get("courseId", ""))}
        if requested_ids:
            scopes = [scope for grant in grants for scope in grant.resourceScopes]
            granted_ids = {value for scope in scopes for value in (scope.resourceIds or [])}
            public = any(scope.scopeType == "PUBLIC_JUNIOR_MATH" for scope in scopes)
            self_scope = any(scope.scopeType == "USER_SELF" for scope in scopes)
            if not public and not self_scope and not requested_ids.issubset(granted_ids):
                raise PermissionError("工具参数超出 Java 授权资源范围")
        elif not any(
            scope.scopeType in {"PUBLIC_JUNIOR_MATH", "USER_SELF", "HELP_KB"}
            for grant in grants for scope in grant.resourceScopes
        ):
            raise PermissionError("受限工具必须携带 Java 授权的精确资源 ID")

    async def _dispatch(
        self,
        run: AgentRunRequest,
        intent: SecondaryIntent,
        invocation: ToolInvocation,
        limits: RunLimits,
    ) -> list[SafeToolEvidence]:
        name = invocation.tool_name
        args = invocation.arguments
        if name in {ToolName.QUESTION_SEARCH, ToolName.KNOWLEDGE_SEARCH, ToolName.COURSE_TEXT_SEARCH}:
            return await self._search(run, intent, name, args)
        if name is ToolName.PREREQUISITE_GRAPH:
            return await self._graph(args)
        if name is ToolName.AUTHORIZED_IMAGE_UNDERSTANDING:
            return await self._image(run, args, limits)
        if name in {
            ToolName.STUDENT_WRONG_QUESTIONS, ToolName.STUDENT_SUBMISSIONS,
            ToolName.STUDENT_PUBLISHED_GRADES, ToolName.STUDENT_PROGRESS,
        }:
            if self.student_fact_reader is None:
                return []
            return await self.student_fact_reader(
                name, str(run.runId), args.get("resourceIds", []),
                int(args.get("limit", 8)), run.traceId,
            )
        if name is ToolName.USER_HELP_SEARCH:
            if self.user_help_reader is None:
                return []
            return await self.user_help_reader(
                str(run.runId), str(args.get("query", ""))[:500],
                min(5, max(1, int(args.get("limit", 5)))), run.traceId,
            )
        raise ValueError("不支持的工具")

    async def _search(
        self, run: AgentRunRequest, intent: SecondaryIntent, name: ToolName, args: dict[str, Any]
    ) -> list[SafeToolEvidence]:
        if self.search_service is None:
            return []
        from edu_agent_app.indexing.models import RetrievalRequest

        collection = {
            ToolName.QUESTION_SEARCH: "math_questions",
            ToolName.KNOWLEDGE_SEARCH: "math_knowledge",
            ToolName.COURSE_TEXT_SEARCH: "course_content",
        }[name]
        request = RetrievalRequest(
            collection=collection,
            query=str(args["query"]),
            intent=intent.value,
            limit=min(20, int(args.get("limit", 8))),
            course_id=args.get("courseId"),
            course_version_id=args.get("courseVersionId"),
            lesson_ids=tuple(args.get("lessonIds", ())),
            knowledge_point_ids=tuple(args.get("knowledgePointIds", ())),
        )
        response = await asyncio.to_thread(self.search_service.search, request)
        hits = list(response.hits)
        if name is ToolName.COURSE_TEXT_SEARCH:
            if self.course_verifier is None:
                return []
            safe_candidates = [
                {
                    "entityId": hit.entity_id,
                    "courseId": hit.payload.get("course_id"),
                    "courseVersionId": hit.payload.get("course_version_id"),
                    "lessonId": hit.payload.get("lesson_id"),
                    "sourceRevision": hit.source_revision,
                }
                for hit in hits
            ]
            allowed = await self.course_verifier(str(run.runId), safe_candidates, run.traceId)
            hits = [hit for hit in hits if hit.entity_id in allowed]
        evidence_type = {
            ToolName.QUESTION_SEARCH: "QUESTION",
            ToolName.KNOWLEDGE_SEARCH: "KNOWLEDGE_POINT",
            ToolName.COURSE_TEXT_SEARCH: "COURSE_TEXT",
        }[name]
        return [
            SafeToolEvidence(
                evidenceId=hit.entity_id,
                evidenceType=evidence_type,
                title=hit.title[:200] or evidence_type,
                summary=hit.summary[:4000],
                revision=hit.source_revision,
                questionId=hit.entity_id if evidence_type == "QUESTION" else None,
                questionVersionId=hit.payload.get("question_version_id") if evidence_type == "QUESTION" else None,
            )
            for hit in hits
        ]

    async def _graph(self, args: dict[str, Any]) -> list[SafeToolEvidence]:
        if self.graph is None:
            return []
        result = await asyncio.to_thread(
            self.graph.related,
            args["knowledgePointIds"], args.get("direction", "PREREQUISITES"),
            min(3, int(args.get("maxDepth", 1))), 50,
        )
        return [
            SafeToolEvidence(
                evidenceId=entity_id,
                evidenceType="PREREQUISITE_RELATION",
                title="前置知识",
                summary="该知识点是当前主题的有界前置知识候选。",
            )
            for entity_id in result.entity_ids
        ]

    async def _image(self, run: AgentRunRequest, args: dict[str, Any], limits: RunLimits) -> list[SafeToolEvidence]:
        media_id = str(args["mediaId"])
        media = next((item for item in run.trustedContext.media if str(item.mediaId) == media_id), None)
        if media is None or media.temporaryAccess is None:
            raise PermissionError("图片未由 Java 授权注入")
        if media.temporaryAccess.expiresAt.timestamp() <= time():
            raise PermissionError("图片短期授权已过期")
        client = self.http_client
        owns_client = client is None
        if client is None:
            import httpx

            client = httpx.AsyncClient(follow_redirects=False, timeout=5.0)
        try:
            response = await client.get(str(media.temporaryAccess.url), headers={"Accept": media.mimeType})
            if 300 <= response.status_code < 400:
                raise PermissionError("图片授权地址不允许重定向")
            response.raise_for_status()
            content_type = response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
            if content_type != media.mimeType:
                raise PermissionError("图片响应类型与 Java 授权不一致")
            data = bytes(response.content)
            if not data or len(data) > media.sizeBytes or len(data) > 10_485_760:
                raise PermissionError("图片响应大小与 Java 授权不一致")
            encoded = base64.b64encode(data).decode("ascii")
            summary, reliable = await self.model.understand_image(
                f"data:{media.mimeType};base64,{encoded}", str(args["task"]), limits
            )
            if not reliable:
                raise ImageUnclear
            return [SafeToolEvidence(
                evidenceId=media_id, evidenceType="IMAGE_UNDERSTANDING",
                title="已授权图片理解", summary=summary,
            )]
        finally:
            if owns_client:
                await client.aclose()

    @staticmethod
    def _failure(call_id, name, trace_id, started, status, code, message, retryable):
        return ToolResult(
            toolCallId=call_id, toolName=name, status=status, traceId=trace_id,
            elapsedMs=min(30_000, int((perf_counter() - started) * 1000)), evidence=[],
            error=SafeError(code=code, message=message, retryable=retryable, traceId=trace_id),
        )
