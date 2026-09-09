"""T13.2 本机真实 Qdrant/Neo4j/RabbitMQ/Java 事实源验收（默认跳过）。"""

from __future__ import annotations

import json
import os
import time
import unittest
from dataclasses import replace
from types import SimpleNamespace
from uuid import uuid4

from edu_agent_app.indexing.config import load_index_settings
from edu_agent_app.indexing.graph_store import Neo4jKnowledgeGraph
from edu_agent_app.indexing.models import IndexDocument, RetrievalRequest
from edu_agent_app.indexing.models_runtime import DenseEncoder, LocalReranker
from edu_agent_app.indexing.qdrant_store import QdrantIndex
from edu_agent_app.indexing.retriever import HybridSearchService
from edu_agent_app.indexing.source_client import JavaProjectionClient
from edu_agent_app.indexing.worker import ROUTING_KEYS, RabbitIndexConsumer


REAL_ACCEPTANCE = os.getenv("AGENT_INDEX_REAL_ACCEPTANCE", "false").lower() == "true"


def _delete_event() -> bytes:
    """生成只含稳定 ID/revision 的合法 DELETE 事件，不创建 Java 领域数据。"""
    entity_id = str(uuid4())
    return json.dumps(
        {
            "eventId": str(uuid4()),
            "eventType": "KnowledgePointIndexProjectionChanged",
            "eventVersion": 1,
            "aggregateId": entity_id,
            "occurredAt": "2026-09-09T00:00:00Z",
            "traceId": "trace-real-rabbit-acceptance",
            "payload": {
                "sourceDomain": "LEARNING",
                "entityType": "KNOWLEDGE_POINT",
                "entityId": entity_id,
                "sourceRevision": 1,
                "operation": "DELETE",
                "sourceState": "DELETED",
                "educationStage": "JUNIOR",
                "visibility": "PUBLIC_MATH_KNOWLEDGE",
                "authorization": {
                    "enabledOnly": True,
                    "projectionApi": "LEARNING_KNOWLEDGE_SAFE_PROJECTION",
                    "graphProjection": "BOUNDED_PREREQUISITE_RELATIONS",
                },
            },
        }
    ).encode("utf-8")


