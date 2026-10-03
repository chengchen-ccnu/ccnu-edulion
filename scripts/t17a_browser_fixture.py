"""T17A 专用浏览器 Fixture：只连接明确 dev Schema，秘密由 PowerShell 安全加载器注入。

创建临时账号/发布课与待审轮次前置，公告、报名、任务发布、驳回均调用真实授权 API。
清理仅匹配无秘密 manifest 的 UUID，不重置 Seed、历史 Outbox、分类、题库或人工课程。
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid
import bcrypt

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / ".m2/t17a-browser/fixture.json"


def sql(domain, statement):
    """读取/写入本轮明确 dev 域；密码仅通过子进程环境传给 mysql，结果不输出隐私。"""
    schema = f"edu_platform_{domain}_dev"
    if os.environ.get(f"{domain.upper()}_MYSQL_DATABASE") != schema:
        raise RuntimeError("Only explicit dev schemas are allowed")
    env = dict(os.environ, MYSQL_PWD=os.environ["MYSQL_PASSWORD"])
    result = subprocess.run(["mysql", "--default-character-set=utf8mb4", "--host=" + os.environ["MYSQL_HOST"],
        "--port=" + os.environ["MYSQL_PORT"], "--user=" + os.environ["MYSQL_USERNAME"], "--database=" + schema,
        "--batch", "--skip-column-names"], input=statement, text=True, encoding="utf-8", env=env, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{domain} fixture SQL failed: {result.stderr}")
    return result.stdout.strip()


def api(method, path, token=None, body=None):
    """调用 Gateway 真实鉴权契约；Token/密码留在内存，失败只输出状态与错误码。"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request("http://localhost:8080/api/v1" + path,
        data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        value = json.loads(error.read())
        raise RuntimeError(f"API {method} {path}: {error.code} {value.get('code')}") from None


def token(data, user):
    return api("POST", "/auth/login", body={"identity": f"t17a-{data[user]}@example.invalid", "password": os.environ["T17A_FIXTURE_PASSWORD"]})["accessToken"]


