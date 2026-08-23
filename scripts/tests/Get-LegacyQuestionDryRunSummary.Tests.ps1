$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scriptsRoot = Split-Path -Parent $here
$summaryScript = Join-Path $scriptsRoot 'Get-LegacyQuestionDryRunSummary.ps1'

function Write-SyntheticCheckpoint([string]$Path, [string[]]$TaskIds, [int]$TotalItems) {
    $checkpoint = [ordered]@{
        version = 1
        action = 'DryRun'
        completed = $true
        activeTask = $null
        totalItems = $TotalItems
        taskSummaries = @($TaskIds | ForEach-Object { [ordered]@{ importTaskId = $_ } })
        syntheticSourcePath = 'I:\fixture-bank\never-read.json'
    } | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText($Path, $checkpoint, (New-Object Text.UTF8Encoding($false)))
}

function New-SyntheticReviewItem([string]$Id, [string]$Outcome, [string[]]$IssueCodes,
        [string]$UnsafeText) {
    return [ordered]@{
        id = $Id
        externalId = "external-$Id"
        outcome = $Outcome
        issues = @($IssueCodes | ForEach-Object { [ordered]@{ code = $_; message = $UnsafeText } })
        rawJson = "{`"question`":`"$UnsafeText`"}"
        absolutePath = 'I:\fixture-bank\secret.json'
    }
}

function Start-ResponseSequenceServer([object[]]$Responses) {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $job = Start-ThreadJob -ArgumentList $listener, $Responses -ScriptBlock {
        param($Listener, $Responses)
        $ErrorActionPreference = 'Stop'
        foreach ($response in @($Responses)) {
            $client = $Listener.AcceptTcpClient()
            try {
                $stream = $client.GetStream()
                $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $false, 1024, $true)
                $reader.ReadLine() | Out-Null
                while ($reader.ReadLine() -ne '') {}
                $reader.Dispose()
                $bodyBytes = [Text.Encoding]::UTF8.GetBytes([string]$response.Body)
                $headers = "HTTP/1.1 $($response.StatusLine)`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
                $headerBytes = [Text.Encoding]::ASCII.GetBytes($headers)
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                $stream.Write($bodyBytes, 0, $bodyBytes.Length)
                $stream.Flush()
            } finally {
                $client.Close()
            }
        }
    }
    return [pscustomobject]@{ listener = $listener; job = $job; port = $port }
}

function New-FixtureResponse([string]$StatusLine, [object]$Body) {
    return [pscustomobject]@{
        StatusLine = $StatusLine
        Body = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 12 -Compress }
    }
}

