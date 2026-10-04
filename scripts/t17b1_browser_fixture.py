"""T17B.1 独立 dev Fixture；只创建合成账号、单课程、报名和公告，不接触题库或手工课程。

复用 T17A 的 dev Schema 校验、Gateway 调用和精确 UUID/eventId 清理工具；独立 manifest
与 DPAPI 密码，不创建数据库/表、不启动 Seed、不 purge MQ、不清空 Redis。
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
import bcrypt
import t17a_browser_fixture as common

DIRECTORY = common.ROOT / ".m2/t17b1-browser"
MANIFEST = DIRECTORY / "fixture.json"
common.MANIFEST = MANIFEST


def token(data, name):
    """用本轮合成身份登录 Gateway；密码只从安全加载器的进程环境取得，Token 不落盘。"""
    return common.api("POST", "/auth/login", body={"identity": f"t17b1-{data[name]}@example.invalid",
        "password": os.environ["T17B1_FIXTURE_PASSWORD"]})["accessToken"]


def preserve():
    """读取存量课程/题库/事件事实作为本轮验后比对；不冻结产品契约或改变历史事件状态。"""
    result = {"courses": common.sql("course", "SELECT id,owner_teacher_id,publication_status,COALESCE(published_version_id,''),COALESCE(current_draft_version_id,'') FROM course ORDER BY id"),
        "questions": common.sql("learning", "SELECT id,scope,status,COALESCE(current_version_id,'') FROM question_aggregate ORDER BY id")}
    for domain, table in [("iam", "iam_outbox_event"), ("course", "outbox_event"), ("learning", "learning_outbox_event"), ("operation", "operation_outbox_event"), ("media", "outbox_event")]:
        result[domain + "Events"] = common.sql(domain, f"SELECT event_id,status FROM {table} ORDER BY event_id")
    return result


def create():
    """先保存独立 UUID 清单再写入，失败也可清理；真实公告/改版/报名全部通过领域 API。"""
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    if MANIFEST.exists():
        raise RuntimeError("Existing T17B.1 fixture requires exact cleanup first")
    (DIRECTORY / "preservation.json").write_text(json.dumps(preserve()), encoding="utf-8")
    data = {key: str(uuid.uuid4()) for key in ["student", "owner", "admin", "nonowner", "course", "version", "chapter", "lesson", "application"]}
    MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    hashed = bcrypt.hashpw(os.environ["T17B1_FIXTURE_PASSWORD"].encode(), bcrypt.gensalt()).decode()
    for name in ["student", "owner", "admin", "nonowner"]:
        uid = data[name]; role = "ADMIN" if name == "admin" else "STUDENT"
        common.sql("iam", f"INSERT INTO user(id,account,display_name,password_hash,status) VALUES('{uid}','t17b1_{uid}','{name}_test_t17b1','{hashed}','ACTIVE'); INSERT INTO user_role SELECT '{uid}',id FROM role WHERE code='{role}'; INSERT INTO user_identity(id,user_id,identity_type,normalized_value,verified_at,created_at) VALUES(UUID(),'{uid}','EMAIL','t17b1-{uid}@example.invalid',NOW(6),NOW(6));" + (f"INSERT INTO user_role SELECT '{uid}',id FROM role WHERE code='TEACHER';" if name in ["owner", "nonowner"] else ""))
    c, v = data["course"], data["version"]
    category = str(uuid.UUID(common.sql("course", "SELECT id FROM category WHERE status='ACTIVE' ORDER BY id LIMIT 1")))
    common.sql("course", f"INSERT INTO course(id,create_request_id,owner_teacher_id,publication_status,published_at) VALUES('{c}',UUID(),'{data['owner']}','PUBLISHED',NOW(6)); INSERT INTO course_version(id,course_id,version_number,version_label,lifecycle_status,title,summary,description,cover_url,instructor_display_name,category_id,level,access_type) VALUES('{v}','{c}',1,'v1','PUBLISHED','T17B.1 独立公告验收课程','仅用于本轮公告闭环','合成临时数据，验收后按 UUID 清理。','','owner_test_t17b1','{category}','BEGINNER','PUBLIC'); UPDATE course SET published_version_id='{v}' WHERE id='{c}'; INSERT INTO chapter(id,course_version_id,title,position) VALUES('{data['chapter']}','{v}','临时章节',1); INSERT INTO lesson(id,chapter_id,title,lesson_type,position,previewable,content_revision_id) VALUES('{data['lesson']}','{data['chapter']}','无媒体的临时视频元数据','VIDEO',1,FALSE,'{data['lesson']}');")
    owner = token(data, "owner"); student = token(data, "student")
    data["enrollment"] = common.api("POST", "/learning/enrollments", student, {"courseId": c})["id"]
    MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    for index in range(21):
        announcement = common.api("POST", f"/teacher/courses/{c}/announcements", owner,
            {"clientRequestId": str(uuid.uuid4()), "title": f"分页验收公告 {index + 1:02}", "body": "纯文本测试\n<script>不会执行</script>"})
        time.sleep(0.1)
    data["auditAnnouncement"] = announcement["id"]
    MANIFEST.write_text(json.dumps(data), encoding="utf-8")
    for revision in range(1, 22):
        announcement = common.api("PUT", f"/teacher/courses/{c}/announcements/{data['auditAnnouncement']}", owner,
            {"clientRequestId": str(uuid.uuid4()), "expectedRevision": revision, "title": "多版本审计验收公告", "body": f"审计纯文本版本 {revision + 1}"})
        time.sleep(0.1)
    for _ in range(30):
        if common.api("GET", "/notifications", student)["total"] >= 21: break
        time.sleep(1)
    else: raise RuntimeError("Fixture notification delivery timeout")
    print("T17B.1 fixture ready: 4 synthetic accounts, 1 published course, 1 enrollment, 21 announcements, 22 audit revisions")


def cleanup(data):
    """先清理额外非 owner 账号，再复用按 UUID/eventId 的依赖清理；最后比对原有事实，支持重复运行。"""
    uid = data["nonowner"]
    common.sql("operation", f"DELETE FROM operation_command WHERE actor_id='{uid}'; DELETE FROM operation_actor_lock WHERE actor_id='{uid}';")
    common.sql("iam", f"DELETE FROM account_security_audit WHERE user_id='{uid}' OR actor_id='{uid}'; DELETE FROM refresh_session WHERE user_id='{uid}' AND parent_id IS NOT NULL ORDER BY created_at DESC; DELETE FROM refresh_session WHERE user_id='{uid}'; DELETE FROM login_audit WHERE user_id='{uid}'; DELETE FROM user_identity WHERE user_id='{uid}'; DELETE FROM user_role WHERE user_id='{uid}'; DELETE FROM user WHERE id='{uid}';")
    env = dict(os.environ, REDISCLI_AUTH=os.environ.get("REDIS_PASSWORD", ""))
    command = ["redis-cli", "-h", os.environ.get("REDIS_HOST", "127.0.0.1"), "-p", os.environ.get("REDIS_PORT", "6379"), "-n", os.environ.get("IAM_REDIS_DATABASE", "1")]
    if os.environ.get("REDIS_USERNAME"): command += ["--user", os.environ["REDIS_USERNAME"]]
    subprocess.run(command + ["DEL", os.environ.get("IAM_SESSION_VERSION_KEY_PREFIX", "edu:iam:session-version") + ":" + uid], env=env, check=True, capture_output=True)
    common.cleanup(data)
    expected = json.loads((DIRECTORY / "preservation.json").read_text(encoding="utf-8"))
    if preserve() != expected: raise RuntimeError("Existing course/question/event facts differ; inspect without restoring or deleting unrelated data")
    print("T17B.1 exact cleanup and existing course/question/history Outbox preservation verified")


def settle(data):
    """停止本轮服务前限时等待已提交 Outbox 派发；只观察，不重试失败消息或修改历史事件。"""
    for _ in range(40):
        counts = [int(common.sql(domain, f"SELECT COUNT(*) FROM {table} WHERE status='PENDING'"))
            for domain, table in [("iam", "iam_outbox_event"), ("course", "outbox_event"), ("learning", "learning_outbox_event"), ("operation", "operation_outbox_event"), ("media", "outbox_event")]]
        if not any(counts):
            print("Five dev domains PENDING=0; safe to stop only this run's service processes")
            print("Temporary announcement rows:", common.sql("operation", f"SELECT COUNT(*) FROM course_announcement WHERE course_id='{data['course']}'"))
            return
        time.sleep(0.5)
    raise RuntimeError("Outbox still pending; keep processes/fixture for inspection")


def main():
    """显式创建/清理独立测试事实；仅允许 manifest 中有效 UUID，凭据缺失由安全调用器拒绝。"""
    parser = argparse.ArgumentParser(); parser.add_argument("action", choices=["create", "cleanup", "settle"])
    action = parser.parse_args().action
    if action == "create": create(); return
    if not MANIFEST.exists(): print("No active T17B.1 fixture; cleanup already completed"); return
    data = {key: str(uuid.UUID(value)) for key, value in json.loads(MANIFEST.read_text(encoding="utf-8")).items()}
    if action == "settle": settle(data)
    else: cleanup(data)


if __name__ == "__main__":
    main()
