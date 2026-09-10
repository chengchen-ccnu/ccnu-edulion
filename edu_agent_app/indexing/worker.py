"""独立 Index Worker：事件消费、revision 防乱序、tombstone 与有界全量重建。"""

from __future__ import annotations

import json
import logging
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Callable, Mapping, Protocol, Sequence

from .config import IndexSettings
from .models import ENTITY_TO_COLLECTION, IndexDocument, IndexEvent, Operation
from .state_store import ApplyResult, WorkerStateStore


LOG = logging.getLogger(__name__)
EVENT_EXCHANGE = "edu.agent.index.events.v1"
RETRY_EXCHANGE = "edu.agent.index.retry.v1"
DEAD_EXCHANGE = "edu.agent.index.dlx.v1"
WORK_QUEUE = "edu.agent.index.worker.v1"
DEAD_QUEUE = "edu.agent.index.worker.dlq.v1"
ROUTING_KEYS = (
    "agent.index.question.changed.v1",
    "agent.index.knowledge-point.changed.v1",
    "agent.index.course-text.changed.v1",
    "agent.index.student-memory.changed.v1",
)
EVENT_ROUTING = {
    "QuestionIndexProjectionChanged": ROUTING_KEYS[0],
    "KnowledgePointIndexProjectionChanged": ROUTING_KEYS[1],
    "CourseTextIndexProjectionChanged": ROUTING_KEYS[2],
    "StudentMemoryIndexProjectionChanged": ROUTING_KEYS[3],
}


class MemoryProjectionProvider(Protocol):
    """从 Agent Access 内部 API 读取记忆安全投影，不访问 MySQL。"""

    def fetch(self, event: IndexEvent) -> list[IndexDocument]: ...


class UnavailableMemoryProjectionProvider:
    """生产默认替身：明确拒绝记忆 UPSERT，不伪装 T14.2 已接通。"""

    def fetch(self, event: IndexEvent) -> list[IndexDocument]:
        raise RuntimeError("T14.2 尚未提供学生记忆安全投影 API")


@dataclass(frozen=True)
class RebuildResult:
    """单个 Java 事实源重建/恢复结果和只读对账计数。"""

    entity_type: str
    processed: int
    completed: bool
    qdrant_points: int