def create():
    """创建最小分页/定位样本；已有 manifest 时拒绝重复创建，密码不写 manifest。"""
    if MANIFEST.exists():
        raise RuntimeError("Existing fixture requires exact cleanup first")
    data = {key: str(uuid.uuid4()) for key in ["student", "owner", "admin", "course", "version", "chapter", "lesson", "application", "round", "fakeAsset"]}
    MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    password_hash = bcrypt.hashpw(os.environ["T17A_FIXTURE_PASSWORD"].encode(), bcrypt.gensalt()).decode()
    for name in ["student", "owner", "admin"]:
        uid = data[name]
        role = "ADMIN" if name == "admin" else "STUDENT"
        sql("iam", f"INSERT INTO user(id,account,display_name,password_hash,status) VALUES('{uid}','t17a_{uid}','{name}_test_t17a','{password_hash}','ACTIVE'); INSERT INTO user_role SELECT '{uid}',id FROM role WHERE code='{role}'; INSERT INTO user_identity(id,user_id,identity_type,normalized_value,verified_at,created_at) VALUES(UUID(),'{uid}','EMAIL','t17a-{uid}@example.invalid',NOW(6),NOW(6));" + (f"INSERT INTO user_role SELECT '{uid}',id FROM role WHERE code='TEACHER';" if name == "owner" else ""))
    c, v, owner = data["course"], data["version"], data["owner"]
    category = sql("course", "SELECT id FROM category WHERE status='ACTIVE' ORDER BY id LIMIT 1")
    sql("course", f"INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status,published_at) VALUES('{c}',UUID(),'{owner}','PUBLISHED',NOW(6)); INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,description,cover_url,instructor_display_name,category_id,level,access_type) VALUES('{v}','{c}',1,'v1','PUBLISHED','T17A 浏览器测试课程','专用临时门户与消息验收','只有此课程属于本轮临时 Fixture。','','owner_test_t17a','{category}','BEGINNER','PUBLIC'); UPDATE course SET current_draft_version_id='{v}',published_version_id='{v}' WHERE id='{c}'; INSERT INTO chapter(id,course_version_id,title,position) VALUES('{data['chapter']}','{v}','测试章节',1); INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,content_revision_id) VALUES('{data['lesson']}','{data['chapter']}','测试视频元数据','VIDEO',1,FALSE,'{data['lesson']}');")
    student_token, owner_token, admin_token = (token(data, name) for name in ["student", "owner", "admin"])
    data["enrollment"] = api("POST", "/learning/enrollments", student_token, {"courseId": c})["id"]
    MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    for number in range(21):
        announcement = api("POST", f"/teacher/courses/{c}/announcements", owner_token, {"clientRequestId": str(uuid.uuid4()), "title": f"测试公告 {number+1:02}", "body": "真实公告正文，仅用于 T17A 浏览器验收。\n<script>不会执行</script>"})
        data["announcement"] = announcement["id"]
        MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    sql("iam", f"INSERT INTO teacher_application(id,user_id,qualification_media_id,statement,status,submitted_at,updated_at) VALUES('{data['application']}','{data['student']}','{data['fakeAsset']}','合成待审申请，仅验证真实驳回事实','UNDER_REVIEW',NOW(6),NOW(6)); INSERT INTO teacher_application_round(id,application_id,round_number,qualification_media_id,statement,status,submitted_at,first_viewed_by,first_viewed_at,bound_enqueued_at) VALUES('{data['round']}','{data['application']}',1,'{data['fakeAsset']}','合成待审申请','UNDER_REVIEW',NOW(6),'{data['admin']}',NOW(6),NOW(6));")
    api("POST", f"/admin/teacher-applications/{data['application']}/decision", admin_token, {"clientRequestId": str(uuid.uuid4()), "expectedRevision": 0, "decision": "REJECTED", "materialConfirmed": False, "rejectionReason": "test_t17a：请重新上传有效资质后提交"})
    now = datetime.datetime.now(datetime.timezone.utc)
    task = api("POST", "/teacher/assessments", owner_token, {"clientRequestId": str(uuid.uuid4()), "courseId": c, "lessonId": None, "title": "T17A 测试任务", "description": "临时验收任务", "type": "HOMEWORK", "gradePublicationPolicy": "MANUAL", "answerPublicationPolicy": "NEVER", "opensAt": (now-datetime.timedelta(minutes=1)).isoformat(), "closesAt": (now+datetime.timedelta(hours=1)).isoformat(), "latePolicy": "DISALLOW", "lateUntil": None, "maxAttempts": 1, "timeLimitSeconds": None})
    data["task"] = task["id"]; MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    question = sql("learning", "SELECT current_version_id FROM question_aggregate WHERE scope='SYSTEM' AND status='ACTIVE' AND current_version_id IS NOT NULL ORDER BY id LIMIT 1")
    replaced = api("PUT", f"/teacher/assessments/{task['id']}/questions", owner_token, {"expectedRevision": task["revision"], "questions": [{"questionVersionId": question, "sequence": 1, "points": 1}]})
    api("PUT", f"/teacher/assessments/{task['id']}/publication", owner_token, {"expectedRevision": replaced["revision"], "status": "PUBLISHED"})
    for _ in range(30):
        if api("GET", "/notifications", student_token)["total"] >= 23:
            break
        time.sleep(1)
    else:
        raise RuntimeError("Real notification delivery did not finish")
    print(json.dumps({key: data[key] for key in ["student", "course", "announcement", "task"]}))


