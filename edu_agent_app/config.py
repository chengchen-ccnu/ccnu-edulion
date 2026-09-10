"""读取 T13.3 在线运行时配置；秘密只从当前进程环境取得。"""

from __future__ import annotations

import os
from dataclasses import dataclass

try:
    from dotenv import load_dotenv
except ImportError:
    def load_dotenv(*_args, **_kwargs):
        return False


load_dotenv(override=False)


def _integer(name: str, default: int, minimum: int, maximum: int) -> int:
    value = int(os.getenv(name, str(default)))
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} 必须位于 {minimum}..{maximum}")
    return value


def _float(name: str, default: float, minimum: float, maximum: float) -> float:
    value = float(os.getenv(name, str(default)))
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} 必须位于 {minimum}..{maximum}")
    return value


@dataclass(frozen=True)
class RuntimeSettings:
    """在线运行时的不可变配置快照；对象本身不得整体写日志。"""

    openai_base_url: str
    openai_api_key: str | None
    chat_model_name: str
    vision_model_name: str
    model_request_timeout_seconds: float
    retrieval_enabled: bool
    conversation_compression_threshold_chars: int
    agent_access_base_url: str
    agent_runtime_callback_token: str | None
    agent_access_timeout_seconds: float


def load_runtime_settings() -> RuntimeSettings:
    """加载模型、检索和压缩配置；不连接供应商、Qdrant、Neo4j 或数据库。"""

    chat_model = os.getenv("CHAT_MODEL_NAME") or os.getenv("MODEL_NAME") or "qwen3.8-flash"
    vision_model = os.getenv("VISION_MODEL_NAME") or chat_model
    if not chat_model.strip() or not vision_model.strip():
        raise ValueError("CHAT_MODEL_NAME 与 VISION_MODEL_NAME 不能为空")
    return RuntimeSettings(
        openai_base_url=os.getenv(
            "OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
        ).rstrip("/"),
        openai_api_key=os.getenv("OPENAI_API_KEY") or None,
        chat_model_name=chat_model,
        vision_model_name=vision_model,
        model_request_timeout_seconds=_float("AGENT_MODEL_REQUEST_TIMEOUT_SECONDS", 30.0, 1.0, 90.0),
        retrieval_enabled=os.getenv("AGENT_ONLINE_RETRIEVAL_ENABLED", "true").lower() == "true",
        conversation_compression_threshold_chars=_integer(
            "AGENT_CONTEXT_COMPRESSION_THRESHOLD_CHARS", 12_000, 2_000, 100_000
        ),
        agent_access_base_url=os.getenv("AGENT_ACCESS_BASE_URL", "http://127.0.0.1:8086").rstrip("/"),
        agent_runtime_callback_token=os.getenv("AGENT_RUNTIME_CALLBACK_TOKEN") or None,
        agent_access_timeout_seconds=_float("AGENT_ACCESS_REQUEST_TIMEOUT_SECONDS", 5.0, 0.1, 30.0),
    )


def build_runtime(settings: RuntimeSettings | None = None):
    """组装模型、T13.2 混合检索和有界知识图；绝不加载旧 FAISS 或 PostgreSQL。"""

    from .runtime.model import ModelSettings, OpenAICompatibleModel
    from .runtime.service import DynamicAgentRuntime
    from .runtime.session import ConversationStore
    from .runtime.tools import ToolExecutor

    settings = settings or load_runtime_settings()
    model = OpenAICompatibleModel(ModelSettings(
        base_url=settings.openai_base_url,
        api_key=settings.openai_api_key,
        chat_model_name=settings.chat_model_name,
        vision_model_name=settings.vision_model_name,
        request_timeout_seconds=settings.model_request_timeout_seconds,
    ))
    search = None
    graph = None
    if settings.retrieval_enabled:
        from .indexing.config import load_index_settings
        from .indexing.graph_store import Neo4jKnowledgeGraph
        from .indexing.models_runtime import DenseEncoder, LocalReranker
        from .indexing.qdrant_store import QdrantIndex
        from .indexing.retriever import HybridSearchService

        index_settings = load_index_settings()
        index_settings.validate_runtime(require_secrets=False)
        encoder = DenseEncoder(index_settings.bge_model_path, index_settings.bge_model_version)
        reranker = LocalReranker(index_settings.reranker_model_path, index_settings.reranker_model_version)
        index = QdrantIndex(
            index_settings.qdrant_url, index_settings.source_namespace,
            index_settings.bge_model_version, index_settings.qdrant_timeout_seconds,
        )
        search = HybridSearchService(index_settings, index, encoder, reranker)
        if index_settings.neo4j_enabled and index_settings.neo4j_password:
            graph = Neo4jKnowledgeGraph(
                index_settings.neo4j_uri, index_settings.neo4j_user,
                index_settings.neo4j_password, index_settings.source_namespace,
                index_settings.graph_timeout_seconds,
            )
    callback = None
    if settings.agent_runtime_callback_token:
        from .runtime.agent_access_client import AgentAccessClient
        callback = AgentAccessClient(settings.agent_access_base_url,
                                     settings.agent_runtime_callback_token,
                                     settings.agent_access_timeout_seconds)
    tools = ToolExecutor(
        search_service=search, graph=graph, model=model,
        course_verifier=callback.verify_course if callback else None,
        student_fact_reader=callback.read_facts if callback else None,
    )
    return DynamicAgentRuntime(
        model=model,
        tools=tools,
        sessions=ConversationStore(settings.conversation_compression_threshold_chars),
        telemetry=callback.report if callback else None,
    )
