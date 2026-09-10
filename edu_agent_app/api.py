"""T13.1 内部 HTTP/SSE 适配层；仅供 Java agent-access-service 调用。"""

from __future__ import annotations

import asyncio
import json
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from .config import build_runtime
from .runtime.protocol import AgentRunRequest, CancelRunRequest
from .runtime.service import DynamicAgentRuntime, RunConflict


def _safe_error(code: str, message: str, retryable: bool, trace_id: str) -> dict[str, Any]:
    return {"code": code, "message": message, "retryable": retryable, "traceId": trace_id}


@asynccontextmanager
async def lifespan(app: FastAPI):
    """在工作线程装配本地检索模型，退出时只关闭连接池，不修改派生索引。"""

    if getattr(app.state, "runtime", None) is None:
        app.state.runtime = await asyncio.to_thread(build_runtime)
    try:
        yield
    finally:
        runtime = getattr(app.state, "runtime", None)
        graph = getattr(getattr(runtime, "tools", None), "graph", None)
        if graph is not None:
            await asyncio.to_thread(graph.close)
        model = getattr(runtime, "model", None)
        close_model = getattr(model, "close", None)
        if close_model is not None:
            await close_model()


def create_app(runtime: DynamicAgentRuntime | None = None) -> FastAPI:
    """创建内部应用；测试可注入完全离线的运行时，不访问真实模型或索引。"""

    app = FastAPI(title="Edu Agent Internal Runtime", version="1.0.0", lifespan=lifespan)
    app.state.runtime = runtime

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, _exc: RequestValidationError):
        return JSONResponse(
            status_code=422,
            content=_safe_error("AGENT.REQUEST_INVALID", "请求不符合内部协议。", False, "trace-invalid-request"),
        )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok" if getattr(app.state, "runtime", None) else "starting"}

    @app.post("/internal/v1/agent/runs")
    async def execute_run(
        request: AgentRunRequest,
        x_agent_internal_protocol_version: str = Header(alias="X-Agent-Internal-Protocol-Version"),
    ):
        if x_agent_internal_protocol_version != "1":
            raise HTTPException(
                status_code=422,
                detail=_safe_error("AGENT.PROTOCOL_VERSION", "只支持内部协议版本 1。", False, request.traceId),
            )
        runtime_value = getattr(app.state, "runtime", None)
        if runtime_value is None:
            raise HTTPException(
                status_code=503,
                detail=_safe_error("AGENT.RUNTIME_UNAVAILABLE", "Agent 服务尚未就绪。", True, request.traceId),
            )
        try:
            handle, replayed = await runtime_value.open_run(request)
        except RunConflict:
            return JSONResponse(
                status_code=409,
                content=_safe_error("AGENT.RUN_CONFLICT", "此会话正在回答，请等待当前回答结束后重试。", True, request.traceId),
            )

        async def frames():
            """每个事件只写 data JSON；断线会关闭生成器并触发运行时取消。"""

            async for event in runtime_value.stream(handle, replayed=replayed):
                yield "data: " + json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n\n"

        return StreamingResponse(
            frames(), media_type="text/event-stream",
            headers={"X-Agent-Internal-Protocol-Version": "1", "Cache-Control": "no-cache"},
        )

    @app.post("/internal/v1/agent/runs/{run_id}/cancel")
    async def cancel_run(
        run_id: str,
        request: CancelRunRequest,
        x_agent_internal_protocol_version: str = Header(alias="X-Agent-Internal-Protocol-Version"),
    ):
        if x_agent_internal_protocol_version != "1":
            return JSONResponse(status_code=422, content=_safe_error(
                "AGENT.PROTOCOL_VERSION", "只支持内部协议版本 1。", False, request.traceId
            ))
        runtime_value = getattr(app.state, "runtime", None)
        if runtime_value is None:
            return JSONResponse(status_code=503, content=_safe_error(
                "AGENT.RUNTIME_UNAVAILABLE", "Agent 服务尚未就绪。", True, request.traceId
            ))
        try:
            outcome, status = await runtime_value.cancel(run_id, str(request.cancelRequestId))
        except KeyError:
            return JSONResponse(status_code=404, content=_safe_error(
                "AGENT.RUN_NOT_FOUND", "run 不存在。", False, request.traceId
            ))
        payload: dict[str, Any] = {
            "runId": run_id,
            "cancelRequestId": str(request.cancelRequestId),
            "outcome": outcome,
            "traceId": request.traceId,
        }
        if status:
            payload["terminalStatus"] = status.value
        return payload

    @app.exception_handler(HTTPException)
    async def safe_http_error(_request: Request, exc: HTTPException):
        content = exc.detail if isinstance(exc.detail, dict) else _safe_error(
            "AGENT.HTTP_ERROR", "内部请求失败。", exc.status_code >= 500, "trace-http-error"
        )
        return JSONResponse(status_code=exc.status_code, content=content)

    return app


app = create_app()
