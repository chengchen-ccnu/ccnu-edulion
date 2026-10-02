#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$MaintenanceConfirmed,
    [Parameter(Mandatory=$true)][string]$VerifiedBackupDirectory,
    [ValidateSet('Rebuild','VerifyAndCleanup')][string]$Action='Rebuild'
)

<#
仅在人工确认源表当前异常限于 idx_learning_outbox_pending、完整备份恢复已验证的维护窗口使用。
从本轮受保护逻辑备份恢复随机隔离表，逐列核对全部历史事件；仅重建原二级索引，绝不覆盖源表。
VerifyAndCleanup 在发布回归精确清理后再次比较15列与ID集合，删除本轮隔离表，保留仓库外备份。
存在主键/数据页错误、其他连接、结构变化或恢复差异时停止，不删业务数据、不改状态或重放。
#>
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if (-not $MaintenanceConfirmed) { throw 'Confirmed Learning maintenance window required.' }
$schema='edu_platform_learning_dev'
$table='learning_outbox_event'
$index='idx_learning_outbox_pending'
$backupRoot=[IO.Path]::GetFullPath((Join-Path ([Environment]::GetFolderPath('UserProfile')) '.ccnu-edulion/backups/t16-4-0'))
$VerifiedBackupDirectory=[IO.Path]::GetFullPath($VerifiedBackupDirectory)
if (-not $VerifiedBackupDirectory.StartsWith($backupRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use the protected current-user backup directory outside Git.'
}
if (-not (Test-Path (Join-Path $VerifiedBackupDirectory 'verified.txt'))) { throw 'Verified recovery evidence is missing.' }
$restoreTable=[IO.File]::ReadAllText((Join-Path $VerifiedBackupDirectory 'restore-table.txt')).Trim()
if ($restoreTable -notmatch '^t1640_outbox_restore_[a-f0-9]{32}$') { throw 'Invalid isolated recovery table.' }
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if ($env:LEARNING_MYSQL_DATABASE -ne $schema) { throw 'Only the Learning development schema is permitted.' }
    if (Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'learning-service' }) {
        throw 'Learning JVM exists; do not race or stop user processes.'
    }
    $previousPwd=[Environment]::GetEnvironmentVariable('MYSQL_PWD','Process')
    $env:MYSQL_PWD=$env:MYSQL_PASSWORD
    $connection=@("--host=$env:MYSQL_HOST","--port=$env:MYSQL_PORT","--user=$env:MYSQL_USERNAME","--database=$schema",'--batch','--skip-column-names')
    try {
        function Invoke-RebuildSql([string]$Sql) {
            $result=& mysql @connection "--execute=$Sql" 2>> (Join-Path $VerifiedBackupDirectory 'rebuild-error.txt')
            if ($LASTEXITCODE -ne 0) { throw 'Rebuild SQL failed; preserve source and recovery materials for investigation.' }
            return $result
        }
        if ([int](Invoke-RebuildSql "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='$schema' AND ID<>CONNECTION_ID()") -ne 0) {
            throw 'Other Learning connections exist; stop.'
        }
        if ($Action -eq 'Rebuild') {
            $existing=[int](Invoke-RebuildSql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$schema' AND TABLE_NAME='$restoreTable'")
            if ($existing -eq 0) {
                $restorePath=Join-Path $VerifiedBackupDirectory 'restore-isolated.sql'
                Invoke-RebuildSql ('SOURCE '+$restorePath.Replace('\','/')) | Out-Null
            }
        }
        $columns=@('event_id','event_type','event_version','aggregate_id','exchange_name','routing_key','trace_id',
            'payload_json','status','attempts','next_attempt_at','last_error','occurred_at','published_at','updated_at')
        $equal=($columns | ForEach-Object { "(BINARY p.$_ <=> BINARY b.$_)" }) -join ' AND '
        function Assert-OriginalFacts {
            $difference=@(Invoke-RebuildSql "SELECT COUNT(*) FROM $table p FORCE INDEX(PRIMARY) LEFT JOIN $restoreTable b FORCE INDEX(PRIMARY) ON p.event_id=b.event_id WHERE b.event_id IS NULL OR NOT ($equal); SELECT COUNT(*) FROM $restoreTable b FORCE INDEX(PRIMARY) LEFT JOIN $table p FORCE INDEX(PRIMARY) ON p.event_id=b.event_id WHERE p.event_id IS NULL;")
            if ($difference.Count -ne 2 -or @($difference | Where-Object { $_ -ne '0' }).Count -ne 0) { throw 'All-field recovery comparison failed; no further maintenance or cleanup.' }
        }
        Assert-OriginalFacts
        if ($Action -eq 'Rebuild') {
            $before=Invoke-RebuildSql "SHOW CREATE TABLE $table"
            if (($before -join '') -notmatch 'KEY `idx_learning_outbox_pending` \(`status`,`next_attempt_at`,`occurred_at`\)') {
                throw 'Index definition differs; no rebuild.'
            }
            $indexIdsBefore=@(Invoke-RebuildSql "SELECT i.NAME,i.INDEX_ID FROM information_schema.INNODB_TABLES t JOIN information_schema.INNODB_INDEXES i ON i.TABLE_ID=t.TABLE_ID WHERE t.NAME='$schema/$table' ORDER BY i.NAME")
            # 只重建原索引；INPLACE禁止回退全表COPY，EXCLUSIVE在短暂DDL期间防止相关并发写入。
            # 本实例同一ALTER中的同定义DROP/ADD被忽略，必须分两条才能更换物理索引；窗口须持续保持暂停。
            Invoke-RebuildSql "SET SESSION lock_wait_timeout=10; ALTER TABLE $table DROP INDEX $index, ALGORITHM=INPLACE, LOCK=EXCLUSIVE; ALTER TABLE $table ADD INDEX $index(status,next_attempt_at,occurred_at), ALGORITHM=INPLACE, LOCK=EXCLUSIVE;" | Out-Null
            $indexIdsAfter=@(Invoke-RebuildSql "SELECT i.NAME,i.INDEX_ID FROM information_schema.INNODB_TABLES t JOIN information_schema.INNODB_INDEXES i ON i.TABLE_ID=t.TABLE_ID WHERE t.NAME='$schema/$table' ORDER BY i.NAME")
            if ($indexIdsBefore.Count -ne 2 -or $indexIdsAfter.Count -ne 2 -or
                $indexIdsBefore[1] -cne $indexIdsAfter[1] -or $indexIdsBefore[0] -ceq $indexIdsAfter[0]) {
                throw 'Physical secondary index did not change or PRIMARY changed; stop.'
            }
            $after=Invoke-RebuildSql "SHOW CREATE TABLE $table"
            if (($before -join '') -cne ($after -join '')) { throw 'Source DDL changed unexpectedly; stop.' }
            Assert-OriginalFacts
        }
        $check=@(Invoke-RebuildSql "SET SESSION innodb_parallel_read_threads=1; CHECK TABLE $table")
        $check | Set-Content (Join-Path $VerifiedBackupDirectory 'repaired-check.txt')
        if ($check.Count -ne 1 -or $check[0] -notmatch '\tstatus\tOK$') { throw 'Source integrity check did not pass; stop.' }
        $difference=@(Invoke-RebuildSql "SELECT COUNT(*) FROM $table s FORCE INDEX($index) LEFT JOIN $table p FORCE INDEX(PRIMARY) ON p.event_id=s.event_id WHERE p.event_id IS NULL OR NOT (BINARY s.status <=> BINARY p.status) OR NOT (s.next_attempt_at <=> p.next_attempt_at) OR NOT (s.occurred_at <=> p.occurred_at); SELECT (SELECT COUNT(*) FROM $table FORCE INDEX(PRIMARY))-(SELECT COUNT(*) FROM $table FORCE INDEX($index));")
        if ($difference.Count -ne 2 -or @($difference | Where-Object { $_ -ne '0' }).Count -ne 0) { throw 'Primary/secondary comparison failed; stop.' }
        Invoke-RebuildSql "SELECT 'primary',status,COUNT(*) FROM $table FORCE INDEX(PRIMARY) GROUP BY status; SELECT 'secondary',status,COUNT(*) FROM $table FORCE INDEX($index) GROUP BY status; EXPLAIN SELECT * FROM $table WHERE status='PENDING' AND next_attempt_at<=NOW(6) ORDER BY occurred_at,event_id LIMIT 50;" |
            Tee-Object -FilePath (Join-Path $VerifiedBackupDirectory 'repaired-status.txt')
        if ($Action -eq 'VerifyAndCleanup') {
            Invoke-RebuildSql "DROP TABLE $restoreTable" | Out-Null
            Write-Output 'Final original-fact comparison passed; exact isolated restore table removed.'
        }
        Write-Output 'Original DDL, all 15 historical fields, event-ID sets, index keys/counts and CHECK TABLE verified.'
    } finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD',$previousPwd,'Process') }
}