Describe 'Get-LegacyQuestionDryRunSummary safe aggregation' {
    It '脚本仅执行 Gateway GET 且不遍历题库或写入检查点' {
        $scriptText = [IO.File]::ReadAllText($summaryScript, [Text.Encoding]::UTF8)
        $scriptText | Should Match 'Invoke-RestMethod\s+-Method\s+Get'
        $scriptText | Should Not Match 'Invoke-RestMethod\s+-Method\s+(Post|Put|Patch|Delete)'
        $scriptText | Should Not Match 'Get-ChildItem|ReadAllBytes|WriteAllText|Set-Content|Add-Content|Move-Item|Remove-Item'
        $scriptText | Should Match '/api/v1/teacher/question-imports/'
        $scriptText | Should Not Match '/legacy-question-imports/.+/batches|created-resources'
    }

    It '分页汇总多个任务、计数、问题码及组合且不泄漏敏感投影' {
        $token = 'summary-secret-token'
        $unsafe = "Bearer $token synthetic secret stem secret answer I:\fixture-bank\secret.json"
        $task1 = '11111111-1111-4111-8111-111111111111'
        $task2 = '22222222-2222-4222-8222-222222222222'
        $checkpointPath = Join-Path $TestDrive 'multi-task-checkpoint.json'
        Write-SyntheticCheckpoint $checkpointPath @($task1, $task2) 5
        $checkpointBefore = [IO.File]::ReadAllText($checkpointPath, [Text.Encoding]::UTF8)

        $responses = @(
            (New-FixtureResponse '200 OK' ([ordered]@{ id=$task1; sourceFormat='LEGACY_JSON'; dryRun=$true; status='COMPLETED'; processedRows=3; failureMessage=$unsafe })),
            (New-FixtureResponse '200 OK' ([ordered]@{ page=1; size=2; total=3; items=@(
                (New-SyntheticReviewItem 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1' 'VALID' @() $unsafe),
                (New-SyntheticReviewItem 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2' 'NEEDS_REVIEW' @('PATH_JSON_METADATA_CONFLICT','EMBEDDED_OPTIONS_AMBIGUOUS') $unsafe)
            ) })),
            (New-FixtureResponse '200 OK' ([ordered]@{ page=2; size=2; total=3; items=@(
                (New-SyntheticReviewItem 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3' 'NEEDS_REVIEW' @('PATH_JSON_METADATA_CONFLICT') $unsafe)
            ) })),
            (New-FixtureResponse '200 OK' ([ordered]@{ id=$task2; sourceFormat='LEGACY_JSON'; dryRun=$true; status='PARTIAL_SUCCESS'; processedRows=2 })),
            (New-FixtureResponse '200 OK' ([ordered]@{ page=1; size=2; total=2; items=@(
                (New-SyntheticReviewItem 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1' 'VALID' @() $unsafe),
                (New-SyntheticReviewItem 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2' 'SKIPPED' @() $unsafe)
            ) }))
        )
        $fixture = Start-ResponseSequenceServer $responses
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $serialized = & $summaryScript -CheckpointPath $checkpointPath `
                -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" -PageSize 2
            $summary = $serialized | ConvertFrom-Json

            (($summary.PSObject.Properties.Name) -join ',') | Should Be 'totalTaskCount,totalRecordCount,outcomes,issueCodes,issueCodeCombinations'
            (($summary.outcomes[0].PSObject.Properties.Name) -join ',') | Should Be 'outcome,count,percentage'
            (($summary.issueCodes[0].PSObject.Properties.Name) -join ',') | Should Be 'issueCode,count,percentage'
            (($summary.issueCodeCombinations[0].PSObject.Properties.Name) -join ',') | Should Be 'issueCodes,count,percentage'
            $summary.totalTaskCount | Should Be 2
            $summary.totalRecordCount | Should Be 5
            ($summary.outcomes | Where-Object outcome -eq 'VALID').count | Should Be 2
            ($summary.outcomes | Where-Object outcome -eq 'VALID').percentage | Should Be 40
            ($summary.outcomes | Where-Object outcome -eq 'NEEDS_REVIEW').count | Should Be 2
            ($summary.outcomes | Where-Object outcome -eq 'FAILED').count | Should Be 0
            ($summary.outcomes | Where-Object outcome -eq 'SKIPPED').count | Should Be 1
            ($summary.issueCodes | Where-Object issueCode -eq 'PATH_JSON_METADATA_CONFLICT').count | Should Be 2
            ($summary.issueCodes | Where-Object issueCode -eq 'PATH_JSON_METADATA_CONFLICT').percentage | Should Be 40
            ($summary.issueCodes | Where-Object issueCode -eq 'EMBEDDED_OPTIONS_AMBIGUOUS').count | Should Be 1
            $summary.issueCodeCombinations.Count | Should Be 2
            (($summary.issueCodeCombinations[0].issueCodes) -join '+') | Should Be 'EMBEDDED_OPTIONS_AMBIGUOUS+PATH_JSON_METADATA_CONFLICT'
            [IO.File]::ReadAllText($checkpointPath, [Text.Encoding]::UTF8) | Should Be $checkpointBefore

            $serialized | Should Not Match ([Regex]::Escape($token))
            $serialized | Should Not Match 'Bearer|synthetic secret stem|secret answer|fixture-bank|rawJson|external-'
            $serialized | Should Not Match ([Regex]::Escape($task1))
            $serialized | Should Not Match ([Regex]::Escape($task2))
            $serialized | Should Not Match 'aaaaaaaa-aaaa|bbbbbbbb-bbbb'
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Stop-Job -Job $fixture.job -ErrorAction SilentlyContinue
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '空任务返回零记录和空问题分布' {
        $taskId = '33333333-3333-4333-8333-333333333333'
        $checkpointPath = Join-Path $TestDrive 'empty-checkpoint.json'
        Write-SyntheticCheckpoint $checkpointPath @($taskId) 0
        $responses = @(
            (New-FixtureResponse '200 OK' ([ordered]@{ id=$taskId; sourceFormat='LEGACY_JSON'; dryRun=$true; status='COMPLETED'; processedRows=0 })),
            (New-FixtureResponse '200 OK' ([ordered]@{ page=1; size=10; total=0; items=@() }))
        )
        $fixture = Start-ResponseSequenceServer $responses
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'empty-fixture-token', 'Process')
        try {
            $summary = (& $summaryScript -CheckpointPath $checkpointPath `
                -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" -PageSize 10) | ConvertFrom-Json
            $summary.totalTaskCount | Should Be 1
            $summary.totalRecordCount | Should Be 0
            ($summary.outcomes | Measure-Object -Property count -Sum).Sum | Should Be 0
            $summary.issueCodes.Count | Should Be 0
            $summary.issueCodeCombinations.Count | Should Be 0
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Stop-Job -Job $fixture.job -ErrorAction SilentlyContinue
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '401 使用白名单诊断并立即停止' {
        $taskId = '44444444-4444-4444-8444-444444444444'
        $checkpointPath = Join-Path $TestDrive 'unauthorized-checkpoint.json'
        Write-SyntheticCheckpoint $checkpointPath @($taskId) 1
        $token = 'unauthorized-summary-token'
        $failure = [ordered]@{
            code='IAM.UNAUTHORIZED'; message="Bearer $token secret stem"; traceId='summary-401-trace'
            path='/api/v1/teacher/question-imports/task'; fieldErrors=@()
            rawJson='{"question":"secret stem"}'
        }
        $fixture = Start-ResponseSequenceServer @((New-FixtureResponse '401 Unauthorized' $failure))
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $caught = $null
            try { & $summaryScript -CheckpointPath $checkpointPath -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" } catch { $caught = $_.Exception.Message }
            $diagnostic = $caught | ConvertFrom-Json
            (($diagnostic.PSObject.Properties.Name) -join ',') | Should Be 'httpStatus,code,traceId,fieldErrors'
            $diagnostic.httpStatus | Should Be 401
            $diagnostic.code | Should Be 'IAM.UNAUTHORIZED'
            $caught | Should Not Match 'unauthorized-summary-token|secret stem|rawJson|message|"path"'
            $caught | Should Not Match ([Regex]::Escape($taskId))
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Stop-Job -Job $fixture.job -ErrorAction SilentlyContinue
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '分页 422 只输出安全字段原因且不输出任务或内容' {
        $taskId = '55555555-5555-4555-8555-555555555555'
        $checkpointPath = Join-Path $TestDrive 'unprocessable-checkpoint.json'
        Write-SyntheticCheckpoint $checkpointPath @($taskId) 1
        $token = 'unprocessable-summary-token'
        $failure = [ordered]@{
            code='COMMON.VALIDATION_FAILED'; traceId='summary-422-trace'
            fieldErrors=@([ordered]@{ field='page'; reason="非法分页 Bearer $token I:\fixture-bank\secret.json" })
            message='secret stem'; path='/unsafe'; rawJson='{"answer":"secret answer"}'
        }
        $responses = @(
            (New-FixtureResponse '200 OK' ([ordered]@{ id=$taskId; sourceFormat='LEGACY_JSON'; dryRun=$true; status='COMPLETED'; processedRows=1 })),
            (New-FixtureResponse '422 Unprocessable Entity' $failure)
        )
        $fixture = Start-ResponseSequenceServer $responses
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $caught = $null
            try { & $summaryScript -CheckpointPath $checkpointPath -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" } catch { $caught = $_.Exception.Message }
            $diagnostic = $caught | ConvertFrom-Json
            $diagnostic.httpStatus | Should Be 422
            $diagnostic.code | Should Be 'COMMON.VALIDATION_FAILED'
            $diagnostic.fieldErrors[0].field | Should Be 'page'
            $diagnostic.fieldErrors[0].reason | Should Match '\[REDACTED\]|\[REDACTED_PATH\]'
            $caught | Should Not Match 'unprocessable-summary-token|fixture-bank|secret stem|secret answer|rawJson|message|"path"'
            $caught | Should Not Match ([Regex]::Escape($taskId))
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Stop-Job -Job $fixture.job -ErrorAction SilentlyContinue
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '中途服务中断不输出部分汇总、响应正文或完整 ID' {
        $task1 = '66666666-6666-4666-8666-666666666666'
        $task2 = '77777777-7777-4777-8777-777777777777'
        $checkpointPath = Join-Path $TestDrive 'interrupted-checkpoint.json'
        Write-SyntheticCheckpoint $checkpointPath @($task1, $task2) 2
        $secret = 'interrupted secret stem I:\fixture-bank\secret.json'
        $responses = @(
            (New-FixtureResponse '200 OK' ([ordered]@{ id=$task1; sourceFormat='LEGACY_JSON'; dryRun=$true; status='COMPLETED'; processedRows=1 })),
            (New-FixtureResponse '200 OK' ([ordered]@{ page=1; size=100; total=1; items=@(
                (New-SyntheticReviewItem 'cccccccc-cccc-4ccc-8ccc-ccccccccccc1' 'VALID' @() $secret)
            ) })),
            (New-FixtureResponse '200 OK' ([ordered]@{ id=$task2; sourceFormat='LEGACY_JSON'; dryRun=$true; status='COMPLETED'; processedRows=1 })),
            (New-FixtureResponse '503 Service Unavailable' $secret)
        )
        $fixture = Start-ResponseSequenceServer $responses
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'interrupted-token', 'Process')
        try {
            $caught = $null
            try { & $summaryScript -CheckpointPath $checkpointPath -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" } catch { $caught = $_.Exception.Message }
            $diagnostic = $caught | ConvertFrom-Json
            $diagnostic.httpStatus | Should Be 503
            $caught | Should Not Match 'totalTaskCount|totalRecordCount|outcomes|interrupted secret stem|fixture-bank'
            $caught | Should Not Match ([Regex]::Escape($task1))
            $caught | Should Not Match ([Regex]::Escape($task2))
            $caught | Should Not Match 'cccccccc-cccc'
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Stop-Job -Job $fixture.job -ErrorAction SilentlyContinue
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }
}
