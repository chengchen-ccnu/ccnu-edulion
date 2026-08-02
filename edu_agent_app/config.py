"""读取数学智能体配置，并构造模型、嵌入模型与只读 FAISS 资源。"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


load_dotenv(override=False)


def _resolve_path(value: str, base: Path) -> Path:
    path = Path(value)
    return path.resolve() if path.is_absolute() else (base / path).resolve()


@dataclass(frozen=True)
class Settings:
    """启动期配置快照；路径均解析为绝对路径，秘密只从进程环境读取。"""
    project_root: Path
    asset_root: Path
    bge_model_path: Path
    model_name: str
    question_score_threshold: float
    knowledge_score_threshold: float
    vector_candidate_k: int
    keyword_candidate_k: int
    rerank_top_k: int
    max_step_retries: int
    max_llm_calls: int
    database_url: str | None
    neo4j_uri: str
    neo4j_user: str
    neo4j_password: str | None

    @property
    def question_index_path(self) -> Path:
        return self.asset_root / "faiss_question_db"

    @property
    def knowledge_index_path(self) -> Path:
        return self.asset_root / "faiss_knowledge_points"


def load_settings() -> Settings:
    """从环境变量读取配置，缺省路径相对项目根目录解析。

    返回值只保存本进程所需设置，不创建目录或外部资源；数值格式错误会在启动期直接抛出，
    避免以含糊的回退值运行。
    """
    project_root = Path(__file__).resolve().parents[1]
    asset_root = _resolve_path(
        os.getenv("EDU_AGENT_ASSET_ROOT", "../edu_agent/智能体项目"),
        project_root,
    )
    bge_model_path = _resolve_path(
        os.getenv("BGE_MODEL_PATH", "../edu_agent/bge-base-zh"),
        project_root,
    )
    return Settings(
        project_root=project_root,
        asset_root=asset_root,
        bge_model_path=bge_model_path,
        model_name=os.getenv("MODEL_NAME", "qwen3.5-flash"),
        question_score_threshold=float(os.getenv("QUESTION_SCORE_THRESHOLD", "0.42")),
        knowledge_score_threshold=float(os.getenv("KNOWLEDGE_SCORE_THRESHOLD", "0.38")),
        vector_candidate_k=int(os.getenv("VECTOR_CANDIDATE_K", "30")),
        keyword_candidate_k=int(os.getenv("KEYWORD_CANDIDATE_K", "30")),
        rerank_top_k=int(os.getenv("RERANK_TOP_K", "10")),
        max_step_retries=int(os.getenv("MAX_STEP_RETRIES", "3")),
        max_llm_calls=int(os.getenv("MAX_LLM_CALLS", "30")),
        database_url=os.getenv("DATABASE_URL") or None,
        neo4j_uri=os.getenv("NEO4J_URI", "bolt://localhost:7687"),
        neo4j_user=os.getenv("NEO4J_USER", "neo4j"),
        neo4j_password=os.getenv("NEO4J_PASSWORD") or None,
    )


def create_llm(settings: Settings):
    """创建确定性聊天模型客户端；实际网络调用发生在工作流节点执行时。"""
    from langchain_openai import ChatOpenAI

    return ChatOpenAI(model=settings.model_name, temperature=0)


def create_embeddings(settings: Settings):
    """从本地模型目录加载归一化嵌入模型，并按可用性选择 CUDA 或 CPU。"""
    import torch
    from langchain_huggingface import HuggingFaceEmbeddings

    device = "cuda" if torch.cuda.is_available() else "cpu"
    return HuggingFaceEmbeddings(
        model_name=str(settings.bge_model_path),
        model_kwargs={"device": device},
        encode_kwargs={"normalize_embeddings": True},
    )


def load_faiss(path: Path, embeddings):
    """加载受信任的本地 FAISS 索引，索引缺失时终止启动且不自动重建。

    ``allow_dangerous_deserialization`` 仅因历史索引包含 pickle 而开启，因此调用方必须保证
    ``path`` 指向项目维护者提供的可信本地资产，不能用于用户上传文件。
    """
    from langchain_community.vectorstores import FAISS

    if not path.exists():
        raise FileNotFoundError(f"找不到 FAISS 索引：{path}")
    # 索引来自受信任的本地旧项目。不要对不可信的 pickle 文件开启此选项。
    return FAISS.load_local(
        folder_path=str(path),
        embeddings=embeddings,
        allow_dangerous_deserialization=True,
    )
