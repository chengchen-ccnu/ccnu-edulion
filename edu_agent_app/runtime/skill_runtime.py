"""标准目录 Skill 的发现、选择、渐进读取、受控脚本与私有执行审计。"""

from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import stat
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Protocol, Sequence
from uuid import UUID, uuid4

import yaml

from .budget import RunLimits


_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
_VERSION = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
_FRONTMATTER = re.compile(r"\A---\r?\n(.*?)\r?\n---(?:\r?\n|\Z)(.*)\Z", re.DOTALL)
_RESOURCE_LINK = re.compile(r"\]\(((?:scripts|references|assets)/[^)#?\s]+)(?:#[^)]*)?\)")
_TEXT_SUFFIXES = frozenset({".md", ".txt", ".json", ".yaml", ".yml", ".csv"})
_FINAL_AUDIT_STATUSES = frozenset({"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"})


class SkillRuntimeError(RuntimeError):
    """Skill 目录、调用或安全边界不合法；消息不得带绝对路径或秘密。"""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class SkillRuntimeConfig:
    """统一限制配置；启动时验证交叉约束，避免局部配置绕过全局上限。"""

    reference_max_files: int = 3
    reference_max_file_bytes: int = 128 * 1024
    reference_max_total_bytes: int = 256 * 1024
    script_default_timeout_seconds: int = 10
    script_max_timeout_seconds: int = 30
    script_max_output_bytes: int = 64 * 1024
    script_global_concurrency: int = 4

    def validate(self) -> None:
        if not 1 <= self.reference_max_files <= 20:
            raise ValueError("reference 最大文件数必须位于 1..20")
        if not 1 <= self.reference_max_file_bytes <= self.reference_max_total_bytes:
            raise ValueError("reference 单文件上限必须为正且不超过总量")
        if not 1 <= self.script_default_timeout_seconds <= self.script_max_timeout_seconds <= 30:
            raise ValueError("Skill 脚本超时必须满足 1 <= 默认值 <= 最大值 <= 30")
        if not 1024 <= self.script_max_output_bytes <= 1024 * 1024:
            raise ValueError("Skill 脚本输出上限必须位于 1 KiB..1 MiB")
        if not 1 <= self.script_global_concurrency <= 32:
            raise ValueError("Skill 脚本全局并发必须位于 1..32")


@dataclass(frozen=True)
class SkillDescriptor:
    """发现阶段可见的最小描述；正文直到选中后才读取。"""

    name: str
    description: str
    version: str
    script_timeout_seconds: int | None
    directory: Path = field(repr=False, compare=False)
    skill_file: Path = field(repr=False, compare=False)


@dataclass(frozen=True)
class LoadedSkill:
    """选中后加载的完整模型执行说明及已校验描述。"""

    descriptor: SkillDescriptor
    instructions: str


@dataclass(frozen=True)
class SkillEligibility:
    """平台维护的选择边界；SKILL.md 不能声明角色、意图或权限。"""

    roles: frozenset[str]
    intents: frozenset[str]


@dataclass(frozen=True)
class SkillSelection:
    descriptor: SkillDescriptor
    source: str


@dataclass(frozen=True)
class SkillResultEnvelope:
    """身份字段全部由 Runtime 生成，脚本 payload 无法覆盖。"""

    skillName: str
    skillVersion: str
    script: str
    executionId: str
    resultType: str
    payload: Any

    def as_dict(self) -> dict[str, Any]:
        return {
            "skillName": self.skillName,
            "skillVersion": self.skillVersion,
            "script": self.script,
            "executionId": self.executionId,
            "resultType": self.resultType,
            "payload": self.payload,
        }


class SkillAuditPort(Protocol):
    """Agent Access 私有执行审计端口；失败时调用方必须 fail closed。"""

    async def start_skill_execution(self, run_id: str, payload: Mapping[str, Any], trace_id: str) -> None: ...
    async def finish_skill_execution(self, run_id: str, execution_id: str,
                                     payload: Mapping[str, Any], trace_id: str) -> None: ...


