"""使用本地 SQLite 保存消费幂等、revision/tombstone 与全量重建检查点。"""

from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterator


@dataclass(frozen=True)
class ApplyResult:
    """一次事件/重建写入的确定性结果，不包含正文或秘密。"""

    outcome: str
    applied_revision: int


class WorkerStateStore:
    """持久化 Worker 的一致性状态。

    每次实体写在 ``BEGIN IMMEDIATE`` 中完成 revision 判定、外部索引动作和状态提交。这样增量
    消费与全量重建即使并发，也不能让旧分页结果覆盖较新事件。外部动作失败会回滚；若进程在
    外部成功后、SQLite 提交前崩溃，at-least-once 重投会再次执行幂等替换并最终收敛。
    """

    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self._initialize()

    def _connection(self) -> sqlite3.Connection:
        connection = getattr(self._local, "connection", None)
        if connection is None:
            connection = sqlite3.connect(str(self.path), timeout=30, isolation_level=None)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("PRAGMA busy_timeout=30000")
            self._local.connection = connection
        return connection

    def _initialize(self) -> None:
        connection = sqlite3.connect(str(self.path))
        try:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS processed_event (
                    event_id TEXT PRIMARY KEY,
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    source_revision INTEGER NOT NULL,
                    processed_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS entity_state (
                    entity_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    highest_revision INTEGER NOT NULL,
                    tombstone INTEGER NOT NULL CHECK (tombstone IN (0, 1)),
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (entity_type, entity_id)
                );
                CREATE TABLE IF NOT EXISTS rebuild_checkpoint (
                    source_kind TEXT PRIMARY KEY,
                    after_revision INTEGER NOT NULL,
                    after_entity_id TEXT NOT NULL,
                    processed_count INTEGER NOT NULL,
                    completed INTEGER NOT NULL CHECK (completed IN (0, 1)),
                    updated_at TEXT NOT NULL
                );
                """
            )
            connection.commit()
        finally:
            connection.close()

    def close(self) -> None:
        """关闭当前线程 SQLite 连接，便于 Worker 停止和 Windows 精确清理状态文件。"""
        connection = getattr(self._local, "connection", None)
        if connection is not None:
            connection.close()
            self._local.connection = None

    @contextmanager
    def _write_transaction(self) -> Iterator[sqlite3.Connection]:
        connection = self._connection()
        connection.execute("BEGIN IMMEDIATE")
        try:
            yield connection
            connection.execute("COMMIT")
        except BaseException:
            connection.execute("ROLLBACK")
            raise

    def apply(
        self,
        *,
        entity_type: str,
        entity_id: str,
        source_revision: int,
        tombstone: bool,
        action: Callable[[], None],
        event_id: str | None = None,
    ) -> ApplyResult:
        """仅当 revision 严格变新时执行外部动作并提交状态。

        ``action`` 写 Qdrant/Neo4j；异常会阻止事件落为 processed，RabbitMQ 调用方必须 NACK 或
        有限重投。重复 eventId、相同/旧 revision 均为 no-op，但仍会持久记录新的过期 eventId。
        """
        if source_revision < 1:
            raise ValueError("sourceRevision 必须是正整数")
        now = datetime.now(timezone.utc).isoformat()
        with self._write_transaction() as connection:
            if event_id is not None:
                duplicate = connection.execute(
                    "SELECT 1 FROM processed_event WHERE event_id = ?", (event_id,)
                ).fetchone()
                if duplicate:
                    row = connection.execute(
                        "SELECT highest_revision FROM entity_state WHERE entity_type=? AND entity_id=?",
                        (entity_type, entity_id),
                    ).fetchone()
                    return ApplyResult("DUPLICATE_EVENT", int(row[0]) if row else 0)
            row = connection.execute(
                "SELECT highest_revision FROM entity_state WHERE entity_type=? AND entity_id=?",
                (entity_type, entity_id),
            ).fetchone()
            highest = int(row[0]) if row else 0
            if source_revision > highest:
                action()
                connection.execute(
                    """
                    INSERT INTO entity_state(entity_type,entity_id,highest_revision,tombstone,updated_at)
                    VALUES(?,?,?,?,?)
                    ON CONFLICT(entity_type,entity_id) DO UPDATE SET
                      highest_revision=excluded.highest_revision,
                      tombstone=excluded.tombstone,
                      updated_at=excluded.updated_at
                    """,
                    (entity_type, entity_id, source_revision, int(tombstone), now),
                )
                outcome = "APPLIED_DELETE" if tombstone else "APPLIED_UPSERT"
                highest = source_revision
            else:
                outcome = "STALE_REVISION"
            if event_id is not None:
                connection.execute(
                    "INSERT INTO processed_event VALUES(?,?,?,?,?)",
                    (event_id, entity_type, entity_id, source_revision, now),
                )
            return ApplyResult(outcome, highest)

    def checkpoint(self, source_kind: str) -> tuple[int, str, int, bool]:
        """读取指定事实源的最近已提交页游标；缺失时从契约起点开始。"""
        row = self._connection().execute(
            "SELECT after_revision,after_entity_id,processed_count,completed FROM rebuild_checkpoint WHERE source_kind=?",
            (source_kind,),
        ).fetchone()
        if row is None:
            return 0, "00000000-0000-0000-0000-000000000000", 0, False
        return int(row[0]), str(row[1]), int(row[2]), bool(row[3])

    def save_checkpoint(
        self,
        source_kind: str,
        after_revision: int,
        after_entity_id: str,
        processed_count: int,
        completed: bool,
    ) -> None:
        """在整页索引成功后原子保存游标；中断时不会越过未完成页。"""
        now = datetime.now(timezone.utc).isoformat()
        with self._write_transaction() as connection:
            connection.execute(
                """
                INSERT INTO rebuild_checkpoint VALUES(?,?,?,?,?,?)
                ON CONFLICT(source_kind) DO UPDATE SET
                  after_revision=excluded.after_revision,
                  after_entity_id=excluded.after_entity_id,
                  processed_count=excluded.processed_count,
                  completed=excluded.completed,
                  updated_at=excluded.updated_at
                """,
                (source_kind, after_revision, after_entity_id, processed_count, int(completed), now),
            )

    def reset_checkpoint(self, source_kind: str) -> None:
        """显式开始一次新重建；只删除该事实源游标，不删除实体 tombstone 或索引数据。"""
        with self._write_transaction() as connection:
            connection.execute("DELETE FROM rebuild_checkpoint WHERE source_kind=?", (source_kind,))

    def entity_counts(self) -> dict[str, dict[str, int]]:
        """返回各实体类型 live/tombstone 聚合，供对账使用且不暴露正文。"""
        rows = self._connection().execute(
            "SELECT entity_type,tombstone,COUNT(*) count FROM entity_state GROUP BY entity_type,tombstone"
        ).fetchall()
        result: dict[str, dict[str, int]] = {}
        for row in rows:
            result.setdefault(str(row[0]), {"live": 0, "tombstone": 0})[
                "tombstone" if row[1] else "live"
            ] = int(row[2])
        return result

    def clear_all(self) -> dict[str, int]:
        """在 namespace 已精确确认并清掉外部投影后清空本状态库，使后续重建可重新应用同 revision。"""
        with self._write_transaction() as connection:
            counts = {
                table: int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
                for table in ("processed_event", "entity_state", "rebuild_checkpoint")
            }
            for table in ("processed_event", "entity_state", "rebuild_checkpoint"):
                connection.execute(f"DELETE FROM {table}")
        return counts