def cleanup(data):
    """按UUID精确清理依赖顺序；重跑幂等，绝不修改已有题目、账号或历史事件。"""
    c, a = data["course"], data["application"]
    aggregate_ids = [c, a, data["student"], data["owner"], data["admin"]] + [data[key] for key in ["enrollment", "task"] if key in data]
    aggregate_sql = ",".join(f"'{value}'" for value in aggregate_ids)
    # 发布/消费副本按本轮精确聚合收集eventId，绝不按历史状态或通配标题删除。
    learning_events = sql("learning", f"SELECT event_id FROM learning_outbox_event WHERE aggregate_id IN({aggregate_sql})").splitlines()
    course_events = sql("course", f"SELECT event_id FROM outbox_event WHERE aggregate_id IN({aggregate_sql})").splitlines()
    for event in learning_events:
        sql("course", f"DELETE FROM consumed_learning_event WHERE event_id='{str(uuid.UUID(event))}';")
    for event in course_events:
        sql("learning", f"DELETE FROM course_availability_consumed_event WHERE event_id='{str(uuid.UUID(event))}'; DELETE FROM consumed_course_content_event WHERE event_id='{str(uuid.UUID(event))}';")
    sql("operation", f"DELETE FROM hot_course_consumed_event WHERE course_id='{c}'; DELETE FROM hot_course_dirty WHERE course_id='{c}'; DELETE FROM hot_course_projection WHERE course_id='{c}';")
    events = sql("operation", f"SELECT event_id FROM notification_delivery_work WHERE course_id='{c}' OR target_id='{a}'").splitlines()
    for event in events:
        sql("operation", f"DELETE FROM operation_consumed_event WHERE event_id='{str(uuid.UUID(event))}';")
    sql("operation", f"DELETE FROM notification_message WHERE course_id='{c}' OR target_id='{a}'; DELETE FROM notification_delivery_work WHERE course_id='{c}' OR target_id='{a}'; DELETE FROM operation_outbox_event WHERE aggregate_id IN(SELECT id FROM course_announcement WHERE course_id='{c}'); DELETE FROM course_announcement_confirmation WHERE announcement_id IN(SELECT id FROM course_announcement WHERE course_id='{c}'); DELETE FROM course_announcement_revision WHERE announcement_id IN(SELECT id FROM course_announcement WHERE course_id='{c}'); DELETE FROM course_announcement WHERE course_id='{c}';")
    for name in ["student", "owner", "admin"]:
        sql("operation", f"DELETE FROM operation_command WHERE actor_id='{data[name]}'; DELETE FROM operation_actor_lock WHERE actor_id='{data[name]}';")
    sql("learning", f"DELETE FROM learning_outbox_event WHERE aggregate_id IN({aggregate_sql}); DELETE FROM learning_course_clock_interval WHERE course_id='{c}'; DELETE FROM learning_course_clock WHERE course_id='{c}';")
    for table in ["assessment_notification_opening", "assessment_deadline_audit", "assessment_question", "assessment_participant", "assessment_assignment"]:
        sql("learning", f"DELETE FROM {table} WHERE assessment_id IN(SELECT id FROM assessment WHERE course_id='{c}');")
    sql("learning", f"DELETE FROM learning_idempotency_request WHERE resource_id IN(SELECT id FROM assessment WHERE course_id='{c}') OR resource_id IN(SELECT id FROM enrollment WHERE course_id='{c}'); DELETE FROM assessment WHERE course_id='{c}'; DELETE FROM notification_enrollment_interval WHERE course_id='{c}'; DELETE FROM course_student_number WHERE course_id='{c}'; DELETE FROM course_student_number_counter WHERE course_id='{c}'; DELETE FROM published_lesson_content_projection WHERE course_id='{c}'; DELETE FROM published_course_content_projection WHERE course_id='{c}'; DELETE FROM learning_activity_day WHERE course_id='{c}'; DELETE FROM lesson_learning_day WHERE course_id='{c}'; DELETE FROM enrollment WHERE course_id='{c}';")
    sql("course", f"DELETE FROM outbox_event WHERE aggregate_id='{c}'; DELETE FROM course_enrollment_access WHERE course_id='{c}'; DELETE FROM course_suspension_interval WHERE course_id='{c}'; DELETE FROM teacher_course_availability WHERE teacher_id='{data['owner']}'; DELETE FROM lesson WHERE id='{data['lesson']}'; DELETE FROM chapter WHERE id='{data['chapter']}'; UPDATE course SET current_draft_version_id=NULL,published_version_id=NULL WHERE id='{c}'; DELETE FROM course_version WHERE id='{data['version']}'; DELETE FROM course WHERE id='{c}';")
    sql("iam", f"DELETE FROM iam_outbox_event WHERE aggregate_id IN({aggregate_sql}); DELETE FROM teacher_review_command WHERE application_id='{a}'; DELETE FROM teacher_review_decision WHERE application_id='{a}'; DELETE FROM teacher_application_round WHERE application_id='{a}'; DELETE FROM teacher_application WHERE id='{a}';")
    for name in ["student", "owner", "admin"]:
        uid = data[name]
        sql("iam", f"DELETE FROM account_permission_retry WHERE command_id IN(SELECT id FROM account_permission_command WHERE user_id='{uid}'); DELETE FROM account_permission_command WHERE user_id='{uid}' OR actor_id='{uid}'; DELETE FROM account_permission_preview WHERE user_id='{uid}' OR actor_id='{uid}';")
        sql("iam", f"DELETE FROM account_security_audit WHERE user_id='{uid}' OR actor_id='{uid}'; DELETE FROM refresh_session WHERE user_id='{uid}' AND parent_id IS NOT NULL ORDER BY created_at DESC; DELETE FROM refresh_session WHERE user_id='{uid}'; DELETE FROM login_audit WHERE user_id='{uid}'; DELETE FROM user_identity WHERE user_id='{uid}'; DELETE FROM user_role WHERE user_id='{uid}'; DELETE FROM user WHERE id='{uid}';")
    redis_env = dict(os.environ, REDISCLI_AUTH=os.environ.get("REDIS_PASSWORD", ""))
    keys = [os.environ.get("IAM_SESSION_VERSION_KEY_PREFIX", "edu:iam:session-version") + ":" + data[name] for name in ["student", "owner", "admin"]]
    redis_command = ["redis-cli", "-h", os.environ.get("REDIS_HOST", "127.0.0.1"), "-p", os.environ.get("REDIS_PORT", "6379"), "-n", os.environ.get("IAM_REDIS_DATABASE", "1")]
    if os.environ.get("REDIS_USERNAME"):
        redis_command += ["--user", os.environ["REDIS_USERNAME"]]
    subprocess.run(redis_command + ["DEL"] + keys, env=redis_env, check=True, capture_output=True)
    remaining = subprocess.run(redis_command + ["EXISTS"] + keys, env=redis_env, check=True, capture_output=True, text=True).stdout.strip()
    if remaining != "0":
        raise RuntimeError("Exact fixture session cache cleanup failed")
    MANIFEST.unlink(missing_ok=True)
    print("T17A exact-ID fixture cleanup completed")


