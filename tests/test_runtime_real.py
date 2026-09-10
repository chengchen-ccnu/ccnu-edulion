"""显式开关下对共享百炼文本/视觉模型执行两个有界真实样本。"""

from __future__ import annotations

import base64
import io
import os
import unittest

from edu_agent_app.config import load_runtime_settings
from edu_agent_app.runtime.budget import RunLimits
from edu_agent_app.runtime.model import ModelSettings, OpenAICompatibleModel
from edu_agent_app.runtime.protocol import RunBudget


@unittest.skipUnless(
    os.getenv("AGENT_RUNTIME_REAL_ACCEPTANCE", "false").lower() == "true",
    "设置 AGENT_RUNTIME_REAL_ACCEPTANCE=true 才执行真实百炼模型验收",
)
class RuntimeRealAcceptanceTests(unittest.TestCase):
    """只断言模型、Token、延迟和可靠性，不打印密钥、请求或完整回答。"""

    def _limits(self):
        return RunLimits(RunBudget(
            maxOrchestrationSteps=12, maxLlmCalls=8, reservedFinalLlmCalls=1,
            maxToolAttempts=10, maxExpertTransfers=2, maxSameToolArgumentsAttempts=2,
            maxTeachingRemediations=3,
            teachingRemediationOrder=["REPHRASE", "SIMPLE_EXAMPLE", "PREREQUISITE_FALLBACK"],
            totalTimeMs=90_000,
        ))

    def _model(self):
        settings = load_runtime_settings()
        self.assertIsNotNone(settings.openai_api_key)
        return OpenAICompatibleModel(ModelSettings(
            base_url=settings.openai_base_url,
            api_key=settings.openai_api_key,
            chat_model_name=settings.chat_model_name,
            vision_model_name=settings.vision_model_name,
            request_timeout_seconds=settings.model_request_timeout_seconds,
        ))

    def test_real_text_stream_records_tokens_and_latency(self):
        async def scenario():
            model = self._model()
            limits = self._limits()
            chunks = [item async for item in model.stream_text([
                {"role": "system", "content": "你是初中数学教师，只给一句简短提示。"},
                {"role": "user", "content": "解方程 x+2=5，先提示第一步。"},
            ], limits, phase="REAL_TEXT")]
            self.assertTrue("".join(chunks).strip())
            self.assertEqual(limits.usage.llm_calls, 1)
            self.assertGreater(limits.usage.prompt_tokens + limits.usage.completion_tokens, 0)
            self.assertGreaterEqual(limits.usage.phase_latency_ms["REAL_TEXT"], 0)
            print(
                "REAL_RUNTIME_METRICS",
                f"kind=text model={model.settings.chat_model_name}",
                f"calls={limits.usage.llm_calls}",
                f"promptTokens={limits.usage.prompt_tokens}",
                f"completionTokens={limits.usage.completion_tokens}",
                f"latencyMs={limits.usage.phase_latency_ms['REAL_TEXT']}",
            )

        __import__("asyncio").run(scenario())

    def test_real_vision_reads_memory_png_without_external_url(self):
        async def scenario():
            from PIL import Image, ImageDraw

            image = Image.new("RGB", (256, 96), "white")
            ImageDraw.Draw(image).text((20, 30), "x + 2 = 5", fill="black")
            buffer = io.BytesIO()
            image.save(buffer, format="PNG")
            data_url = "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")
            limits = self._limits()
            model = self._model()
            summary, reliable = await model.understand_image(
                data_url, "READ_MATH_CONTENT", limits
            )
            self.assertTrue(summary)
            self.assertTrue(reliable)
            self.assertEqual(limits.usage.llm_calls, 1)
            self.assertGreater(limits.usage.prompt_tokens + limits.usage.completion_tokens, 0)
            self.assertGreaterEqual(limits.usage.phase_latency_ms["MODEL_VISION"], 0)
            print(
                "REAL_RUNTIME_METRICS",
                f"kind=vision model={model.settings.vision_model_name}",
                f"calls={limits.usage.llm_calls}",
                f"promptTokens={limits.usage.prompt_tokens}",
                f"completionTokens={limits.usage.completion_tokens}",
                f"latencyMs={limits.usage.phase_latency_ms['MODEL_VISION']}",
                f"reliable={str(reliable).lower()}",
            )

        __import__("asyncio").run(scenario())


if __name__ == "__main__":
    unittest.main()
