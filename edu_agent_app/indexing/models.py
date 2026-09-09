"""定义索引事件、安全投影和检索结果的内部类型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Mapping, Sequence
from uuid import UUID


class Operation(str, Enum):
    """派生索引只允许覆盖写和 tombstone 删除两种状态变化。"""

    UPSERT = "UPSERT"
    DELETE = "DELETE"


EVENT_TO_ENTITY = {
    "QuestionIndexProjectionChanged": "QUESTION",
    "KnowledgePointIndexProjectionChanged": "KNOWLEDGE_POINT",
    "CourseTextIndexProjectionChanged": "COURSE_TEXT",
    "StudentMemoryIndexProjectionChanged": "STUDENT_MEMORY",
}

ENTITY_TO_COLLECTION = {
    "QUESTION": "math_questions",
    "KNOWLEDGE_POINT": "math_knowledge",
    "COURSE_TEXT": "course_content",
    "STUDENT_MEMORY": "student_memories",
}

EVENT_PAYLOAD_RULES = {
    "QUESTION": {
        "required": {
            "sourceDomain", "entityType", "entityId", "questionVersionId", "sourceRevision",
            "operation", "sourceState", "educationStage", "visibility", "authorization",
        },
        "optional": {"grade"},
        "constants": {
            "sourceDomain": "LEARNING", "educationStage": "JUNIOR",
            "visibility": "PUBLIC_SYSTEM",
        },
        "authorization": {
            "questionScope": "ACTIVE_SYSTEM_ONLY",
            "projectionApi": "LEARNING_QUESTION_SAFE_PROJECTION",
        },
        "uuid_fields": {"entityId", "questionVersionId"},
        "upsert_state": "ACTIVE",
        "delete_states": {"ARCHIVED", "DELETED"},
    },
    "KNOWLEDGE_POINT": {
        "required": {
            "sourceDomain", "entityType", "entityId", "sourceRevision", "operation",
            "sourceState", "educationStage", "visibility", "authorization",
        },
        "optional": set(),
        "constants": {
            "sourceDomain": "LEARNING", "educationStage": "JUNIOR",
            "visibility": "PUBLIC_MATH_KNOWLEDGE",
        },
        "authorization": {
            "enabledOnly": True,
            "projectionApi": "LEARNING_KNOWLEDGE_SAFE_PROJECTION",
            "graphProjection": "BOUNDED_PREREQUISITE_RELATIONS",
        },
        "uuid_fields": {"entityId"},
        "upsert_state": "ACTIVE",
        "delete_states": {"ARCHIVED", "DELETED"},
    },
    "COURSE_TEXT": {
        "required": {
            "sourceDomain", "entityType", "entityId", "courseId", "courseVersionId", "lessonId",
            "sourceRevision", "operation", "sourceState", "educationStage", "visibility",
            "authorization",
        },
        "optional": set(),
        "constants": {
            "sourceDomain": "COURSE", "educationStage": "JUNIOR",
            "visibility": "COURSE_AUTHORIZED",
        },
        "authorization": {
            "requiresCurrentCourseAccess": True,
            "projectionApi": "COURSE_AUTHORIZED_TEXT_PROJECTION",
        },
        "uuid_fields": {"entityId", "courseId", "lessonId"},
        "upsert_state": "PUBLISHED",
        "delete_states": {"ARCHIVED", "DELETED"},
    },
    "STUDENT_MEMORY": {
        "required": {
            "sourceDomain", "entityType", "entityId", "subjectUserId", "sourceRevision",
            "operation", "sourceState", "educationStage", "visibility", "authorization",
        },
        "optional": set(),
        "constants": {
            "sourceDomain": "AGENT_ACCESS", "educationStage": "JUNIOR",
            "visibility": "USER_PRIVATE",
        },
        "authorization": {
            "ownerScope": "SUBJECT_USER_ONLY",
            "projectionApi": "AGENT_ACCESS_MEMORY_SAFE_PROJECTION",
        },
        "uuid_fields": {"entityId", "subjectUserId"},
        "upsert_state": "ACTIVE",
        "delete_states": {"EXPIRED", "DELETED"},
    },
}


def _uuid(value: Any, name: str) -> str:
    try:
        return str(UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ValueError(f"{name} 必须是 UUID") from exc


@dataclass(frozen=True)
class IndexEvent:
    """RabbitMQ v1 索引通知。

    输入仅接受事件信封中的稳定 ID、revision 与最小授权元数据；正文、答案、解析、课程内容
    或签名 URL 一旦出现在消息中会被拒绝，失败由 Worker 重试/DLQ 处理且不会 ACK 成功。
    """

    event_id: str
    event_type: str
    aggregate_id: str
    trace_id: str
    entity_type: str
    entity_id: str
    source_revision: int
    operation: Operation
    payload: Mapping[str, Any]

    @classmethod
    def from_mapping(cls, raw: Mapping[str, Any]) -> "IndexEvent":
        """严格解析 v1 信封并拒绝可能复制敏感正文的字段。"""
        expected_envelope = {
            "eventId", "eventType", "eventVersion", "aggregateId", "occurredAt", "traceId", "payload"
        }
        if set(raw) != expected_envelope:
            raise ValueError("索引事件信封字段不符合 v1 契约")
        if raw.get("eventVersion") != 1:
            raise ValueError("只支持索引事件 v1")
        event_type = str(raw.get("eventType", ""))
        expected_entity = EVENT_TO_ENTITY.get(event_type)
        if expected_entity is None:
            raise ValueError("不支持的索引事件类型")
        payload = raw.get("payload")
        if not isinstance(payload, Mapping):
            raise ValueError("payload 必须是对象")
        entity_type = str(payload.get("entityType", ""))
        if entity_type != expected_entity:
            raise ValueError("eventType 与 entityType 不一致")
        forbidden = {
            "stem", "answers", "answer", "analysis", "articleMarkdown", "content",
            "signedUrl", "url", "token", "password", "userName", "email", "phone",
        }
        if forbidden.intersection(payload):
            raise ValueError("RabbitMQ 消息包含禁止复制的正文、答案、URL 或秘密字段")
        rule = EVENT_PAYLOAD_RULES[entity_type]
        allowed = rule["required"] | rule["optional"]
        if not rule["required"].issubset(payload) or set(payload) - allowed:
            raise ValueError("索引事件 payload 字段不符合冻结 v1 契约")
        if any(payload.get(name) != value for name, value in rule["constants"].items()):
            raise ValueError("索引事件来源、学段或可见性不符合安全契约")
        if payload.get("authorization") != rule["authorization"]:
            raise ValueError("索引事件授权元数据不符合安全契约")
        for uuid_field in rule["uuid_fields"]:
            _uuid(payload.get(uuid_field), f"payload.{uuid_field}")
        revision = payload.get("sourceRevision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            raise ValueError("sourceRevision 必须是正整数")
        try:
            operation = Operation(str(payload.get("operation")))
        except ValueError as exc:
            raise ValueError("operation 必须是 UPSERT 或 DELETE") from exc
        state = payload.get("sourceState")
        if operation is Operation.UPSERT and state != rule["upsert_state"]:
            raise ValueError("UPSERT sourceState 不符合安全契约")
        if operation is Operation.DELETE and state not in rule["delete_states"]:
            raise ValueError("DELETE sourceState 不符合安全契约")
        if entity_type == "COURSE_TEXT" and not 1 <= len(str(payload.get("courseVersionId", ""))) <= 64:
            raise ValueError("courseVersionId 长度不合法")
        if entity_type == "QUESTION" and "grade" in payload and payload["grade"] not in {
            "GRADE_7", "GRADE_8", "GRADE_9"
        }:
            raise ValueError("grade 不符合索引事件契约")
        trace_id = str(raw.get("traceId", ""))
        if not 8 <= len(trace_id) <= 64:
            raise ValueError("traceId 长度不合法")
        try:
            occurred_at = datetime.fromisoformat(str(raw.get("occurredAt", "")).replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError("occurredAt 必须是带时区的 ISO-8601 时间") from exc
        if occurred_at.tzinfo is None:
            raise ValueError("occurredAt 必须包含时区")
        aggregate_id = _uuid(raw.get("aggregateId"), "aggregateId")
        expected_aggregate = payload.get("courseId") if entity_type == "COURSE_TEXT" else payload.get("entityId")
        if aggregate_id != _uuid(expected_aggregate, "payload aggregate reference"):
            raise ValueError("aggregateId 与索引实体所属聚合不一致")
        return cls(
            event_id=_uuid(raw.get("eventId"), "eventId"),
            event_type=event_type,
            aggregate_id=aggregate_id,
            trace_id=trace_id,
            entity_type=entity_type,
            entity_id=_uuid(payload.get("entityId"), "payload.entityId"),
            source_revision=revision,
            operation=operation,
            payload=dict(payload),
        )

    @property
    def collection(self) -> str:
        """返回事件唯一允许写入的 collection。"""
        return ENTITY_TO_COLLECTION[self.entity_type]


@dataclass(frozen=True)
class IndexDocument:
    """从 Java 安全投影转换后的单个可索引文档。"""

    collection: str
    entity_type: str
    entity_id: str
    source_revision: int
    title: str
    text: str
    payload: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RetrievalRequest:
    """混合检索请求及服务端可信权限范围。

    课程检索必须带 Java 已授权的精确课程版本；学生记忆必须带全局 userId。缺失范围直接
    拒绝，不能退化为全 collection 搜索。公共题和知识点无需用户范围。
    """

    collection: str
    query: str
    intent: str
    limit: int = 8
    course_id: str | None = None
    course_version_id: str | None = None
    lesson_ids: Sequence[str] = ()
    user_id: str | None = None

    def validate(self) -> None:
        if self.collection not in ENTITY_TO_COLLECTION.values():
            raise ValueError("不支持的检索集合")
        if not self.query.strip() or len(self.query) > 2000:
            raise ValueError("query 必须为 1..2000 个字符")
        if not 1 <= self.limit <= 8:
            raise ValueError("limit 必须位于 1..8")
        if self.collection == "course_content":
            if not self.course_id or not self.course_version_id:
                raise PermissionError("课程检索缺少 Java 当前授权范围")
            _uuid(self.course_id, "courseId")
            for lesson_id in self.lesson_ids:
                _uuid(lesson_id, "lessonId")
        if self.collection == "student_memories":
            if not self.user_id:
                raise PermissionError("记忆检索缺少全局 userId")
            _uuid(self.user_id, "userId")


@dataclass(frozen=True)
class RetrievalHit:
    """通过 RRF、语义重排和阈值过滤后的安全候选及阶段分数。"""

    entity_id: str
    title: str
    summary: str
    source_revision: int
    dense_rank: int | None
    bm25_rank: int | None
    rrf_score: float
    rerank_score: float
    payload: Mapping[str, Any]


@dataclass(frozen=True)
class RetrievalTrace:
    """不含正文的检索阶段计数和耗时，供测试与安全审计。"""

    dense_count: int
    bm25_count: int
    fused_count: int
    reranked_count: int
    accepted_count: int
    dense_ms: float
    bm25_ms: float
    rerank_ms: float
    total_ms: float
