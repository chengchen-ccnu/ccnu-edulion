"""T17C.3 local/dev最小夹具：一管理员、一普通账号及真实邮件邀请事实，精确UUID清理。

不读取邮件/原秘密，不创建库或表，不修改其他领域；秘密只由环境包装器注入。
操作者通过浏览器正常邀请和开户；清单不保存邮箱/理由/密码/Token。
"""
import argparse
import hashlib
import hmac
import json
import os
import subprocess
import uuid
import urllib.request
import urllib.error
import bcrypt
import t17a_browser_fixture as common
from t17b1_browser_fixture import preserve

DIRECTORY = common.ROOT / '.m2/t17c3-browser'
MANIFEST = DIRECTORY / 'fixture.json'


def load():
    """清单只接受已知的UUID字段，不从调用参数获取待删除账号。"""
    data = json.loads(MANIFEST.read_text(encoding='utf-8'))
    if set(data) != {'admin', 'student'}:
        raise RuntimeError('Unexpected fixture manifest')
    return {key: str(uuid.UUID(value)) for key, value in data.items()}


def preservation():
    """只读比对存量管理员、课程/题库和历史Outbox；不建立产品门禁或修改其事实。"""
    result = preserve()
    result['admins'] = common.sql('iam', "SELECT u.id,u.status,ur.role_id FROM user u JOIN user_role ur ON ur.user_id=u.id JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN' ORDER BY u.id")
    return result


def create():
    """先写无秘密UUID清单再创建最小身份；重复执行复用清单，合成密码由操作者安全输入。"""
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    if MANIFEST.exists():
        inspect(load()); return
    (DIRECTORY / 'preservation.json').write_text(json.dumps(preservation()), encoding='utf-8')
    data = {key: str(uuid.uuid4()) for key in ['admin', 'student']}
    MANIFEST.write_text(json.dumps(data), encoding='utf-8')
    hashed = bcrypt.hashpw(os.environ['T17C3_FIXTURE_PASSWORD'].encode(), bcrypt.gensalt()).decode()
    for key, uid in data.items():
        role = 'ADMIN' if key == 'admin' else 'STUDENT'
        common.sql('iam', f"INSERT INTO user(id,account,display_name,password_hash,status) VALUES('{uid}','t17c3_{uid}','{key}_test_t17c3','{hashed}','ACTIVE'); INSERT INTO user_role SELECT '{uid}',id FROM role WHERE code='{role}'; INSERT INTO user_identity(id,user_id,identity_type,normalized_value,verified_at,created_at) VALUES(UUID(),'{uid}','EMAIL','t17c3-{uid}@example.invalid',NOW(6),NOW(6));")
    inspect(data)


def inspect(data):
    """只输出合成登录标识和脱敏安全状态，不读取/输出邮箱、凭证摘要、命令载荷或日志正文。"""
    print('Temporary administrator login: t17c3-' + data['admin'] + '@example.invalid')
    print('Temporary ordinary login: t17c3-' + data['student'] + '@example.invalid')
    print(common.sql('iam', f"SELECT id,delivery_status,send_attempts,revision,accepted_at IS NOT NULL FROM administrator_invitation WHERE invited_by='{data['admin']}' ORDER BY created_at,id"))


def verify(data):
    """只读确认正常开户为仅ADMIN、仅管理能力及已消费事实；不从前端userId猜权限。"""
    ids = common.sql('iam', f"SELECT accepted_by FROM administrator_invitation WHERE invited_by='{data['admin']}' AND accepted_at IS NOT NULL").splitlines()
    if not ids:
        raise RuntimeError('No accepted invitation; real receipt and browser acceptance remain unverified')
    for uid in ids:
        uid = str(uuid.UUID(uid))
        if common.sql('iam', f"SELECT r.code FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.user_id='{uid}' ORDER BY r.code") != 'ADMIN':
            raise RuntimeError('Accepted account is not isolated ADMIN')
        if common.sql('iam', f"SELECT p.code FROM user_role ur JOIN role_permission rp ON rp.role_id=ur.role_id JOIN permission p ON p.id=rp.permission_id WHERE ur.user_id='{uid}' ORDER BY p.code") != 'administration:access':
            raise RuntimeError('Unexpected accepted account permission')
    print('PASS real accepted invitation and isolated ADMIN role/permission (read-only)')