def main():
    """显式create/cleanup或修改本轮公告；修改仅经真实所有权API，未提供密码拒绝命令。"""
    parser = argparse.ArgumentParser(); parser.add_argument("action", choices=["create", "cleanup", "edit", "withdraw", "unenroll", "pause"])
    action = parser.parse_args().action
    if action == "create":
        create(); return
    if action == "cleanup" and not MANIFEST.exists():
        print("No active T17A fixture; cleanup already completed")
        return
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for key, value in data.items():
        data[key] = str(uuid.UUID(value))
    if action == "cleanup":
        cleanup(data); return
    if action == "pause":
        admin_token = token(data, "admin")
        preview = api("POST", f"/admin/users/{data['owner']}/permission-previews", admin_token, {"action": "REVOKE_TEACHING"})
        command = api("POST", f"/admin/users/{data['owner']}/permission-commands", admin_token, {"clientRequestId": str(uuid.uuid4()), "previewId": preview["previewId"], "expectedRevision": preview["expectedRevision"], "action": "REVOKE_TEACHING", "reason": "test_t17a 专用临时账号课程暂停验收", "confirmed": True})
        for _ in range(30):
            result = api("GET", f"/admin/permission-commands/{command['commandId']}", admin_token)
            if result["coordinationStatus"] == "APPLIED":
                print("Real IAM permission decision and Course coordination applied")
                return
            time.sleep(1)
        raise RuntimeError("Coordination did not reach APPLIED")
    if action == "unenroll":
        api("DELETE", f"/learning/enrollments/{data['enrollment']}", token(data, "student")); return
    current = api("GET", f"/courses/{data['course']}/announcements/{data['announcement']}", token(data, "student"))
    body = {"clientRequestId": str(uuid.uuid4()), "expectedRevision": current["revision"]}
    if action == "edit":
        body.update(title="测试公告改版", body="新版真实正文。请再次明确确认。")
        api("PUT", f"/teacher/courses/{data['course']}/announcements/{data['announcement']}", token(data, "owner"), body)
    else:
        api("POST", f"/teacher/courses/{data['course']}/announcements/{data['announcement']}/withdraw", token(data, "owner"), body)


if __name__ == "__main__":
    main()
