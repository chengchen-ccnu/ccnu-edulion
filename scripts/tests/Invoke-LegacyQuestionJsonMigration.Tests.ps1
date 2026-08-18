$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scriptsRoot = Split-Path -Parent $here
. (Join-Path $scriptsRoot 'private\LegacyMigrationHttpDiagnostics.ps1')

function New-TestHttpErrorRecord([int]$Status, [string]$Body, [string]$UnsafeExceptionMessage) {
    $exception = New-Object System.Exception($UnsafeExceptionMessage)
    $exception | Add-Member -MemberType NoteProperty -Name Response -Value ([pscustomobject]@{ StatusCode = $Status })
    $record = New-Object System.Management.Automation.ErrorRecord(
        $exception, 'TEST_HTTP_FAILURE', [System.Management.Automation.ErrorCategory]::InvalidResult, $null)
    $record.ErrorDetails = New-Object System.Management.Automation.ErrorDetails($Body)
    return $record
}

function Start-SingleResponseServer([string]$StatusLine, [string]$Body) {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    $server = Start-ThreadJob -ArgumentList $listener, $StatusLine, $Body -ScriptBlock {
        param($Listener, $StatusLine, $Body)
        $ErrorActionPreference = 'Stop'
        $client = $Listener.AcceptTcpClient()
        $stream = $client.GetStream()
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $false, 1024, $true)
        $reader.ReadLine() | Out-Null
        while ($reader.ReadLine() -ne '') {}
        $reader.Dispose()
        $bodyBytes = [Text.Encoding]::UTF8.GetBytes($Body)
        $headers = "HTTP/1.1 $StatusLine`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
        $headerBytes = [Text.Encoding]::ASCII.GetBytes($headers)
        $stream.Write($headerBytes, 0, $headerBytes.Length)
        $stream.Write($bodyBytes, 0, $bodyBytes.Length)
        $stream.Flush()
        $client.Close()
    }
    return [pscustomobject]@{ listener = $listener; job = $server; port = $port }
}

