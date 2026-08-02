"""数学智能体的 HTTP/SSE 适配层，负责初始化本地模型资产并恢复多轮会话状态。"""

from __future__ import annotations

import json
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage, HumanMessage
from pydantic import BaseModel, Field

from .config import (
    create_embeddings,
    create_llm,
    load_faiss,
    load_settings,
)
from .retrieval import HybridRetriever, KnowledgeGraph, RetrievalServices
from .workflow import WorkflowServices, create_workflow


runtime: dict[str, Any] = {}


def _build_retrieval(settings, embeddings) -> RetrievalServices:
    """从只读 FAISS 索引和 Neo4j 连接配置组装检索服务。

    启动时索引或图数据库配置不可用会直接导致应用初始化失败；本函数不创建、覆盖索引，
    也不向 Java 平台数据库写入数据。
    """
    question_store = load_faiss(settings.question_index_path, embeddings)
    knowledge_store = load_faiss(settings.knowledge_index_path, embeddings)
    return RetrievalServices(
        question=HybridRetriever(
            question_store,
            score_threshold=settings.question_score_threshold,
            vector_k=settings.vector_candidate_k,
            keyword_k=settings.keyword_candidate_k,
            rerank_top_k=settings.rerank_top_k,
        ),
        knowledge=HybridRetriever(
            knowledge_store,
            score_threshold=settings.knowledge_score_threshold,
            vector_k=settings.vector_candidate_k,
            keyword_k=settings.keyword_candidate_k,
            rerank_top_k=settings.rerank_top_k,
        ),
        graph=KnowledgeGraph(
            settings.neo4j_uri,
            settings.neo4j_user,
            settings.neo4j_password,
        ),
    )


@asynccontextmanager
async def lifespan(_app: FastAPI):
    """在应用生命周期内初始化工作流、检查点存储和知识图谱连接。

    配置了 ``DATABASE_URL`` 时会由 LangGraph 在该 PostgreSQL 中维护会话检查点；否则仅使用
    进程内存。退出时清空运行时引用并关闭 Neo4j 驱动，不修改 FAISS 文件。
    """
    settings = load_settings()
    embeddings = create_embeddings(settings)
    retrieval = _build_retrieval(settings, embeddings)
    workflow = create_workflow(
        WorkflowServices(
            llm=create_llm(settings),
            retrieval=retrieval,
            settings=settings,
        )
    )

    try:
        if settings.database_url:
            from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

            async with AsyncPostgresSaver.from_conn_string(
                settings.database_url
            ) as checkpointer:
                await checkpointer.setup()
                runtime["agent"] = workflow.compile(
                    checkpointer=checkpointer,
                    interrupt_before=["assess_feedback"],
                )
                runtime["settings"] = settings
                yield
        else:
            from langgraph.checkpoint.memory import InMemorySaver

            runtime["agent"] = workflow.compile(
                checkpointer=InMemorySaver(),
                interrupt_before=["assess_feedback"],
            )
            runtime["settings"] = settings
            yield
    finally:
        runtime.clear()
        await retrieval.graph.close()


app = FastAPI(title="Edu Agent API", version="2.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


class ChatRequest(BaseModel):
    """一次学生输入；``thread_id`` 是恢复 LangGraph 检查点的会话幂等键。"""
    prompt: str = Field(min_length=1, max_length=10_000)
    thread_id: str = Field(min_length=1, max_length=128)
    image_url: str | None = None


@app.get("/health")
async def health() -> dict:
    """返回工作流是否完成初始化，不探测外部模型或索引的实时健康状态。"""
    return {"status": "ok" if runtime.get("agent") else "starting"}


def _ai_messages(output: Any) -> list[str]:
    """从单个节点更新中提取非空 AI 消息，忽略状态字段和学生消息。"""
    if not isinstance(output, dict):
        return []
    messages = output.get("messages") or []
    return [
        str(message.content)
        for message in messages
        if isinstance(message, AIMessage) and str(message.content).strip()
    ]


@app.post("/generate_stream")
async def generate_stream(request: ChatRequest):
    """继续或创建一个教学会话，并以 SSE 逐节点返回完整 AI 消息。

    当 ``thread_id`` 对应快照停在反馈中断点时，请求被视为学生对当前步骤的反馈；否则创建
    新一轮题目输入。检查点写入由 LangGraph 后端负责，重复使用同一会话 ID 会继续既有状态，
    而不是重新开始。初始化未完成返回 503，运行期异常则转换为 SSE ``error`` 事件。
    """
    agent = runtime.get("agent")
    settings = runtime.get("settings")
    if agent is None or settings is None:
        raise HTTPException(status_code=503, detail="智能体尚未初始化")

    config = {
        "configurable": {"thread_id": request.thread_id},
        # 即使业务路由失误，LangGraph 本身也会在达到递归上限时停止。
        "recursion_limit": 80,
    }
    snapshot = await agent.aget_state(config)

    if snapshot.next:
        await agent.aupdate_state(
            config,
            {"messages": [HumanMessage(content=request.prompt)]},
        )
        run_input = None
    else:
        previous_calls = int(snapshot.values.get("llm_call_count", 0)) if snapshot.values else 0
        run_input = {
            "messages": [HumanMessage(content=request.prompt)],
            "user_image_url": request.image_url,
            "llm_call_count": previous_calls,
            "max_llm_calls": settings.max_llm_calls,
            "max_step_retries": settings.max_step_retries,
            "stop_reason": "",
        }

    async def event_generator():
        """把 LangGraph 节点更新转换为 SSE 数据帧，并始终给出结束或错误事件。"""
        try:
            async for event in agent.astream(
                run_input,
                config=config,
                stream_mode="updates",
            ):
                if not isinstance(event, dict):
                    continue
                for node, output in event.items():
                    for content in _ai_messages(output):
                        payload = json.dumps(
                            {"content": content, "node": node, "type": "full"},
                            ensure_ascii=False,
                        )
                        yield f"data: {payload}\n\n"
            yield "event: end\ndata: [DONE]\n\n"
        except Exception as exc:
            payload = json.dumps(
                {"content": f"AI运行出错：{exc}", "node": "error", "type": "error"},
                ensure_ascii=False,
            )
            yield f"data: {payload}\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")
