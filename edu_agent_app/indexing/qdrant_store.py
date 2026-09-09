"""Qdrant 四集合、版本隔离、真实服务端 BM25 与安全 payload 过滤。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence
from uuid import NAMESPACE_URL, uuid5

from .models import IndexDocument, RetrievalRequest


COLLECTIONS = ("math_questions", "math_knowledge", "course_content", "student_memories")
SCHEMA_VERSION = 1
DENSE_VECTOR = "dense"
BM25_VECTOR = "bm25"
BM25_OPTIONS = {
    "tokenizer": "multilingual",
    "stemmer": {"type": "none"},
    "stopwords": {},
    "lowercase": True,
}


class RebuildRequired(RuntimeError):
    """现有集合版本或向量模型不兼容；必须显式重建，禁止混写。"""


@dataclass(frozen=True)
class RawCandidate:
    """Qdrant 单路召回候选，正文只在本进程传给本地 reranker。"""

    point_id: str
    score: float
    payload: Mapping[str, Any]


class QdrantIndex:
    """管理 Worker 自有命名空间内的四个派生索引集合。"""

    def __init__(self, url: str, namespace: str, model_version: str, timeout: float = 10.0):
        from qdrant_client import QdrantClient

        self.namespace = namespace
        self.model_version = model_version
        self.client = QdrantClient(url=url, timeout=timeout, prefer_grpc=False)

    def _metadata(self) -> dict[str, Any]:
        return {
            "owner": "edu_agent_app-index-worker",
            "schema_version": SCHEMA_VERSION,
            "dense_model_version": self.model_version,
            "dense_dimension": 768,
            "bm25_model": "qdrant/bm25",
            "bm25_tokenizer": "multilingual",
            "bm25_language_processing": "disabled",
        }

    def ensure_collections(self) -> None:
        """幂等创建四集合和 payload 索引；发现模型/Schema 漂移即要求显式重建。"""
        from qdrant_client import models

        existing = {item.name for item in self.client.get_collections().collections}
        for collection in COLLECTIONS:
            if collection not in existing:
                self.client.create_collection(
                    collection_name=collection,
                    vectors_config={
                        DENSE_VECTOR: models.VectorParams(size=768, distance=models.Distance.COSINE)
                    },
                    sparse_vectors_config={
                        BM25_VECTOR: models.SparseVectorParams(modifier=models.Modifier.IDF)
                    },
                    on_disk_payload=True,
                    metadata=self._metadata(),
                )
            else:
                info = self.client.get_collection(collection)
                metadata = getattr(info.config, "metadata", None) or {}
                if dict(metadata) != self._metadata():
                    raise RebuildRequired(
                        f"{collection} 的 Schema/模型版本不一致；请显式重建，禁止混写向量"
                    )
                dense = info.config.params.vectors.get(DENSE_VECTOR)
                sparse = info.config.params.sparse_vectors or {}
                if dense is None or dense.size != 768 or BM25_VECTOR not in sparse:
                    raise RebuildRequired(f"{collection} 的 dense/BM25 配置不兼容")
            payload_indexes = {
                "math_questions": {
                    "namespace": models.PayloadSchemaType.KEYWORD,
                    "entity_id": models.PayloadSchemaType.KEYWORD,
                    "entity_type": models.PayloadSchemaType.KEYWORD,
                    "record_type": models.PayloadSchemaType.KEYWORD,
                    "model_version": models.PayloadSchemaType.KEYWORD,
                    "education_stage": models.PayloadSchemaType.KEYWORD,
                    "subject": models.PayloadSchemaType.KEYWORD,
                    "visibility": models.PayloadSchemaType.KEYWORD,
                    "source_revision": models.PayloadSchemaType.INTEGER,
                },
                "math_knowledge": {
                    "namespace": models.PayloadSchemaType.KEYWORD,
                    "entity_id": models.PayloadSchemaType.KEYWORD,
                    "entity_type": models.PayloadSchemaType.KEYWORD,
                    "record_type": models.PayloadSchemaType.KEYWORD,
                    "model_version": models.PayloadSchemaType.KEYWORD,
                    "education_stage": models.PayloadSchemaType.KEYWORD,
                    "subject": models.PayloadSchemaType.KEYWORD,
                    "source_revision": models.PayloadSchemaType.INTEGER,
                },
                "course_content": {
                    "namespace": models.PayloadSchemaType.KEYWORD,
                    "entity_id": models.PayloadSchemaType.KEYWORD,
                    "entity_type": models.PayloadSchemaType.KEYWORD,
                    "record_type": models.PayloadSchemaType.KEYWORD,
                    "model_version": models.PayloadSchemaType.KEYWORD,
                    "education_stage": models.PayloadSchemaType.KEYWORD,
                    "subject": models.PayloadSchemaType.KEYWORD,
                    "course_id": models.PayloadSchemaType.KEYWORD,
                    "course_version_id": models.PayloadSchemaType.KEYWORD,
                    "lesson_id": models.PayloadSchemaType.KEYWORD,
                    "requires_java_reauthorization": models.PayloadSchemaType.BOOL,
                    "source_revision": models.PayloadSchemaType.INTEGER,
                },
                "student_memories": {
                    "namespace": models.PayloadSchemaType.KEYWORD,
                    "entity_id": models.PayloadSchemaType.KEYWORD,
                    "entity_type": models.PayloadSchemaType.KEYWORD,
                    "record_type": models.PayloadSchemaType.KEYWORD,
                    "model_version": models.PayloadSchemaType.KEYWORD,
                    "education_stage": models.PayloadSchemaType.KEYWORD,
                    "subject": models.PayloadSchemaType.KEYWORD,
                    "user_id": models.PayloadSchemaType.KEYWORD,
                    "source_revision": models.PayloadSchemaType.INTEGER,
                },
            }[collection]
            current_schema = self.client.get_collection(collection).payload_schema
            for field_name, field_schema in payload_indexes.items():
                current = current_schema.get(field_name)
                current_type = str(getattr(current, "data_type", "")).lower()
                if current is not None and current_type == str(field_schema.value).lower():
                    continue
                if current is not None and current_type != str(field_schema.value).lower():
                    # 只校正本 Worker 元数据已验证集合中的同名索引，不触碰 payload 或 collection。
                    self.client.delete_payload_index(collection, field_name, wait=True)
                self.client.create_payload_index(
                    collection, field_name, field_schema=field_schema, wait=False
                )

    def _entity_filter(self, entity_id: str):
        from qdrant_client import models

        return models.Filter(
            must=[
                models.FieldCondition(key="namespace", match=models.MatchValue(value=self.namespace)),
                models.FieldCondition(key="entity_id", match=models.MatchValue(value=entity_id)),
            ]
        )

    def replace_entity(
        self, documents: Sequence[IndexDocument], dense_vectors: Sequence[Sequence[float]]
    ) -> None:
        """精确替换一个实体的全部块；只删除当前 Worker 命名空间中的同一 entityId。"""
        from qdrant_client import models

        if not documents or len(documents) != len(dense_vectors):
            raise ValueError("文档和 dense 向量数量必须一致且非空")
        first = documents[0]
        if any(doc.collection != first.collection or doc.entity_id != first.entity_id for doc in documents):
            raise ValueError("一次替换只能包含同集合、同实体的文档块")
        self.client.delete(
            first.collection,
            points_selector=models.FilterSelector(filter=self._entity_filter(first.entity_id)),
            wait=True,
        )
        points = []
        for index, (document, vector) in enumerate(zip(documents, dense_vectors)):
            if len(vector) != 768:
                raise ValueError("dense 向量必须为 768 维")
            payload = {
                "record_type": "DOCUMENT",
                "namespace": self.namespace,
                "entity_type": document.entity_type,
                "entity_id": document.entity_id,
                "source_revision": document.source_revision,
                "model_version": self.model_version,
                "education_stage": "JUNIOR",
                "subject": "MATH",
                "title": document.title,
                "text": document.text,
                **dict(document.payload),
            }
            point_id = str(uuid5(NAMESPACE_URL, f"{self.namespace}:{document.collection}:{document.entity_id}:{index}"))
            points.append(
                models.PointStruct(
                    id=point_id,
                    vector={
                        DENSE_VECTOR: list(vector),
                        BM25_VECTOR: models.Document(
                            text=document.text, model="qdrant/bm25", options=BM25_OPTIONS
                        ),
                    },
                    payload=payload,
                )
            )
        self.client.upsert(first.collection, points=points, wait=True)

    def delete_entity(self, collection: str, entity_id: str) -> None:
        """按精确集合、namespace 和 entityId 删除派生点；不删除集合或来源不明数据。"""
        from qdrant_client import models

        if collection not in COLLECTIONS:
            raise ValueError("不支持的 collection")
        self.client.delete(
            collection,
            points_selector=models.FilterSelector(filter=self._entity_filter(entity_id)),
            wait=True,
        )

    def _query_filter(self, request: RetrievalRequest):
        from qdrant_client import models

        must = [
            models.FieldCondition(key="record_type", match=models.MatchValue(value="DOCUMENT")),
            models.FieldCondition(key="namespace", match=models.MatchValue(value=self.namespace)),
            models.FieldCondition(key="model_version", match=models.MatchValue(value=self.model_version)),
            models.FieldCondition(key="education_stage", match=models.MatchValue(value="JUNIOR")),
            models.FieldCondition(key="subject", match=models.MatchValue(value="MATH")),
        ]
        if request.collection == "math_questions":
            must.append(models.FieldCondition(key="visibility", match=models.MatchValue(value="PUBLIC_SYSTEM")))
        elif request.collection == "course_content":
            must.extend(
                [
                    models.FieldCondition(key="course_id", match=models.MatchValue(value=request.course_id)),
                    models.FieldCondition(
                        key="course_version_id", match=models.MatchValue(value=request.course_version_id)
                    ),
                    models.FieldCondition(
                        key="requires_java_reauthorization", match=models.MatchValue(value=True)
                    ),
                ]
            )
            if request.lesson_ids:
                must.append(
                    models.FieldCondition(
                        key="lesson_id", match=models.MatchAny(any=list(request.lesson_ids))
                    )
                )
        elif request.collection == "student_memories":
            must.append(models.FieldCondition(key="user_id", match=models.MatchValue(value=request.user_id)))
        return models.Filter(must=must)

    def search_dense(self, request: RetrievalRequest, vector: Sequence[float], limit: int) -> list[RawCandidate]:
        """执行 dense 召回；课程/记忆权限过滤在 Qdrant 查询内强制应用。"""
        response = self.client.query_points(
            request.collection,
            query=list(vector),
            using=DENSE_VECTOR,
            query_filter=self._query_filter(request),
            limit=limit,
            with_payload=True,
            with_vectors=False,
        )
        return [RawCandidate(str(point.id), float(point.score), point.payload or {}) for point in response.points]

    def search_bm25(self, request: RetrievalRequest, limit: int) -> list[RawCandidate]:
        """使用 Qdrant 1.19 服务端 BM25 与 multilingual tokenizer 排序，非字符串过滤。"""
        from qdrant_client import models

        response = self.client.query_points(
            request.collection,
            query=models.Document(text=request.query, model="qdrant/bm25", options=BM25_OPTIONS),
            using=BM25_VECTOR,
            query_filter=self._query_filter(request),
            limit=limit,
            with_payload=True,
            with_vectors=False,
        )
        return [RawCandidate(str(point.id), float(point.score), point.payload or {}) for point in response.points]

    def namespace_count(self, collection: str) -> int:
        """只统计 Worker 命名空间文档，用于对账；不枚举或删除其他来源点。"""
        from qdrant_client import models

        response = self.client.count(
            collection,
            count_filter=models.Filter(
                must=[
                    models.FieldCondition(key="namespace", match=models.MatchValue(value=self.namespace)),
                    models.FieldCondition(key="record_type", match=models.MatchValue(value="DOCUMENT")),
                ]
            ), exact=True
        )
        return int(response.count)

    def cleanup_namespace(self, collection: str) -> None:
        """显式清理当前 Worker namespace，绝不删除 collection 或其他命名空间数据。"""
        from qdrant_client import models

        self.client.delete(
            collection,
            points_selector=models.FilterSelector(
                filter=models.Filter(
                    must=[models.FieldCondition(key="namespace", match=models.MatchValue(value=self.namespace))]
                )
            ),
            wait=True,
        )
