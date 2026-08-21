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

# 创建包含一个非法图片 URL 和一个未批准主机的 VALID 合成快照，验证整题排除只由显式开关触发。
function New-ImageExclusionFixture([string]$SourceRoot, [string]$DryRunCheckpointPath) {
    [IO.Directory]::CreateDirectory($SourceRoot) | Out-Null
    $encoding = New-Object Text.UTF8Encoding($false)
    $sources = @(
        [ordered]@{ name='01-approved.json'; json=[ordered]@{ exerciseID='approved-image'; question='safe <img src="https://images.example.test/a.png">' } },
        [ordered]@{ name='02-invalid.json'; json=[ordered]@{ exerciseID='invalid-image'; question='safe <img src="https://">' } },
        [ordered]@{ name='03-unapproved.json'; json=[ordered]@{ exerciseID='unapproved-image'; question='safe <img src="https://other.example.test/a.png">' } },
        [ordered]@{ name='04-text.json'; json=[ordered]@{ exerciseID='valid-text'; question='safe text' } }
    )
    foreach ($source in $sources) {
        [IO.File]::WriteAllText((Join-Path $SourceRoot $source.name),
            ($source.json | ConvertTo-Json -Compress), $encoding)
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
    $taskId = '33333333-3333-3333-3333-333333333333'
    $checkpoint = [ordered]@{
        version=1;runFingerprint='synthetic-image-exclusion';action='DryRun';sourceSystem='LEGACY_RAWSTON_V3'
        sourceRootFingerprint=Get-TestSha256 $rootLabel
        sourceManifestFingerprint=Get-TestSha256 ($manifestLines -join "`n")
        totalSourceFiles=4;totalItems=4;activeTask=$null
        taskSummaries=@([ordered]@{importTaskId=$taskId})
        totals=[ordered]@{processed=4;imported=0;valid=4;review=0;skipped=0;failed=0};completed=$true
    }
    [IO.File]::WriteAllText($DryRunCheckpointPath, ($checkpoint | ConvertTo-Json -Depth 10), $encoding)
    return [pscustomobject]@{ taskId=$taskId }
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
                $itemCount = if ($Mode -eq 'ExclusionPlan') { 4 } else { 3 }
                $body = [ordered]@{
                    id = $TaskId; sourceFormat = 'LEGACY_JSON'; sourceSystem = 'LEGACY_RAWSTON_V3'; questionScope = 'SYSTEM'
                    dryRun = $true; status = 'COMPLETED'; processedRows = $itemCount
                } | ConvertTo-Json -Compress
                Send-TestResponse $client $request '200 OK' $body
            } elseif ($index -eq 1) {
                $validItems = if ($Mode -eq 'ExclusionPlan') {
                    @('approved-image','invalid-image','unapproved-image','valid-text') | ForEach-Object {
                        [ordered]@{ externalId=$_;sourceSystem='LEGACY_RAWSTON_V3';outcome='VALID';issues=@() }
                    }
                } else {
                    @(
                        [ordered]@{ externalId = 'valid-image'; sourceSystem = 'LEGACY_RAWSTON_V3'; outcome = 'VALID'; issues = @() },
                        [ordered]@{ externalId = 'valid-text'; sourceSystem = 'LEGACY_RAWSTON_V3'; outcome = 'VALID'; issues = @() }
                    )
                }
                $body = [ordered]@{
                    page = 1; size = 100; total = @($validItems).Count
                    items = @($validItems)
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
    It '均衡样本选择确定覆盖现有分类并优先增加知识点' {
        $tokens = $null; $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile(
            (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1'), [ref]$tokens, [ref]$parseErrors)
        @($parseErrors).Count | Should Be 0
        $definition = $ast.FindAll({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -eq 'Select-BalancedLegacySample'
        }, $true) | Select-Object -First 1
        . ([scriptblock]::Create($definition.Extent.Text))

        $candidates = for ($index = 0; $index -lt 650; $index++) {
            [pscustomobject]@{
                sourceIndex = $index
                questionType = if ($index -lt 7) { 'MULTIPLE_CHOICE' } else {
                    @('SINGLE_CHOICE','FILL_BLANK','SHORT_ANSWER','COMPOSITE')[$index % 4]
                }
                grade = @('7','8','9')[$index % 3]
                difficulty = @('1','5','9')[$index % 3]
                imageHosts = if ($index % 2 -eq 0) { @('images.example.test') } else { @() }
                knowledgePointIds = @("knowledge-$index")
            }
        }
        $first = Select-BalancedLegacySample -Candidates ([object[]]$candidates) -SampleSize 520
        $second = Select-BalancedLegacySample -Candidates ([object[]]$candidates) -SampleSize 520

        @($first.candidates).Count | Should Be 520
        $first.categoryQuota | Should Be 101
        $first.questionTypeCounts.MULTIPLE_CHOICE | Should Be 7
        $first.questionTypeTargets.MULTIPLE_CHOICE | Should Be 7
        $first.questionTypeCounts.SINGLE_CHOICE | Should BeGreaterThan 100
        $first.questionTypeCounts.FILL_BLANK | Should BeGreaterThan 100
        $first.questionTypeCounts.SHORT_ANSWER | Should BeGreaterThan 100
        $first.questionTypeCounts.COMPOSITE | Should BeGreaterThan 100
        $first.questionTypeAvailableCounts.TRUE_FALSE | Should Be 0
        $first.gradeCounts.'7' | Should BeGreaterThan 100
        $first.gradeCounts.'8' | Should BeGreaterThan 100
        $first.gradeCounts.'9' | Should BeGreaterThan 100
        $first.gradeAvailableCounts.'1' | Should Be 0
        $first.difficultyCounts.'1' | Should BeGreaterThan 100
        $first.difficultyCounts.'5' | Should BeGreaterThan 100
        $first.difficultyCounts.'9' | Should BeGreaterThan 100
        $first.imageQuestionTarget | Should Be 260
        $first.imageQuestionCount | Should BeGreaterThan 259
        $first.distinctKnowledgePointCount | Should Be 520
        (@($first.candidates.sourceIndex) -join ',') | Should Be (@($second.candidates.sourceIndex) -join ',')
    }

    It '含图候选不足一半时拒绝生成样本' {
        $tokens = $null; $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile(
            (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1'), [ref]$tokens, [ref]$parseErrors)
        $definition = $ast.FindAll({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -eq 'Select-BalancedLegacySample'
        }, $true) | Select-Object -First 1
        . ([scriptblock]::Create($definition.Extent.Text))
        $candidates = for ($index = 0; $index -lt 150; $index++) {
            [pscustomobject]@{
                sourceIndex = $index; questionType = 'SINGLE_CHOICE'; grade = '7'; difficulty = '1'
                imageHosts = if ($index -lt 59) { @('images.example.test') } else { @() }
                knowledgePointIds = @("knowledge-$index")
            }
        }

        $caught = $null
        try { Select-BalancedLegacySample -Candidates ([object[]]$candidates) -SampleSize 120 } catch { $caught = $_.Exception.Message }
        $caught | Should Match '50 percent image ratio'
    }

    It '正式均衡样本缺少显式确认时在读取来源前停止' {
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'sample-confirm-token', 'Process')
        try {
            $caught = $null
            try {
                & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action Import `
                    -BalancedSampleSize 120 -SourceRoot (Join-Path $TestDrive 'missing-source') `
                    -ValidatedDryRunCheckpointPath (Join-Path $TestDrive 'missing-dryrun.json')
            } catch { $caught = $_.Exception.Message }
            $caught | Should Match 'ConfirmSampleImport'
            $caught | Should Not Match 'sample-confirm-token|missing-source|missing-dryrun'
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
        }
    }

    It '显式策略整题排除非法 URL 与未批准图片主机并保留精确计数' {
        $sourceRoot = Join-Path $TestDrive 'exclusion-source'
        $dryRunCheckpoint = Join-Path $TestDrive 'exclusion-dryrun.json'
        $capturePath = Join-Path $TestDrive 'exclusion-capture.json'
        $fixtureData = New-ImageExclusionFixture $sourceRoot $dryRunCheckpoint
        $token = 'exclusion-secret-token'
        $fixture = Start-ValidatedImportServer 'ExclusionPlan' $fixtureData.taskId $capturePath $token
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $output = & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action Import -AllItems -PlanOnly `
                -SourceRoot $sourceRoot -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" `
                -ValidatedDryRunCheckpointPath $dryRunCheckpoint -AllowedImageHost 'images.example.test' `
                -ExcludeInvalidImageItems -ExcludeUnapprovedImageHostItems
            $result = $output | ConvertFrom-Json

            $result.selectedValidCount | Should Be 2
            $result.selectedImageCount | Should Be 1
            $result.excludedInvalidImageItemCount | Should Be 1
            $result.excludedUnapprovedImageHostItemCount | Should Be 1
            $result.imageHostCounts.'images.example.test' | Should Be 1
            $output | Should Not Match 'exclusion-secret-token|approved-image|invalid-image|unapproved-image|valid-text|33333333|exclusion-source'
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Wait-Job -Job $fixture.job -Timeout 5 | Out-Null
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

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
