"""T13.5.1 标准 Skill Runtime 的目录、安全、预算、取消与审计回归。"""

from __future__ import annotations

import asyncio
import os
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from edu_agent_app.runtime.budget import RunLimits
from edu_agent_app.runtime.model import ScriptedModel
from edu_agent_app.runtime.protocol import RunBudget
from edu_agent_app.runtime.skill_runtime import (
    SkillEligibility,
    SkillExecution,
    SkillRegistry,
    SkillRoundState,
    SkillRuntimeConfig,
    SkillRuntimeError,
    SkillScriptRunner,
    SkillSelector,
)


def limits(*, tools: int = 10, calls: int = 8) -> RunLimits:
    return RunLimits(RunBudget(
        maxOrchestrationSteps=12, maxLlmCalls=calls, reservedFinalLlmCalls=1,
        maxToolAttempts=tools, maxExpertTransfers=2, maxSameToolArgumentsAttempts=2,
        maxTeachingRemediations=3,
        teachingRemediationOrder=["REPHRASE", "SIMPLE_EXAMPLE", "PREREQUISITE_FALLBACK"],
        totalTimeMs=30_000,
    ))


def write_skill(root: Path, name: str, *, description: str = "测试能力", version: str = "1.0.0",
                timeout: int | None = None) -> Path:
    directory = root / name
    directory.mkdir()
    metadata = f"\n  script-timeout-seconds: {timeout}" if timeout is not None else ""
    (directory / "SKILL.md").write_text(
        f"---\nname: {name}\ndescription: {description}\nmetadata:\n  version: {version}{metadata}\n---\n仅执行测试说明。\n",
        encoding="utf-8",
    )
    return directory


class FakeAudit:
    def __init__(self, *, fail_start: bool = False):
        self.fail_start = fail_start
        self.started = []
        self.finished = []

    async def start_skill_execution(self, run_id, payload, trace_id):
        if self.fail_start:
            raise RuntimeError("audit unavailable")
        self.started.append((run_id, dict(payload), trace_id))

    async def finish_skill_execution(self, run_id, execution_id, payload, trace_id):
        self.finished.append((run_id, execution_id, dict(payload), trace_id))


class SkillDiscoveryTest(unittest.TestCase):
    def test_runtime_configuration_rejects_cross_limit_errors(self):
        with self.assertRaises(ValueError):
            SkillRuntimeConfig(script_default_timeout_seconds=20,
                               script_max_timeout_seconds=10).validate()
        with self.assertRaises(ValueError):
            SkillRuntimeConfig(reference_max_file_bytes=9,
                               reference_max_total_bytes=8).validate()

    def test_discovery_and_progressive_load(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_skill(root, "alpha-skill", description="alpha desc")
            registry = SkillRegistry.discover(root)
            self.assertEqual(["alpha-skill"], [item.name for item in registry.descriptors()])
            self.assertEqual("alpha desc", registry.descriptors()[0].description)
            self.assertEqual("仅执行测试说明。", registry.load("alpha-skill").instructions)

    def test_invalid_frontmatter_duplicate_name_version_timeout_and_fields(self):
        cases = [
            ("broken", "not yaml", "SKILL_FRONTMATTER_INVALID"),
            ("wrong-name", "---\nname: another\ndescription: x\n---\nx", "SKILL_NAME_INVALID"),
            ("bad-version", "---\nname: bad-version\ndescription: x\nmetadata:\n  version: v1\n---\nx", "SKILL_VERSION_INVALID"),
            ("bad-timeout", "---\nname: bad-timeout\ndescription: x\nmetadata:\n  script-timeout-seconds: 31\n---\nx", "SKILL_TIMEOUT_INVALID"),
            ("bad-field", "---\nname: bad-field\ndescription: x\npermissions: all\n---\nx", "SKILL_FIELD_INVALID"),
        ]
        for directory_name, text, code in cases:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary) / directory_name
                directory.mkdir()
                (directory / "SKILL.md").write_text(text, encoding="utf-8")
                with self.assertRaises(SkillRuntimeError) as raised:
                    SkillRegistry.discover(Path(temporary))
                self.assertEqual(code, raised.exception.code)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = write_skill(root, "first")
            second = write_skill(root, "second")
            (second / "SKILL.md").write_text((first / "SKILL.md").read_text(encoding="utf-8"), encoding="utf-8")
            with self.assertRaises(SkillRuntimeError):
                SkillRegistry.discover(root)

    def test_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = write_skill(root, "linked")
            target = directory / "target.txt"
            target.write_text("x", encoding="utf-8")
            try:
                (directory / "references").symlink_to(root, target_is_directory=True)
            except OSError:
                self.skipTest("当前 Windows 未允许创建符号链接")
            with self.assertRaises(SkillRuntimeError) as raised:
                SkillRegistry.discover(root)
            self.assertEqual("SKILL_SYMLINK", raised.exception.code)

    def test_declared_missing_resource_fails_startup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = write_skill(root, "missing-resource")
            text = (directory / "SKILL.md").read_text(encoding="utf-8")
            (directory / "SKILL.md").write_text(text + "\n[规则](references/missing.md)\n", encoding="utf-8")
            with self.assertRaises(SkillRuntimeError) as raised:
                SkillRegistry.discover(root)
            self.assertEqual("SKILL_RESOURCE_MISSING", raised.exception.code)

    def test_non_standard_top_level_resource_fails_startup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = write_skill(root, "unknown-resource")
            (directory / "plugins").mkdir()
            with self.assertRaises(SkillRuntimeError) as raised:
                SkillRegistry.discover(root)
            self.assertEqual("SKILL_RESOURCE_INVALID", raised.exception.code)