def negative(data):
    """通过正常临时账号登录验证真实Gateway/IAM权限和绑定保护；不发送邮件、不消费正确凭证。"""
    token = common.api('POST', '/auth/login', body={
        'identity': 't17c3-' + data['admin'] + '@example.invalid', 'password': os.environ['T17C3_FIXTURE_PASSWORD']})['accessToken']
    student = common.api('POST', '/auth/login', body={
        'identity': 't17c3-' + data['student'] + '@example.invalid', 'password': os.environ['T17C3_FIXTURE_PASSWORD']})['accessToken']

    def rejected(method, path, expected, credential=None, body=None):
        """只核对HTTP状态，不输出请求、Token或响应正文。"""
        headers = {'Content-Type': 'application/json'}
        if credential:
            headers['Authorization'] = 'Bearer ' + credential
        request = urllib.request.Request('http://localhost:8080/api/v1' + path, method=method, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                response.read()
                raise RuntimeError('Expected rejection, received success')
        except urllib.error.HTTPError as failure:
            if failure.code != expected:
                raise RuntimeError(f'Negative HTTP expected {expected}, received {failure.code}') from None
    rejected('GET', '/admin/invitations/' + str(uuid.uuid4()), 401)
    rejected('GET', '/admin/invitations/' + str(uuid.uuid4()), 403, student)
    rejected('POST', '/admin/invitations', 403, student, {'clientRequestId': str(uuid.uuid4()), 'email': 'not-sent@example.invalid', 'reason': '合成测试越权邀请，必须拒绝'})
    rejected('POST', '/admin/invitations', 409, token, {'clientRequestId': str(uuid.uuid4()),
        'email': 't17c3-' + data['student'] + '@example.invalid', 'reason': '已绑定普通账号邮箱，不能转换为管理员'})
    rejected('POST', '/learning/enrollments', 403, token, {'courseId': str(uuid.uuid4())})
    rejected('GET', '/teacher/courses', 403, token)
    rejected('GET', '/teacher-applications/me', 403, token)
    for invitation in common.sql('iam', f"SELECT id FROM administrator_invitation WHERE invited_by='{data['admin']}' AND accepted_at IS NOT NULL").splitlines():
        invitation = str(uuid.UUID(invitation))
        # 已消费邀请不再有发信副作用；用合成凭证核对统一拒绝，不读取邮件原凭证。
        rejected('POST', '/auth/admin-invitations/accept', 422, body={'invitationId': invitation,
            'email': 'synthetic@example.invalid', 'secret': 's' * 43, 'password': 'Synthetic-test-only-123', 'displayName': 'admin_test_synthetic'})
        revision = int(common.sql('iam', f"SELECT revision FROM administrator_invitation WHERE id='{invitation}'"))
        rejected('POST', '/admin/invitations/' + invitation + '/resend', 409, token,
            {'clientRequestId': str(uuid.uuid4()), 'expectedRevision': revision, 'reason': '已接受邀请必须拒绝重发，不向真实邮箱再次发信'})
    print('PASS real Gateway/IAM 401/403, bound409, independent ADMIN learning/teaching/application denial; no mail sent')


def cleanup(data):
    """以本轮管理员UUID定位邀请及开户账号，校验所有权后事务精确删除；原管理员和外域事实不动。"""
    for uid in data.values():
        if common.sql('iam', f"SELECT COUNT(*) FROM user WHERE id='{uid}' AND account<>'t17c3_{uid}'") != '0':
            raise RuntimeError('Refuse cleanup of a non-fixture account')
    admin = data['admin']
    plan_file = DIRECTORY / 'cleanup.json'
    if plan_file.exists():
        plan = json.loads(plan_file.read_text(encoding='utf-8'))
        if plan['admin'] != admin:
            raise RuntimeError('Cleanup plan belongs to another run')
        invitations = [str(uuid.UUID(value)) for value in plan['invitations']]
        accepted = [str(uuid.UUID(value)) for value in plan['accepted']]
    else:
        invitations = common.sql('iam', f"SELECT id FROM administrator_invitation WHERE invited_by='{admin}'").splitlines()
        accepted = common.sql('iam', f"SELECT accepted_by FROM administrator_invitation WHERE invited_by='{admin}' AND accepted_by IS NOT NULL").splitlines()
        # 先保存无秘密UUID，SQL已提交后Redis/比对失败也能精确恢复，不能丢失accepted_by定位。
        plan_file.write_text(json.dumps({'admin': admin, 'invitations': invitations, 'accepted': accepted}), encoding='utf-8')
    for uid in accepted:
        uid = str(uuid.UUID(uid))
        if common.sql('iam', f"SELECT COUNT(*) FROM user WHERE id='{uid}' AND account NOT LIKE 'admin_%'") != '0':
            raise RuntimeError('Unexpected invited account prefix')
    owners = list(data.values()) + [str(uuid.UUID(uid)) for uid in accepted]
    ids = ','.join("'" + uid + "'" for uid in owners)
    invite_ids = ','.join("'" + str(uuid.UUID(value)) + "'" for value in invitations) or 'NULL'
    # 本轮服务关闭无关Worker；只删除本轮invited_by及UUID关联事实，不清理共享表或历史Outbox。
    common.sql('iam', f"START TRANSACTION; DELETE FROM administrator_invitation_command WHERE invitation_id IN({invite_ids}) AND actor_id='{admin}'; DELETE FROM administrator_invitation WHERE id IN({invite_ids}) AND invited_by='{admin}'; DELETE FROM account_security_audit WHERE user_id IN({ids}) OR actor_id IN({ids}); DELETE FROM refresh_session WHERE user_id IN({ids}) AND parent_id IS NOT NULL ORDER BY created_at DESC; DELETE FROM refresh_session WHERE user_id IN({ids}); DELETE FROM login_audit WHERE user_id IN({ids}); DELETE FROM user_identity WHERE user_id IN({ids}); DELETE FROM user_role WHERE user_id IN({ids}); DELETE FROM user WHERE id IN({ids}); COMMIT;")
    env = dict(os.environ, REDISCLI_AUTH=os.environ.get('REDIS_PASSWORD', ''))
    cmd = ['redis-cli', '-h', os.environ.get('REDIS_HOST', '127.0.0.1'), '-p', os.environ.get('REDIS_PORT', '6379'), '-n', os.environ.get('IAM_REDIS_DATABASE', '1')]
    if os.environ.get('REDIS_USERNAME'):
        cmd += ['--user', os.environ['REDIS_USERNAME']]
    prefix = os.environ.get('IAM_SESSION_VERSION_KEY_PREFIX', 'edu:iam:session-version')
    risk_prefix = prefix.replace(':session-version', '')
    keys = [prefix + ':' + uid for uid in owners]
    for uid in owners:
        digest = hmac.new(os.environ['IAM_VERIFICATION_HMAC_SECRET'].encode(), ('login-user\n' + uid).encode(), hashlib.sha256).hexdigest()
        keys += [risk_prefix + ':login-failure:account:' + digest, risk_prefix + ':login-lock:account:' + digest]
    subprocess.run(cmd + ['DEL'] + keys, env=env, check=True, capture_output=True)
    if subprocess.run(cmd + ['EXISTS'] + keys, env=env, check=True, capture_output=True, text=True).stdout.strip() != '0':
        raise RuntimeError('Fixture Redis keys remain')
    gateway_cmd = list(cmd)
    gateway_cmd[gateway_cmd.index('-n') + 1] = os.environ.get('GATEWAY_REDIS_DATABASE', '0')
    gateway_keys = [os.environ.get('GATEWAY_RATE_LIMIT_KEY_PREFIX', 'edu:gateway:rate') + ':admin-invitation-send:'
        + hashlib.sha256(('user:' + uid).encode()).hexdigest() for uid in owners]
    # 只删除本轮用户维度限流键，不操作共享IP的登录/接受邀请限流窗口。
    subprocess.run(gateway_cmd + ['DEL'] + gateway_keys, env=env, check=True, capture_output=True)
    if subprocess.run(gateway_cmd + ['EXISTS'] + gateway_keys, env=env, check=True, capture_output=True, text=True).stdout.strip() != '0':
        raise RuntimeError('Fixture Gateway user rate keys remain')
    if preservation() != json.loads((DIRECTORY / 'preservation.json').read_text(encoding='utf-8')):
        raise RuntimeError('Existing facts differ; inspect without overwriting unrelated data')
    if common.sql('iam', f"SELECT COUNT(*) FROM user WHERE id IN({ids})") != '0' or common.sql('iam', f"SELECT COUNT(*) FROM administrator_invitation WHERE id IN({invite_ids})") != '0':
        raise RuntimeError('Fixture rows remain')
    plan_file.unlink(); MANIFEST.unlink()
    print('PASS exact fixture cleanup; original administrators/courses/questions/history Outbox unchanged')


def main():
    """只执行显式动作；无活动清单时cleanup幂等返回，不猜测删除范围。"""
    parser = argparse.ArgumentParser(); parser.add_argument('action', choices=['create', 'inspect', 'verify', 'negative', 'cleanup'])
    action = parser.parse_args().action
    if action == 'create':
        create(); return
    if not MANIFEST.exists():
        if action == 'cleanup':
            print('No active fixture; cleanup already completed'); return
        raise RuntimeError('No active T17C.3 fixture')
    data = load()
    {'inspect': inspect, 'verify': verify, 'negative': negative, 'cleanup': cleanup}[action](data)


if __name__ == '__main__':
    main()