class IndexProcessor:
    """把事件或分页投影安全写入 Qdrant/Neo4j，并持久化一致性状态。"""

    def __init__(
        self,
        settings: IndexSettings,
        state: WorkerStateStore,
        source,
        index,
        encoder,
        graph=None,
        memory_source: MemoryProjectionProvider | None = None,
    ):
        self.settings = settings
        self.state = state
        self.source = source
        self.index = index
        self.encoder = encoder
        self.graph = graph
        self.memory_source = memory_source or UnavailableMemoryProjectionProvider()

    def _replace(self, documents: Sequence[IndexDocument]) -> None:
        """编码并替换一个实体；知识点同时更新 Worker 专属 Neo4j 投影。"""
        vectors = self.encoder.encode_documents(
            [document.text for document in documents], self.settings.embedding_batch_size
        )
        self._write_documents(documents, vectors)

    def _write_documents(
        self, documents: Sequence[IndexDocument], vectors: Sequence[Sequence[float]]
    ) -> None:
        """写入已编码文档；重建页可批量编码后按实体复用，事件路径仍逐实体编码。"""
        self.index.replace_entity(documents, vectors)
        first = documents[0]
        if first.entity_type == "KNOWLEDGE_POINT" and self.graph is not None:
            self.graph.replace_knowledge(
                first.entity_id,
                first.source_revision,
                str(first.payload.get("code", "")),
                first.title,
                list(first.payload.get("prerequisite_ids", [])),
            )

    def _delete(self, entity_type: str, entity_id: str) -> None:
        """写 tombstone 前精确删除派生投影；不删除 collection 或其他 namespace。"""
        self.index.delete_entity(ENTITY_TO_COLLECTION[entity_type], entity_id)
        if entity_type == "KNOWLEDGE_POINT" and self.graph is not None:
            self.graph.delete_knowledge(entity_id)

    def process_event(self, event: IndexEvent) -> ApplyResult:
        """处理一次 at-least-once 事件；只有全部外部写入成功后调用方才能 ACK。"""
        if event.operation is Operation.DELETE:
            return self.state.apply(
                entity_type=event.entity_type,
                entity_id=event.entity_id,
                source_revision=event.source_revision,
                tombstone=True,
                action=lambda: self._delete(event.entity_type, event.entity_id),
                event_id=event.event_id,
            )
        documents = (
            self.memory_source.fetch(event)
            if event.entity_type == "STUDENT_MEMORY"
            else self.source.fetch_entity(event)
        )
        if not documents:
            return self.state.apply(
                entity_type=event.entity_type,
                entity_id=event.entity_id,
                source_revision=event.source_revision,
                tombstone=True,
                action=lambda: self._delete(event.entity_type, event.entity_id),
                event_id=event.event_id,
            )
        revision = documents[0].source_revision
        return self.state.apply(
            entity_type=event.entity_type,
            entity_id=event.entity_id,
            source_revision=revision,
            tombstone=False,
            action=lambda: self._replace(documents),
            event_id=event.event_id,
        )

    def rebuild_source(self, entity_type: str) -> RebuildResult:
        """从 Java 稳定游标恢复重建；每页成功后才推进检查点，不自动删除对账差异。"""
        after_revision, after_entity_id, processed, completed = self.state.checkpoint(entity_type)
        if completed:
            return RebuildResult(
                entity_type, processed, True, self.index.namespace_count(ENTITY_TO_COLLECTION[entity_type])
            )
        while True:
            page = self.source.page(
                entity_type, after_revision, after_entity_id, self.settings.source_page_size
            )
            flattened = [document for documents in page.documents for document in documents]
            page_vectors = self.encoder.encode_documents(
                [document.text for document in flattened], self.settings.embedding_batch_size
            ) if flattened else []
            vector_offset = 0
            for documents in page.documents:
                first = documents[0]
                vectors = page_vectors[vector_offset : vector_offset + len(documents)]
                vector_offset += len(documents)
                self.state.apply(
                    entity_type=entity_type,
                    entity_id=first.entity_id,
                    source_revision=first.source_revision,
                    tombstone=False,
                    action=lambda docs=documents, dense=vectors: self._write_documents(docs, dense),
                )
                processed += 1
            after_revision, after_entity_id = page.next_revision, page.next_entity_id
            completed = not page.has_more
            self.state.save_checkpoint(
                entity_type, after_revision, after_entity_id, processed, completed
            )
            if completed:
                break
        return RebuildResult(
            entity_type, processed, True, self.index.namespace_count(ENTITY_TO_COLLECTION[entity_type])
        )

    def rebuild_all(self) -> list[RebuildResult]:
        """有界并发重建四类 Java 事实源；单实体写仍由 SQLite revision 锁串行保护。"""
        entity_types = ("QUESTION", "KNOWLEDGE_POINT", "COURSE_TEXT", "STUDENT_MEMORY")
        results: list[RebuildResult] = []
        with ThreadPoolExecutor(max_workers=self.settings.source_concurrency) as executor:
            futures = {executor.submit(self.rebuild_source, item): item for item in entity_types}
            for future in as_completed(futures):
                results.append(future.result())
        return sorted(results, key=lambda result: result.entity_type)


