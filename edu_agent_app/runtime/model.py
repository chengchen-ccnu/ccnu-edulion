"""阿里云百炼 OpenAI 兼容聊天/视觉客户端及安全用量记录。"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from time import perf_counter
from typing import Any, AsyncIterator, Sequence

from .budget import RunLimits


class ModelUnavailable(RuntimeError):
    """模型配置或供应商暂时不可用，错误中不得包含请求正文或秘密。"""


@dataclass(frozen=True)
class ModelSettings:
    """模型名可记录，API Key 仅保留在客户端对象内且绝不进入 repr/日志。"""

    base_url: str
    api_key: str | None = field(repr=False)
    chat_model_name: str
    vision_model_name: str
    request_timeout_seconds: float


class OpenAICompatibleModel:
    """用当前共享百炼配置执行结构化输出、文本流和授权图片理解。"""

    def __init__(self, settings: ModelSettings):
        self.settings = settings
        self._client = None
        if settings.api_key:
            from openai import AsyncOpenAI

            self._client = AsyncOpenAI(
                api_key=settings.api_key,
                base_url=settings.base_url,
                timeout=settings.request_timeout_seconds,
                max_retries=0,
            )

    def _require_client(self):
        if self._client is None:
            raise ModelUnavailable("OPENAI_API_KEY 未通过安全环境注入")
        return self._client

    async def close(self) -> None:
        """关闭供应商 HTTP 连接池；没有配置客户端时保持幂等。"""

        if self._client is not None:
            await self._client.close()

    async def complete_json(self, prompt: str, *, limits: RunLimits | None = None) -> dict[str, Any]:
        """请求 JSON 对象；调用方负责 Pydantic 校验与最多一次有限修复。"""

        client = self._require_client()
        started = perf_counter()
        try:
            response = await client.chat.completions.create(
                model=self.settings.chat_model_name,
                messages=[{"role": "user", "content": prompt}],
                temperature=0,
                response_format={"type": "json_object"},
                stream=False,
            )
            content = response.choices[0].message.content or "{}"
            if limits is not None:
                usage = response.usage
                limits.add_tokens(
                    getattr(usage, "prompt_tokens", 0), getattr(usage, "completion_tokens", 0)
                )
            value = json.loads(content)
            if not isinstance(value, dict):
                raise ValueError("结构化输出不是对象")
            return value
        except ModelUnavailable:
            raise
        except Exception as exc:
            raise ModelUnavailable(f"结构化模型调用失败：{type(exc).__name__}") from exc
        finally:
            if limits is not None:
                limits.add_phase_duration("MODEL_INTENT", int((perf_counter() - started) * 1000))

    async def stream_text(
        self,
        messages: Sequence[dict[str, Any]],
        limits: RunLimits,
        *,
        phase: str,
    ) -> AsyncIterator[str]:
        """流式输出非空文本增量；只记录计数、Token 与阶段耗时，不记录完整回答。"""

        limits.llm(allow_reserved=True)
        client = self._require_client()
        started = perf_counter()
        try:
            stream = await client.chat.completions.create(
                model=self.settings.chat_model_name,
                messages=list(messages),
                temperature=0.2,
                stream=True,
                stream_options={"include_usage": True},
            )
            async for chunk in stream:
                limits.check_time()
                usage = getattr(chunk, "usage", None)
                if usage is not None:
                    limits.add_tokens(
                        getattr(usage, "prompt_tokens", 0),
                        getattr(usage, "completion_tokens", 0),
                    )
                for choice in chunk.choices:
                    delta = choice.delta.content
                    if delta:
                        yield str(delta)
        except ModelUnavailable:
            raise
        except Exception as exc:
            raise ModelUnavailable(f"文本模型调用失败：{type(exc).__name__}") from exc
        finally:
            limits.add_phase_duration(phase, int((perf_counter() - started) * 1000))

    async def understand_image(
        self,
        image_data_url: str,
        task: str,
        limits: RunLimits,
    ) -> tuple[str, bool]:
        """把已安全读取的图片字节交给视觉模型，返回描述和“是否足够可靠”判断。"""

        limits.llm()
        limits.usage.vision_calls += 1
        client = self._require_client()
        started = perf_counter()
        instruction = (
            "读取图片中的初中数学内容。不要猜模糊字符；若题意、数字、符号或学生步骤不可靠，"
            "必须将 reliable 设为 false。仅返回 JSON：{summary:string,reliable:boolean}。"
        )
        try:
            response = await client.chat.completions.create(
                model=self.settings.vision_model_name,
                messages=[{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": f"{instruction}\n任务：{task}"},
                        {"type": "image_url", "image_url": {"url": image_data_url}},
                    ],
                }],
                temperature=0,
                response_format={"type": "json_object"},
                stream=False,
            )
            usage = response.usage
            limits.add_tokens(
                getattr(usage, "prompt_tokens", 0), getattr(usage, "completion_tokens", 0)
            )
            value = json.loads(response.choices[0].message.content or "{}")
            summary = str(value.get("summary", "")).strip()[:4000]
            reliable = value.get("reliable") is True and bool(summary)
            return summary, reliable
        except Exception as exc:
            raise ModelUnavailable(f"视觉模型调用失败：{type(exc).__name__}") from exc
        finally:
            limits.add_phase_duration("MODEL_VISION", int((perf_counter() - started) * 1000))


class ScriptedModel:
    """纯单元测试使用的确定性模型；不访问网络、模型资产或秘密。"""

    def __init__(self, *, json_values: list[dict[str, Any]] | None = None, texts: list[str] | None = None):
        self.json_values = list(json_values or [])
        self.texts = list(texts or ["我先给出一个可靠的分步说明。"])
        self.json_calls = 0
        self.text_calls = 0
        self.vision_calls = 0

    async def complete_json(self, _prompt: str, *, limits: RunLimits | None = None) -> dict[str, Any]:
        self.json_calls += 1
        if not self.json_values:
            return {}
        return self.json_values.pop(0)

    async def stream_text(self, _messages, limits: RunLimits, *, phase: str):
        limits.llm(allow_reserved=True)
        self.text_calls += 1
        text = self.texts[min(self.text_calls - 1, len(self.texts) - 1)]
        midpoint = max(1, len(text) // 2)
        for part in (text[:midpoint], text[midpoint:]):
            if part:
                yield part

    async def understand_image(self, _data: str, _task: str, limits: RunLimits):
        limits.llm()
        limits.usage.vision_calls += 1
        self.vision_calls += 1
        return "图片中的数学题可清晰读取。", True