class SkillRegistry:
    """启动期扫描包内一层 Skill；任何不完整或越界目录都会阻止启动。"""

    def __init__(self, descriptors: Mapping[str, SkillDescriptor]):
        self._descriptors = dict(descriptors)

    @classmethod
    def discover(cls, root: Path | None = None) -> "SkillRegistry":
        root = root or Path(__file__).resolve().parents[1] / "skills"
        if not root.exists() or not root.is_dir() or root.is_symlink():
            raise SkillRuntimeError("SKILL_ROOT_INVALID", "内置 Skill 根目录缺失或不安全")
        root = root.resolve(strict=True)
        found: dict[str, SkillDescriptor] = {}
        for child in sorted(root.iterdir(), key=lambda item: item.name):
            if child.name == "__pycache__" or child.name.startswith(".") or child.is_file():
                continue
            if child.is_symlink() or not child.is_dir():
                raise SkillRuntimeError("SKILL_SYMLINK", "Skill 目录不能是符号链接")
            skill_file = child / "SKILL.md"
            if not skill_file.exists():
                raise SkillRuntimeError("SKILL_FILE_MISSING", "Skill 目录缺少 SKILL.md")
            descriptor, body = cls._parse(child, skill_file, load_body=True)
            if descriptor.name in found:
                raise SkillRuntimeError("SKILL_DUPLICATE", "Skill 名称重复")
            found[descriptor.name] = descriptor
            cls._validate_tree(root, child)
            cls._validate_declared_resources(child, body)
        return cls(found)

    @staticmethod
    def _validate_tree(root: Path, directory: Path) -> None:
        for item in directory.rglob("*"):
            if item.is_symlink():
                raise SkillRuntimeError("SKILL_SYMLINK", "Skill 内不能包含符号链接")
            try:
                item.resolve(strict=True).relative_to(root)
            except (OSError, ValueError) as exc:
                raise SkillRuntimeError("SKILL_PATH_ESCAPE", "Skill 资源越出包内根目录") from exc
        for optional in ("scripts", "references", "assets"):
            path = directory / optional
            if path.exists() and not path.is_dir():
                raise SkillRuntimeError("SKILL_RESOURCE_INVALID", "Skill 可选资源必须是目录")

    @staticmethod
    def _validate_declared_resources(directory: Path, body: str) -> None:
        """只校验正文中声明的包内资源存在；正文不会进入发现描述或模型上下文。"""

        for matched in _RESOURCE_LINK.finditer(body):
            relative = matched.group(1)
            try:
                path = _resolve_member(directory.resolve(), relative)
            except SkillRuntimeError as exc:
                raise SkillRuntimeError("SKILL_RESOURCE_MISSING", "SKILL.md 声明的资源缺失") from exc
            if path.is_symlink() or not path.is_file():
                raise SkillRuntimeError("SKILL_RESOURCE_MISSING", "SKILL.md 声明的资源缺失")

    @classmethod
    def _parse(cls, directory: Path, skill_file: Path, *, load_body: bool) -> tuple[SkillDescriptor, str]:
        if skill_file.is_symlink() or not skill_file.is_file():
            raise SkillRuntimeError("SKILL_FILE_INVALID", "SKILL.md 不是普通文件")
        try:
            text = skill_file.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            raise SkillRuntimeError("SKILL_FILE_INVALID", "SKILL.md 必须是 UTF-8 文本") from exc
        matched = _FRONTMATTER.match(text)
        if not matched:
            raise SkillRuntimeError("SKILL_FRONTMATTER_INVALID", "SKILL.md frontmatter 损坏")
        try:
            raw = yaml.safe_load(matched.group(1))
        except yaml.YAMLError as exc:
            raise SkillRuntimeError("SKILL_FRONTMATTER_INVALID", "SKILL.md frontmatter 损坏") from exc
        if not isinstance(raw, dict) or set(raw) - {"name", "description", "metadata"}:
            raise SkillRuntimeError("SKILL_FIELD_INVALID", "SKILL.md 含非法顶层字段")
        if set(raw) < {"name", "description"}:
            raise SkillRuntimeError("SKILL_FIELD_MISSING", "SKILL.md 缺少 name 或 description")
        name, description = raw.get("name"), raw.get("description")
        if not isinstance(name, str) or not _NAME.fullmatch(name) or name != directory.name:
            raise SkillRuntimeError("SKILL_NAME_INVALID", "Skill 名称必须合法且与目录名一致")
        if not isinstance(description, str) or not 1 <= len(description.strip()) <= 1000:
            raise SkillRuntimeError("SKILL_DESCRIPTION_INVALID", "Skill description 必须为非空短文本")
        metadata = raw.get("metadata", {})
        if metadata is None:
            metadata = {}
        if not isinstance(metadata, dict) or set(metadata) - {"version", "script-timeout-seconds"}:
            raise SkillRuntimeError("SKILL_FIELD_INVALID", "Skill metadata 含非法字段")
        version = metadata.get("version", "0.0.0")
        timeout = metadata.get("script-timeout-seconds")
        if not isinstance(version, str) or not _VERSION.fullmatch(version):
            raise SkillRuntimeError("SKILL_VERSION_INVALID", "Skill 版本必须是 SemVer 三段数字")
        if timeout is not None and (not isinstance(timeout, int) or isinstance(timeout, bool) or not 1 <= timeout <= 30):
            raise SkillRuntimeError("SKILL_TIMEOUT_INVALID", "Skill 超时申请必须位于 1..30 秒")
        descriptor = SkillDescriptor(name, description.strip(), version, timeout, directory.resolve(), skill_file.resolve())
        return descriptor, matched.group(2).strip() if load_body else ""

    def descriptors(self) -> tuple[SkillDescriptor, ...]:
        return tuple(self._descriptors[name] for name in sorted(self._descriptors))

    def get(self, name: str) -> SkillDescriptor | None:
        return self._descriptors.get(name)

    def load(self, name: str) -> LoadedSkill:
        descriptor = self._descriptors.get(name)
        if descriptor is None:
            raise SkillRuntimeError("SKILL_NOT_FOUND", "Skill 不存在")
        current, body = self._parse(descriptor.directory, descriptor.skill_file, load_body=True)
        if current != descriptor:
            raise SkillRuntimeError("SKILL_CHANGED", "Skill 在启动后发生变化，拒绝热加载")
        return LoadedSkill(descriptor, body)