Describe 'Invoke-LegacyQuestionJsonMigration safe HTTP diagnostics' {
    It '只保留状态码、统一 code、traceId 和 fieldErrors 字段原因' {
        $token = 'secret-migration-token'
        $sourcePath = 'I:\private-bank\grade8\secret.json'
        $stem = '不应该出现的真实题干'
        $answer = '不应该出现的真实答案'
        $body = [ordered]@{
            code = 'COMMON.VALIDATION_FAILED'
            message = "Bearer $token $sourcePath $stem $answer"
            traceId = 'trace_123-abc'
            timestamp = '2026-08-18T10:00:00Z'
            path = '/api/v1/teacher/legacy-question-imports/task/batches'
            fieldErrors = @(
                [ordered]@{ field = 'items[0].json.exerciseType'; reason = '枚举值不合法' },
                [ordered]@{ field = 'items[0].json.question'; reason = '题干不能为空' },
                [ordered]@{ field = 'request'; reason = "{`"question`":`"$stem`",`"queAns`":`"$answer`"}" }
            )
            rawResponse = "{`"question`":`"$stem`",`"answer`":`"$answer`"}"
        } | ConvertTo-Json -Depth 5 -Compress
        $errorRecord = New-TestHttpErrorRecord 422 $body "full response: $token $sourcePath $stem $answer"

        $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $errorRecord `
            -SensitiveValues @($token, $sourcePath, $stem, $answer)
        $serialized = $diagnostic | ConvertTo-Json -Depth 4 -Compress

        (($diagnostic.PSObject.Properties.Name) -join ',') | Should Be 'httpStatus,code,traceId,fieldErrors'
        $diagnostic.httpStatus | Should Be 422
        $diagnostic.code | Should Be 'COMMON.VALIDATION_FAILED'
        $diagnostic.traceId | Should Be 'trace_123-abc'
        $diagnostic.fieldErrors.Count | Should Be 3
        (($diagnostic.fieldErrors[0].PSObject.Properties.Name) -join ',') | Should Be 'field,reason'
        $diagnostic.fieldErrors[0].field | Should Be 'items[0].json.exerciseType'
        $diagnostic.fieldErrors[0].reason | Should Be '枚举值不合法'
        $diagnostic.fieldErrors[2].reason | Should Be 'Structured source content was suppressed.'
        $serialized | Should Not Match ([Regex]::Escape($token))
        $serialized | Should Not Match ([Regex]::Escape($sourcePath))
        $serialized | Should Not Match ([Regex]::Escape($stem))
        $serialized | Should Not Match ([Regex]::Escape($answer))
        $serialized | Should Not Match 'message|timestamp|rawResponse|"path"'
    }

    It '响应不是统一错误 JSON 时不回显完整响应或异常消息' {
        $unsafe = 'Bearer secret-token I:\private-bank\secret.json {"question":"secret stem"}'
        $errorRecord = New-TestHttpErrorRecord 502 $unsafe "request failed: $unsafe"

        $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $errorRecord
        $serialized = $diagnostic | ConvertTo-Json -Depth 4 -Compress

        $diagnostic.httpStatus | Should Be 502
        $diagnostic.code | Should BeNullOrEmpty
        $diagnostic.traceId | Should BeNullOrEmpty
        $diagnostic.fieldErrors.Count | Should Be 0
        $serialized | Should Not Match 'secret-token|private-bank|secret stem|question'
    }

    It '对 fieldErrors 中的 Token、JWT、绝对路径和当前题目文本二次脱敏' {
        $token = 'process-secret-token'
        $stem = '当前批次敏感题干'
        $body = [ordered]@{
            code = 'COMMON.VALIDATION_FAILED'
            traceId = 'safe-trace'
            fieldErrors = @([ordered]@{
                field = 'items[0].json.question'
                reason = "Bearer $token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature123 I:\private-bank\secret.json $stem"
            })
        } | ConvertTo-Json -Depth 5 -Compress
        $errorRecord = New-TestHttpErrorRecord 422 $body 'suppressed'

        $diagnostic = ConvertTo-SafeMigrationHttpDiagnostic -ErrorRecord $errorRecord -SensitiveValues @($token, $stem)
        $reason = $diagnostic.fieldErrors[0].reason

        $reason | Should Match '\[REDACTED\]'
        $reason | Should Match '\[REDACTED_PATH\]'
        $reason | Should Not Match 'process-secret-token|eyJhbGci|private-bank|当前批次敏感题干'
    }

    It '真实 HTTP 任务创建失败时输出安全诊断且不重建检查点' {
        $sourceRoot = Join-Path $TestDrive 'create-failure-source'
        $checkpointPath = Join-Path $TestDrive 'create-failure-checkpoint.json'
        [IO.Directory]::CreateDirectory($sourceRoot) | Out-Null
        $token = 'create-failure-secret-token'
        $sourceFile = Join-Path $sourceRoot 'sample.json'
        [IO.File]::WriteAllText($sourceFile,
            '{"exerciseID":"safe-id","exerciseType":"fillin","question":"synthetic stem","queAns":"1"}',
            (New-Object Text.UTF8Encoding($false)))
        $failure = [ordered]@{
            code = 'IAM.UNAUTHORIZED'
            message = "Bearer $token $sourceRoot"
            traceId = 'create-trace-123'
            timestamp = '2026-08-18T10:00:00Z'
            path = '/api/v1/teacher/legacy-question-imports'
            fieldErrors = @()
        } | ConvertTo-Json -Compress
        $fixture = Start-SingleResponseServer '401 Unauthorized' $failure
        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $caught = $null
            try {
                & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action DryRun `
                    -SourceRoot $sourceRoot -ApiBaseUrl "http://127.0.0.1:$($fixture.port)" -MaxItems 1 -BatchSize 1 `
                    -CheckpointPath $checkpointPath
            } catch { $caught = $_.Exception.Message }

            $diagnostic = $caught | ConvertFrom-Json
            (($diagnostic.PSObject.Properties.Name) -join ',') | Should Be 'httpStatus,code,traceId,fieldErrors'
            $diagnostic.httpStatus | Should Be 401
            $diagnostic.code | Should Be 'IAM.UNAUTHORIZED'
            $diagnostic.traceId | Should Be 'create-trace-123'
            $caught | Should Not Match 'create-failure-secret-token|create-failure-source|message|timestamp|"path"'
            $checkpoint = [IO.File]::ReadAllText($checkpointPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
            $checkpoint.activeTask.importTaskId | Should BeNullOrEmpty
            $checkpoint.activeTask.nextBatchIndex | Should Be 0
            $checkpoint.nextSourceIndex | Should Be 0
            $checkpoint.completed | Should Be $false
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $fixture.listener.Stop()
            Stop-Job -Job $fixture.job -ErrorAction SilentlyContinue
            Remove-Job -Job $fixture.job -Force -ErrorAction SilentlyContinue
        }
    }

    It '真实 HTTP 批次失败时只抛出安全诊断并保留原任务检查点' {
        $sourceRoot = Join-Path $TestDrive 'source'
        $checkpointPath = Join-Path $TestDrive 'checkpoint.json'
        [IO.Directory]::CreateDirectory($sourceRoot) | Out-Null
        $token = 'end-to-end-secret-token'
        $stem = '不可输出的端到端题干'
        $answer = '不可输出的端到端答案'
        $sourceFile = Join-Path $sourceRoot 'sample.json'
        $sourceJson = [ordered]@{ exerciseID = 'safe-id'; exerciseType = 'fillin'; question = $stem; queAns = $answer; solution = '' } |
            ConvertTo-Json -Compress
        [IO.File]::WriteAllText($sourceFile, $sourceJson, (New-Object Text.UTF8Encoding($false)))

        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
        $taskId = '11111111-1111-1111-1111-111111111111'
        $server = Start-ThreadJob -ArgumentList $listener, $taskId, $token, $sourceRoot, $stem, $answer -ScriptBlock {
            param($Listener, $TaskId, $Token, $SourceRoot, $Stem, $Answer)
            $ErrorActionPreference = 'Stop'
            function Read-TestRequest($Client) {
                $stream = $Client.GetStream()
                $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $false, 1024, $true)
                $reader.ReadLine() | Out-Null
                while ($reader.ReadLine() -ne '') {}
                $reader.Dispose()
                return $stream
            }
            function Send-TestResponse($Client, [string]$StatusLine, [string]$Body) {
                $stream = Read-TestRequest $Client
                $bodyBytes = [Text.Encoding]::UTF8.GetBytes($Body)
                $headers = "HTTP/1.1 $StatusLine`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
                $headerBytes = [Text.Encoding]::ASCII.GetBytes($headers)
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                $stream.Write($bodyBytes, 0, $bodyBytes.Length)
                $stream.Flush()
                $Client.Close()
            }

            $create = $Listener.AcceptTcpClient()
            Send-TestResponse $create '201 Created' (@{ id = $TaskId } | ConvertTo-Json -Compress)
            $failure = [ordered]@{
                code = 'COMMON.VALIDATION_FAILED'
                message = "Bearer $Token $SourceRoot $Stem $Answer"
                traceId = 'e2e-trace-123'
                timestamp = '2026-08-18T10:00:00Z'
                path = '/unsafe/full/response/path'
                fieldErrors = @([ordered]@{
                    field = 'items[0].json.question'
                    reason = "题干校验失败 $Token $SourceRoot $Stem $Answer"
                })
                rawJson = "{`"question`":`"$Stem`",`"answer`":`"$Answer`"}"
            } | ConvertTo-Json -Depth 5 -Compress
            $batch = $Listener.AcceptTcpClient()
            Send-TestResponse $batch '422 Unprocessable Entity' $failure
        }

        $previousToken = [Environment]::GetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', 'Process')
        [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $token, 'Process')
        try {
            $caught = $null
            try {
                & (Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1') -Action DryRun `
                    -SourceRoot $sourceRoot -ApiBaseUrl "http://127.0.0.1:$port" -MaxItems 1 -BatchSize 1 `
                    -CheckpointPath $checkpointPath
            } catch { $caught = $_.Exception.Message }

            $caught | Should Not BeNullOrEmpty
            $caught | Should Match '^\{'
            $diagnostic = $caught | ConvertFrom-Json
            (($diagnostic.PSObject.Properties.Name) -join ',') | Should Be 'httpStatus,code,traceId,fieldErrors'
            $diagnostic.httpStatus | Should Be 422
            $diagnostic.code | Should Be 'COMMON.VALIDATION_FAILED'
            $diagnostic.traceId | Should Be 'e2e-trace-123'
            $caught | Should Not Match 'end-to-end-secret-token|private|sample.json|不可输出的端到端题干|不可输出的端到端答案|rawJson|message|timestamp|"path"'

            (Test-Path -LiteralPath $checkpointPath -PathType Leaf) | Should Be $true
            $checkpoint = [IO.File]::ReadAllText($checkpointPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
            $checkpoint.activeTask.importTaskId | Should Be $taskId
            $checkpoint.activeTask.nextBatchIndex | Should Be 0
            $checkpoint.nextSourceIndex | Should Be 0
            $checkpoint.completed | Should Be $false
        } finally {
            [Environment]::SetEnvironmentVariable('EDU_MIGRATION_ACCESS_TOKEN', $previousToken, 'Process')
            $listener.Stop()
            Stop-Job -Job $server -ErrorAction SilentlyContinue
            Remove-Job -Job $server -Force -ErrorAction SilentlyContinue
        }
    }

    It '主迁移脚本在批次 catch 中使用白名单诊断且不重置检查点' {
        $scriptPath = Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1'
        $tokens = $null
        $parseErrors = $null
        $ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
        $parseErrors.Count | Should Be 0
        $safeCatches = @($ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.CatchClauseAst] -and
                $node.Extent.Text -match 'ConvertTo-SafeMigrationHttpDiagnostic'
        }, $true))
        $safeCatches.Count | Should Be 2
        $safeCatchText = $safeCatches.Extent.Text -join "`n"
        $safeCatchText | Should Match 'ConvertTo-SafeMigrationHttpDiagnostic\s+-ErrorRecord\s+\$_'
        $safeCatchText | Should Match 'Get-BatchSensitiveValues\s+\$batchItems'
        $safeCatchText | Should Not Match 'activeTask\s*=\s*\$null|Remove-Item|Save-Checkpoint'
    }

    It '创建任务和批次请求均显式发送 UTF-8 无 BOM 字节' {
        $scriptPath = Join-Path $scriptsRoot 'Invoke-LegacyQuestionJsonMigration.ps1'
        $scriptText = [IO.File]::ReadAllText($scriptPath, [Text.Encoding]::UTF8)

        $scriptText | Should Match '\$createBodyBytes\s*=\s*\$utf8NoBom\.GetBytes\(\$createBody\)'
        $scriptText | Should Match '-Body\s+\$createBodyBytes'
        $scriptText | Should Match '\$bodyBytes\s*=\s*\$utf8NoBom\.GetBytes\(\$body\)'
        $scriptText | Should Match '-Body\s+\$bodyBytes'

        $encoder = New-Object Text.UTF8Encoding($false)
        $bytes = $encoder.GetBytes('{"question":"中文题干","queAns":"答案"}')
        $bytes[0] | Should Be 123
        (($bytes[0..2] -join ',')) | Should Not Be '239,187,191'
        $encoder.GetString($bytes) | Should Be '{"question":"中文题干","queAns":"答案"}'
    }
}
