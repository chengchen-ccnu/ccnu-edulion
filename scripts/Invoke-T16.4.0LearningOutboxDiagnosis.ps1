#requires -Version 7.0
[CmdletBinding()]
param([switch]$InspectLocalPageHeaders)

<#
只读诊断 edu_platform_learning_dev.learning_outbox_event；凭据通过 DPAPI 包装器加载。
输出索引结构、同一快照的数量差异和安全日志分类，不输出事件正文或身份。
InspectLocalPageHeaders 仅在服务器为本机、页大小可确认时读取 ibd 前 74 字节，
将历史 LSN 异常页归属到当前索引；不修改文件，不运行 CHECK TABLE、恢复或重建。
历史 PRIMARY 页异常表示不能证明仅二级索引损坏，必须停止修复并由恢复调查继续处理。
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if ($env:LEARNING_MYSQL_DATABASE -ne 'edu_platform_learning_dev') { throw 'Only the dedicated Learning development schema is permitted.' }
    $previousPwd = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $connection = @("--host=$env:MYSQL_HOST", "--port=$env:MYSQL_PORT", "--user=$env:MYSQL_USERNAME",
        '--database=edu_platform_learning_dev', '--batch', '--skip-column-names')
    try {
        # 调用者只传固定只读 SQL；任何数据库错误停止诊断，不尝试修复或切换 Schema。
        function Invoke-DiagnosticSql([string]$Sql) {
            $result = & mysql @connection "--execute=$Sql"
            if ($LASTEXITCODE -ne 0) { throw 'Read-only diagnostic failed; no recovery attempted.' }
            return $result
        }
        Invoke-DiagnosticSql @'
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT 'server',DATABASE(),VERSION(),@@innodb_force_recovery;
SELECT 'other_learning_connections',COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='edu_platform_learning_dev' AND ID<>CONNECTION_ID();
SELECT 'primary',status,COUNT(*) FROM learning_outbox_event FORCE INDEX(PRIMARY) GROUP BY status;
SELECT 'secondary',status,COUNT(*) FROM learning_outbox_event FORCE INDEX(idx_learning_outbox_pending) GROUP BY status;
SELECT 'index_disagreements',COUNT(*) FROM learning_outbox_event s FORCE INDEX(idx_learning_outbox_pending)
LEFT JOIN learning_outbox_event p FORCE INDEX(PRIMARY) ON p.event_id=s.event_id
WHERE p.event_id IS NULL OR NOT (BINARY s.status <=> BINARY p.status)
OR NOT (s.next_attempt_at <=> p.next_attempt_at) OR NOT (s.occurred_at <=> p.occurred_at);
EXPLAIN SELECT COUNT(*) FROM learning_outbox_event FORCE INDEX(PRIMARY) WHERE status='PENDING';
EXPLAIN SELECT COUNT(*) FROM learning_outbox_event FORCE INDEX(idx_learning_outbox_pending) WHERE status='PENDING';
EXPLAIN SELECT * FROM learning_outbox_event WHERE status='PENDING' AND next_attempt_at<=NOW(6) ORDER BY occurred_at,event_id LIMIT 50;
SELECT 'icp_on',status,attempts FROM learning_outbox_event FORCE INDEX(idx_learning_outbox_pending) WHERE status='PENDING' AND next_attempt_at<=NOW(6) ORDER BY occurred_at,event_id LIMIT 50;
SET SESSION optimizer_switch='index_condition_pushdown=off';
SELECT 'icp_off',status,attempts FROM learning_outbox_event FORCE INDEX(idx_learning_outbox_pending) WHERE status='PENDING' AND next_attempt_at<=NOW(6) ORDER BY occurred_at,event_id LIMIT 50;
SELECT 'secondary_pending_without_icp',COUNT(*) FROM learning_outbox_event FORCE INDEX(idx_learning_outbox_pending) WHERE status='PENDING';
COMMIT;
SHOW CREATE TABLE learning_outbox_event;
SELECT t.SPACE,i.NAME,i.INDEX_ID,i.PAGE_NO FROM information_schema.INNODB_TABLES t JOIN information_schema.INNODB_INDEXES i ON i.TABLE_ID=t.TABLE_ID WHERE t.NAME='edu_platform_learning_dev/learning_outbox_event';
SELECT 'learning_engine_errors',ERROR_CODE,MIN(LOGGED),MAX(LOGGED),COUNT(*) FROM performance_schema.error_log WHERE DATA LIKE '%learning_outbox_event%' GROUP BY ERROR_CODE;
SELECT 'historical_page_lsn',LOGGED,REGEXP_SUBSTR(DATA,'Page .*') FROM performance_schema.error_log WHERE ERROR_CODE='MY-011971' AND DATA LIKE '%learning_outbox_event%' ORDER BY LOGGED LIMIT 3;
SELECT 'index_update_missing',LOGGED,DATA LIKE '%idx_learning_outbox_pending%',DATA LIKE '%was not found on update%' FROM performance_schema.error_log WHERE ERROR_CODE='MY-012869' AND DATA LIKE '%learning_outbox_event%';
'@
        if (-not $InspectLocalPageHeaders) { return }
        if ($env:MYSQL_HOST -notin @('127.0.0.1', 'localhost', '::1')) { throw 'Local page inspection requires a confirmed local MySQL server.' }
        # raw 仅用于把 datadir 转为本机路径；目录内容和业务页内容不输出。
        $metadata = @(& mysql @connection --raw --execute='SELECT @@datadir,@@innodb_page_size')
        if ($LASTEXITCODE -ne 0) { throw 'Page-size metadata unavailable.' }
        $parts = $metadata[0].Split("`t")
        $pageSize = [int]$parts[1]
        if ($pageSize -notin @(4096,8192,16384,32768,65536)) { throw 'Unrecognized InnoDB page size.' }
        $tableMetadata = @(Invoke-DiagnosticSql "SELECT SPACE,ROW_FORMAT FROM information_schema.INNODB_TABLES WHERE NAME='edu_platform_learning_dev/learning_outbox_event'")
        $tableParts = $tableMetadata[0].Split("`t")
        if ($tableParts[1] -eq 'Compressed') { throw 'Compressed page inspection is unsupported; no inference attempted.' }
        $space = [long]$tableParts[0]
        $pages = @(Invoke-DiagnosticSql "SELECT DISTINCT REGEXP_SUBSTR(DATA,'page number=[0-9]+') FROM performance_schema.error_log WHERE ERROR_CODE='MY-011971' AND DATA LIKE '%learning_outbox_event%' AND DATA LIKE '%space=$space,%'")
        $path = Join-Path $parts[0] 'edu_platform_learning_dev/learning_outbox_event.ibd'
        $stream = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            $classifications = foreach ($page in $pages) {
                if ($page -notmatch '^page number=[0-9]+$') { throw 'Unrecognized logged page number.' }
                $number = [int]($page -replace 'page number=', '')
                $buffer = [byte[]]::new(74)
                $stream.Seek([long]$number * $pageSize, [IO.SeekOrigin]::Begin) | Out-Null
                if ($stream.Read($buffer, 0, 74) -ne 74) { throw 'Could not read the complete page header.' }
                # FIL_PAGE_SPACE_ID、FIL_PAGE_TYPE 和 PAGE_INDEX_ID 均为网络字节序；不读用户记录。
                $pageSpace = [long]0
                for ($offset = 34; $offset -lt 38; $offset++) { $pageSpace = $pageSpace * 256 + $buffer[$offset] }
                if ($pageSpace -ne $space) { throw 'Page space differs from the current table; stop attribution.' }
                $type = [int]$buffer[24] * 256 + $buffer[25]
                $indexId = [long]0
                if ($type -eq 17855) {
                    for ($offset = 66; $offset -lt 74; $offset++) { $indexId = $indexId * 256 + $buffer[$offset] }
                }
                [pscustomobject]@{ PageType = $type; IndexId = $indexId }
            }
            $classifications | Group-Object PageType,IndexId | Select-Object Name,Count
            Write-Output 'Historical page ownership is diagnostic evidence, not proof of current data completeness or a recoverable backup.'
        } finally { $stream.Dispose() }
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPwd, 'Process')
    }
}