class SkillSelector:
    """显式选择优先；多个规则候选最多调用一次结构化模型，失败安全降级为空。"""

    def __init__(self, registry: SkillRegistry, policies: Mapping[str, SkillEligibility], model: Any):
        self.registry = registry
        self.policies = dict(policies)
        self.model = model

    async def select(self, *, role: str, intent: str, limits: RunLimits,
                     explicit_name: str | None = None) -> SkillSelection | None:
        if explicit_name is not None:
            descriptor = self.registry.get(explicit_name)
            return SkillSelection(descriptor, "EXPLICIT") if descriptor else None
        candidates = [item for item in self.registry.descriptors()
                      if (policy := self.policies.get(item.name)) is not None
                      and role in policy.roles and intent in policy.intents]
        if not candidates:
            return None
        if len(candidates) == 1:
            return SkillSelection(candidates[0], "RULE")
        limits.llm()
        prompt = "从候选中选择最符合当前角色和意图的一个 Skill。只返回 {\"skillName\":...}。\n" + json.dumps({
            "role": role, "intent": intent,
            "candidates": [{"name": item.name, "description": item.description} for item in candidates],
        }, ensure_ascii=False)
        try:
            value = await self.model.complete_json(prompt, limits=limits)
            chosen = str(value.get("skillName", ""))
        except Exception:
            return None
        match = next((item for item in candidates if item.name == chosen), None)
        return SkillSelection(match, "LLM") if match else None


@dataclass
class SkillRoundState:
    """一轮最多两个 Skill，且每个子请求只能绑定一个。"""

    by_sub_request: dict[int, str] = field(default_factory=dict)

    def claim(self, sequence: int, skill_name: str) -> None:
        if sequence in self.by_sub_request and self.by_sub_request[sequence] != skill_name:
            raise SkillRuntimeError("SKILL_SUBREQUEST_LIMIT", "每个子请求最多选择一个 Skill")
        if sequence not in self.by_sub_request and len(self.by_sub_request) >= 2:
            raise SkillRuntimeError("SKILL_ROUND_LIMIT", "每轮最多选择两个 Skill")
        self.by_sub_request[sequence] = skill_name


