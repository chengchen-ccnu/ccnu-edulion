"""T13.2 独立 Index Worker 命令行入口。"""

from __future__ import annotations

import argparse
import json
import logging
from typing import Sequence

from .config import IndexSettings, load_index_settings
from .graph_store import Neo4jKnowledgeGraph
from .models import ENTITY_TO_COLLECTION
from .models_runtime import DenseEncoder
from .qdrant_store import COLLECTIONS, QdrantIndex
from .source_client import JavaProjectionClient
from .state_store import WorkerStateStore
from .worker import IndexProcessor, RabbitIndexConsumer


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m edu_agent_app index-worker")
    parser.add_argument(
        "--mode", choices=("consume", "init", "rebuild", "reconcile", "cleanup"), default="consume"
    )
    parser.add_argument(
        "--restart-rebuild",
        action="store_true",
        help="仅重置四类 Java 分页检查点；不会删除实体 revision/tombstone 或索引。",
    )
    parser.add_argument(
        "--confirm-namespace",
        help="cleanup 必须精确重复 AGENT_INDEX_NAMESPACE，避免误删其他命名空间。",
    )
    return parser


def _index(settings: IndexSettings) -> QdrantIndex:
    return QdrantIndex(
        settings.qdrant_url,
        settings.source_namespace,
        settings.bge_model_version,
        settings.qdrant_timeout_seconds,
    )


def _graph(settings: IndexSettings):
    if not settings.neo4j_enabled:
        return None
    if not settings.neo4j_password:
        raise ValueError("NEO4J_PASSWORD 缺失；启用图投影时必须通过安全环境注入")
    return Neo4jKnowledgeGraph(
        settings.neo4j_uri,
        settings.neo4j_user,
        settings.neo4j_password,
        settings.source_namespace,
        settings.graph_timeout_seconds,
    )


def _processor(
    settings: IndexSettings, state: WorkerStateStore, index: QdrantIndex, graph
) -> IndexProcessor:
    if not settings.agent_index_service_token or len(settings.agent_index_service_token) < 32:
        raise ValueError("AGENT_INDEX_SERVICE_TOKEN 缺失或长度不足")
    settings.validate_runtime(require_secrets=False)
    source = JavaProjectionClient(
        settings.learning_source_url,
        settings.course_source_url,
        settings.agent_index_service_token,
        settings.source_timeout_seconds,
        settings.source_max_attempts,
        settings.agent_access_source_url,
    )
    encoder = DenseEncoder(settings.bge_model_path, settings.bge_model_version)
    return IndexProcessor(
        settings,
        state,
        source,
        index,
        encoder,
        graph,
        memory_source=source,
    )


def run_index_worker(argv: Sequence[str] | None = None) -> int:
    """执行独立初始化、消费、重建、对账或精确清理；不会启动在线 Agent HTTP。"""
    args = _parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    settings = load_index_settings()
    index = _index(settings)
    graph = None
    state = None
    try:
        if args.mode == "cleanup":
            if args.confirm_namespace != settings.source_namespace:
                raise ValueError("cleanup 需要 --confirm-namespace 精确确认")
            for collection in COLLECTIONS:
                index.cleanup_namespace(collection)
            graph = _graph(settings)
            deleted_nodes = graph.cleanup_namespace() if graph is not None else 0
            state = WorkerStateStore(settings.state_db_path)
            cleared_state = state.clear_all()
            print(
                json.dumps(
                    {
                        "namespace": settings.source_namespace,
                        "qdrant": "cleaned",
                        "neo4jNodes": deleted_nodes,
                        "clearedStateRows": cleared_state,
                    }
                )
            )
            return 0

        index.ensure_collections()
        graph = _graph(settings)
        if graph is not None:
            graph.ensure_schema()
        if args.mode == "init":
            print(json.dumps({"collections": list(COLLECTIONS), "schemaVersion": 1}))
            return 0

        state = WorkerStateStore(settings.state_db_path)
        if args.mode == "reconcile":
            print(
                json.dumps(
                    {
                        "state": state.entity_counts(),
                        "qdrantPoints": {
                            collection: index.namespace_count(collection) for collection in COLLECTIONS
                        },
                        "policy": "report-only-no-automatic-delete",
                    },
                    ensure_ascii=False,
                )
            )
            return 0

        processor = _processor(settings, state, index, graph)
        if args.mode == "rebuild":
            if args.restart_rebuild:
                for entity_type in ("QUESTION", "KNOWLEDGE_POINT", "COURSE_TEXT", "STUDENT_MEMORY"):
                    state.reset_checkpoint(entity_type)
            results = processor.rebuild_all()
            print(json.dumps([result.__dict__ for result in results], ensure_ascii=False))
            return 0

        if not settings.rabbitmq_password:
            raise ValueError("RABBITMQ_PASSWORD 缺失；必须通过安全环境注入")
        RabbitIndexConsumer(settings, processor).run_forever()
        return 0
    finally:
        if state is not None:
            state.close()
        if graph is not None:
            graph.close()
