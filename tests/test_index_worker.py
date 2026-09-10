import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

from edu_agent_app.indexing.models import IndexDocument, IndexEvent, RetrievalRequest
from edu_agent_app.indexing.source_client import ProjectionPage, projection_to_documents
from edu_agent_app.indexing.state_store import WorkerStateStore
from edu_agent_app.indexing.worker import IndexProcessor, RabbitIndexConsumer


def event(entity_type="QUESTION", operation="UPSERT", revision=1, event_id=None):
    event_types = {
        "QUESTION": "QuestionIndexProjectionChanged",
        "KNOWLEDGE_POINT": "KnowledgePointIndexProjectionChanged",
        "COURSE_TEXT": "CourseTextIndexProjectionChanged",
        "STUDENT_MEMORY": "StudentMemoryIndexProjectionChanged",
    }
    entity_id = str(uuid4())
    security = {
        "QUESTION": (
            "LEARNING", "PUBLIC_SYSTEM",
            {"questionScope": "ACTIVE_SYSTEM_ONLY", "projectionApi": "LEARNING_QUESTION_SAFE_PROJECTION"},
        ),
        "KNOWLEDGE_POINT": (
            "LEARNING", "PUBLIC_MATH_KNOWLEDGE",
            {
                "enabledOnly": True,
                "projectionApi": "LEARNING_KNOWLEDGE_SAFE_PROJECTION",
                "graphProjection": "BOUNDED_PREREQUISITE_RELATIONS",
            },
        ),
        "COURSE_TEXT": (
            "COURSE", "COURSE_AUTHORIZED",
            {"requiresCurrentCourseAccess": True, "projectionApi": "COURSE_AUTHORIZED_TEXT_PROJECTION"},
        ),
        "STUDENT_MEMORY": (
            "AGENT_ACCESS", "USER_PRIVATE",
            {"ownerScope": "SUBJECT_USER_ONLY", "projectionApi": "AGENT_ACCESS_MEMORY_SAFE_PROJECTION"},
        ),
    }
    source_domain, visibility, authorization = security[entity_type]
    payload = {
        "sourceDomain": source_domain,
        "entityType": entity_type,
        "entityId": entity_id,
        "sourceRevision": revision,
        "operation": operation,
        "sourceState": (
            "PUBLISHED" if entity_type == "COURSE_TEXT" and operation == "UPSERT"
            else "ACTIVE" if operation == "UPSERT" else "DELETED"
        ),
        "educationStage": "JUNIOR",
        "visibility": visibility,
        "authorization": authorization,
    }
    if entity_type == "QUESTION":
        payload["questionVersionId"] = str(uuid4())
        payload["grade"] = "GRADE_8"
    elif entity_type == "COURSE_TEXT":
        payload.update(courseId=str(uuid4()), courseVersionId="v1", lessonId=entity_id)
    elif entity_type == "STUDENT_MEMORY":
        payload["subjectUserId"] = str(uuid4())
    return IndexEvent.from_mapping(
        {
            "eventId": event_id or str(uuid4()),
            "eventType": event_types[entity_type],
            "eventVersion": 1,
            "aggregateId": payload.get("courseId", entity_id),
            "occurredAt": "2026-09-09T00:00:00Z",
            "traceId": "trace-test-001",
            "payload": payload,
        }
    )


class FakeEncoder:
    def encode_documents(self, texts, _batch_size):
        return [[float(len(text))] + [0.0] * 767 for text in texts]


class FakeIndex:
    def __init__(self):
        self.live = {}
        self.operations = []

    def replace_entity(self, documents, vectors):
        self.operations.append(("upsert", documents[0].entity_id, documents[0].source_revision))
        self.live[documents[0].entity_id] = (list(documents), list(vectors))

    def delete_entity(self, _collection, entity_id):
        self.operations.append(("delete", entity_id))
        self.live.pop(entity_id, None)

    def namespace_count(self, _collection):
        return len(self.live)


class FakeSource:
    def __init__(self):
        self.documents = {}

    def fetch_entity(self, item):
        return self.documents.get(item.entity_id, [])


class FakeRabbitChannel:
    """记录 ACK/NACK/发布动作，验证失败消息只有 confirm 成功后才 ACK。"""

    def __init__(self):
        self.acked = []
        self.nacked = []
        self.published = []

    def basic_ack(self, delivery_tag):
        self.acked.append(delivery_tag)

    def basic_nack(self, delivery_tag, requeue):
        self.nacked.append((delivery_tag, requeue))

    def basic_publish(self, **kwargs):
        self.published.append(kwargs)
        return None


class IndexWorkerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.state = WorkerStateStore(Path(self.temporary.name) / "worker.sqlite3")
        self.additional_states = []
        self.index = FakeIndex()
        self.source = FakeSource()
        settings = SimpleNamespace(embedding_batch_size=8)
        self.processor = IndexProcessor(settings, self.state, self.source, self.index, FakeEncoder())

    def tearDown(self):
        self.state.close()
        for state in self.additional_states:
            state.close()
        self.temporary.cleanup()

    def test_event_rejects_content_copied_into_rabbitmq(self):
        raw_event = event()
        raw = {
            "eventId": raw_event.event_id,
            "eventType": raw_event.event_type,
            "eventVersion": 1,
            "aggregateId": raw_event.aggregate_id,
            "occurredAt": "2026-09-09T00:00:00Z",
            "traceId": raw_event.trace_id,
            "payload": {**raw_event.payload, "stem": "不得进入消息的完整题干"},
        }
        with self.assertRaisesRegex(ValueError, "禁止复制"):
            IndexEvent.from_mapping(raw)

    def test_duplicate_and_old_revision_are_noop(self):
        first = event(revision=2)
        document = IndexDocument(
            "math_questions", "QUESTION", first.entity_id, 2, "公共数学题", "一元二次方程"
        )
        self.source.documents[first.entity_id] = [document]
        self.assertEqual(self.processor.process_event(first).outcome, "APPLIED_UPSERT")
        self.assertEqual(self.processor.process_event(first).outcome, "DUPLICATE_EVENT")

        stale_raw = event(revision=1)
        stale = IndexEvent(
            stale_raw.event_id, stale_raw.event_type, first.aggregate_id, stale_raw.trace_id,
            "QUESTION", first.entity_id, 1, stale_raw.operation, {**stale_raw.payload, "entityId": first.entity_id}
        )
        self.source.documents[first.entity_id] = [
            IndexDocument("math_questions", "QUESTION", first.entity_id, 1, "旧题", "旧正文")
        ]
        self.assertEqual(self.processor.process_event(stale).outcome, "STALE_REVISION")
        self.assertEqual([item[0] for item in self.index.operations], ["upsert"])

    def test_delete_tombstone_blocks_late_upsert_after_restart(self):
        base = event(revision=5)
        delete = IndexEvent(
            base.event_id, base.event_type, base.aggregate_id, base.trace_id, "QUESTION",
            base.entity_id, 5, type(base.operation).DELETE, {**base.payload, "operation": "DELETE"}
        )
        self.assertEqual(self.processor.process_event(delete).outcome, "APPLIED_DELETE")

        restarted_state = WorkerStateStore(Path(self.temporary.name) / "worker.sqlite3")
        self.additional_states.append(restarted_state)
        restarted = IndexProcessor(
            SimpleNamespace(embedding_batch_size=8),
            restarted_state,
            self.source,
            self.index,
            FakeEncoder(),
        )
        late = event(revision=4)
        late = IndexEvent(
            late.event_id, late.event_type, base.aggregate_id, late.trace_id, "QUESTION",
            base.entity_id, 4, late.operation, {**late.payload, "entityId": base.entity_id}
        )
        self.source.documents[base.entity_id] = [
            IndexDocument("math_questions", "QUESTION", base.entity_id, 4, "旧题", "旧正文")
        ]
        self.assertEqual(restarted.process_event(late).outcome, "STALE_REVISION")
        self.assertNotIn(base.entity_id, self.index.live)

    def test_upsert_event_with_missing_current_projection_becomes_tombstone(self):
        item = event(revision=3)
        result = self.processor.process_event(item)
        self.assertEqual(result.outcome, "APPLIED_DELETE")

    def test_course_projection_is_chunked_and_requires_java_reauthorization(self):
        item = {
            "entityId": str(uuid4()),
            "courseId": str(uuid4()),
            "courseVersionId": "v3",
            "lessonId": str(uuid4()),
            "sourceRevision": 7,
            "educationStage": "JUNIOR",
            "subject": "MATH",
            "title": "勾股定理",
            "articleMarkdown": "勾股定理。\n" * 500,
            "accessType": "PAID",
        }
        documents = projection_to_documents("COURSE_TEXT", item)
        self.assertGreater(len(documents), 1)
        self.assertTrue(documents[0].payload["requires_java_reauthorization"])
        self.assertNotIn("articleMarkdown", documents[0].payload)

    def test_question_projection_rejects_answer_or_analysis(self):
        item = {
            "entityId": str(uuid4()), "questionVersionId": str(uuid4()), "sourceRevision": 1,
            "educationStage": "JUNIOR", "grade": "GRADE_8", "subject": "MATH",
            "questionType": "SHORT_ANSWER", "stem": "证明", "options": [],
            "knowledgePointIds": [], "analysis": "未公布解析",
        }
        with self.assertRaisesRegex(RuntimeError, "冻结契约"):
            projection_to_documents("QUESTION", item)

    def test_restricted_retrieval_never_falls_back_to_global_scope(self):
        with self.assertRaises(PermissionError):
            RetrievalRequest("course_content", "勾股定理", "COURSE_QUESTION").validate()
        with self.assertRaises(PermissionError):
            RetrievalRequest("student_memories", "我的错题", "REVIEW_WRONG_QUESTION").validate()

    def test_student_memory_production_projection_uses_global_user_filter_payload(self):
        item = event(entity_type="STUDENT_MEMORY", revision=2)

        class MemoryHttpProvider:
            def fetch(self, incoming):
                return projection_to_documents("STUDENT_MEMORY", {
                    "entityId": incoming.entity_id,
                    "subjectUserId": incoming.payload["subjectUserId"],
                    "sourceRevision": incoming.source_revision,
                    "educationStage": "JUNIOR", "subject": "MATH",
                    "memoryType": "UNRESOLVED_POINT", "text": "一元一次方程移项仍需练习",
                    "visibility": "USER_PRIVATE", "accessScope": "USER_PRIVATE",
                    "requiresCurrentCourseAccess": False,
                })

        processor = IndexProcessor(
            SimpleNamespace(embedding_batch_size=8),
            self.state,
            self.source,
            self.index,
            FakeEncoder(),
            memory_source=MemoryHttpProvider(),
        )
        self.assertEqual(processor.process_event(item).outcome, "APPLIED_UPSERT")
        stored = self.index.live[item.entity_id][0][0]
        self.assertEqual(stored.collection, "student_memories")
        self.assertEqual(stored.payload["user_id"], item.payload["subjectUserId"])

    def test_rebuild_resumes_from_last_completed_page(self):
        first_id, second_id = str(uuid4()), str(uuid4())
        pages = {
            (0, "00000000-0000-0000-0000-000000000000"): ProjectionPage(
                [[IndexDocument("math_questions", "QUESTION", first_id, 1, "题一", "一次函数")]],
                [], True, 1, first_id,
            ),
            (1, first_id): ProjectionPage(
                [[IndexDocument("math_questions", "QUESTION", second_id, 2, "题二", "二次函数")]],
                [], False, 2, second_id,
            ),
        }

        class InterruptingSource:
            def __init__(self):
                self.failed = False

            def page(self, _entity_type, revision, entity_id, _size):
                if revision == 1 and not self.failed:
                    self.failed = True
                    raise RuntimeError("模拟分页中断")
                return pages[(revision, entity_id)]

        settings = SimpleNamespace(embedding_batch_size=8, source_page_size=1)
        processor = IndexProcessor(
            settings, self.state, InterruptingSource(), self.index, FakeEncoder()
        )
        with self.assertRaisesRegex(RuntimeError, "模拟分页中断"):
            processor.rebuild_source("QUESTION")
        self.assertEqual(self.state.checkpoint("QUESTION")[:3], (1, first_id, 1))
        result = processor.rebuild_source("QUESTION")
        self.assertTrue(result.completed)
        self.assertEqual(result.processed, 2)
        self.assertEqual([operation[1] for operation in self.index.operations], [first_id, second_id])

    def test_rabbit_failure_is_confirmed_to_retry_before_manual_ack(self):
        settings = SimpleNamespace(rabbitmq_max_attempts=3)
        consumer = RabbitIndexConsumer(settings, self.processor)
        channel = FakeRabbitChannel()
        method = SimpleNamespace(delivery_tag=9, routing_key="agent.index.question.changed.v1")
        properties = SimpleNamespace(headers={}, message_id="m1", correlation_id="c1")
        consumer._callback(channel, method, properties, b"not-json")
        self.assertEqual(channel.acked, [9])
        self.assertEqual(channel.nacked, [])
        self.assertEqual(channel.published[0]["exchange"], consumer.retry_exchange)
        self.assertEqual(
            channel.published[0]["properties"].headers["x-agent-index-attempt"], 2
        )

    def test_rabbit_exhausted_failure_is_confirmed_to_dlq(self):
        settings = SimpleNamespace(rabbitmq_max_attempts=3)
        consumer = RabbitIndexConsumer(settings, self.processor)
        channel = FakeRabbitChannel()
        method = SimpleNamespace(delivery_tag=10, routing_key="agent.index.question.changed.v1")
        properties = SimpleNamespace(
            headers={"x-agent-index-attempt": 3}, message_id="m2", correlation_id="c2"
        )
        consumer._callback(channel, method, properties, b"not-json")
        self.assertEqual(channel.acked, [10])
        self.assertEqual(channel.published[0]["exchange"], consumer.dead_exchange)

    def test_explicit_cleanup_clears_revision_and_checkpoint_for_rebuild(self):
        item = event(revision=4)
        self.source.documents[item.entity_id] = [
            IndexDocument("math_questions", "QUESTION", item.entity_id, 4, "题", "二次函数")
        ]
        self.processor.process_event(item)
        self.state.save_checkpoint("QUESTION", 4, item.entity_id, 1, True)
        counts = self.state.clear_all()
        self.assertEqual(counts, {"processed_event": 1, "entity_state": 1, "rebuild_checkpoint": 1})
        self.assertEqual(self.state.entity_counts(), {})
        self.assertEqual(
            self.state.checkpoint("QUESTION"),
            (0, "00000000-0000-0000-0000-000000000000", 0, False),
        )


if __name__ == "__main__":
    unittest.main()
