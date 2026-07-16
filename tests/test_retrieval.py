from dataclasses import dataclass, field
import unittest

from edu_agent_app.retrieval import BM25Index, HybridRetriever, tokenize


@dataclass
class FakeDocument:
    page_content: str
    metadata: dict = field(default_factory=dict)


class FakeVectorStore:
    def __init__(self, results):
        self.results = results

    def similarity_search_with_relevance_scores(self, _query, k):
        return self.results[:k]


class RetrievalTests(unittest.TestCase):
    def test_chinese_tokenizer_keeps_math_phrases(self):
        tokens = tokenize("利用一元二次方程求根公式")
        self.assertIn("一元", tokens)
        self.assertIn("求根", tokens)

    def test_bm25_prefers_exact_math_terms(self):
        math_doc = FakeDocument("一元二次方程的求根公式与判别式")
        other_doc = FakeDocument("三角形全等的判定")
        results = BM25Index([other_doc, math_doc]).search("一元二次方程求根", 2)
        self.assertEqual(results[0][0], math_doc)

    def test_hybrid_rerank_combines_keyword_and_vector(self):
        math_doc = FakeDocument(
            "【知识点】一元二次方程求根公式\n用判别式和求根公式解方程",
            {"exercise_id": "math"},
        )
        semantic_but_wrong = FakeDocument(
            "这是一段与数学题无关的生活对话",
            {"exercise_id": "other"},
        )
        store = FakeVectorStore([(semantic_but_wrong, 0.80), (math_doc, 0.60)])
        retriever = HybridRetriever(
            store,
            documents=[math_doc, semantic_but_wrong],
            score_threshold=0.50,
            vector_k=5,
            keyword_k=5,
            rerank_top_k=5,
        )
        hits = retriever.search("一元二次方程怎么求根")
        self.assertTrue(hits)
        self.assertEqual(hits[0].document, math_doc)
        self.assertTrue(all(hit.score >= 0.50 for hit in hits))

    def test_similarity_threshold_rejects_weak_results(self):
        doc = FakeDocument("今天午饭吃什么", {"exercise_id": "chat"})
        retriever = HybridRetriever(
            FakeVectorStore([(doc, 0.20)]),
            documents=[doc],
            score_threshold=0.40,
            vector_k=5,
            keyword_k=5,
            rerank_top_k=5,
        )
        self.assertEqual(retriever.search("完全不同的问题"), [])


if __name__ == "__main__":
    unittest.main()
