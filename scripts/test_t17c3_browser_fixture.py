"""夹具清理的隔离测试：替换全部SQL/Redis，不连接本地数据库或读取任何真实凭据。"""
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import t17c3_browser_fixture as fixture


class FixtureCleanupTest(unittest.TestCase):
    """验证破坏性清理的目标校验与中断恢复；不验证生产业务状态机。"""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.data = {'admin': '00000000-0000-4000-8000-000000000001', 'student': '00000000-0000-4000-8000-000000000002'}
        self.accepted = '00000000-0000-4000-8000-000000000003'
        self.invitation = '00000000-0000-4000-8000-000000000004'
        self.manifest = self.root / 'fixture.json'; self.manifest.write_text(json.dumps(self.data))
        (self.root / 'preservation.json').write_text(json.dumps({'kept': True}))
        self.statements = []

    def tearDown(self):
        self.directory.cleanup()

    def sql(self, domain, statement):
        """按最小合成事实回应，第二轮模拟SQL已提交后查询不再能恢复accepted_by。"""
        self.statements.append(statement)
        if statement.startswith('SELECT id FROM administrator_invitation'):
            return self.invitation
        if statement.startswith('SELECT accepted_by'):
            return self.accepted
        return '0'

    def test_refuses_an_existing_non_fixture_account_before_any_delete(self):
        """误填原管理员UUID时，前缀核验失败，SQL删除/Redis删除均不得发生。"""
        with patch.object(fixture, 'DIRECTORY', self.root), patch.object(fixture, 'MANIFEST', self.manifest), \
                patch.object(fixture.common, 'sql', return_value='1') as sql, patch.object(fixture.subprocess, 'run') as redis:
            with self.assertRaisesRegex(RuntimeError, 'non-fixture'):
                fixture.cleanup(self.data)
            self.assertEqual(sql.call_count, 1); redis.assert_not_called()
            self.assertTrue(self.manifest.exists()); self.assertFalse((self.root / 'cleanup.json').exists())

    def test_keeps_precise_cleanup_plan_after_sql_commit_and_redis_failure(self):
        """SQL已删除关联行后Redis失败；再执行仍从已保存UUID清理受邀账号键，成功才删活动清单。"""
        with patch.object(fixture, 'DIRECTORY', self.root), patch.object(fixture, 'MANIFEST', self.manifest), \
                patch.object(fixture.common, 'sql', side_effect=self.sql), patch.object(fixture, 'preservation', return_value={'kept': True}), \
                patch.dict(os.environ, {'IAM_VERIFICATION_HMAC_SECRET': 'synthetic-test-secret'}), \
                patch.object(fixture.subprocess, 'run') as redis:
            redis.side_effect = RuntimeError('synthetic Redis unavailable')
            with self.assertRaisesRegex(RuntimeError, 'Redis unavailable'):
                fixture.cleanup(self.data)
            plan = json.loads((self.root / 'cleanup.json').read_text())
            self.assertEqual(plan['accepted'], [self.accepted]); self.assertEqual(plan['invitations'], [self.invitation])
            self.assertTrue(self.manifest.exists())
            redis.side_effect = None; redis.return_value = SimpleNamespace(stdout='0')
            fixture.cleanup(self.data)
            deletion = [value for value in self.statements if value.startswith('START TRANSACTION')]
            self.assertEqual(len(deletion), 2); self.assertTrue(all(self.accepted in value and self.invitation in value for value in deletion))
            self.assertTrue(any(self.accepted in argument for call in redis.call_args_list for argument in call.args[0]))
            self.assertFalse(self.manifest.exists()); self.assertFalse((self.root / 'cleanup.json').exists())


if __name__ == '__main__':
    unittest.main()
