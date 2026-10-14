[CmdletBinding()]
param([switch]$InspectOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 一次性本地 Seed 修复：旧 v1 的 VIDEO 没有媒体，不能通过现代发布校验。
# 只把本轮教师正常上传并绑定到 v2 的 READY 封面移到固定已发布 v1，撤掉本轮草稿。
# 不改课时、不发布新内容、不降低生产发布校验；每个引用变更与 Outbox 同事务。
& (Join-Path $PSScriptRoot "Invoke-WithLocalEnvironment.ps1") -ScriptBlock {
    foreach ($domain in @("COURSE", "MEDIA")) {
        if ([Environment]::GetEnvironmentVariable("${domain}_MYSQL_DATABASE") -ne "edu_platform_$($domain.ToLower())_dev") {
            throw "Only dedicated course/media development schemas are allowed."
        }
    }
    $media = "96a3f373-607c-4351-a094-fb9391d67c8e"
    $course = "00000000-0000-4000-8000-000000000201"
    $published = "00000000-0000-4000-8000-000000000211"
    $draft = "f740b07d-1186-43f4-a843-8950a8b0d364"
    $teacher = "00000000-0000-4000-8000-000000000102"
    $previousMysqlPassword = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")
    try {
        $env:MYSQL_PWD = $env:MYSQL_PASSWORD
        # 独立领域连接读取明确开发数据；秘密只来自包装器环境，不输出 URL/对象键/Token。
        function Invoke-CoverSql {
            param([string]$Database, [string]$Sql)
            $result = & mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME `
                --database=$Database --default-character-set=utf8mb4 --batch --skip-column-names --execute=$Sql
            if ($LASTEXITCODE -ne 0) { throw "Cover repair SQL failed; inspect the reported SQL error." }
            return $result
        }
        $ready = Invoke-CoverSql $env:MEDIA_MYSQL_DATABASE "SELECT COUNT(*) FROM media_asset WHERE media_id='$media' AND owner_id='$teacher' AND purpose='COURSE_COVER' AND status='READY'"
        if ([int]$ready -ne 1) { throw "The exact teacher-owned COURSE_COVER is not READY." }
        $done = Invoke-CoverSql $env:COURSE_MYSQL_DATABASE "SELECT COUNT(*) FROM course c JOIN course_version v ON v.id=c.published_version_id WHERE c.id='$course' AND v.id='$published' AND c.current_draft_version_id IS NULL AND v.cover_media_id='$media' AND v.cover_url IS NULL"
        if ([int]$done -eq 1) { Write-Output "Published demo cover already repaired; no changes."; return }
        $resources = Invoke-CoverSql $env:COURSE_MYSQL_DATABASE "SELECT COUNT(*) FROM lesson_media_resource r JOIN lesson l ON l.id=r.lesson_id JOIN chapter c ON c.id=l.chapter_id WHERE c.course_version_id='$draft'"
        if ([int]$resources -ne 0) { throw "The draft has resources; refusing to discard it." }
        $expected = Invoke-CoverSql $env:COURSE_MYSQL_DATABASE "SELECT COUNT(*) FROM course c JOIN course_version d ON d.id=c.current_draft_version_id JOIN course_version p ON p.id=c.published_version_id WHERE c.id='$course' AND c.owner_teacher_id='$teacher' AND c.row_version=1 AND d.id='$draft' AND d.row_version=1 AND d.cover_media_id='$media' AND p.id='$published' AND p.cover_media_id IS NULL AND p.cover_url='https://cdn.example.test/courses/algebra-cover.jpg'"
        if ([int]$expected -ne 1) { throw "Course differs from this repair's expected Seed/draft; stop without overwriting." }
        if ($InspectOnly) { Write-Output "Teacher-owned READY cover and exact original Seed/draft verified."; return }
        $trace = [guid]::NewGuid().ToString()
        $timestamp = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.ffffffZ")
        $eventSql = @()
        # v1 绑定与 v2 释放复用 media-events v1 信封；数据库与事件只包含稳定媒体/引用 ID。
        foreach ($item in @(
            @{ Type="MediaReferenceBound"; Reference=$published; Routing="media.reference.bound.v1" },
            @{ Type="MediaReferenceReleased"; Reference=$draft; Routing="media.reference.released.v1" }
        )) {
            $eventId = [guid]::NewGuid().ToString()
            $body = @{ eventId=$eventId; eventType=$item.Type; eventVersion=1; aggregateId=$media;
                occurredAt=$timestamp; traceId=$trace; payload=@{ mediaId=$media; ownerService="course-service";
                    referenceType="COURSE_VERSION_COVER"; referenceId=$item.Reference } } | ConvertTo-Json -Compress
            $eventSql += "INSERT INTO outbox_event(event_id,event_type,event_version,aggregate_id,exchange_name,routing_key,trace_id,payload_json,status,attempts,next_attempt_at,created_at,updated_at) SELECT '$eventId','$($item.Type)',1,'$media','edu.media.events.v1','$($item.Routing)','$trace','$body','PENDING',0,NOW(6),NOW(6),NOW(6) WHERE @changed=1;"
        }
        # 事务锁住课程及两个版本，仍校验原 revision；有并发修改时整个操作为零写入。
        $result = Invoke-CoverSql $env:COURSE_MYSQL_DATABASE @"
START TRANSACTION;
SELECT id FROM course WHERE id='$course' FOR UPDATE;
SELECT id FROM course_version WHERE id IN ('$published','$draft') ORDER BY id FOR UPDATE;
SET @eligible=(SELECT COUNT(*) FROM course c JOIN course_version d ON d.id=c.current_draft_version_id
 JOIN course_version p ON p.id=c.published_version_id WHERE c.id='$course' AND c.row_version=1
 AND d.id='$draft' AND d.row_version=1 AND d.cover_media_id='$media' AND p.id='$published' AND p.cover_media_id IS NULL);
UPDATE course_version SET cover_media_id='$media',cover_url=NULL,row_version=row_version+1
 WHERE id='$published' AND @eligible=1;
SET @changed=ROW_COUNT();
$($eventSql -join "`n")
UPDATE course SET current_draft_version_id=NULL,row_version=row_version+1 WHERE id='$course' AND @changed=1;
DELETE l FROM lesson l JOIN chapter c ON c.id=l.chapter_id WHERE c.course_version_id='$draft' AND @changed=1;
DELETE FROM chapter WHERE course_version_id='$draft' AND @changed=1;
DELETE FROM course_version WHERE id='$draft' AND @changed=1;
SELECT @changed;
COMMIT;
"@
        if ([int]@($result)[-1] -ne 1) { throw "Concurrent course change prevented repair; no changes applied." }
        Write-Output "Published Seed cover repaired; only this run's draft removed; bound/released Outbox queued."
    } finally {
        [Environment]::SetEnvironmentVariable("MYSQL_PWD", $previousMysqlPassword, "Process")
    }
}