class SkillExecution:
    """当前已选择 Skill 的有界执行上下文；身份、路径与审计状态均不接受模型覆盖。"""

    def __init__(self, *, loaded: LoadedSkill, selection_source: str, run_id: str, sequence: int,
                 trace_id: str, limits: RunLimits, config: SkillRuntimeConfig,
                 runner: "SkillScriptRunner", audit: SkillAuditPort):
        self.loaded = loaded
        self.selection_source = selection_source
        self.run_id = str(UUID(run_id))
        self.sequence = sequence
        self.trace_id = trace_id
        self.limits = limits
        self.config = config
        self.runner = runner
        self.audit = audit
        self.execution_id = str(uuid4())
        self.references: list[str] = []
        self.reference_bytes = 0
        self._script_calls: set[str] = set()
        self._started = False
        self._finished = False

    async def start(self) -> None:
        """先写私有审计；失败直接抛出，调用方不得执行脚本或业务动作。"""

        if self._started:
            return
        await self.audit.start_skill_execution(self.run_id, {
            "executionId": self.execution_id,
            "sequence": self.sequence,
            "skillName": self.loaded.descriptor.name,
            "skillVersion": self.loaded.descriptor.version,
            "selectionSource": self.selection_source,
        }, self.trace_id)
        self._started = True

    def read_skill_reference(self, relative_path: str) -> str:
        """只读取当前 references 下 UTF-8 文本，并把尝试与内容纳入本轮工具/上下文预算。"""

        self._require_started()
        normalized = _safe_relative(relative_path, required_prefix="references", suffixes=_TEXT_SUFFIXES)
        self.limits.tool("read_skill_reference", {"path": normalized})
        if normalized in self.references:
            raise SkillRuntimeError("SKILL_REFERENCE_REPEATED", "同一 reference 本轮不能重复读取")
        if len(self.references) >= self.config.reference_max_files:
            raise SkillRuntimeError("SKILL_REFERENCE_COUNT_LIMIT", "reference 数量超过限制")
        path = _resolve_member(self.loaded.descriptor.directory, normalized)
        if path.is_symlink() or not path.is_file():
            raise SkillRuntimeError("SKILL_REFERENCE_INVALID", "reference 缺失或不是普通文件")
        raw = path.read_bytes()
        if len(raw) > self.config.reference_max_file_bytes:
            raise SkillRuntimeError("SKILL_REFERENCE_FILE_TOO_LARGE", "reference 单文件超过限制")
        if self.reference_bytes + len(raw) > self.config.reference_max_total_bytes:
            raise SkillRuntimeError("SKILL_REFERENCE_TOTAL_TOO_LARGE", "reference 总量超过限制")
        if b"\x00" in raw:
            raise SkillRuntimeError("SKILL_REFERENCE_BINARY", "reference 不是文本")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise SkillRuntimeError("SKILL_REFERENCE_BINARY", "reference 必须是 UTF-8 文本") from exc
        self.references.append(normalized)
        self.reference_bytes += len(raw)
        return text

    async def run_skill_script(self, relative_path: str, json_input: Any) -> SkillResultEnvelope:
        """执行当前 scripts 下 Python；模型只提供相对路径和可序列化 JSON 输入。"""

        self._require_started()
        normalized = _safe_relative(relative_path, required_prefix="scripts", suffixes=frozenset({".py"}))
        canonical_input = json.dumps(json_input, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        key = normalized + ":" + canonical_input
        self.limits.tool("run_skill_script", {"script": normalized, "input": json_input})
        if key in self._script_calls:
            raise SkillRuntimeError("SKILL_SCRIPT_REPEATED", "同一脚本与输入本轮不能重复执行")
        self._script_calls.add(key)
        timeout = self.loaded.descriptor.script_timeout_seconds or self.config.script_default_timeout_seconds
        timeout = min(timeout, self.config.script_max_timeout_seconds, max(1, self.limits.remaining_ms() / 1000))
        try:
            payload = await self.runner.run(self.loaded.descriptor, normalized, canonical_input, timeout)
            envelope = SkillResultEnvelope(self.loaded.descriptor.name, self.loaded.descriptor.version,
                                           normalized, self.execution_id, "JSON", payload)
            await self.finish("SUCCEEDED", script=normalized)
            return envelope
        except asyncio.CancelledError:
            await self.finish("CANCELLED", script=normalized, failure_code="RUNTIME_CANCELLED")
            raise
        except SkillRuntimeError as exc:
            status = "TIMED_OUT" if exc.code == "SKILL_SCRIPT_TIMEOUT" else "FAILED"
            await self.finish(status, script=normalized, failure_code=exc.code)
            raise

    async def finish(self, status: str, *, script: str | None = None,
                     failure_code: str | None = None) -> None:
        """更新同一 execution；只发送有界相对路径和安全失败码，不发送输入输出或正文。"""

        if self._finished:
            return
        if status not in _FINAL_AUDIT_STATUSES:
            raise ValueError("非法 Skill 审计终态")
        await self.audit.finish_skill_execution(self.run_id, self.execution_id, {
            "status": status,
            "referencePaths": list(self.references),
            "script": script,
            "failureCode": failure_code,
        }, self.trace_id)
        self._finished = True

    def _require_started(self) -> None:
        if not self._started:
            raise SkillRuntimeError("SKILL_AUDIT_REQUIRED", "Skill 审计尚未开始")


class SkillScriptRunner:
    """以独立临时目录、清理环境和 JSON stdin/stdout 运行可信内置 Python。"""

    _WRAPPER = r'''import json,os,runpy,socket,subprocess,sys
def blocked(*args,**kwargs): raise RuntimeError("operation disabled by skill runtime")
socket.socket=blocked
socket.create_connection=blocked
subprocess.Popen=blocked
subprocess.run=blocked
subprocess.call=blocked
subprocess.check_call=blocked
subprocess.check_output=blocked
os.system=blocked
os.popen=blocked
for name in ("spawnl","spawnle","spawnlp","spawnlpe","spawnv","spawnve","spawnvp","spawnvpe"):
    if hasattr(os,name): setattr(os,name,blocked)
sys.argv=[sys.argv[1]]
runpy.run_path(sys.argv[0],run_name="__main__")
'''

    def __init__(self, config: SkillRuntimeConfig):
        config.validate()
        self.config = config
        self._semaphore = asyncio.Semaphore(config.script_global_concurrency)

    async def run(self, descriptor: SkillDescriptor, relative_path: str,
                  canonical_input: str, timeout_seconds: float) -> Any:
        source = _resolve_member(descriptor.directory, relative_path)
        if source.is_symlink() or not source.is_file():
            raise SkillRuntimeError("SKILL_SCRIPT_INVALID", "脚本缺失或不是普通文件")
        temporary = Path(tempfile.mkdtemp(prefix="edu-skill-"))
        process: asyncio.subprocess.Process | None = None
        try:
            skill_copy = temporary / "skill"
            shutil.copytree(descriptor.directory, skill_copy, symlinks=False)
            for item in skill_copy.rglob("*"):
                if item.is_file():
                    item.chmod(stat.S_IREAD)
            work = temporary / "work"
            work.mkdir()
            copied_script = skill_copy / PurePosixPath(relative_path)
            env = _clean_environment(temporary)
            creationflags = getattr(__import__("subprocess"), "CREATE_NEW_PROCESS_GROUP", 0) if os.name == "nt" else 0
            async with self._semaphore:
                process = await asyncio.create_subprocess_exec(
                    sys.executable, "-I", "-S", "-c", self._WRAPPER, str(copied_script),
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE, cwd=work, env=env, creationflags=creationflags,
                )
                try:
                    stdout = await asyncio.wait_for(
                        _communicate_bounded(process, canonical_input.encode("utf-8"),
                                             self.config.script_max_output_bytes),
                        timeout=timeout_seconds,
                    )
                except asyncio.TimeoutError as exc:
                    await _terminate(process)
                    raise SkillRuntimeError("SKILL_SCRIPT_TIMEOUT", "Skill 脚本执行超时") from exc
                except SkillRuntimeError:
                    await _terminate(process)
                    raise
                except asyncio.CancelledError:
                    await _terminate(process)
                    raise
            if process.returncode != 0:
                raise SkillRuntimeError("SKILL_SCRIPT_NON_ZERO", "Skill 脚本非零退出")
            try:
                return json.loads(stdout.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise SkillRuntimeError("SKILL_SCRIPT_INVALID_JSON", "Skill 脚本未返回合法 UTF-8 JSON") from exc
        finally:
            if process is not None and process.returncode is None:
                await _terminate(process)
            _remove_tree(temporary)


def _safe_relative(value: str, *, required_prefix: str, suffixes: frozenset[str]) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise SkillRuntimeError("SKILL_PATH_INVALID", "Skill 路径必须使用相对 POSIX 路径")
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or "." in path.parts or path.parts[0] != required_prefix:
        raise SkillRuntimeError("SKILL_PATH_ESCAPE", "Skill 路径越界")
    if path.suffix.lower() not in suffixes:
        raise SkillRuntimeError("SKILL_FILE_TYPE_REJECTED", "Skill 文件类型不允许")
    return path.as_posix()


def _resolve_member(directory: Path, relative: str) -> Path:
    candidate = directory.joinpath(*PurePosixPath(relative).parts)
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(directory.resolve(strict=True))
    except (OSError, ValueError) as exc:
        raise SkillRuntimeError("SKILL_PATH_ESCAPE", "Skill 路径越界或资源缺失") from exc
    return resolved


def _clean_environment(temporary: Path) -> dict[str, str]:
    safe: dict[str, str] = {"PYTHONIOENCODING": "utf-8", "PYTHONUTF8": "1",
                            "HOME": str(temporary), "USERPROFILE": str(temporary),
                            "TMP": str(temporary), "TEMP": str(temporary)}
    for name in ("SYSTEMROOT", "WINDIR"):
        if os.getenv(name):
            safe[name] = os.environ[name]
    return safe


async def _terminate(process: asyncio.subprocess.Process) -> None:
    if process.returncode is not None:
        return
    process.kill()
    try:
        await asyncio.wait_for(process.wait(), timeout=2)
    except asyncio.TimeoutError:
        pass


async def _communicate_bounded(process: asyncio.subprocess.Process, stdin: bytes, limit: int) -> bytes:
    """持续排空 stderr，并在 stdout 首次越界时立即失败，避免把无限输出读入内存。"""

    assert process.stdin is not None and process.stdout is not None and process.stderr is not None
    process.stdin.write(stdin)
    await process.stdin.drain()
    process.stdin.close()

    async def read_stdout() -> bytes:
        chunks: list[bytes] = []
        size = 0
        while True:
            chunk = await process.stdout.read(4096)
            if not chunk:
                return b"".join(chunks)
            size += len(chunk)
            if size > limit:
                raise SkillRuntimeError("SKILL_SCRIPT_OUTPUT_TOO_LARGE", "Skill 脚本输出超过限制")
            chunks.append(chunk)

    async def discard_stderr() -> None:
        while await process.stderr.read(4096):
            pass

    stdout_task = asyncio.create_task(read_stdout())
    stderr_task = asyncio.create_task(discard_stderr())
    wait_task = asyncio.create_task(process.wait())
    try:
        stdout, _, _ = await asyncio.gather(stdout_task, stderr_task, wait_task)
        return stdout
    finally:
        for task in (stdout_task, stderr_task, wait_task):
            if not task.done():
                task.cancel()


def _remove_tree(path: Path) -> None:
    """恢复只读测试副本的写权限后精确删除本次独立临时目录。"""

    def retry(function, value, _error):
        os.chmod(value, stat.S_IWRITE)
        function(value)

    shutil.rmtree(path, onerror=retry)
