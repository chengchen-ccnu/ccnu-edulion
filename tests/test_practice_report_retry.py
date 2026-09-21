"""组题终态 Java 复核较慢时，私有用量报告必须有界等待且保持幂等。"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

import httpx

from edu_agent_app.runtime.agent_access_client import AgentAccessClient


class PracticeReportRetryTests(unittest.TestCase):
    def test_retry_exhausts_after_six_attempts(self):
        """终态一直未就绪也只提交六次；同一 reportId、十五秒总退避，不后台循环。"""
        async def scenario():
            request = httpx.Request("PUT", "http://agent.test/report")
            client = AsyncMock()
            client.__aenter__.return_value = client
            client.put.return_value = httpx.Response(409, json={"code": "AGENT.RUN_NOT_TERMINAL"}, request=request)
            with patch("edu_agent_app.runtime.agent_access_client.httpx.AsyncClient", return_value=client), \
                    patch("edu_agent_app.runtime.agent_access_client.asyncio.sleep", new_callable=AsyncMock) as sleep:
                with self.assertRaises(httpx.HTTPStatusError):
                    await AgentAccessClient("http://agent.test", "test-only").report({"runId": "test-run"})
                self.assertEqual(6, client.put.call_count)
                self.assertEqual([1, 2, 3, 4, 5], [call.args[0] for call in sleep.call_args_list])
                self.assertEqual(1, len({call.kwargs["json"]["reportId"] for call in client.put.call_args_list}))
        asyncio.run(scenario())

    def test_terminal_delay_reuses_payload_and_conflict_is_not_retried(self):
        async def scenario():
            request = httpx.Request("PUT", "http://agent.test/report")
            waiting = httpx.Response(409, json={"code": "AGENT.RUN_NOT_TERMINAL"}, request=request)
            client = AsyncMock()
            client.__aenter__.return_value = client
            client.put.side_effect = [waiting, waiting, httpx.Response(200, json={}, request=request)]
            with patch("edu_agent_app.runtime.agent_access_client.httpx.AsyncClient", return_value=client), \
                    patch("edu_agent_app.runtime.agent_access_client.asyncio.sleep", new_callable=AsyncMock) as sleep:
                await AgentAccessClient("http://agent.test", "test-only").report({"runId": "test-run"})
                payloads = [call.kwargs["json"] for call in client.put.call_args_list]
                self.assertEqual(payloads[0], payloads[1])
                self.assertEqual(payloads[1], payloads[2])
                self.assertEqual([1, 2], [call.args[0] for call in sleep.call_args_list])
                client.put.reset_mock()
                client.put.side_effect = [httpx.Response(409,
                    json={"code": "AGENT.REPORT_IDEMPOTENCY_CONFLICT"}, request=request)]
                with self.assertRaises(httpx.HTTPStatusError):
                    await AgentAccessClient("http://agent.test", "test-only").report({"runId": "test-run"})
                self.assertEqual(1, client.put.call_count)
        asyncio.run(scenario())