class RabbitIndexConsumer:
    """Pika 手动 ACK 消费器，使用 confirm 后重投实现有限重试和 DLQ。"""

    def __init__(
        self,
        settings: IndexSettings,
        processor: IndexProcessor,
        *,
        event_exchange: str = EVENT_EXCHANGE,
        retry_exchange: str = RETRY_EXCHANGE,
        dead_exchange: str = DEAD_EXCHANGE,
        work_queue: str = WORK_QUEUE,
        dead_queue: str = DEAD_QUEUE,
    ):
        self.settings = settings
        self.processor = processor
        self.event_exchange = event_exchange
        self.retry_exchange = retry_exchange
        self.dead_exchange = dead_exchange
        self.work_queue = work_queue
        self.dead_queue = dead_queue

    def _connection(self):
        import pika

        credentials = pika.PlainCredentials(
            self.settings.rabbitmq_username,
            self.settings.rabbitmq_password or "",
            erase_on_connect=True,
        )
        return pika.BlockingConnection(
            pika.ConnectionParameters(
                host=self.settings.rabbitmq_host,
                port=self.settings.rabbitmq_port,
                virtual_host=self.settings.rabbitmq_vhost,
                credentials=credentials,
                heartbeat=30,
                blocked_connection_timeout=30,
                connection_attempts=3,
                retry_delay=1,
            )
        )

    def _declare(self, channel) -> None:
        """声明 durable 主队列、四条 TTL 重试队列和 DLQ，不触碰 RabbitMQ 其他资源。"""
        channel.exchange_declare(self.event_exchange, "topic", durable=True)
        channel.exchange_declare(self.retry_exchange, "topic", durable=True)
        channel.exchange_declare(self.dead_exchange, "topic", durable=True)
        # 单活消费者让可独立扩缩的多个进程保持实体事件全局顺序；备用实例自动接管。
        channel.queue_declare(
            self.work_queue, durable=True, arguments={"x-single-active-consumer": True}
        )
        channel.queue_declare(self.dead_queue, durable=True)
        # 迁移早期宽泛绑定，避免未知事件版本落入无法路由的重试死循环。
        channel.queue_unbind(self.work_queue, self.event_exchange, routing_key="agent.index.#")
        channel.queue_unbind(self.dead_queue, self.dead_exchange, routing_key="agent.index.#")
        for routing_key in ROUTING_KEYS:
            channel.queue_bind(self.work_queue, self.event_exchange, routing_key=routing_key)
            channel.queue_bind(self.dead_queue, self.dead_exchange, routing_key=routing_key)
            retry_queue = f"{self.work_queue}.retry.{routing_key.split('.')[2]}"
            channel.queue_declare(
                retry_queue,
                durable=True,
                arguments={
                    "x-message-ttl": self.settings.rabbitmq_retry_delay_ms,
                    "x-dead-letter-exchange": self.event_exchange,
                },
            )
            channel.queue_bind(retry_queue, self.retry_exchange, routing_key=routing_key)
        channel.basic_qos(prefetch_count=self.settings.rabbitmq_prefetch)
        channel.confirm_delivery()

    def _republish(self, channel, exchange: str, method, properties, body: bytes, attempt: int) -> None:
        """在 ACK 原消息前发布持久重试/DLQ 消息并等待 broker confirm。"""
        import pika

        headers = dict(properties.headers or {})
        headers["x-agent-index-attempt"] = attempt
        channel.basic_publish(
            exchange=exchange,
            routing_key=method.routing_key,
            body=body,
            properties=pika.BasicProperties(
                content_type="application/json",
                delivery_mode=2,
                message_id=properties.message_id,
                correlation_id=properties.correlation_id,
                headers=headers,
            ),
            mandatory=True,
        )
        # Pika 1.3.2 在 confirm 模式下以 NackError/UnroutableError 表示失败，成功返回 None。

    def _callback(self, channel, method, properties, body: bytes) -> None:
        """消费单条消息；失败只记录类型，不记录正文、题干、Token 或异常响应。"""
        attempt = self.settings.rabbitmq_max_attempts
        try:
            attempt = int((properties.headers or {}).get("x-agent-index-attempt", 1))
            if not 1 <= attempt <= self.settings.rabbitmq_max_attempts:
                raise ValueError("RabbitMQ 重试次数头不合法")
            if len(body) > 65536:
                raise ValueError("索引事件超过 64KiB")
            raw = json.loads(body.decode("utf-8"))
            if not isinstance(raw, Mapping):
                raise ValueError("索引事件必须是 JSON 对象")
            event = IndexEvent.from_mapping(raw)
            if method.routing_key != EVENT_ROUTING[event.event_type]:
                raise ValueError("索引事件 routing key 与 eventType 不一致")
            result = self.processor.process_event(event)
            LOG.info("index event processed outcome=%s entity_type=%s", result.outcome, event.entity_type)
            channel.basic_ack(method.delivery_tag)
        except Exception as exc:
            LOG.warning("index event failed attempt=%s error_type=%s", attempt, type(exc).__name__)
            try:
                if attempt < self.settings.rabbitmq_max_attempts:
                    self._republish(
                        channel, self.retry_exchange, method, properties, body, attempt + 1
                    )
                else:
                    self._republish(channel, self.dead_exchange, method, properties, body, attempt)
                channel.basic_ack(method.delivery_tag)
            except Exception:
                channel.basic_nack(method.delivery_tag, requeue=True)
                raise

    def consume_once(self, timeout_seconds: float = 5.0) -> bool:
        """测试/运维模式下有界等待一条消息；返回是否收到投递。"""
        connection = self._connection()
        try:
            channel = connection.channel()
            self._declare(channel)
            delivered = False

            def callback(ch, method, properties, body):
                nonlocal delivered
                delivered = True
                self._callback(ch, method, properties, body)
                ch.stop_consuming()

            channel.basic_consume(self.work_queue, callback, auto_ack=False)
            connection.call_later(timeout_seconds, channel.stop_consuming)
            channel.start_consuming()
            return delivered
        finally:
            connection.close()

    def run_forever(self) -> None:
        """独立阻塞运行消费器；连接故障有限退避重连，消息本身仍只尝试配置次数。"""
        delay = 1.0
        while True:
            connection = None
            try:
                connection = self._connection()
                channel = connection.channel()
                self._declare(channel)
                channel.basic_consume(self.work_queue, self._callback, auto_ack=False)
                LOG.info("agent index worker started")
                channel.start_consuming()
                delay = 1.0
            except KeyboardInterrupt:
                return
            except Exception as exc:
                LOG.error("index worker connection failed error_type=%s", type(exc).__name__)
                time.sleep(delay)
                delay = min(delay * 2, 30.0)
            finally:
                if connection is not None and connection.is_open:
                    connection.close()
