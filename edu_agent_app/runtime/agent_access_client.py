"""Python 到 Agent Access 的私有回调；请求中永不发送 userId。"""

from __future__ import annotations

import asyncio
from typing import Any, Mapping, Sequence
from uuid import uuid4

import httpx

from .protocol import SafeToolEvidence, ToolName


class AgentAccessClient:
    """以服务身份执行候选复核、学习事实读取和幂等结果报告。"""

    def __init__(self, base_url: str, token: str, timeout_seconds: float = 5.0):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout_seconds = timeout_seconds

    def _headers(self, trace_id: str) -> dict[str, str]:
        return {
            "X-Internal-Caller": "edu-agent-runtime",
            "X-Internal-Service-Token": self.token,
            "X-Trace-Id": trace_id,
        }

    async def verify_course(self, run_id: str, candidates: Sequence[Mapping[str, Any]], trace_id: str) -> set[str]:
        async with httpx.AsyncClient(timeout=self.timeout_seconds, follow_redirects=False) as client:
            response = await client.post(
                f"{self.base_url}/internal/v1/agent/runs/{run_id}/course-candidates:verify",
                headers=self._headers(trace_id), json={"candidates": list(candidates)},
            )
            response.raise_for_status()
            value = response.json()
            return {str(item) for item in value.get("allowedEntityIds", [])}

    async def read_facts(self, tool: ToolName, run_id: str, resource_ids: Sequence[str],
                         limit: int, trace_id: str) -> list[SafeToolEvidence]:
        async with httpx.AsyncClient(timeout=self.timeout_seconds, follow_redirects=False) as client:
            response = await client.post(
                f"{self.base_url}/internal/v1/agent/runs/{run_id}/student-facts:query",
                headers=self._headers(trace_id),
                json={"toolName": tool.value, "resourceIds": list(resource_ids), "limit": limit},
            )
            response.raise_for_status()
            return [SafeToolEvidence(
                evidenceId=str(item["evidenceId"]), evidenceType="STUDENT_FACT",
                title=str(item["title"]), summary=str(item["summary"]),
                revision=int(item["revision"]),
            ) for item in response.json().get("facts", [])]

    async def search_user_help(self, run_id: str, query: str, limit: int,
                               trace_id: str) -> list[SafeToolEvidence]:
        """按真实 run 查询 Java 当前帮助版本；请求不携带用户、版本、路由或任意资源范围。"""

        async with httpx.AsyncClient(timeout=self.timeout_seconds, follow_redirects=False) as client:
            response = await client.post(
                f"{self.base_url}/internal/v1/agent/runs/{run_id}/user-help:search",
                headers=self._headers(trace_id),
                json={"query": query[:500], "limit": min(5, max(1, limit))},
            )
            response.raise_for_status()
            value = response.json()
            version = str(value["version"])
            return [SafeToolEvidence(
                evidenceId=str(item["evidenceId"]), evidenceType="USER_HELP",
                title=str(item["title"]), summary=str(item["summary"]),
                helpVersion=version, directAnswer=item.get("directAnswer") is True,
                appRoute=item.get("appRoute"),
            ) for item in value.get("evidence", [])[:5]]

    async def report(self, data: dict[str, Any]) -> None:
        """终态网络传播可能略晚于 Python 生成器，固定 reportId 有限重试仍保持严格幂等。"""

        run_id = str(data["runId"])
        payload = dict(data)
        payload.pop("runId", None)
        payload["reportId"] = str(uuid4())
        trace_id = str(data.get("traceId", "trace-agent-report"))
        async with httpx.AsyncClient(timeout=self.timeout_seconds, follow_redirects=False) as client:
            for attempt in range(5):
                response = await client.put(
                    f"{self.base_url}/internal/v1/agent/runs/{run_id}/result-report",
                    headers=self._headers(trace_id), json=payload,
                )
                if response.status_code < 400:
                    return
                if response.status_code != 409 or attempt == 4:
                    response.raise_for_status()
                await asyncio.sleep(0.1 * (attempt + 1))

    async def start_skill_execution(self, run_id: str, payload: Mapping[str, Any], trace_id: str) -> None:
        """在 Skill 获选后创建私有 RUNNING 审计；失败向上抛出并阻止脚本执行。"""

        async with httpx.AsyncClient(timeout=self.timeout_seconds, follow_redirects=False) as client:
            response = await client.post(
                f"{self.base_url}/internal/v1/agent/runs/{run_id}/skill-executions",
                headers=self._headers(trace_id), json=dict(payload),
            )
            response.raise_for_status()

    async def finish_skill_execution(self, run_id: str, execution_id: str,
                                     payload: Mapping[str, Any], trace_id: str) -> None:
        """更新同一 execution 的完成、失败、取消或超时事实，不发送脚本输入输出。"""

        async with httpx.AsyncClient(timeout=self.timeout_seconds, follow_redirects=False) as client:
            response = await client.put(
                f"{self.base_url}/internal/v1/agent/runs/{run_id}/skill-executions/{execution_id}",
                headers=self._headers(trace_id), json=dict(payload),
            )
            response.raise_for_status()
