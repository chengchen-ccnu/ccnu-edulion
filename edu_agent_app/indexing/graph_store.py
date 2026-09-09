"""Neo4j 中受控的知识点/前置关系派生投影与有界查询。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence


LABEL = "EduAgentIndexKnowledge"
RELATIONSHIP = "EDU_AGENT_PREREQUISITE_OF"


@dataclass(frozen=True)
class GraphResult:
    """有界知识图结果；degraded 表示 Neo4j 故障时已安全省略增强。"""

    entity_ids: list[str]
    degraded: bool


class Neo4jKnowledgeGraph:
    """只读写 Worker 命名空间的受控标签，不接收模型生成的 Cypher。"""

    def __init__(self, uri: str, user: str, password: str, namespace: str, timeout: float):
        from neo4j import GraphDatabase

        if not password:
            raise ValueError("NEO4J_PASSWORD 缺失")
        self.namespace = namespace
        self.timeout = timeout
        self.driver = GraphDatabase.driver(uri, auth=(user, password))

    def close(self) -> None:
        """关闭连接池，不更改图数据。"""
        self.driver.close()

    def ensure_schema(self) -> None:
        """创建 Worker 专属复合唯一约束；不会修改既有知识图标签或节点。"""
        from neo4j import Query

        cypher = (
            f"CREATE CONSTRAINT edu_agent_index_knowledge_v1 IF NOT EXISTS "
            f"FOR (n:{LABEL}) REQUIRE (n.namespace, n.entityId) IS UNIQUE"
        )
        with self.driver.session() as session:
            session.run(Query(cypher, timeout=self.timeout)).consume()

    def replace_knowledge(
        self,
        entity_id: str,
        source_revision: int,
        code: str,
        name: str,
        prerequisite_ids: Sequence[str],
    ) -> None:
        """覆盖一个知识点及其直接前置边；只删除该节点上 Worker 自有关系。"""
        from neo4j import Query

        if len(prerequisite_ids) > 32:
            raise ValueError("直接前置关系不得超过契约上限 32")
        cypher = f"""
        MERGE (n:{LABEL} {{namespace: $namespace, entityId: $entityId}})
        SET n.code=$code, n.name=$name, n.sourceRevision=$sourceRevision,
            n.projectionState='ACTIVE'
        WITH n
        OPTIONAL MATCH (p:{LABEL} {{namespace: $namespace}})-[old:{RELATIONSHIP} {{namespace: $namespace}}]->(n)
        DELETE old
        WITH n
        UNWIND $prerequisiteIds AS prerequisiteId
        MERGE (p:{LABEL} {{namespace: $namespace, entityId: prerequisiteId}})
        ON CREATE SET p.projectionState='REFERENCE_ONLY'
        MERGE (p)-[r:{RELATIONSHIP} {{namespace: $namespace}}]->(n)
        SET r.sourceRevision=$sourceRevision
        """
        with self.driver.session() as session:
            session.run(
                Query(cypher, timeout=self.timeout),
                namespace=self.namespace,
                entityId=entity_id,
                sourceRevision=source_revision,
                code=code,
                name=name,
                prerequisiteIds=list(prerequisite_ids),
            ).consume()

    def delete_knowledge(self, entity_id: str) -> None:
        """精确删除 Worker namespace 中一个知识节点及其派生边，不触碰其他来源节点。"""
        from neo4j import Query

        cypher = f"MATCH (n:{LABEL} {{namespace:$namespace, entityId:$entityId}}) DETACH DELETE n"
        with self.driver.session() as session:
            session.run(
                Query(cypher, timeout=self.timeout), namespace=self.namespace, entityId=entity_id
            ).consume()

    def related(
        self, entity_ids: Sequence[str], direction: str, max_depth: int, max_nodes: int
    ) -> GraphResult:
        """查询 1～3 层前置/后续关系；超时或故障返回 degraded，不影响公共 Qdrant 检索。"""
        from neo4j import Query

        if direction not in {"PREREQUISITES", "DEPENDENTS"}:
            raise ValueError("direction 不合法")
        if not 1 <= max_depth <= 3 or not 1 <= max_nodes <= 200 or len(entity_ids) > 20:
            raise ValueError("知识图查询边界不合法")
        arrow = f"<-[:{RELATIONSHIP}*1..{max_depth}]-" if direction == "PREREQUISITES" else f"-[:{RELATIONSHIP}*1..{max_depth}]->"
        cypher = f"""
        MATCH (start:{LABEL})
        WHERE start.namespace=$namespace AND start.entityId IN $entityIds
        MATCH (start){arrow}(related:{LABEL})
        WHERE related.namespace=$namespace
        RETURN DISTINCT related.entityId AS entityId
        ORDER BY entityId
        LIMIT $maxNodes
        """
        try:
            with self.driver.session() as session:
                result = session.run(
                    Query(cypher, timeout=self.timeout),
                    namespace=self.namespace,
                    entityIds=list(entity_ids),
                    maxNodes=max_nodes,
                )
                return GraphResult([str(row["entityId"]) for row in result], False)
        except Exception:
            return GraphResult([], True)

    def cleanup_namespace(self) -> int:
        """显式删除 Worker namespace 的节点并返回数量；不匹配 namespace 的 3,671 节点均保留。"""
        from neo4j import Query

        cypher = f"""
        MATCH (n:{LABEL} {{namespace:$namespace}})
        WITH collect(n) AS nodes, count(n) AS count
        FOREACH (n IN nodes | DETACH DELETE n)
        RETURN count
        """
        with self.driver.session() as session:
            row = session.run(Query(cypher, timeout=self.timeout), namespace=self.namespace).single()
            return int(row["count"] if row else 0)

    def namespace_counts(self) -> tuple[int, int]:
        """只统计 Worker namespace 节点/关系，供精确清理前后复核。"""
        from neo4j import Query

        cypher = f"""
        MATCH (n:{LABEL} {{namespace:$namespace}})
        OPTIONAL MATCH (n)-[r:{RELATIONSHIP} {{namespace:$namespace}}]->()
        RETURN count(DISTINCT n) AS nodes, count(DISTINCT r) AS relationships
        """
        with self.driver.session() as session:
            row = session.run(Query(cypher, timeout=self.timeout), namespace=self.namespace).single()
            return (int(row["nodes"]), int(row["relationships"])) if row else (0, 0)
