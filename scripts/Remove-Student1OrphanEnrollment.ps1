[CmdletBinding()]
param([switch]$InspectOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 精确修复 student1 的 T12F.1 演示残留；不按 404 批量删课，不创建数据库或测试数据。
# 输入仅为只读检查开关；默认删除固定报名及其级联通知区间，保留其他课程和学习历史。
& (Join-Path $PSScriptRoot "Invoke-WithLocalEnvironment.ps1") -ScriptBlock {
    if ($env:COURSE_MYSQL_DATABASE -ne "edu_platform_course_dev" -or
        $env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev") {
        throw "Only the dedicated course/learning development schemas are allowed."
    }
    $student = "00000000-0000-4000-8000-000000000101"
    $course = "00000000-0000-4000-8000-00000000f201"
    $enrollment = "0225ddc0-24fa-43d4-b5b6-ec3da741dd7f"
    $previousMysqlPassword = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")
    try {
        $env:MYSQL_PWD = $env:MYSQL_PASSWORD
        # 每次连接只查询一个领域 Schema；密码由安全包装器注入环境，不进入命令参数和输出。
        function Invoke-RepairSql {
            param([string]$Database, [string]$Sql)
            $result = & mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME `
                --database=$Database --default-character-set=utf8mb4 --batch --skip-column-names --execute=$Sql
            if ($LASTEXITCODE -ne 0) { throw "Development repair SQL failed; inspect the reported SQL error." }
            return $result
        }
        $courseCount = Invoke-RepairSql $env:COURSE_MYSQL_DATABASE "SELECT COUNT(*) FROM course WHERE id='$course'"
        if ([int]$courseCount -ne 0) { throw "Course exists; refusing to remove its enrollment." }
        # 有真实进度、笔记、作答或图文完成事实时停止，不把历史学习误当一次性 Seed 残留。
        $historyCount = Invoke-RepairSql $env:LEARNING_MYSQL_DATABASE @"
SELECT
 (SELECT COUNT(*) FROM lesson_progress WHERE learner_id='$student' AND course_id='$course') +
 (SELECT COUNT(*) FROM learning_note WHERE learner_id='$student' AND course_id='$course') +
 (SELECT COUNT(*) FROM article_lesson_completion WHERE learner_id='$student' AND course_id='$course') +
 (SELECT COUNT(*) FROM article_completion_request WHERE learner_id='$student' AND course_id='$course') +
 (SELECT COUNT(*) FROM assessment_attempt a JOIN assessment s ON s.id=a.assessment_id
  WHERE a.learner_id='$student' AND s.course_id='$course');
"@
        if ([int]$historyCount -ne 0) { throw "Learning history exists; refusing physical cleanup." }
        $before = Invoke-RepairSql $env:LEARNING_MYSQL_DATABASE "SELECT COUNT(*) FROM enrollment WHERE id='$enrollment' AND learner_id='$student' AND course_id='$course'"
        Write-Output "Verified missing course, no learning history; matching orphan enrollment count: $before"
        if (-not $InspectOnly) {
            # 三个固定身份同时限定删除；通知区间由既有本域外键 ON DELETE CASCADE 清理。
            # 这是不存在演示课程的运维清理，不发布虚构的正常退课事件，不改写既有 Outbox。
            $removed = Invoke-RepairSql $env:LEARNING_MYSQL_DATABASE @"
START TRANSACTION;
DELETE FROM enrollment WHERE id='$enrollment' AND learner_id='$student' AND course_id='$course';
SELECT ROW_COUNT();
COMMIT;
"@
            $remaining = Invoke-RepairSql $env:LEARNING_MYSQL_DATABASE "SELECT COUNT(*) FROM enrollment WHERE id='$enrollment' AND learner_id='$student' AND course_id='$course'"
            if ([int]$remaining -ne 0) { throw "Orphan enrollment still exists after cleanup." }
            Write-Output "Removed orphan enrollment rows: $removed; remaining: $remaining"
        }
    } finally {
        [Environment]::SetEnvironmentVariable("MYSQL_PWD", $previousMysqlPassword, "Process")
    }
}
