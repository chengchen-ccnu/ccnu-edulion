"""为 T13.1 示例提供轻量 JSON Schema 与跨事件语义校验。

输入是仓库内版本化 YAML 契约和示例，输出为空错误列表或可读错误文本。这里不启动 Agent、
模型、数据库、消息队列或索引；后续 T14.1/T13.2 可用相同样例验证各自适配器。
"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path
from typing import Any, Iterable, Mapping

import yaml
from jsonschema import Draft7Validator, FormatChecker


def load_yaml(path: Path) -> dict[str, Any]:
    """读取一个受版本控制的 YAML 文件，并要求根节点为对象。"""
    with path.open("r", encoding="utf-8") as handle:
        value = yaml.safe_load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"YAML 根节点必须是对象：{path}")
    return value


def _rewrite_openapi_refs(value: Any) -> Any:
    """把 OpenAPI components 引用改写成独立 Draft 7 definitions 引用。"""
    if isinstance(value, dict):
        return {key: _rewrite_openapi_refs(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_rewrite_openapi_refs(item) for item in value]
    if isinstance(value, str):
        return value.replace("#/components/schemas/", "#/definitions/")
    return value


def openapi_schema(document: Mapping[str, Any], schema_name: str) -> dict[str, Any]:
    """从内部 OpenAPI 提取可独立执行的 schema，保留所有组件引用。"""
    components = deepcopy(document["components"]["schemas"])
    return _rewrite_openapi_refs(
        {
            "$schema": "http://json-schema.org/draft-07/schema#",
            "definitions": components,
            "$ref": f"#/definitions/{schema_name}",
        }
    )


def event_envelope_schema(document: Mapping[str, Any]) -> dict[str, Any]:
    """把仓库事件文件包装成可直接校验单条事件信封的 Draft 7 schema。"""
    schema = deepcopy(dict(document))
    schema["$schema"] = "http://json-schema.org/draft-07/schema#"
    schema["$ref"] = "#/envelope"
    return schema


def schema_errors(schema: Mapping[str, Any], instance: Any) -> list[str]:
    """校验实例并返回按路径排序的安全错误；不输出秘密或异常堆栈。"""
    validator = Draft7Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.path))
    return [f"{'/'.join(map(str, error.path)) or '<root>'}: {error.message}" for error in errors]


def local_ref_errors(document: Mapping[str, Any]) -> list[str]:
    """检查文档内全部本地 JSON Pointer 引用，返回无法解析的引用路径。"""
    errors: list[str] = []

    def resolve(reference: str) -> bool:
        if not reference.startswith("#/"):
            return True
        current: Any = document
        for raw_part in reference[2:].split("/"):
            part = raw_part.replace("~1", "/").replace("~0", "~")
            if not isinstance(current, Mapping) or part not in current:
                return False
            current = current[part]
        return True

    def visit(value: Any, path: str) -> None:
        if isinstance(value, Mapping):
            reference = value.get("$ref")
            if isinstance(reference, str) and not resolve(reference):
                errors.append(f"{path or '<root>'}: 无法解析 {reference}")
            for key, item in value.items():
                visit(item, f"{path}/{key}")
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, f"{path}/{index}")

    visit(document, "")
    return errors


def stream_semantic_errors(events: Iterable[Mapping[str, Any]]) -> list[str]:
    """验证一个已保存流的顺序、run 一致性和唯一终态语义。"""
    sequence = list(events)
    if not sequence:
        return ["流不能为空"]

    errors: list[str] = []
    if sequence[0].get("eventType") != "RUN_ACCEPTED":
        errors.append("首事件必须是 RUN_ACCEPTED")
    expected_sequences = list(range(1, len(sequence) + 1))
    actual_sequences = [event.get("sequence") for event in sequence]
    if actual_sequences != expected_sequences:
        errors.append("sequence 必须从 1 开始连续递增")

    run_ids = {event.get("runId") for event in sequence}
    if len(run_ids) != 1:
        errors.append("同一流只能包含一个 runId")

    terminal_indexes = [
        index for index, event in enumerate(sequence) if event.get("eventType") == "FINAL_RESULT"
    ]
    if len(terminal_indexes) != 1:
        errors.append("每个 run 必须且只能有一个 FINAL_RESULT")
    elif terminal_indexes[0] != len(sequence) - 1:
        errors.append("FINAL_RESULT 必须是最后一个事件")
    return errors


def intent_semantic_errors(
    contract: Mapping[str, Any], decision: Mapping[str, Any]
) -> list[str]:
    """验证一级意图选角、二级意图槽位和最多两个有序子请求。"""
    errors: list[str] = []
    routing = contract["x-role-routing"]
    secondary_contracts = contract["x-secondary-intent-contracts"]
    primary = decision.get("primaryIntent")
    secondary = decision.get("secondaryIntent")
    role = decision.get("role")
    resolution = decision.get("resolution")
    slots = decision.get("slots") or {}

    route = routing.get(primary)
    if route and route.get("role") != role:
        errors.append("一级意图与专家角色不匹配")
    if route and route.get("secondaryIntents") and secondary not in route["secondaryIntents"]:
        errors.append("二级意图不属于所选一级意图")

    if resolution == "ROUTED" and secondary in secondary_contracts:
        rule = secondary_contracts[secondary]
        for name in rule.get("requiredSlots", []):
            if name not in slots:
                errors.append(f"缺少必需槽位 {name}")
        required_any = rule.get("requiredAnySlots", [])
        if required_any and not any(name in slots for name in required_any):
            errors.append(f"至少需要一个槽位：{','.join(required_any)}")

    sub_requests = decision.get("subRequests") or []
    if len(sub_requests) > 2:
        errors.append("一次请求最多两个子请求")
    if [item.get("sequence") for item in sub_requests] != list(
        range(1, len(sub_requests) + 1)
    ):
        errors.append("子请求 sequence 必须从 1 连续递增")

    role_by_secondary = {
        item: config["role"]
        for config in routing.values()
        for item in config.get("secondaryIntents", [])
    }
    for item in sub_requests:
        expected_role = role_by_secondary.get(item.get("secondaryIntent"))
        if expected_role and item.get("role") != expected_role:
            errors.append("子请求二级意图与专家角色不匹配")
    return errors


def tool_authorization_errors(
    tool_call: Mapping[str, Any], grants: Iterable[Mapping[str, Any]]
) -> list[str]:
    """验证工具名和显式资源 ID 没有超出 Java 下发的白名单范围。"""
    matching = [grant for grant in grants if grant.get("toolName") == tool_call.get("toolName")]
    if not matching:
        return ["工具不在 Java 白名单中"]

    requested_ids = set((tool_call.get("arguments") or {}).get("resourceIds") or [])
    if not requested_ids:
        return []
    granted_ids = {
        resource_id
        for grant in matching
        for scope in grant.get("resourceScopes", [])
        for resource_id in scope.get("resourceIds", [])
    }
    if not requested_ids.issubset(granted_ids):
        return ["工具请求包含未授权资源"]
    return []


def index_version_errors(records: Iterable[Mapping[str, Any]]) -> list[str]:
    """模拟消费者幂等和 revision 门禁，拒绝旧事件覆盖新投影。"""
    errors: list[str] = []
    seen_event_ids: set[str] = set()
    latest: dict[tuple[Any, Any], int] = {}
    for record in records:
        event_id = str(record.get("eventId"))
        if event_id in seen_event_ids:
            continue
        seen_event_ids.add(event_id)
        key = (record.get("eventType"), record.get("entityId"))
        revision = int(record.get("sourceRevision", -1))
        if key in latest and revision <= latest[key]:
            errors.append("旧或相同 sourceRevision 不得覆盖已应用投影")
            continue
        latest[key] = revision
    return errors