class SkillSelectionTest(unittest.IsolatedAsyncioTestCase):
    async def test_explicit_rule_llm_none_and_failed_selection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_skill(root, "alpha", description="代数")
            write_skill(root, "beta", description="几何")
            registry = SkillRegistry.discover(root)
            policy = {name: SkillEligibility(frozenset({"MATH_TUTOR"}), frozenset({"SOLVE"}))
                      for name in ("alpha", "beta")}
            model = ScriptedModel(json_values=[{"skillName": "beta"}, {"skillName": "missing"}])
            selector = SkillSelector(registry, policy, model)
            current = limits()
            self.assertEqual("alpha", (await selector.select(role="OTHER", intent="NO", limits=current,
                                                               explicit_name="alpha")).descriptor.name)
            self.assertIsNone(await selector.select(role="OTHER", intent="NO", limits=current))
            selected = await selector.select(role="MATH_TUTOR", intent="SOLVE", limits=current)
            self.assertEqual(("beta", "LLM"), (selected.descriptor.name, selected.source))
            self.assertEqual(1, current.usage.llm_calls)
            self.assertIsNone(await selector.select(role="MATH_TUTOR", intent="SOLVE", limits=current))
            self.assertEqual(2, current.usage.llm_calls)

            one = SkillSelector(registry, {"alpha": policy["alpha"]}, model)
            before = current.usage.llm_calls
            self.assertEqual("RULE", (await one.select(role="MATH_TUTOR", intent="SOLVE", limits=current)).source)
            self.assertEqual(before, current.usage.llm_calls)

    async def test_round_and_subrequest_limits(self):
        state = SkillRoundState()
        state.claim(1, "alpha")
        state.claim(1, "alpha")
        with self.assertRaises(SkillRuntimeError):
            state.claim(1, "beta")
        state.claim(2, "beta")
        with self.assertRaises(SkillRuntimeError):
            state.claim(3, "gamma")


class SkillExecutionTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.directory = write_skill(self.root, "scripted", timeout=1)
        (self.directory / "scripts").mkdir()
        (self.directory / "references").mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def execution(self, audit=None, config=None):
        registry = SkillRegistry.discover(self.root)
        effective = config or SkillRuntimeConfig()
        return SkillExecution(
            loaded=registry.load("scripted"), selection_source="EXPLICIT", run_id=str(uuid4()), sequence=1,
            trace_id="trace-skill-test", limits=limits(), config=effective,
            runner=SkillScriptRunner(effective), audit=audit or FakeAudit(),
        )

    async def test_reference_limits_paths_binary_and_budget(self):
        for index, content in enumerate(("甲", "乙", "丙", "丁"), start=1):
            (self.directory / "references" / f"r{index}.txt").write_text(content, encoding="utf-8")
        execution = self.execution(config=SkillRuntimeConfig(reference_max_files=2,
                                                              reference_max_file_bytes=4,
                                                              reference_max_total_bytes=8))
        await execution.start()
        self.assertEqual("甲", execution.read_skill_reference("references/r1.txt"))
        with self.assertRaises(SkillRuntimeError):
            execution.read_skill_reference("../SKILL.md")
        with self.assertRaises(SkillRuntimeError):
            execution.read_skill_reference(str((self.directory / "SKILL.md").resolve()))
        self.assertEqual("乙", execution.read_skill_reference("references/r2.txt"))
        with self.assertRaises(SkillRuntimeError) as raised:
            execution.read_skill_reference("references/r3.txt")
        self.assertEqual("SKILL_REFERENCE_COUNT_LIMIT", raised.exception.code)
        self.assertGreaterEqual(execution.limits.usage.tool_attempts, 3)

        (self.directory / "references" / "large.txt").write_text("12345", encoding="utf-8")
        too_large = self.execution(config=SkillRuntimeConfig(reference_max_file_bytes=4,
                                                              reference_max_total_bytes=8))
        await too_large.start()
        with self.assertRaises(SkillRuntimeError) as raised:
            too_large.read_skill_reference("references/large.txt")
        self.assertEqual("SKILL_REFERENCE_FILE_TOO_LARGE", raised.exception.code)

        total = self.execution(config=SkillRuntimeConfig(reference_max_file_bytes=4,
                                                          reference_max_total_bytes=5))
        await total.start()
        total.read_skill_reference("references/r1.txt")
        with self.assertRaises(SkillRuntimeError) as raised:
            total.read_skill_reference("references/r2.txt")
        self.assertEqual("SKILL_REFERENCE_TOTAL_TOO_LARGE", raised.exception.code)

        (self.directory / "references" / "binary.txt").write_bytes(b"a\x00b")
        binary = self.execution()
        await binary.start()
        with self.assertRaises(SkillRuntimeError) as raised:
            binary.read_skill_reference("references/binary.txt")
        self.assertEqual("SKILL_REFERENCE_BINARY", raised.exception.code)

    async def test_script_success_envelope_secret_environment_and_cleanup(self):
        os.environ["OPENAI_API_KEY"] = "must-not-leak"
        (self.directory / "scripts" / "ok.py").write_text(
            "import json,os,sys\nvalue=json.load(sys.stdin)\njson.dump({'skillName':'forged','executionId':'forged','input':value,'secret':os.getenv('OPENAI_API_KEY'),'temp':os.getenv('TEMP')},sys.stdout)\n",
            encoding="utf-8",
        )
        audit = FakeAudit()
        execution = self.execution(audit=audit)
        await execution.start()
        envelope = await execution.run_skill_script("scripts/ok.py", {"n": 2})
        self.assertEqual("scripted", envelope.skillName)
        self.assertEqual(execution.execution_id, envelope.executionId)
        self.assertEqual("forged", envelope.payload["skillName"])
        self.assertIsNone(envelope.payload["secret"])
        self.assertFalse(Path(envelope.payload["temp"]).exists())
        self.assertEqual("SUCCEEDED", audit.finished[0][2]["status"])
        with self.assertRaises(SkillRuntimeError):
            await execution.run_skill_script("scripts/ok.py", {"n": 2})

    async def test_nonzero_timeout_invalid_json_output_too_large_and_cancel(self):
        scripts = {
            "nonzero.py": "raise SystemExit(4)\n",
            "invalid.py": "print('not-json')\n",
            "large.py": "print('\\\"'+'x'*3000+'\\\"')\n",
            "slow.py": "import time; time.sleep(5); print('{}')\n",
        }
        for name, source in scripts.items():
            (self.directory / "scripts" / name).write_text(source, encoding="utf-8")
        expected = {"nonzero.py": "SKILL_SCRIPT_NON_ZERO", "invalid.py": "SKILL_SCRIPT_INVALID_JSON",
                    "large.py": "SKILL_SCRIPT_OUTPUT_TOO_LARGE", "slow.py": "SKILL_SCRIPT_TIMEOUT"}
        for name, code in expected.items():
            config = SkillRuntimeConfig(script_max_output_bytes=1024)
            execution = self.execution(config=config)
            await execution.start()
            with self.assertRaises(SkillRuntimeError) as raised:
                await execution.run_skill_script(f"scripts/{name}", {})
            self.assertEqual(code, raised.exception.code)

        execution = self.execution()
        await execution.start()
        task = asyncio.create_task(execution.run_skill_script("scripts/slow.py", {}))
        await asyncio.sleep(0.1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task

    async def test_network_and_child_process_are_blocked(self):
        scripts = {
            "network.py": "import socket\nsocket.create_connection(('127.0.0.1',1))\nprint('{}')\n",
            "child.py": "import subprocess,sys\nsubprocess.run([sys.executable,'-c','print(1)'])\nprint('{}')\n",
        }
        for name, source in scripts.items():
            (self.directory / "scripts" / name).write_text(source, encoding="utf-8")
            execution = self.execution()
            await execution.start()
            with self.assertRaises(SkillRuntimeError) as raised:
                await execution.run_skill_script(f"scripts/{name}", {})
            self.assertEqual("SKILL_SCRIPT_NON_ZERO", raised.exception.code)

    async def test_audit_start_failure_prevents_script(self):
        marker = self.root / "must-not-exist"
        (self.directory / "scripts" / "write.py").write_text(
            "from pathlib import Path\nPath('ran').write_text('x')\nprint('{}')\n", encoding="utf-8")
        execution = self.execution(audit=FakeAudit(fail_start=True))
        with self.assertRaises(RuntimeError):
            await execution.start()
        with self.assertRaises(SkillRuntimeError) as raised:
            await execution.run_skill_script("scripts/write.py", {})
        self.assertEqual("SKILL_AUDIT_REQUIRED", raised.exception.code)
        self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
