"""Dense + Qdrant BM25 → RRF → 本地 BGE reranker → 阈值过滤检索流水线。"""

from __future__ import annotations

from dataclasses import dataclass
from time import perf_counter
from typing import Mapping

from .config import IndexSettings
from .models import RetrievalHit, RetrievalRequest, RetrievalTrace
from .qdrant_store import QdrantIndex, RawCandidate


@dataclass(frozen=True)
class RetrievalResponse:
    """最终安全候选及不含正文的阶段证据。"""

    hits: list[RetrievalHit]
    trace: RetrievalTrace
    threshold: float
    before_threshold_count: int


class HybridSearchService:
    """执行四阶段检索，并对课程/记忆请求强制使用可信 scope。"""

    def __init__(self, settings: IndexSettings, index: QdrantIndex, encoder, reranker):
        self.settings = settings
        self.index = index
        self.encoder = encoder
        self.reranker = reranker

    def _threshold(self, request: RetrievalRequest) -> float:
        """按 collection 为主、意图为辅返回校准阈值；不复用旧 FAISS 阈值。"""
        threshold = self.settings.threshold_for(request.collection)
        intent_adjustments = {
            "RECOMMEND_PRACTICE": 0.03,
            "CHECK_STUDENT_WORK": 0.02,
            "COURSE_QUESTION": 0.00,
            "EXPLAIN_CONCEPT": -0.02,
        }
        return max(0.0, min(1.0, threshold + intent_adjustments.get(request.intent, 0.0)))

    def search(self, request: RetrievalRequest) -> RetrievalResponse:
        """执行真实两路召回、RRF 和交叉编码重排；无可靠命中时返回空，不硬凑结果。"""
        request.validate()
        started = perf_counter()
        query_vector = self.encoder.encode_query(request.query)

        dense_started = perf_counter()
        dense = self.index.search_dense(request, query_vector, self.settings.dense_candidate_k)
        dense_ms = (perf_counter() - dense_started) * 1000

        bm25_started = perf_counter()
        sparse = self.index.search_bm25(request, self.settings.sparse_candidate_k)
        bm25_ms = (perf_counter() - bm25_started) * 1000

        fused = _rrf(dense, sparse, self.settings.rrf_k)
        selected = fused[: self.settings.rerank_candidate_k]
        rerank_started = perf_counter()
        rerank_scores = self.reranker.score(
            request.query, [str(item[0].payload.get("text", "")) for item in selected]
        )
        rerank_ms = (perf_counter() - rerank_started) * 1000
        reranked = sorted(
            zip(selected, rerank_scores),
            key=lambda item: (float(item[1]), float(item[0][1])),
            reverse=True,
        )
        threshold = self._threshold(request)
        accepted = [item for item in reranked if float(item[1]) >= threshold]
        # 自主组题需要一次取得最多 20 个稳定候选 ID；其余问答意图仍沿用 T13.2 的 8 条上限，
        # 避免把组题专用容量扩散到普通回答上下文。
        intent_max_results = 20 if request.intent == "RECOMMEND_PRACTICE" else self.settings.final_max_results
        limit = min(request.limit, intent_max_results)
        hits: list[RetrievalHit] = []
        for ((candidate, rrf_score, dense_rank, bm25_rank), rerank_score) in accepted[:limit]:
            payload = dict(candidate.payload)
            text = str(payload.pop("text", ""))
            hits.append(
                RetrievalHit(
                    entity_id=str(payload.get("entity_id", "")),
                    title=str(payload.pop("title", "")),
                    summary=text[:4000],
                    source_revision=int(payload.get("source_revision", 0)),
                    dense_rank=dense_rank,
                    bm25_rank=bm25_rank,
                    rrf_score=rrf_score,
                    rerank_score=float(rerank_score),
                    payload=payload,
                )
            )
        trace = RetrievalTrace(
            dense_count=len(dense),
            bm25_count=len(sparse),
            fused_count=len(fused),
            reranked_count=len(reranked),
            accepted_count=len(hits),
            dense_ms=round(dense_ms, 3),
            bm25_ms=round(bm25_ms, 3),
            rerank_ms=round(rerank_ms, 3),
            total_ms=round((perf_counter() - started) * 1000, 3),
        )
        return RetrievalResponse(hits, trace, threshold, len(reranked))


def _rrf(
    dense: list[RawCandidate], sparse: list[RawCandidate], rrf_k: int
) -> list[tuple[RawCandidate, float, int | None, int | None]]:
    """按 pointId 对两路名次执行 Reciprocal Rank Fusion，分数不冒充相似度。"""
    candidates: dict[str, RawCandidate] = {}
    dense_ranks: dict[str, int] = {}
    sparse_ranks: dict[str, int] = {}
    scores: dict[str, float] = {}
    for rank, candidate in enumerate(dense, start=1):
        candidates[candidate.point_id] = candidate
        dense_ranks[candidate.point_id] = rank
        scores[candidate.point_id] = scores.get(candidate.point_id, 0.0) + 1.0 / (rrf_k + rank)
    for rank, candidate in enumerate(sparse, start=1):
        candidates.setdefault(candidate.point_id, candidate)
        sparse_ranks[candidate.point_id] = rank
        scores[candidate.point_id] = scores.get(candidate.point_id, 0.0) + 1.0 / (rrf_k + rank)
    ordered = sorted(scores, key=lambda key: (-scores[key], key))
    return [
        (candidates[key], scores[key], dense_ranks.get(key), sparse_ranks.get(key))
        for key in ordered
    ]
