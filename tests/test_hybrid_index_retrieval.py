import unittest
from types import SimpleNamespace

from edu_agent_app.indexing.models import RetrievalRequest
from edu_agent_app.indexing.qdrant_store import RawCandidate
from edu_agent_app.indexing.retriever import HybridSearchService, _rrf


def candidate(point_id, text, entity_id=None):
    return RawCandidate(
        point_id,
        1.0,
        {
            "entity_id": entity_id or point_id,
            "source_revision": 1,
            "title": "代表样本",
            "text": text,
            "visibility": "PUBLIC_SYSTEM",
        },
    )


class FakeEncoder:
    def encode_query(self, query):
        self.query = query
        return [1.0] + [0.0] * 767


class FakeReranker:
    def score(self, query, documents):
        self.documents = list(documents)
        return [
            0.96 if "一元二次方程" in text and "根" in text
            else 0.88 if "判别式" in text
            else 0.19
            for text in documents
        ]


class FakeIndex:
    def search_dense(self, _request, _vector, _limit):
        return [
            candidate("semantic", "一元二次方程的两个根与系数关系"),
            candidate("wrong", "相近但错误：一次方程的根"),
            candidate("latex", r"方程 $x^2-5x+6=0$ 的两个根"),
        ]

    def search_bm25(self, _request, _limit):
        return [
            candidate("latex", r"方程 $x^2-5x+6=0$ 的两个根"),
            candidate("semantic", "一元二次方程的两个根与系数关系"),
            candidate("other", "三角形全等判定"),
        ]


class HybridIndexRetrievalTests(unittest.TestCase):
    def settings(self, threshold=0.5):
        return SimpleNamespace(
            dense_candidate_k=30,
            sparse_candidate_k=30,
            rerank_candidate_k=25,
            rrf_k=60,
            final_max_results=8,
            threshold_for=lambda _collection: threshold,
        )

    def test_rrf_rewards_candidates_from_dense_and_bm25(self):
        dense = [candidate("a", "语义"), candidate("b", "词法")]
        sparse = [candidate("b", "词法"), candidate("c", "其他")]
        fused = _rrf(dense, sparse, 60)
        self.assertEqual(fused[0][0].point_id, "b")
        self.assertEqual((fused[0][2], fused[0][3]), (2, 1))

    def test_all_four_stages_participate_and_wrong_candidate_is_filtered(self):
        reranker = FakeReranker()
        service = HybridSearchService(self.settings(), FakeIndex(), FakeEncoder(), reranker)
        response = service.search(
            RetrievalRequest("math_questions", "一元二次方程 $x^2-5x+6=0$ 的根", "SOLVE_PROBLEM", 8)
        )
        self.assertGreater(response.trace.dense_count, 0)
        self.assertGreater(response.trace.bm25_count, 0)
        self.assertGreater(response.trace.fused_count, 0)
        self.assertGreater(response.trace.reranked_count, 0)
        self.assertGreater(response.trace.accepted_count, 0)
        self.assertNotIn("相近但错误", [hit.summary for hit in response.hits])
        self.assertTrue(any(hit.dense_rank and hit.bm25_rank for hit in response.hits))

    def test_no_reliable_hit_returns_empty_instead_of_padding_to_three(self):
        service = HybridSearchService(self.settings(0.99), FakeIndex(), FakeEncoder(), FakeReranker())
        response = service.search(
            RetrievalRequest("math_questions", "今天午饭吃什么", "SOLVE_PROBLEM", 8)
        )
        self.assertEqual(response.hits, [])
        self.assertEqual(response.trace.accepted_count, 0)


if __name__ == "__main__":
    unittest.main()
