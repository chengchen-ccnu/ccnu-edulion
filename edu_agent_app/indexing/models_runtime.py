"""封装本地 BGE dense 编码与 reranker，模型仅在 Worker/检索进程需要时加载。"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Sequence


QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："


class DenseEncoder:
    """使用本地 bge-base-zh 生成 768 维归一化向量。

    查询按模型说明增加检索指令，文档保持原文；调用方用显式 model_version 隔离索引，升级
    模型后必须重建，不能把不同模型向量写入同一版本。
    """

    def __init__(self, path: Path, model_version: str):
        import torch
        from sentence_transformers import SentenceTransformer

        self.model_version = model_version
        device = "cuda" if torch.cuda.is_available() else "cpu"
        self._model = SentenceTransformer(str(path), device=device, local_files_only=True)
        dimension = self._model.get_sentence_embedding_dimension()
        if dimension != 768:
            raise ValueError("bge-base-zh 输出必须为 768 维")

    def encode_documents(self, texts: Sequence[str], batch_size: int) -> list[list[float]]:
        """批量编码安全投影正文；不增加查询指令，也不记录或持久化模型输入。"""
        vectors = self._model.encode(
            list(texts), batch_size=batch_size, normalize_embeddings=True, show_progress_bar=False
        )
        result = vectors.tolist()
        if any(len(vector) != 768 for vector in result):
            raise RuntimeError("dense 文档向量维度不一致")
        return result

    def encode_query(self, query: str) -> list[float]:
        """只对查询增加 BGE 检索指令，返回单个 768 维归一化向量。"""
        vector = self._model.encode(
            QUERY_INSTRUCTION + query, normalize_embeddings=True, show_progress_bar=False
        ).tolist()
        if len(vector) != 768:
            raise RuntimeError("dense 查询向量维度不一致")
        return vector


class LocalReranker:
    """使用本地 bge-reranker-base 对约 20～30 个候选做交叉编码重排。"""

    def __init__(self, path: Path, model_version: str):
        import torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self.model_version = model_version
        self._torch = torch
        self._device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self._tokenizer = AutoTokenizer.from_pretrained(str(path), local_files_only=True)
        self._model = AutoModelForSequenceClassification.from_pretrained(
            str(path), local_files_only=True
        ).to(self._device)
        self._model.eval()

    def score(self, query: str, documents: Sequence[str], batch_size: int = 8) -> list[float]:
        """返回 0..1 相关性概率；批次失败整体抛出，调用方不得把未重排结果伪装成成功。"""
        scores: list[float] = []
        torch = self._torch
        for start in range(0, len(documents), batch_size):
            pairs = [[query, text] for text in documents[start : start + batch_size]]
            inputs = self._tokenizer(
                pairs,
                padding=True,
                truncation=True,
                max_length=512,
                return_tensors="pt",
            ).to(self._device)
            with torch.no_grad():
                logits = self._model(**inputs).logits.reshape(-1).detach().cpu().tolist()
            scores.extend(1.0 / (1.0 + math.exp(-max(min(float(value), 60.0), -60.0))) for value in logits)
        return scores
