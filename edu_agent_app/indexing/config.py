"""读取 T13.2 Index Worker 与混合检索的非敏感配置并执行启动期校验。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

try:
    from dotenv import load_dotenv
except ImportError:  # 纯契约/单元 CI 不安装运行时依赖，环境变量仍可直接使用。
    def load_dotenv(*_args, **_kwargs):
        return False


load_dotenv(override=False)


def _path(value: str, root: Path) -> Path:
    candidate = Path(value)
    return candidate.resolve() if candidate.is_absolute() else (root / candidate).resolve()


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
class IndexSettings:
    """Index Worker 的不可变启动快照。

    地址、批次和阈值是非敏感配置；RabbitMQ/Neo4j 密码及 Java 内部服务令牌只保留在
    进程内存中，调用方不得把该对象整体写入日志或错误响应。
    """

    project_root: Path
    qdrant_url: str
    qdrant_timeout_seconds: float
    bge_model_path: Path
    bge_model_version: str
    reranker_model_path: Path
    reranker_model_version: str
    state_db_path: Path
    source_namespace: str
    learning_source_url: str
    course_source_url: str
    agent_access_source_url: str
    source_timeout_seconds: float
    source_max_attempts: int
    source_page_size: int
    source_concurrency: int
    embedding_batch_size: int
    dense_candidate_k: int
    sparse_candidate_k: int
    rerank_candidate_k: int
    final_min_results: int
    final_max_results: int
    rrf_k: int
    question_threshold: float
    knowledge_threshold: float
    course_threshold: float
    memory_threshold: float
    rabbitmq_host: str
    rabbitmq_port: int
    rabbitmq_vhost: str
    rabbitmq_username: str
    rabbitmq_password: str | None
    rabbitmq_max_attempts: int
    rabbitmq_retry_delay_ms: int
    rabbitmq_prefetch: int
    agent_index_service_token: str | None
    neo4j_uri: str
    neo4j_user: str
    neo4j_password: str | None
    neo4j_enabled: bool
    graph_max_depth: int
    graph_max_nodes: int
    graph_timeout_seconds: float

    def validate_runtime(self, *, require_secrets: bool = True) -> None:
        """校验 Worker 所需模型、令牌和密码，失败时只报告变量名而不回显秘密。"""
        for name, path in (
            ("BGE_MODEL_PATH", self.bge_model_path),
            ("RERANKER_MODEL_PATH", self.reranker_model_path),
        ):
            if not path.is_dir():
                raise ValueError(f"{name} 必须指向已存在的本地模型目录")
        if require_secrets:
            if not self.agent_index_service_token or len(self.agent_index_service_token) < 32:
                raise ValueError("AGENT_INDEX_SERVICE_TOKEN 缺失或长度不足")
            if not self.rabbitmq_password:
                raise ValueError("RABBITMQ_PASSWORD 缺失")
        if self.neo4j_password is not None and not self.neo4j_password:
            raise ValueError("NEO4J_PASSWORD 不能是空字符串")
        if require_secrets and self.neo4j_enabled and not self.neo4j_password:
            raise ValueError("NEO4J_PASSWORD 缺失；启用图投影时必须通过安全环境注入")

    def threshold_for(self, collection: str) -> float:
        """返回集合专属 reranker 概率阈值；未知集合会被拒绝而不是放宽检索。"""
        thresholds = {
            "math_questions": self.question_threshold,
            "math_knowledge": self.knowledge_threshold,
            "course_content": self.course_threshold,
            "student_memories": self.memory_threshold,
        }
        try:
            return thresholds[collection]
        except KeyError as exc:
            raise ValueError("不支持的索引集合") from exc


def load_index_settings() -> IndexSettings:
    """从环境变量构造 T13.2 配置，不连接任何外部服务或创建文件。"""
    root = Path(__file__).resolve().parents[2]
    source_page_size = _integer("AGENT_INDEX_PAGE_SIZE", 100, 1, 200)
    settings = IndexSettings(
        project_root=root,
        qdrant_url=os.getenv("QDRANT_URL", "http://127.0.0.1:6333").rstrip("/"),
        qdrant_timeout_seconds=_float("QDRANT_TIMEOUT_SECONDS", 30.0, 1.0, 120.0),
        bge_model_path=_path(os.getenv("BGE_MODEL_PATH", "../edu_agent/bge-base-zh"), root),
        bge_model_version=os.getenv("BGE_MODEL_VERSION", "BAAI/bge-base-zh@local-v1"),
        reranker_model_path=_path(
            os.getenv("RERANKER_MODEL_PATH", "../edu_agent/bge-reranker-base"), root
        ),
        reranker_model_version=os.getenv(
            "RERANKER_MODEL_VERSION", "BAAI/bge-reranker-base@local-v1"
        ),
        state_db_path=_path(
            os.getenv("AGENT_INDEX_STATE_DB", ".m2/agent-index/worker-state.sqlite3"), root
        ),
        source_namespace=os.getenv("AGENT_INDEX_NAMESPACE", "ccnu-edulion-t13.2-v1"),
        learning_source_url=os.getenv(
            "AGENT_INDEX_LEARNING_SOURCE_URL", "http://127.0.0.1:8084"
        ).rstrip("/"),
        course_source_url=os.getenv(
            "AGENT_INDEX_COURSE_SOURCE_URL", "http://127.0.0.1:8082"
        ).rstrip("/"),
        agent_access_source_url=os.getenv(
            "AGENT_INDEX_AGENT_ACCESS_SOURCE_URL", "http://127.0.0.1:8086"
        ).rstrip("/"),
        source_timeout_seconds=_float("AGENT_INDEX_SOURCE_TIMEOUT_SECONDS", 5.0, 0.1, 30.0),
        source_max_attempts=_integer("AGENT_INDEX_SOURCE_MAX_ATTEMPTS", 3, 1, 5),
        source_page_size=source_page_size,
        source_concurrency=_integer("AGENT_INDEX_SOURCE_CONCURRENCY", 3, 1, 3),
        embedding_batch_size=_integer("AGENT_INDEX_EMBEDDING_BATCH_SIZE", 32, 1, 200),
        dense_candidate_k=_integer("AGENT_RETRIEVAL_DENSE_K", 30, 1, 100),
        sparse_candidate_k=_integer("AGENT_RETRIEVAL_BM25_K", 30, 1, 100),
        rerank_candidate_k=_integer("AGENT_RETRIEVAL_RERANK_K", 25, 20, 30),
        final_min_results=_integer("AGENT_RETRIEVAL_MIN_RESULTS", 3, 1, 8),
        final_max_results=_integer("AGENT_RETRIEVAL_MAX_RESULTS", 8, 3, 8),
        rrf_k=_integer("AGENT_RETRIEVAL_RRF_K", 60, 1, 200),
        question_threshold=_float("AGENT_QUESTION_RERANK_THRESHOLD", 0.58, 0.0, 1.0),
        knowledge_threshold=_float("AGENT_KNOWLEDGE_RERANK_THRESHOLD", 0.54, 0.0, 1.0),
        course_threshold=_float("AGENT_COURSE_RERANK_THRESHOLD", 0.60, 0.0, 1.0),
        memory_threshold=_float("AGENT_MEMORY_RERANK_THRESHOLD", 0.62, 0.0, 1.0),
        rabbitmq_host=os.getenv("RABBITMQ_HOST", "127.0.0.1"),
        rabbitmq_port=_integer("RABBITMQ_PORT", 5672, 1, 65535),
        rabbitmq_vhost=os.getenv("RABBITMQ_VHOST", "/"),
        rabbitmq_username=os.getenv("RABBITMQ_USERNAME", "guest"),
        rabbitmq_password=os.getenv("RABBITMQ_PASSWORD") or None,
        rabbitmq_max_attempts=_integer("AGENT_INDEX_MAX_ATTEMPTS", 3, 1, 10),
        rabbitmq_retry_delay_ms=_integer("AGENT_INDEX_RETRY_DELAY_MS", 1000, 100, 60000),
        rabbitmq_prefetch=_integer("AGENT_INDEX_PREFETCH", 1, 1, 32),
        agent_index_service_token=os.getenv("AGENT_INDEX_SERVICE_TOKEN") or None,
        neo4j_uri=os.getenv("NEO4J_URI", "bolt://127.0.0.1:7687"),
        neo4j_user=os.getenv("NEO4J_USER", "neo4j"),
        neo4j_password=os.getenv("NEO4J_PASSWORD") or None,
        neo4j_enabled=os.getenv("AGENT_GRAPH_ENABLED", "true").lower() == "true",
        graph_max_depth=_integer("AGENT_GRAPH_MAX_DEPTH", 3, 1, 3),
        graph_max_nodes=_integer("AGENT_GRAPH_MAX_NODES", 50, 1, 200),
        graph_timeout_seconds=_float("AGENT_GRAPH_TIMEOUT_SECONDS", 2.0, 0.1, 10.0),
    )
    if settings.final_min_results > settings.final_max_results:
        raise ValueError("AGENT_RETRIEVAL_MIN_RESULTS 不能大于 MAX_RESULTS")
    if not settings.bge_model_version.strip() or not settings.reranker_model_version.strip():
        raise ValueError("模型版本不能为空；升级模型时必须使用新版本并执行显式重建")
    return settings
