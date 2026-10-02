#requires -Version 7.0
[CmdletBinding()]
param([switch]$MaintenanceConfirmed)

<#
在确认的维护窗口验证 Learning 开发 Outbox 的当前可恢复性。逻辑备份仅恢复到本轮随机隔离表，
逐列比较全部事件后精确清理；源表不修改状态、不导入、不重建索引。
成功后短暂持有本表 FOR EXPORT 锁，复制 ibd/cfg 到当前用户受保护目录，仅对离线副本运行
innochecksum。该验证不能证明历史事件从未丢失，不能代替物理根因调查或授权业务恢复。
凭据由 DPAPI 包装器注入，备份/错误输出均留在仓库外；失败保留材料和隔离表供调查。
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $MaintenanceConfirmed) { throw 'A confirmed quiet Learning maintenance window is required.' }
$schema = 'edu_platform_learning_dev'
$table = 'learning_outbox_event'
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if ($env:LEARNING_MYSQL_DATABASE -ne $schema) { throw 'Only the Learning development schema is permitted.' }
    if ($env:MYSQL_HOST -notin @('127.0.0.1', 'localhost', '::1')) { throw 'This recovery investigation requires a local MySQL server.' }
    if (Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'learning-service' }) {
        throw 'Existing Learning JVM found; never stop user services automatically.'
    }
    $previousPwd = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $connection = @("--host=$env:MYSQL_HOST", "--port=$env:MYSQL_PORT", "--user=$env:MYSQL_USERNAME",
        "--database=$schema", '--batch', '--skip-column-names')
    $backupDirectory = Join-Path ([Environment]::GetFolderPath('UserProfile')) ('.ccnu-edulion/backups/t16-4-0/' + [Guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Path $backupDirectory | Out-Null
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls $backupDirectory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Backup directory protection failed.' }
        $errorPath = Join-Path $backupDirectory 'mysql-error.txt'
        # 禁止错误中的原始键/SQL流向终端；调查材料也受备份目录 ACL 保护。
        function Invoke-RecoverySql([string]$Sql) {
            $result = & mysql @connection "--execute=$Sql" 2>> $errorPath
            if ($LASTEXITCODE -ne 0) { throw 'Recovery SQL failed; protected materials retained, source not overwritten.' }
            return $result
        }
        $active = Invoke-RecoverySql "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='$schema' AND ID<>CONNECTION_ID()"
        if ([int]$active -ne 0) { throw 'Other Learning connections exist; stop before backup.' }
        $ddl = Invoke-RecoverySql "SHOW CREATE TABLE $table"
        if (($ddl -join '') -match "ENCRYPTION='Y'") { throw 'Encrypted tablespace export is outside this script scope.' }
        $ddl | Set-Content (Join-Path $backupDirectory 'original-ddl.txt')
        $dumpPath = Join-Path $backupDirectory 'learning-outbox.sql'
        & mysqldump "--host=$env:MYSQL_HOST" "--port=$env:MYSQL_PORT" "--user=$env:MYSQL_USERNAME" `
            --single-transaction --skip-lock-tables --skip-add-locks --skip-add-drop-table --skip-disable-keys `
            --no-tablespaces --set-gtid-purged=OFF --skip-comments --complete-insert --hex-blob --order-by-primary `
            "--result-file=$dumpPath" $schema $table 2>> $errorPath
        if ($LASTEXITCODE -ne 0 -or (Get-Item $dumpPath).Length -eq 0) { throw 'Logical backup failed; no restore attempted.' }
        $restoreTable = 't1640_outbox_restore_' + [Guid]::NewGuid().ToString('N')
        [IO.File]::WriteAllText((Join-Path $backupDirectory 'restore-table.txt'), $restoreTable)
        $dump = [IO.File]::ReadAllText($dumpPath)
        # 仅替换固定 CREATE/INSERT 指令的表标识，不替换字符串中的正文。
        $restore = [regex]::Replace($dump, '(?m)^(CREATE TABLE |INSERT INTO )`learning_outbox_event`', ('$1`' + $restoreTable + '`'))
        if ($restore -match '(?m)^(CREATE TABLE |INSERT INTO |DROP TABLE |ALTER TABLE |LOCK TABLES )`learning_outbox_event`') {
            throw 'Restore would target the source table; stop.'
        }
        $restorePath = Join-Path $backupDirectory 'restore-isolated.sql'
        [IO.File]::WriteAllText($restorePath, $restore, [Text.UTF8Encoding]::new($false))
        Invoke-RecoverySql ('SOURCE ' + $restorePath.Replace('\', '/')) | Out-Null
        $columns = @('event_id','event_type','event_version','aggregate_id','exchange_name','routing_key','trace_id',
            'payload_json','status','attempts','next_attempt_at','last_error','occurred_at','published_at','updated_at')
        $equal = ($columns | ForEach-Object { "(BINARY p.$_ <=> BINARY b.$_)" }) -join ' AND '
        # 按 PRIMARY 双向核对 ID 集合和全部15列；正文只在 SQL 内比较，输出仅为差异数量。
        function Assert-RestoreMatches {
            $difference = @(Invoke-RecoverySql "SELECT COUNT(*) FROM $table p FORCE INDEX(PRIMARY) LEFT JOIN $restoreTable b FORCE INDEX(PRIMARY) ON p.event_id=b.event_id WHERE b.event_id IS NULL OR NOT ($equal); SELECT COUNT(*) FROM $restoreTable b FORCE INDEX(PRIMARY) LEFT JOIN $table p FORCE INDEX(PRIMARY) ON p.event_id=b.event_id WHERE p.event_id IS NULL;")
            if ($difference.Count -ne 2 -or @($difference | Where-Object { $_ -ne '0' }).Count -ne 0) {
                throw 'Full restore comparison failed; retain isolated table and stop.'
            }
        }
        Assert-RestoreMatches
        $check = @(Invoke-RecoverySql "CHECK TABLE $restoreTable")
        $check | Set-Content (Join-Path $backupDirectory 'restored-check.txt')
        if ($check.Count -ne 1 -or $check[0] -notmatch '\tstatus\tOK$') { throw 'Restored table failed integrity check; stop.' }
        Write-Output 'Logical restore: all 15 columns and both event-ID sets match; isolated CHECK TABLE OK.'

        $metadata = @(& mysql @connection --raw --execute='SELECT @@datadir,@@innodb_page_size' 2>> $errorPath)
        if ($LASTEXITCODE -ne 0) { throw 'Local export metadata unavailable.' }
        $dataDirectory = $metadata[0].Split("`t")[0]
        $sourceIbd = Join-Path $dataDirectory "$schema/$table.ibd"
        $sourceCfg = Join-Path $dataDirectory "$schema/$table.cfg"
        $copyIbd = Join-Path $backupDirectory 'learning-outbox.ibd'
        # 独立 mysql 会话持有本表导出锁；管道仅接收锁确认，不运行全局 FLUSH 或停服务器。
        $start = [Diagnostics.ProcessStartInfo]::new((Get-Command mysql -ErrorAction Stop).Source)
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardInput = $true
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        foreach ($argument in ($connection + @('--unbuffered'))) { $start.ArgumentList.Add($argument) }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $start
        $exportLocked = $false
        $started = $false
        $errors = $null
        try {
            $process.Start() | Out-Null
            $started = $true
            $errors = $process.StandardError.ReadToEndAsync()
            $process.StandardInput.WriteLine("SET SESSION lock_wait_timeout=10; FLUSH TABLES $table FOR EXPORT; SELECT 'T1640_EXPORT_LOCKED';")
            $process.StandardInput.Flush()
            $ready = $process.StandardOutput.ReadLineAsync()
            if (-not $ready.Wait(15000) -or $ready.Result -ne 'T1640_EXPORT_LOCKED') { throw 'Could not acquire the bounded table export lock.' }
            $exportLocked = $true
            if (-not (Test-Path $sourceCfg)) { throw 'Export metadata missing; no unprotected physical copy attempted.' }
            # 使用共享读文件句柄复制服务器拥有的源文件；只写本轮受保护备份目录。
            $inputStream = [IO.File]::Open($sourceIbd, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
            try {
                $outputStream = [IO.File]::Open($copyIbd, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
                try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
            } finally { $inputStream.Dispose() }
            Copy-Item -LiteralPath $sourceCfg -Destination (Join-Path $backupDirectory 'learning-outbox.cfg')
        } finally {
            if ($started -and -not $process.HasExited) {
                if ($exportLocked) { $process.StandardInput.WriteLine('UNLOCK TABLES;') }
                $process.StandardInput.Close()
                if (-not $process.WaitForExit(10000)) { $process.Kill(); $process.WaitForExit() }
            }
            if ($null -ne $errors) { [IO.File]::WriteAllText((Join-Path $backupDirectory 'export-error.txt'), $errors.GetAwaiter().GetResult()) }
            $exportExit = if ($started) { $process.ExitCode } else { -1 }
            $process.Dispose()
        }
        if ($exportExit -ne 0) { throw 'Export session failed; retain materials and stop.' }
        & innochecksum $copyIbd *> (Join-Path $backupDirectory 'offline-checksum.txt')
        if ($LASTEXITCODE -ne 0) { throw 'Offline copy checksum failed; no source repair allowed.' }
        Assert-RestoreMatches
        Invoke-RecoverySql "SELECT 'primary',status,COUNT(*) FROM $table FORCE INDEX(PRIMARY) GROUP BY status; SELECT 'secondary',status,COUNT(*) FROM $table FORCE INDEX(idx_learning_outbox_pending) GROUP BY status;" |
            Tee-Object -FilePath (Join-Path $backupDirectory 'source-status.txt')
        Invoke-RecoverySql "DROP TABLE $restoreTable" | Out-Null
        [IO.File]::WriteAllText((Join-Path $backupDirectory 'verified.txt'), 'Logical restore byte comparisons and isolated CHECK TABLE passed. Export copy innochecksum passed. Isolated restore table removed. Source index not repaired.')
        Write-Output "Verified current logical backup and offline page checksums: $backupDirectory"
        Write-Output 'Isolated restore table removed. Source business facts preserved; historical completeness and physical cause remain unproven.'
    } finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPwd, 'Process')
    }
}
