"""通过 T14.1 内部 API 获取 Java 安全索引投影，不访问 Java MySQL。"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any, Iterable, Mapping
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

from .models import IndexDocument, IndexEvent


SOURCE_PATHS = {
    "QUESTION": "/internal/v1/agent-index/questions",
    "KNOWLEDGE_POINT": "/internal/v1/agent-index/knowledge-points",
    "COURSE_TEXT": "/internal/v1/agent-index/course-texts",
    "STUDENT_MEMORY": "/internal/v1/agent-index/student-memories",
}


@dataclass(frozen=True)
class ProjectionPage:
    """Java 稳定分页的一页结果和下一游标。"""

    documents: list[list[IndexDocument]]
    source_items: list[Mapping[str, Any]]
    has_more: bool
    next_revision: int
    next_entity_id: str


class JavaProjectionClient:
    """固定调用方身份和专用令牌的 Java 事实源客户端。"""

    def __init__(
        self, learning_url: str, course_url: str, token: str, timeout: float,
        max_attempts: int = 3, agent_access_url: str | None = None
    ):
        if len(token) < 32:
            raise ValueError("AGENT_INDEX_SERVICE_TOKEN 缺失或长度不足")
        self.learning_url = learning_url.rstrip("/")
        self.course_url = course_url.rstrip("/")
        self.agent_access_url = (agent_access_url or learning_url).rstrip("/")
        self.token = token
        self.timeout = timeout
        self.max_attempts = max_attempts

    def _request(self, entity_type: str, query: Mapping[str, Any]) -> Mapping[str, Any]:
        """执行一次有界 GET；错误不拼接响应正文或令牌，交由 Worker 重试。"""
        path = SOURCE_PATHS[entity_type]
        base = (
            self.course_url if entity_type == "COURSE_TEXT"
            else self.agent_access_url if entity_type == "STUDENT_MEMORY"
            else self.learning_url
        )
        request = Request(
            f"{base}{path}?{urlencode(query)}",
            headers={
                "Accept": "application/json",
                "X-Internal-Caller": "agent-index-worker",
                "X-Internal-Service-Token": self.token,
            },
            method="GET",
        )
        raw = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                with urlopen(request, timeout=self.timeout) as response:
                    if response.status != 200:
                        raise RuntimeError(f"Java 索引源返回 HTTP {response.status}")
                    raw = response.read(2_000_001)
                break
            except HTTPError as exc:
                exc.close()
                if exc.code < 500 or attempt >= self.max_attempts:
                    raise RuntimeError(f"Java 索引源返回 HTTP {exc.code}") from exc
            except (URLError, TimeoutError, OSError) as exc:
                if attempt >= self.max_attempts:
                    raise RuntimeError("Java 索引源连接失败") from exc
            time.sleep(0.25 * attempt)
        if raw is None:
            raise RuntimeError("Java 索引源有限重试耗尽")
        if len(raw) > 2_000_000:
            raise RuntimeError("Java 索引源响应超过 2MB 上限")
        payload = json.loads(raw.decode("utf-8"))
        if not isinstance(payload, Mapping):
            raise RuntimeError("Java 索引源响应不是对象")
        return payload

    def fetch_entity(self, event: IndexEvent) -> list[IndexDocument]:
        """按事件稳定 ID 获取最新安全投影；不存在时返回空，由调用方写 tombstone。"""
        page = self._request(event.entity_type, {"entityId": event.entity_id, "size": 1})
        items = page.get("items")
        if not isinstance(items, list) or len(items) > 1:
            raise RuntimeError("Java 单实体索引投影响应不合法")
        if not items:
            return []
        documents = projection_to_documents(event.entity_type, items[0])
        if documents[0].source_revision < event.source_revision:
            raise RuntimeError("Java 投影 revision 落后于事件，等待事实源收敛")
        return documents

    def fetch(self, event: IndexEvent) -> list[IndexDocument]:
        """实现生产 MemoryProjectionProvider 端口，并复用相同 HTTP 校验与有限重试。"""
        if event.entity_type != "STUDENT_MEMORY":
            raise ValueError("记忆投影端口只接受 STUDENT_MEMORY")
        return self.fetch_entity(event)

    def page(
        self, entity_type: str, after_revision: int, after_entity_id: str, size: int
    ) -> ProjectionPage:
        """读取最多 200 项稳定页并转换；下一页游标完全采用 Java 响应。"""
        if entity_type not in SOURCE_PATHS:
            raise ValueError("不支持的 Java 索引源类型")
        if not 1 <= size <= 200:
            raise ValueError("Java 索引源单页上限为 200")
        raw = self._request(
            entity_type,
            {"afterRevision": after_revision, "afterEntityId": after_entity_id, "size": size},
        )
        items = raw.get("items")
        if not isinstance(items, list) or len(items) > size:
            raise RuntimeError("Java 索引源 items 超过请求上限")
        has_more = raw.get("hasMore")
        if not isinstance(has_more, bool):
            raise RuntimeError("Java 索引源 hasMore 不合法")
        cursor = raw.get("nextCursor")
        if items and not isinstance(cursor, Mapping):
            raise RuntimeError("非空分页缺少 nextCursor")
        next_revision = int(cursor["sourceRevision"]) if cursor else after_revision
        next_entity_id = str(cursor["entityId"]) if cursor else after_entity_id
        return ProjectionPage(
            documents=[projection_to_documents(entity_type, item) for item in items],
            source_items=items,
            has_more=has_more,
            next_revision=next_revision,
            next_entity_id=next_entity_id,
        )


def _require_fields(item: Mapping[str, Any], allowed: set[str], required: set[str]) -> None:
    unknown = set(item) - allowed
    missing = required - set(item)
    if unknown or missing:
        raise RuntimeError("Java 安全投影字段与冻结契约不一致")


def _chunks(text: str, limit: int = 1600, overlap: int = 160) -> Iterable[str]:
    """按段落生成有界课程文本块；只在内存处理，不读原始 JSON 或媒体。"""
    normalized = text.strip()
    start = 0
    while start < len(normalized):
        end = min(start + limit, len(normalized))
        if end < len(normalized):
            boundary = normalized.rfind("\n", start + limit // 2, end)
            if boundary > start:
                end = boundary
        chunk = normalized[start:end].strip()
        if chunk:
            yield chunk
        if end >= len(normalized):
            break
        start = max(end - overlap, start + 1)


def projection_to_documents(entity_type: str, item: Mapping[str, Any]) -> list[IndexDocument]:
    """把四类冻结 Java DTO 转为 Qdrant 文档，并再次执行安全字段白名单校验。"""
    if not isinstance(item, Mapping):
        raise RuntimeError("Java 索引投影条目不是对象")
    common_required = {"entityId", "sourceRevision", "educationStage", "subject"}
    if item.get("educationStage") != "JUNIOR" or item.get("subject") != "MATH":
        raise PermissionError("只允许初中数学安全投影")
    if entity_type == "QUESTION":
        allowed = common_required | {
            "questionVersionId", "grade", "questionType", "stem", "options", "knowledgePointIds"
        }
        required = allowed
        _require_fields(item, allowed, required)
        text = str(item["stem"]).strip()
        options = item.get("options") or []
        if options:
            text += "\n" + "\n".join(str(option) for option in options)
        payload = {
            "question_version_id": str(item["questionVersionId"]),
            "grade": str(item["grade"]),
            "question_type": str(item["questionType"]),
            "knowledge_point_ids": [str(value) for value in item.get("knowledgePointIds", [])],
            "visibility": "PUBLIC_SYSTEM",
        }
        collection, title = "math_questions", "公共数学题"
    elif entity_type == "KNOWLEDGE_POINT":
        allowed = common_required | {"code", "name", "prerequisiteIds"}
        _require_fields(item, allowed, allowed)
        title = str(item["name"])
        text = f"{title}\n知识点编码：{item['code']}"
        payload = {
            "code": str(item["code"]),
            "prerequisite_ids": [str(value) for value in item.get("prerequisiteIds", [])],
            "visibility": "PUBLIC_MATH_KNOWLEDGE",
        }
        collection = "math_knowledge"
    elif entity_type == "COURSE_TEXT":
        allowed = common_required | {
            "courseId", "courseVersionId", "lessonId", "title", "articleMarkdown", "accessType"
        }
        _require_fields(item, allowed, allowed)
        title = str(item["title"])
        payload = {
            "course_id": str(item["courseId"]),
            "course_version_id": str(item["courseVersionId"]),
            "lesson_id": str(item["lessonId"]),
            "access_type": str(item["accessType"]),
            "requires_java_reauthorization": True,
            "visibility": "COURSE_AUTHORIZED",
        }
        collection = "course_content"
        chunks = list(_chunks(str(item["articleMarkdown"])))
        if not chunks:
            raise RuntimeError("课程 ARTICLE 正文为空")
        return [
            IndexDocument(
                collection=collection,
                entity_type=entity_type,
                entity_id=str(item["entityId"]),
                source_revision=int(item["sourceRevision"]),
                title=title,
                text=chunk,
                payload={**payload, "chunk_index": index, "chunk_count": len(chunks)},
            )
            for index, chunk in enumerate(chunks)
        ]
    elif entity_type == "STUDENT_MEMORY":
        allowed = common_required | {
            "subjectUserId", "memoryType", "text", "visibility", "accessScope",
            "courseId", "courseVersionId", "lessonId", "requiresCurrentCourseAccess", "expiresAt",
        }
        required = common_required | {
            "subjectUserId", "memoryType", "text", "visibility", "accessScope",
            "requiresCurrentCourseAccess",
        }
        _require_fields(item, allowed, required)
        if item.get("visibility") != "USER_PRIVATE" or item.get("accessScope") != "USER_PRIVATE":
            raise PermissionError("学生记忆必须是 USER_PRIVATE 投影")
        text = str(item["text"]).strip()
        title = str(item["memoryType"])
        payload = {
            "user_id": str(item["subjectUserId"]),
            "memory_type": str(item["memoryType"]),
            "visibility": "USER_PRIVATE",
            "requires_java_reauthorization": bool(item["requiresCurrentCourseAccess"]),
        }
        if item.get("courseId"):
            payload.update({
                "course_id": str(item["courseId"]),
                "course_version_id": str(item.get("courseVersionId") or ""),
                "lesson_id": str(item.get("lessonId") or ""),
            })
        collection = "student_memories"
    else:
        raise ValueError("不支持的 Java 索引投影类型")
    if not text:
        raise RuntimeError("可索引文本为空")
    return [
        IndexDocument(
            collection=collection,
            entity_type=entity_type,
            entity_id=str(item["entityId"]),
            source_revision=int(item["sourceRevision"]),
            title=title,
            text=text,
            payload=payload,
        )
    ]
