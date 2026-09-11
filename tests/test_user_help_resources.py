"""版本化中文帮助资源与 T13.4 内部协议的构建期校验。"""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

from tests.contract_schema_support import load_yaml, openapi_schema, schema_errors


ROOT = Path(__file__).resolve().parents[1]
HELP_ROOT = ROOT / "java-platform" / "agent-access-service" / "src" / "main" / "resources" / "help-kb"


class UserHelpResourceTests(unittest.TestCase):
    """确保 manifest 当前版本、所有历史文件和新增内部 DTO 在构建期保持一致。"""

    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((HELP_ROOT / "manifest.json").read_text(encoding="utf-8"))
        cls.schema = json.loads((HELP_ROOT / "user-help.schema.json").read_text(encoding="utf-8"))
        cls.contract = load_yaml(ROOT / "docs" / "api" / "agent-internal.yaml")

    def test_manifest_and_every_published_json_match_schema_and_version(self):
        Draft202012Validator.check_schema(self.schema)
        validator = Draft202012Validator(self.schema)
        entries = self.manifest["versions"]
        self.assertEqual(len({item["version"] for item in entries}), len(entries))
        self.assertEqual(len({item["file"] for item in entries}), len(entries))
        current = None
        for entry in entries:
            path = (HELP_ROOT / entry["file"]).resolve()
            self.assertTrue(path.is_relative_to(HELP_ROOT.resolve()))
            document = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(list(validator.iter_errors(document)), [])
            self.assertEqual(document["version"], entry["version"])
            self.assertTrue(all(article["version"] == entry["version"] for article in document["articles"]))
            if entry["file"] == self.manifest["currentFile"]:
                current = document
        self.assertIsNotNone(current)
        self.assertEqual(current["version"], self.manifest["currentVersion"])

    def test_schema_rejects_unknown_fields_bad_route_and_version_mix_shape(self):
        current_path = HELP_ROOT / self.manifest["currentFile"]
        current = json.loads(current_path.read_text(encoding="utf-8"))
        validator = Draft202012Validator(self.schema)

        unknown = json.loads(json.dumps(current, ensure_ascii=False))
        unknown["articles"][0]["internalPath"] = "/internal/help.json"
        self.assertTrue(list(validator.iter_errors(unknown)))

        bad_route = json.loads(json.dumps(current, ensure_ascii=False))
        bad_route["articles"][0]["appRoute"] = "https://example.com/help"
        self.assertTrue(list(validator.iter_errors(bad_route)))

        mixed = json.loads(json.dumps(current, ensure_ascii=False))
        mixed["articles"][0]["version"] = "other-version"
        self.assertTrue(list(validator.iter_errors(mixed)))

    def test_internal_contract_adds_only_bounded_help_query_and_safe_optional_route(self):
        paths = self.contract["paths"]
        operation = paths["/internal/v1/agent/runs/{runId}/user-help:search"]["post"]
        self.assertEqual(operation["operationId"], "searchUserHelp")
        request_schema = openapi_schema(self.contract, "UserHelpSearchRequest")
        self.assertEqual(schema_errors(request_schema, {"query": "怎么登录", "limit": 5}), [])
        self.assertTrue(schema_errors(request_schema, {
            "query": "怎么登录", "limit": 5, "userId": "00000000-0000-4000-8000-000000000001",
        }))
        self.assertTrue(schema_errors(request_schema, {"query": "x", "limit": 6}))
        secondary = self.contract["components"]["schemas"]["SecondaryIntent"]["enum"]
        self.assertIn("PLATFORM_USAGE_HELP", secondary)
        routing = self.contract["x-role-routing"]["PLATFORM_HELP"]
        self.assertEqual(routing["secondaryIntents"], ["PLATFORM_USAGE_HELP"])
        self.assertEqual(
            self.contract["x-secondary-intent-contracts"]["PLATFORM_USAGE_HELP"]["allowedTools"],
            ["USER_HELP_SEARCH"],
        )


if __name__ == "__main__":
    unittest.main()