@unittest.skipUnless(REAL_ACCEPTANCE, "设置 AGENT_INDEX_REAL_ACCEPTANCE=true 后执行真实验收")
class IndexWorkerRealAcceptanceTests(unittest.TestCase):
    """只使用有界样本；临时索引 namespace 和 Rabbit 拓扑在测试结束后精确清理。"""

    @classmethod
    def setUpClass(cls):
        cls.settings = load_index_settings()
        cls.settings.validate_runtime(require_secrets=False)
        cls.index = QdrantIndex(
            cls.settings.qdrant_url,
            cls.settings.source_namespace,
            cls.settings.bge_model_version,
            cls.settings.qdrant_timeout_seconds,
        )
        cls.encoder = DenseEncoder(cls.settings.bge_model_path, cls.settings.bge_model_version)
        cls.reranker = LocalReranker(
            cls.settings.reranker_model_path, cls.settings.reranker_model_version
        )

    def test_java_projection_pages_are_bounded_and_safe(self):
        client = JavaProjectionClient(
            self.settings.learning_source_url,
            self.settings.course_source_url,
            self.settings.agent_index_service_token or "",
            self.settings.source_timeout_seconds,
            self.settings.source_max_attempts,
        )
        for entity_type in ("QUESTION", "KNOWLEDGE_POINT", "COURSE_TEXT"):
            page = client.page(
                entity_type, 0, "00000000-0000-0000-0000-000000000000", 5
            )
            self.assertLessEqual(len(page.documents), 5)
            for item in page.source_items:
                self.assertFalse(
                    {"answers", "answer", "analysis", "signedUrl", "url", "token"} & set(item)
                )

    def test_qdrant_schema_versions_and_question_payload_are_safe(self):
        from qdrant_client import models

        for collection in (
            "math_questions", "math_knowledge", "course_content", "student_memories"
        ):
            info = self.index.client.get_collection(collection)
            self.assertEqual(info.config.metadata["schema_version"], 1)
            self.assertEqual(info.config.metadata["dense_dimension"], 768)
            self.assertEqual(info.config.metadata["bm25_tokenizer"], "multilingual")
        self.assertEqual(
            str(
                self.index.client.get_collection("course_content")
                .payload_schema["requires_java_reauthorization"]
                .data_type
            ),
            "bool",
        )
        scope = models.Filter(
            must=[
                models.FieldCondition(
                    key="namespace",
                    match=models.MatchValue(value=self.settings.source_namespace),
                )
            ]
        )
        questions, _ = self.index.client.scroll(
            "math_questions", scroll_filter=scope, limit=50, with_payload=True, with_vectors=False
        )
        self.assertGreater(len(questions), 0)
        forbidden = {"answers", "answer", "analysis", "articleMarkdown", "signedUrl", "url"}
        self.assertTrue(all(not (forbidden & set(point.payload or {})) for point in questions))
        self.assertTrue(
            all((point.payload or {}).get("visibility") == "PUBLIC_SYSTEM" for point in questions)
        )
        self.assertEqual(self.index.namespace_count("student_memories"), 0)

    def test_real_hybrid_stages_thresholds_and_no_hit(self):
        service = HybridSearchService(
            self.settings, self.index, self.encoder, self.reranker
        )
        positive = service.search(
            RetrievalRequest("math_knowledge", "勾股定理", "EXPLAIN_CONCEPT", 8)
        )
        self.assertGreater(positive.trace.dense_count, 0)
        self.assertGreater(positive.trace.bm25_count, 0)
        self.assertGreater(positive.trace.fused_count, 0)
        self.assertGreater(positive.trace.reranked_count, 0)
        self.assertGreaterEqual(len(positive.hits), 3)
        self.assertTrue(any(hit.dense_rank and hit.bm25_rank for hit in positive.hits))

        latex = service.search(
            RetrievalRequest("math_questions", r"解方程 $x^2-5x+6=0$", "SOLVE_PROBLEM", 8)
        )
        self.assertGreater(latex.trace.dense_count, 0)
        self.assertGreater(latex.trace.bm25_count, 0)
        self.assertGreater(latex.before_threshold_count, len(latex.hits))

        near_wrong = service.search(
            RetrievalRequest(
                "math_questions", "一元二次方程求根公式，不是一次方程", "SOLVE_PROBLEM", 8
            )
        )
        self.assertFalse(
            any(
                "一元一次方程" in hit.summary and "一元二次方程" not in hit.summary
                for hit in near_wrong.hits
            )
        )

        no_hit = service.search(
            RetrievalRequest("math_questions", "今天午饭吃什么", "SOLVE_PROBLEM", 8)
        )
        self.assertEqual(no_hit.before_threshold_count, self.settings.rerank_candidate_k)
        self.assertEqual(no_hit.hits, [])

    def test_course_and_memory_filters_never_fall_back_globally(self):
        temporary_namespace = f"ccnu-edulion-t13.2-acceptance-{uuid4()}"
        temporary = QdrantIndex(
            self.settings.qdrant_url,
            temporary_namespace,
            self.settings.bge_model_version,
            self.settings.qdrant_timeout_seconds,
        )
        course_id, version_id, lesson_id = str(uuid4()), "acceptance-v1", str(uuid4())
        document = IndexDocument(
            "course_content",
            "COURSE_TEXT",
            lesson_id,
            1,
            "验收课程",
            "勾股定理中直角三角形两直角边平方和等于斜边平方。",
            {
                "course_id": course_id,
                "course_version_id": version_id,
                "lesson_id": lesson_id,
                "access_type": "PAID",
                "requires_java_reauthorization": True,
                "visibility": "COURSE_AUTHORIZED",
            },
        )
        try:
            temporary.replace_entity(
                [document], self.encoder.encode_documents([document.text], 1)
            )
            allowed = RetrievalRequest(
                "course_content", "勾股定理", "COURSE_QUESTION", 8,
                course_id=course_id, course_version_id=version_id, lesson_ids=[lesson_id],
            )
            denied_version = replace(allowed, course_version_id="archived-v0")
            self.assertGreater(len(temporary.search_bm25(allowed, 8)), 0)
            self.assertEqual(temporary.search_bm25(denied_version, 8), [])
            with self.assertRaises(PermissionError):
                RetrievalRequest("course_content", "勾股定理", "COURSE_QUESTION").validate()
            with self.assertRaises(PermissionError):
                RetrievalRequest("student_memories", "我的错题", "REVIEW").validate()
        finally:
            temporary.cleanup_namespace("course_content")
        self.assertEqual(temporary.namespace_count("course_content"), 0)

    def test_neo4j_bounded_query_and_closed_driver_degradation(self):
        from qdrant_client import models

        graph = Neo4jKnowledgeGraph(
            self.settings.neo4j_uri,
            self.settings.neo4j_user,
            self.settings.neo4j_password or "",
            self.settings.source_namespace,
            self.settings.graph_timeout_seconds,
        )
        try:
            points, _ = self.index.client.scroll(
                "math_knowledge",
                scroll_filter=models.Filter(
                    must=[
                        models.FieldCondition(
                            key="namespace",
                            match=models.MatchValue(value=self.settings.source_namespace),
                        )
                    ]
                ),
                limit=200,
                with_payload=True,
                with_vectors=False,
            )
            seed = next(
                (
                    str(point.payload["entity_id"])
                    for point in points
                    if (point.payload or {}).get("prerequisite_ids")
                ),
                None,
            )
            self.assertIsNotNone(seed)
            result = graph.related([seed], "PREREQUISITES", 3, 50)
            self.assertFalse(result.degraded)
            self.assertGreater(len(result.entity_ids), 0)
            self.assertLessEqual(len(result.entity_ids), 50)
        finally:
            graph.close()
        degraded = graph.related([], "PREREQUISITES", 3, 50)
        self.assertTrue(degraded.degraded)

    def test_neo4j_temporary_namespace_is_cleaned_exactly(self):
        namespace = f"ccnu-edulion-t13.2-acceptance-{uuid4()}"
        graph = Neo4jKnowledgeGraph(
            self.settings.neo4j_uri,
            self.settings.neo4j_user,
            self.settings.neo4j_password or "",
            namespace,
            self.settings.graph_timeout_seconds,
        )
        try:
            graph.replace_knowledge(str(uuid4()), 1, "ACCEPTANCE", "验收节点", [str(uuid4())])
            self.assertEqual(graph.namespace_counts(), (2, 1))
            self.assertEqual(graph.cleanup_namespace(), 2)
            self.assertEqual(graph.namespace_counts(), (0, 0))
        finally:
            graph.cleanup_namespace()
            graph.close()

    def test_rabbit_confirm_retry_dlq_redelivery_and_exact_cleanup(self):
        import pika

        suffix = uuid4().hex
        defaults = pika.ConnectionParameters().credentials
        settings = replace(
            self.settings,
            rabbitmq_username=defaults.username,
            rabbitmq_password=defaults.password,
            rabbitmq_retry_delay_ms=100,
            rabbitmq_max_attempts=3,
        )

        class Processor:
            def __init__(self):
                self.events = []

            def process_event(self, event):
                self.events.append(event.event_id)
                return SimpleNamespace(outcome="APPLIED_DELETE")

        processor = Processor()
        event_exchange = f"edu.agent.index.acceptance.events.{suffix}"
        retry_exchange = f"edu.agent.index.acceptance.retry.{suffix}"
        dead_exchange = f"edu.agent.index.acceptance.dlx.{suffix}"
        work_queue = f"edu.agent.index.acceptance.worker.{suffix}"
        dead_queue = f"edu.agent.index.acceptance.dlq.{suffix}"
        consumer = RabbitIndexConsumer(
            settings,
            processor,
            event_exchange=event_exchange,
            retry_exchange=retry_exchange,
            dead_exchange=dead_exchange,
            work_queue=work_queue,
            dead_queue=dead_queue,
        )
        connection = consumer._connection()
        try:
            channel = connection.channel()
            consumer._declare(channel)
            channel.basic_publish(
                event_exchange,
                ROUTING_KEYS[1],
                _delete_event(),
                pika.BasicProperties(delivery_mode=2),
                mandatory=True,
            )
            self.assertTrue(consumer.consume_once(2))
            self.assertEqual(len(processor.events), 1)

            channel.basic_publish(
                event_exchange,
                ROUTING_KEYS[1],
                b"invalid-json",
                pika.BasicProperties(delivery_mode=2),
                mandatory=True,
            )
            for _ in range(3):
                time.sleep(0.2)
                self.assertTrue(consumer.consume_once(2))
            method, properties, _ = channel.basic_get(dead_queue, auto_ack=False)
            self.assertIsNotNone(method)
            self.assertEqual(properties.headers["x-agent-index-attempt"], 3)
            channel.basic_ack(method.delivery_tag)
        finally:
            if connection.is_open:
                cleanup = connection.channel()
                for routing_key in ROUTING_KEYS:
                    cleanup.queue_delete(f"{work_queue}.retry.{routing_key.split('.')[2]}")
                cleanup.queue_delete(work_queue)
                cleanup.queue_delete(dead_queue)
                cleanup.exchange_delete(event_exchange)
                cleanup.exchange_delete(retry_exchange)
                cleanup.exchange_delete(dead_exchange)
                connection.close()


if __name__ == "__main__":
    unittest.main()
