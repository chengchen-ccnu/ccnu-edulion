$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scriptsRoot = Split-Path -Parent $here

function Get-TestSha256([string]$Value) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-', '').ToLowerInvariant()
    } finally { $algorithm.Dispose() }
}

# 创建三份合成来源和一个已完成 DryRun 检查点；不读取真实历史题库。
function New-ValidatedImportFixture([string]$SourceRoot, [string]$DryRunCheckpointPath) {
    [IO.Directory]::CreateDirectory($SourceRoot) | Out-Null
    $encoding = New-Object Text.UTF8Encoding($false)
    $sources = @(
        [ordered]@{
            name = '01-valid-image.json'
            json = [ordered]@{ exerciseID = 'valid-image'; exerciseType = 'fillin'; gradeIndex = 7; difficulty = 0.5
                question = 'safe stem <img src="https://images.example.test/a.png">'; queAns = '1'; solution = '' }
        },
        [ordered]@{
            name = '02-review.json'
            json = [ordered]@{ exerciseID = 'review-item'; exerciseType = 'fillin'; gradeIndex = 7; difficulty = 0.5
                question = 'review-only-secret'; queAns = ''; solution = '' }
        },
        [ordered]@{
            name = '03-valid-text.json'
            json = [ordered]@{ exerciseID = 'valid-text'; exerciseType = 'fillin'; gradeIndex = 7; difficulty = 0.5
                question = 'second safe stem'; queAns = '2'; solution = '' }
        }
    )
    foreach ($source in $sources) {
        [IO.File]::WriteAllText((Join-Path $SourceRoot $source.name),
            ($source.json | ConvertTo-Json -Depth 10 -Compress), $encoding)
    }

    $paths = @(Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Filter '*.json' |
        Select-Object -ExpandProperty FullName | Sort-Object)
    $resolvedRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
    $rootLabel = $resolvedRoot.Replace('\', '/').TrimEnd('/').ToLowerInvariant()
    $manifestLines = foreach ($path in $paths) {
        $file = Get-Item -LiteralPath $path
        $relative = $path.Substring($resolvedRoot.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
        "$relative|$($file.Length)|$($file.LastWriteTimeUtc.Ticks)"
    }
    $taskId = '11111111-1111-1111-1111-111111111111'
    $checkpoint = [ordered]@{
        version = 1
        runFingerprint = 'synthetic-dry-run-fingerprint'
        action = 'DryRun'
        sourceSystem = 'LEGACY_RAWSTON_V3'
        sourceRootFingerprint = Get-TestSha256 $rootLabel
        sourceManifestFingerprint = Get-TestSha256 ($manifestLines -join "`n")
        totalSourceFiles = 3
        totalItems = 3
        activeTask = $null
        taskSummaries = @([ordered]@{ importTaskId = $taskId })
        totals = [ordered]@{ processed = 3; imported = 0; valid = 2; review = 1; skipped = 0; failed = 0 }
        completed = $true
    }
    [IO.File]::WriteAllText($DryRunCheckpointPath, ($checkpoint | ConvertTo-Json -Depth 10), $encoding)
    return [pscustomobject]@{ taskId = $taskId }
}

# 依次模拟任务详情、VALID 分页和可选的正式任务/批次响应，并把合成请求体写入 TestDrive 供断言。
function Start-ValidatedImportServer([string]$Mode, [string]$TaskId, [string]$CapturePath, [string]$Token) {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $server = Start-ThreadJob -ArgumentList $listener, $Mode, $TaskId, $CapturePath, $Token -ScriptBlock {
        param($Listener, $Mode, $TaskId, $CapturePath, $Token)
        $ErrorActionPreference = 'Stop'
        $captures = New-Object System.Collections.Generic.List[object]

        function Receive-TestRequest($Client) {
            $stream = $Client.GetStream()
            $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $false, 4096, $true)
            $requestLine = $reader.ReadLine()
            $contentLength = 0
            while ($true) {
                $line = $reader.ReadLine()
                if ($line -eq '') { break }
                if ($line -match '^Content-Length:\s*(\d+)$') { $contentLength = [int]$Matches[1] }
            }
            $body = ''
            if ($contentLength -gt 0) {
                $buffer = New-Object char[] $contentLength
                $read = 0
                while ($read -lt $contentLength) {
                    $count = $reader.Read($buffer, $read, $contentLength - $read)
                    if ($count -le 0) { break }
                    $read += $count
                }
                $body = -join $buffer[0..($read - 1)]
            }
            $reader.Dispose()
            return [pscustomobject]@{ stream = $stream; requestLine = $requestLine; body = $body }
        }

        function Send-TestResponse($Client, $Request, [string]$StatusLine, [string]$Body) {
            $bodyBytes = [Text.Encoding]::UTF8.GetBytes($Body)
            $headers = "HTTP/1.1 $StatusLine`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
            $headerBytes = [Text.Encoding]::ASCII.GetBytes($headers)
            $Request.stream.Write($headerBytes, 0, $headerBytes.Length)
            $Request.stream.Write($bodyBytes, 0, $bodyBytes.Length)
            $Request.stream.Flush()
            $Client.Close()
        }

        $requestCount = if ($Mode -eq 'Import') { 4 } elseif ($Mode -eq 'Unauthorized') { 1 } else { 2 }
        for ($index = 0; $index -lt $requestCount; $index++) {
            $client = $Listener.AcceptTcpClient()
            $request = Receive-TestRequest $client
            $captures.Add([ordered]@{ requestLine = $request.requestLine; body = $request.body })
            if ($Mode -eq 'Unauthorized') {
                $failure = [ordered]@{
                    code = 'IAM.UNAUTHORIZED'; message = "Bearer $Token $TaskId private-source-path"
                    traceId = 'validated-401-trace'; fieldErrors = @()
                } | ConvertTo-Json -Compress
                Send-TestResponse $client $request '401 Unauthorized' $failure
                continue
            }
            if ($index -eq 0) {
                $body = [ordered]@{
                    id = $TaskId; sourceFormat = 'LEGACY_JSON'; sourceSystem = 'LEGACY_RAWSTON_V3'
                    dryRun = $true; status = 'COMPLETED'; processedRows = 3
                } | ConvertTo-Json -Compress
                Send-TestResponse $client $request '200 OK' $body
            } elseif ($index -eq 1) {
                $body = [ordered]@{
                    page = 1; size = 100; total = 2
                    items = @(
                        [ordered]@{ externalId = 'valid-image'; sourceSystem = 'LEGACY_RAWSTON_V3'; outcome = 'VALID'; issues = @() },
                        [ordered]@{ externalId = 'valid-text'; sourceSystem = 'LEGACY_RAWSTON_V3'; outcome = 'VALID'; issues = @() }
                    )
                } | ConvertTo-Json -Depth 6 -Compress
                Send-TestResponse $client $request '200 OK' $body
            } elseif ($index -eq 2) {
                Send-TestResponse $client $request '201 Created' '{"id":"22222222-2222-2222-2222-222222222222"}'
            } else {
                $body = [ordered]@{
                    processedCount = 2; importedCount = 2; validCount = 0
                    reviewCount = 0; skippedCount = 0; failedCount = 0
                } | ConvertTo-Json -Compress
                Send-TestResponse $client $request '200 OK' $body
            }
        }
        [IO.File]::WriteAllText($CapturePath, ($captures | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    }
    return [pscustomobject]@{ listener = $listener; job = $server; port = $port }
}

Describe 'Invoke-LegacyQuestionJsonMigration VALID-only Import' {
    It '计划模式只输出安全聚合且不创建正式检查点' {
        $sourceRoot = Join-Path $TestDrive 'plan-source'
        $dryRunCheckpoint = Join-Path $TestDrive 'plan-dryrun.json'
        $importCheckpoint = Join-Path $TestDrive 'plan-import.json'
        $capturePath = Join-Path $TestDrive 'plan-capture.json'
        $fixtureData = New-ValidatedImportFixture $sourceRoot $dryRunCheckpoint
        $token = 'plan-secret-token'
        $fixture = Start-ValidatedImportServer 'Plan' $fixtureData.taskId $capturePath $token
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $output = & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action Import -AllItems -PlanOnly `
                -SourceRoot $sourceRoot -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" `
                -ValidatedDryRunCheckpointPath $dryRunCheckpoint -CheckpointPath $importCheckpoint
            $result = $output | ConvertFrom-Json

            $result.selectionMode | Should Be 'DRY_RUN_VALID_ONLY'
            $result.dryRunTaskCount | Should Be 1
            $result.totalSourceFiles | Should Be 3
            $result.selectedValidCount | Should Be 2
            $result.selectedImageCount | Should Be 1
            $result.imageHostCounts.'images.example.test' | Should Be 1
            Test-Path -LiteralPath $importCheckpoint | Should Be $false
            $output | Should Not Match 'plan-secret-token|review-only-secret|valid-image|valid-text|11111111|plan-source|\.json'
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Wait-Job -Job $fixture.job -Timeout 5 | Out-Null
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '正式模式只提交 VALID 项并保留图片 URL 给 media-service 转存' {
        $sourceRoot = Join-Path $TestDrive 'import-source'
        $dryRunCheckpoint = Join-Path $TestDrive 'import-dryrun.json'
        $importCheckpoint = Join-Path $TestDrive 'import-checkpoint.json'
        $capturePath = Join-Path $TestDrive 'import-capture.json'
        $fixtureData = New-ValidatedImportFixture $sourceRoot $dryRunCheckpoint
        $token = 'import-secret-token'
        $fixture = Start-ValidatedImportServer 'Import' $fixtureData.taskId $capturePath $token
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $output = & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action Import -AllItems `
                -ConfirmFullMigration -SourceRoot $sourceRoot -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" `
                -ValidatedDryRunCheckpointPath $dryRunCheckpoint -AllowedImageHost 'images.example.test' `
                -TaskSize 10 -BatchSize 10 -CheckpointPath $importCheckpoint
            Wait-Job -Job $fixture.job -Timeout 5 | Out-Null
            $result = $output | ConvertFrom-Json
            $requests = [IO.File]::ReadAllText($capturePath, [Text.Encoding]::UTF8) | ConvertFrom-Json
            $batchBody = [string]$requests[3].body

            $result.totalSourceFiles | Should Be 3
            $result.totalItems | Should Be 2
            $result.importedCount | Should Be 2
            $result.selectedImageCount | Should Be 1
            (@($result.PSObject.Properties.Name) -contains 'importTaskIds') | Should Be $false
            $batchBody | Should Match 'valid-image|valid-text'
            $batchBody | Should Match ([Regex]::Escape('https://images.example.test/a.png'))
            $batchBody | Should Not Match 'review-item|review-only-secret'
            $output | Should Not Match 'import-secret-token|valid-image|valid-text|review-item|22222222|import-source|\.json'
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Stop-Job -Job $fixture.job -ErrorAction SilentlyContinue
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '图片域名未明确批准时在任何正式写请求前停止' {
        $sourceRoot = Join-Path $TestDrive 'host-gate-source'
        $dryRunCheckpoint = Join-Path $TestDrive 'host-gate-dryrun.json'
        $importCheckpoint = Join-Path $TestDrive 'host-gate-import.json'
        $capturePath = Join-Path $TestDrive 'host-gate-capture.json'
        $fixtureData = New-ValidatedImportFixture $sourceRoot $dryRunCheckpoint
        $token = 'host-gate-token'
        $fixture = Start-ValidatedImportServer 'Plan' $fixtureData.taskId $capturePath $token
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $caught = $null
            try {
                & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action Import -AllItems `
                    -ConfirmFullMigration -SourceRoot $sourceRoot -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" `
                    -ValidatedDryRunCheckpointPath $dryRunCheckpoint -CheckpointPath $importCheckpoint
            } catch { $caught = $_.Exception.Message }
            $caught | Should Match 'AllowedImageHost'
            Test-Path -LiteralPath $importCheckpoint | Should Be $false
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Wait-Job -Job $fixture.job -Timeout 5 | Out-Null
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '401 时使用安全诊断停止且不泄露令牌、路径或任务 ID' {
        $sourceRoot = Join-Path $TestDrive 'unauthorized-source'
        $dryRunCheckpoint = Join-Path $TestDrive 'unauthorized-dryrun.json'
        $capturePath = Join-Path $TestDrive 'unauthorized-capture.json'
        $fixtureData = New-ValidatedImportFixture $sourceRoot $dryRunCheckpoint
        $token = 'unauthorized-secret-token'
        $fixture = Start-ValidatedImportServer 'Unauthorized' $fixtureData.taskId $capturePath $token
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $caught = $null
            try {
                & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action Import -AllItems -PlanOnly `
                    -SourceRoot $sourceRoot -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" `
                    -ValidatedDryRunCheckpointPath $dryRunCheckpoint
            } catch { $caught = $_.Exception.Message }
            $diagnostic = $caught | ConvertFrom-Json

            $diagnostic.httpStatus | Should Be 401
            $diagnostic.code | Should Be 'IAM.UNAUTHORIZED'
            $diagnostic.traceId | Should Be 'validated-401-trace'
            $caught | Should Not Match 'unauthorized-secret-token|unauthorized-source|11111111|private-source-path|message'
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Wait-Job -Job $fixture.job -Timeout 5 | Out-Null
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }
}
