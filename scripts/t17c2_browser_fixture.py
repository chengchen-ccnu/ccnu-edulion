"""T17C.2 最小dev夹具：四个合成身份，申请/上传经既有页面，审批/课程经正常授权API。

不创建数据库或表；复用安全加载器和dev Schema检查，记录精确UUID用于中断后的清理。
OSS SDK只用于独立验收脚本精确删除本轮原件，不进入Python Agent或业务生产代码。
"""
import argparse
import json
import os
import subprocess
import time
import uuid
import hashlib
import hmac
import bcrypt
import t17a_browser_fixture as common
from t17b1_browser_fixture import preserve

DIRECTORY = common.ROOT / ".m2/t17c2-browser"
MANIFEST = DIRECTORY / "fixture.json"


def load():
    """只接受清单中的四个固定角色UUID；不从调用参数接受任意待删除账号。"""
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return {key: str(uuid.UUID(value)) for key, value in data.items()}


def accounts(data):
    """清单可增加课程定位，但账号删除范围始终固定四个初始合成UUID。"""
    return {key: data[key] for key in ["student", "resubmit", "admin", "competitor"]}


def preservation():
    """比对原有课程、题库、历史Outbox和唯一管理员；只读快照不是产品门禁。"""
    value = preserve()
    value["admins"] = common.sql("iam", "SELECT u.id,u.status,ur.role_id FROM user u JOIN user_role ur ON ur.user_id=u.id JOIN role r ON r.id=ur.role_id WHERE r.code='ADMIN' ORDER BY u.id")
    return value


