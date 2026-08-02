"""提供本地题库/知识点的混合检索、可审计轨迹和只读知识图谱查询。"""

from __future__ import annotations

import heapq
import math
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from hashlib import sha1
from typing import Any, Iterable, Sequence


_TEXT_PART_RE = re.compile(r"[\u4e00-\u9fff]+|[a-zA-Z]+|\d+(?:\.\d+)?")
_STOP_WORDS = {
    "一个",
    "这个",
    "那个",
    "什么",
    "怎么",
    "如何",
    "其中",
    "已知",
    "求解",
}


def tokenize(text: str) -> list[str]:
    """把中文数学文本切为二元词片和拉丁/数字词元，供内存 BM25 使用。"""
    tokens: list[str] = []
    for part in _TEXT_PART_RE.findall((text or "").lower()):
        if "\u4e00" <= part[0] <= "\u9fff":
            if len(part) == 1:
                tokens.append(part)
            else:
                tokens.extend(part[i : i + 2] for i in range(len(part) - 1))
                if len(part) <= 8:
                    tokens.append(part)
        else:
            tokens.append(part)
    return [token for token in tokens if token not in _STOP_WORDS]


def _document_key(doc: Any) -> str:
    """优先用稳定业务元数据去重；无标识时使用正文摘要，且不修改文档。"""
    metadata = getattr(doc, "metadata", {}) or {}
    identity = (
        metadata.get("exercise_id")
        or metadata.get("knowledge_name")
        or metadata.get("file_path")
    )
    if identity:
        return str(identity)
    content = str(getattr(doc, "page_content", ""))
    return sha1(content.encode("utf-8", errors="ignore")).hexdigest()


class BM25Index:
    """小型只读内存 BM25 索引，用于补充已有 FAISS 的精确关键词召回。"""

    def __init__(self, documents: Sequence[Any], *, k1: float = 1.5, b: float = 0.75):
        """预计算文档词频、倒排表和 IDF；仅占用进程内存，不回写 FAISS 文件。"""
        self.documents = list(documents)
        self.k1 = k1
        self.b = b
        self.term_frequencies: list[Counter[str]] = []
        self.document_lengths: list[int] = []
        self.postings: dict[str, list[int]] = defaultdict(list)
        document_frequency: Counter[str] = Counter()

        for index, doc in enumerate(self.documents):
            terms = tokenize(str(getattr(doc, "page_content", "")))
            frequencies = Counter(terms)
            self.term_frequencies.append(frequencies)
            self.document_lengths.append(len(terms))
            for term in frequencies:
                document_frequency[term] += 1
                self.postings[term].append(index)

        count = max(len(self.documents), 1)
        self.average_length = sum(self.document_lengths) / count or 1.0
        self.idf = {
            term: math.log(1.0 + (count - freq + 0.5) / (freq + 0.5))
            for term, freq in document_frequency.items()
        }

    def search(self, query: str, k: int) -> list[tuple[Any, float]]:
        """返回分数大于零的前 ``k`` 个文档；空查询或空索引直接返回空列表。"""
        query_terms = tokenize(query)
        if not query_terms or not self.documents:
            return []

        scores: dict[int, float] = defaultdict(float)
        for term, query_frequency in Counter(query_terms).items():
            idf = self.idf.get(term)
            if idf is None:
                continue
            for index in self.postings.get(term, []):
                frequency = self.term_frequencies[index][term]
                length = self.document_lengths[index]
                denominator = frequency + self.k1 * (
                    1.0 - self.b + self.b * length / self.average_length
                )
                scores[index] += query_frequency * idf * (
                    frequency * (self.k1 + 1.0) / denominator
                )

        best = heapq.nlargest(k, scores.items(), key=lambda item: item[1])
        return [(self.documents[index], score) for index, score in best if score > 0]


@dataclass(frozen=True)
class HybridHit:
    """一次混合检索命中及其总分、向量分、关键词分和查询词覆盖率。"""
    document: Any
    score: float
    vector_score: float
    keyword_score: float
    term_coverage: float