def create():
    """先记录UUID再创建专用local身份；已有清单时复用，重复执行不产生更多账号。"""
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    if MANIFEST.exists():
        load(); print("Existing T17C.2 fixture reused; cleanup before rebuilding"); return
    (DIRECTORY / "preservation.json").write_text(json.dumps(preservation()), encoding="utf-8")
    data = {key: str(uuid.uuid4()) for key in ["student", "resubmit", "admin", "competitor"]}
    MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    hashed = bcrypt.hashpw(os.environ["T17C2_FIXTURE_PASSWORD"].encode(), bcrypt.gensalt()).decode()
    for name, uid in data.items():
        role = "ADMIN" if name in ["admin", "competitor"] else "STUDENT"
        common.sql("iam", f"INSERT INTO user(id,account,display_name,password_hash,status) VALUES('{uid}','t17c2_{uid}','{name}_test_t17c2','{hashed}','ACTIVE'); INSERT INTO user_role SELECT '{uid}',id FROM role WHERE code='{role}'; INSERT INTO user_identity(id,user_id,identity_type,normalized_value,verified_at,created_at) VALUES(UUID(),'{uid}','EMAIL','t17c2-{uid}@example.invalid',NOW(6),NOW(6));")
    # PNG只作合成资格文件，后续通过学生真实上传器上传，不伪造READY。
    import base64
    (DIRECTORY / "qualification.png").write_bytes(base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII="))
    print("T17C.2 fixture ready: 2 synthetic students + 2 temporary administrators; no application/media rows seeded")


def settle():
    """停止服务前限时观察本轮发布、消费与投递完成，不修改或重放历史失败事件。"""
    data = load()
    ids = ",".join(f"'{uid}'" for uid in accounts(data).values())
    applications = common.sql("iam", f"SELECT id FROM teacher_application WHERE user_id IN({ids})").splitlines()
    assets = common.sql("media", f"SELECT media_id FROM media_asset WHERE owner_id IN({ids})").splitlines()
    aggregate_sql = ",".join(f"'{str(uuid.UUID(value))}'" for value in applications + assets + list(data.values()))
    for _ in range(80):
        counts = [int(common.sql(domain, f"SELECT COUNT(*) FROM {table} WHERE status='PENDING'"))
            for domain, table in [("iam", "iam_outbox_event"), ("course", "outbox_event"), ("learning", "learning_outbox_event"), ("operation", "operation_outbox_event"), ("media", "outbox_event")]]
        # 按事件ID分别查消费回执，避免发布已确认但消费者仍在途时清理后又生成本轮孤儿记录。
        consumed = True
        events = common.sql("iam", f"SELECT event_id,event_type FROM iam_outbox_event WHERE aggregate_id IN({aggregate_sql})").splitlines()
        for event in events:
            event_id, event_type = event.split("\t")
            # 权限变化消息不要求Media消费；其课程协调由Course事实和独立消费者核对。
            if event_type == "AccountPermissionChanged":
                continue
            domain, table = ("operation", "operation_consumed_event") if event_type == "TeacherQualificationDecided" else ("media", "consumed_event")
            consumed = consumed and common.sql(domain, f"SELECT COUNT(*) FROM {table} WHERE event_id='{str(uuid.UUID(event_id))}'") == "1"
        app_sql = ",".join(f"'{str(uuid.UUID(value))}'" for value in applications) or "NULL"
        pending_work = int(common.sql("operation", f"SELECT COUNT(*) FROM notification_delivery_work WHERE target_id IN({app_sql}) AND status='PENDING'"))
        course_ids = [value for key, value in data.items() if key in ["courseA", "courseB", "courseDraft"]]
        course_sql = ",".join(f"'{value}'" for value in course_ids) or "NULL"
        for line in common.sql("course", f"SELECT event_id,event_type FROM outbox_event WHERE aggregate_id IN({course_sql})").splitlines():
            event_id, event_type = line.split("\t")
            if event_type == "CourseContentPublished":
                consumed = consumed and common.sql("learning", f"SELECT COUNT(*) FROM consumed_course_content_event WHERE event_id='{event_id}'") == "1"
            elif event_type == "CourseAvailabilityChanged":
                consumed = consumed and common.sql("learning", f"SELECT COUNT(*) FROM course_availability_consumed_event WHERE event_id='{event_id}'") == "1"
        for line in common.sql("learning", f"SELECT event_id FROM learning_outbox_event WHERE aggregate_id IN(SELECT id FROM enrollment WHERE course_id IN({course_sql}))").splitlines():
            consumed = consumed and common.sql("course", f"SELECT COUNT(*) FROM consumed_learning_event WHERE event_id='{line}'") == "1"
        pending_work += int(common.sql("operation", f"SELECT COUNT(*) FROM notification_delivery_work WHERE course_id IN({course_sql}) AND status='PENDING'"))
        if not any(counts) and consumed and not pending_work: print("Five dev domain Outbox PENDING=0; fixture consumers/delivery settled"); return
        time.sleep(0.5)
    raise RuntimeError("Outbox pending; retain fixture/services for inspection")


def remove_objects(owners):
    """只删除本轮账号上传记录明确拥有的OSS原件，HEAD确认不存在；不列举桶、不删除其他对象。"""
    import oss2
    bucket = oss2.Bucket(oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"]), os.environ["OSS_ENDPOINT"], os.environ["OSS_BUCKET"])
    ids = ",".join(f"'{owner}'" for owner in owners)
    keys = common.sql("media", f"SELECT object_key FROM upload_session WHERE owner_id IN({ids})").splitlines()
    for key in keys:
        bucket.delete_object(key)
        if bucket.object_exists(key): raise RuntimeError("Exact fixture object remains")
    print(f"Exact OSS original deletion verified: {len(keys)} objects")


def verify_owners(owners):
    """清理前复核账号的本轮技术前缀，防止清单UUID误填为既有管理员；已清理账号允许幂等重跑。"""
    for uid in owners:
        uid = str(uuid.UUID(uid))
        if common.sql("iam", f"SELECT COUNT(*) FROM user WHERE id='{uid}' AND account<>'t17c2_{uid}'") != "0":
            raise RuntimeError("Manifest contains a non-fixture account; no deletion authorized")


def cleanup(data):
    """按账号/申请/轮次/资产/eventId精确清理，重复运行幂等，保留原管理员和历史事实。"""
    verify_owners(accounts(data).values()); settle(); cleanup_courses(data); data = accounts(data)
    ids = ",".join(f"'{uid}'" for uid in data.values())
    remove_objects(data.values())
    applications = common.sql("iam", f"SELECT id FROM teacher_application WHERE user_id IN({ids})").splitlines()
    assets = common.sql("media", f"SELECT media_id FROM media_asset WHERE owner_id IN({ids})").splitlines()
    app_sql = ",".join(f"'{str(uuid.UUID(value))}'" for value in applications) or "NULL"
    asset_sql = ",".join(f"'{str(uuid.UUID(value))}'" for value in assets) or "NULL"
    events = common.sql("iam", f"SELECT event_id FROM iam_outbox_event WHERE aggregate_id IN({app_sql},{asset_sql},{ids})").splitlines()
    for event in events:
        event = str(uuid.UUID(event))
        common.sql("media", f"DELETE FROM consumed_event WHERE event_id='{event}';")
        common.sql("operation", f"DELETE FROM operation_consumed_event WHERE event_id='{event}';")
    common.sql("operation", f"DELETE FROM notification_message WHERE recipient_id IN({ids}) OR target_id IN({app_sql}); DELETE FROM notification_delivery_work WHERE target_id IN({app_sql}); DELETE FROM operation_command WHERE actor_id IN({ids}); DELETE FROM operation_actor_lock WHERE actor_id IN({ids});")
    common.sql("iam", f"DELETE FROM iam_outbox_event WHERE aggregate_id IN({app_sql},{asset_sql},{ids}); DELETE FROM teacher_review_command WHERE application_id IN({app_sql}); DELETE FROM teacher_application_command WHERE application_id IN({app_sql}); DELETE FROM teacher_review_decision WHERE application_id IN({app_sql}); DELETE FROM teacher_application_round WHERE application_id IN({app_sql}); DELETE FROM teacher_application WHERE id IN({app_sql});")
    common.sql("iam", f"DELETE FROM account_permission_retry WHERE command_id IN(SELECT id FROM account_permission_command WHERE user_id IN({ids})); DELETE FROM account_permission_command WHERE user_id IN({ids}); DELETE FROM account_permission_preview WHERE user_id IN({ids});")
    common.sql("media", f"DELETE FROM consumed_event WHERE event_id IN(SELECT event_id FROM outbox_event WHERE aggregate_id IN({asset_sql})); DELETE FROM consumed_service_authorization WHERE visitor_id IN({ids}); DELETE FROM temporary_access_object WHERE media_id IN({asset_sql}); DELETE FROM access_grant_audit WHERE media_id IN({asset_sql}); DELETE FROM media_audit_record WHERE media_id IN({asset_sql}) OR upload_id IN(SELECT upload_id FROM upload_session WHERE owner_id IN({ids})); DELETE FROM outbox_event WHERE aggregate_id IN({asset_sql}); DELETE FROM media_reference WHERE media_id IN({asset_sql}); DELETE FROM processing_job WHERE media_id IN({asset_sql}); DELETE FROM media_variant_component WHERE media_id IN({asset_sql}); DELETE FROM media_variant WHERE media_id IN({asset_sql}); DELETE FROM media_asset WHERE media_id IN({asset_sql}); DELETE FROM upload_part WHERE upload_id IN(SELECT upload_id FROM upload_session WHERE owner_id IN({ids})); DELETE FROM upload_session WHERE owner_id IN({ids});")
    common.sql("iam", f"DELETE FROM account_security_audit WHERE user_id IN({ids}) OR actor_id IN({ids}); DELETE FROM refresh_session WHERE user_id IN({ids}) AND parent_id IS NOT NULL ORDER BY created_at DESC; DELETE FROM refresh_session WHERE user_id IN({ids}); DELETE FROM login_audit WHERE user_id IN({ids}); DELETE FROM user_identity WHERE user_id IN({ids}); DELETE FROM user_role WHERE user_id IN({ids}); DELETE FROM user WHERE id IN({ids});")
    env = dict(os.environ, REDISCLI_AUTH=os.environ.get("REDIS_PASSWORD", ""))
    cmd = ["redis-cli", "-h", os.environ.get("REDIS_HOST", "127.0.0.1"), "-p", os.environ.get("REDIS_PORT", "6379"), "-n", os.environ.get("IAM_REDIS_DATABASE", "1")]
    if os.environ.get("REDIS_USERNAME"): cmd += ["--user", os.environ["REDIS_USERNAME"]]
    keys = [os.environ.get("IAM_SESSION_VERSION_KEY_PREFIX", "edu:iam:session-version") + ":" + uid for uid in data.values()]
    risk_prefix = os.environ.get("IAM_SESSION_VERSION_KEY_PREFIX", "edu:iam:session-version").replace(":session-version", "")
    for uid in data.values():
        # 复用现有风控摘要算法定位本轮账号Key，不输出密钥或摘要，不删除共享IP限流。
        digest = hmac.new(os.environ["IAM_VERIFICATION_HMAC_SECRET"].encode(), ("login-user\n" + uid).encode(), hashlib.sha256).hexdigest()
        keys += [risk_prefix + ":login-failure:account:" + digest, risk_prefix + ":login-lock:account:" + digest]
    subprocess.run(cmd + ["DEL"] + keys, env=env, check=True, capture_output=True)
    if subprocess.run(cmd + ["EXISTS"] + keys, env=env, check=True, capture_output=True, text=True).stdout.strip() != "0":
        raise RuntimeError("Exact fixture Redis keys remain")
    if preservation() != json.loads((DIRECTORY / "preservation.json").read_text(encoding="utf-8")):
        raise RuntimeError("Existing facts differ; do not restore or delete unrelated data")
    MANIFEST.unlink(); print("Exact fixture cleanup verified; original administrator/course/question/history Outbox preserved")


def cleanup_courses(data):
    """课程只由正常API创建；先验证owner和标题，再按精确courseId/eventId删除派生与本域事实。"""
    for key, c in data.items():
        if key not in ["courseA", "courseB", "courseDraft"]:
            continue
        if common.sql("course", f"SELECT COUNT(*) FROM course WHERE id='{c}' AND owner_teacher_id<>'{data['student']}'") != "0" or common.sql("course", f"SELECT COUNT(*) FROM course_version WHERE course_id='{c}' AND title NOT LIKE 'T17C.2 %'") != "0":
            raise RuntimeError("Course UUID does not belong to this fixture")
        for event in common.sql("course", f"SELECT event_id FROM outbox_event WHERE aggregate_id='{c}'").splitlines():
            for table in ["course_availability_consumed_event", "consumed_course_content_event"]:
                common.sql("learning", f"DELETE FROM {table} WHERE event_id='{event}';")
            common.sql("operation", f"DELETE FROM operation_consumed_event WHERE event_id='{event}'; DELETE FROM hot_course_consumed_event WHERE event_id='{event}';")
        for event in common.sql("learning", f"SELECT event_id FROM learning_outbox_event WHERE aggregate_id IN(SELECT id FROM enrollment WHERE course_id='{c}')").splitlines():
            common.sql("course", f"DELETE FROM consumed_learning_event WHERE event_id='{event}';")
        common.sql("operation", f"DELETE FROM notification_message WHERE course_id='{c}'; DELETE FROM notification_delivery_work WHERE course_id='{c}'; DELETE FROM hot_course_dirty WHERE course_id='{c}'; DELETE FROM hot_course_projection WHERE course_id='{c}';")
        common.sql("learning", f"DELETE FROM learning_outbox_event WHERE aggregate_id IN(SELECT id FROM enrollment WHERE course_id='{c}'); DELETE FROM learning_idempotency_request WHERE resource_id IN(SELECT id FROM enrollment WHERE course_id='{c}'); DELETE FROM learning_course_clock_interval WHERE course_id='{c}'; DELETE FROM learning_course_clock WHERE course_id='{c}'; DELETE FROM notification_enrollment_interval WHERE course_id='{c}'; DELETE FROM course_student_number WHERE course_id='{c}'; DELETE FROM course_student_number_counter WHERE course_id='{c}'; DELETE FROM published_lesson_content_projection WHERE course_id='{c}'; DELETE FROM published_course_content_projection WHERE course_id='{c}'; DELETE FROM enrollment WHERE course_id='{c}';")
        common.sql("course", f"DELETE FROM outbox_event WHERE aggregate_id='{c}'; DELETE FROM course_enrollment_access WHERE course_id='{c}'; DELETE FROM course_suspension_interval WHERE course_id='{c}'; DELETE FROM lesson WHERE chapter_id IN(SELECT id FROM chapter WHERE course_version_id IN(SELECT id FROM course_version WHERE course_id='{c}')); DELETE FROM chapter WHERE course_version_id IN(SELECT id FROM course_version WHERE course_id='{c}'); UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id='{c}'; DELETE FROM course_version WHERE course_id='{c}'; DELETE FROM course WHERE id='{c}';")
        # 源课程删除后再次精确移除派生，避免有界热点Worker在前一次删除期间重读旧源。
        common.sql("operation", f"DELETE FROM hot_course_dirty WHERE course_id='{c}'; DELETE FROM hot_course_projection WHERE course_id='{c}';")
    common.sql("course", f"DELETE FROM teacher_course_availability WHERE teacher_id='{data['student']}';")
    course_ids = {value for key, value in data.items() if key in ["courseA", "courseB", "courseDraft"]}
    if course_ids:
        for _ in range(30):
            board = common.api("GET", "/operations/hot-courses")
            if not any(item["courseId"] in course_ids for item in board["items"]):
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Temporary course still appears in derived cache; do not delete shared Redis key")


def verify_coordination():
    """只读验证两次暂停/恢复同周期、草稿不受影响及Learning消费；不修改服务端协调事实。"""
    data = load(); settle()
    for key in ["courseA", "courseB"]:
        c = data[key]
        if common.sql("course", f"SELECT COUNT(*) FROM course_suspension_interval WHERE course_id='{c}' AND resumed_at IS NOT NULL") != "2":
            raise RuntimeError("Published course did not resume the two original suspension cycles")
        if common.sql("learning", f"SELECT COUNT(*) FROM learning_course_clock_interval WHERE course_id='{c}' AND resumed_at IS NOT NULL") != "2":
            raise RuntimeError("Learning did not consume both original pause/resume cycles")
    if common.sql("course", f"SELECT COUNT(*) FROM course_suspension_interval WHERE course_id='{data['courseDraft']}'") != "0":
        raise RuntimeError("Draft course was unexpectedly paused")
    print("Real two-course two-cycle suspension/resume and Learning consumption verified; draft unchanged")


def main():
    """显式create/settle/故障原件删除/cleanup；账号范围固定在本轮无秘密清单。"""
    parser = argparse.ArgumentParser(); parser.add_argument("action", choices=["create", "settle", "cleanup", "verify", "inspect"])
    action = parser.parse_args().action
    if action == "create": create(); return
    data = load()
    if action == "settle": settle()
    elif action == "verify": verify_coordination()
    elif action == "inspect":
        print(common.sql("iam", f"SELECT action,coordination_status,attempts,retry_ceiling,COALESCE(last_error_code,'') FROM account_permission_command WHERE user_id='{data['student']}' ORDER BY decided_revision"))
    else: cleanup(data)


if __name__ == "__main__": main()