class HybridRetriever:
    """组合 FAISS 向量召回与 BM25 关键词召回，并执行确定性去重和重排。"""

    def __init__(
        self,
        vector_store: Any,
        *,
        score_threshold: float,
        vector_k: int = 30,
        keyword_k: int = 30,
        rerank_top_k: int = 10,
        documents: Sequence[Any] | None = None,
    ):
        """从既有向量库提取文档建立内存索引，并保存召回上限和最低总分。"""
        self.vector_store = vector_store
        self.score_threshold = score_threshold
        self.vector_k = vector_k
        self.keyword_k = keyword_k
        self.rerank_top_k = rerank_top_k
        self.documents = list(documents or self._documents_from_store(vector_store))
        self.keyword_index = BM25Index(self.documents)

    @staticmethod
    def _documents_from_store(vector_store: Any) -> list[Any]:
        """读取 LangChain FAISS docstore 中的文档；不支持时安全返回空集合。"""
        docstore = getattr(vector_store, "docstore", None)
        mapping = getattr(vector_store, "index_to_docstore_id", {}) or {}
        if docstore is None:
            return []
        documents = []
        for index in sorted(mapping):
            doc = docstore.search(mapping[index])
            if doc is not None and not isinstance(doc, str):
                documents.append(doc)
        return documents

    def _vector_search(self, query: str) -> list[tuple[Any, float]]:
        """把向量库结果统一为越大越相关的零到一分数。"""
        try:
            results = self.vector_store.similarity_search_with_relevance_scores(
                query,
                k=self.vector_k,
            )
            return [(doc, max(0.0, min(1.0, float(score)))) for doc, score in results]
        except (AttributeError, NotImplementedError):
            # 兼容只提供距离的向量库：距离越小，转换后的相似度越高。
            results = self.vector_store.similarity_search_with_score(query, k=self.vector_k)
            return [(doc, 1.0 / (1.0 + max(float(distance), 0.0))) for doc, distance in results]

    def search(self, query: str) -> list[HybridHit]:
        """合并两路候选并按稳定规则重排，过滤阈值以下结果后返回前若干项。

        同一业务文档只保留一项；重复调用不会修改向量库或知识库，排序在相同输入下保持
        确定性，便于测试和记录检索轨迹。
        """
        vector_results = self._vector_search(query)
        keyword_results = self.keyword_index.search(query, self.keyword_k)
        max_keyword = max((score for _, score in keyword_results), default=1.0)

        candidates: dict[str, dict[str, Any]] = {}
        for doc, score in vector_results:
            candidates.setdefault(
                _document_key(doc),
                {"document": doc, "vector": 0.0, "keyword": 0.0},
            )["vector"] = score
        for doc, score in keyword_results:
            candidates.setdefault(
                _document_key(doc),
                {"document": doc, "vector": 0.0, "keyword": 0.0},
            )["keyword"] = score / max_keyword if max_keyword else 0.0

        query_terms = set(tokenize(query))
        reranked: list[HybridHit] = []
        for candidate in candidates.values():
            doc = candidate["document"]
            document_terms = set(tokenize(str(getattr(doc, "page_content", ""))))
            coverage = (
                len(query_terms & document_terms) / len(query_terms)
                if query_terms
                else 0.0
            )
            # 向量负责语义，BM25 负责精确术语，coverage 是第二阶段重排信号。
            score = (
                0.55 * candidate["vector"]
                + 0.35 * candidate["keyword"]
                + 0.10 * coverage
            )
            reranked.append(
                HybridHit(
                    document=doc,
                    score=score,
                    vector_score=candidate["vector"],
                    keyword_score=candidate["keyword"],
                    term_coverage=coverage,
                )
            )

        reranked.sort(key=lambda hit: hit.score, reverse=True)
        return [
            hit
            for hit in reranked[: self.rerank_top_k]
            if hit.score >= self.score_threshold
        ]


def extract_knowledge_names(hits: Iterable[HybridHit]) -> list[str]:
    """从命中元数据或约定正文标记中提取知识点名称，并保持顺序去重。"""
    names: list[str] = []
    for hit in hits:
        doc = hit.document
        metadata = getattr(doc, "metadata", {}) or {}
        value = metadata.get("knowledge_name") or metadata.get("knowledge_points")
        if isinstance(value, str):
            names.append(value)
        elif isinstance(value, list):
            names.extend(str(item) for item in value)

        if not value:
            content = str(getattr(doc, "page_content", ""))
            match = re.search(r"【知识点】\s*([^\r\n]+)", content)
            if match:
                names.extend(re.split(r"[，,]", match.group(1)))

    return list(dict.fromkeys(name.strip() for name in names if name.strip()))


def retrieval_trace(stage: str, hits: Iterable[HybridHit]) -> dict:
    """生成不含正文的检索审计摘要，供会话记录阶段、来源标识和各项分数。"""
    hit_list = list(hits)
    return {
        "stage": stage,
        "count": len(hit_list),
        "hits": [
            {
                "score": round(hit.score, 4),
                "vector": round(hit.vector_score, 4),
                "keyword": round(hit.keyword_score, 4),
                "source": _document_key(hit.document),
            }
            for hit in hit_list
        ],
    }


class KnowledgeGraph:
    """按知识点名称查询前置关系的 Neo4j 只读适配器；未配置密码时禁用。"""

    def __init__(self, uri: str, user: str, password: str | None):
        """配置存在时创建异步驱动；不创建节点、关系或数据库结构。"""
        self._driver = None
        if password:
            from neo4j import AsyncGraphDatabase

            self._driver = AsyncGraphDatabase.driver(uri, auth=(user, password))

    async def prerequisites(self, knowledge_names: Iterable[str]) -> list[str]:
        """查询候选知识点的一跳前置知识；禁用时返回空列表且不建立连接。"""
        if self._driver is None:
            return []
        query = """
        UNWIND $names AS name
        MATCH (pre:KnowGraph)-[:PRECEDES]->(current:KnowGraph {knowName: name})
        RETURN DISTINCT pre.knowName AS name
        """
        async with self._driver.session() as session:
            result = await session.run(query, names=list(knowledge_names))
            records = await result.data()
        return [record["name"] for record in records if record.get("name")]

    async def close(self) -> None:
        """关闭已创建的 Neo4j 驱动；禁用状态下重复调用无副作用。"""
        if self._driver is not None:
            await self._driver.close()


@dataclass
class RetrievalServices:
    """工作流使用的题目检索器、知识点检索器和前置知识图谱集合。"""
    question: HybridRetriever
    knowledge: HybridRetriever
    graph: KnowledgeGraph
