[CmdletBinding()]
param(
    [int]$AgentAccessPort = 18086,
    [string]$PythonPath = "D:\Anaconda\envs\langgraph\python.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $env:AGENT_RUNTIME_CALLBACK_TOKEN -or -not $env:OPENAI_API_KEY -or
    -not $env:MYSQL_USERNAME -or -not $env:MYSQL_PASSWORD) {
    throw "T13.4 真实验收必须通过 Invoke-WithLocalEnvironment.ps1 注入现有本地配置。"
}
if (-not (Test-Path -LiteralPath $PythonPath)) {
    throw "未找到用于启动 Agent runtime 的 Python：$PythonPath"
}

$runtimePort = 8000
$ownerId = [guid]::NewGuid().ToString()
$conversationIds = [System.Collections.Generic.List[string]]::new()
$runIds = [System.Collections.Generic.List[string]]::new()
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("edu-t134-help-" + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $tempRoot
$javaLog = Join-Path $tempRoot "agent-access.log"
$javaError = Join-Path $tempRoot "agent-access.err.log"
$pythonLog = Join-Path $tempRoot "agent-runtime.log"
$pythonError = Join-Path $tempRoot "agent-runtime.err.log"
$javaProcess = $null
$pythonProcess = $null
$mysqlPasswordBefore = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")
$environmentBefore = @{}
$managedEnvironment = @(
    "SERVER_PORT", "AGENT_RUNTIME_BASE_URL", "AGENT_ACCESS_BASE_URL",
    "AGENT_ONLINE_RETRIEVAL_ENABLED", "AGENT_INDEX_PUBLISH_ENABLED",
    "AGENT_MESSAGE_RETENTION_ENABLED", "AGENT_MEDIA_OUTBOX_PUBLISH_ENABLED"
)
foreach ($name in $managedEnvironment) {
    $environmentBefore[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

function Restore-ProcessVariable {
    param([string]$Name, [AllowNull()][string]$Value)
    if ($null -eq $Value) { Remove-Item -Path "Env:$Name" -ErrorAction SilentlyContinue }
    else { Set-Item -Path "Env:$Name" -Value $Value }
}

function Wait-Health {
    param([string]$Url, [string]$Service)
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        }
        catch { Start-Sleep -Milliseconds 500 }
    }
    throw "$Service 未在限定时间内就绪。"
}

function Invoke-MySql {
    param([Parameter(Mandatory)][string]$Sql)
    $database = if ($env:AGENT_MYSQL_DATABASE) { $env:AGENT_MYSQL_DATABASE } else { "edu_platform_agent_dev" }
    $hostName = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "127.0.0.1" }
    $port = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "3306" }
    $arguments = @(
        "--batch", "--skip-column-names", "--host=$hostName", "--port=$port",
        "--user=$($env:MYSQL_USERNAME)", "--database=$database", "--execute=$Sql"
    )
    $result = & mysql @arguments
    if ($LASTEXITCODE -ne 0) { throw "MySQL 验收操作失败。" }
    return @($result)
}

function New-Conversation {
    $body = @{
        clientRequestId = [guid]::NewGuid().ToString()
        entryPoint = "INDEPENDENT_CHAT"
        title = "帮助验收"
    } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$AgentAccessPort/api/v1/ai/conversations" `
        -Method Post -Headers $script:browserHeaders -ContentType "application/json" -Body $body
    $conversationIds.Add([string]$response.id)
    return [string]$response.id
}

function Send-HelpQuestion {
    param([Parameter(Mandatory)][string]$ConversationId, [Parameter(Mandatory)][string]$Question)
    $body = @{
        clientRequestId = [guid]::NewGuid().ToString()
        content = $Question
        context = $null
    } | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -UseBasicParsing `
        -Uri "http://127.0.0.1:$AgentAccessPort/api/v1/ai/conversations/$ConversationId/messages" `
        -Method Post -Headers $script:browserHeaders -ContentType "application/json" -Body $body -TimeoutSec 100
    if ($response.StatusCode -ne 200) { throw "Help SSE 请求未成功。" }
    $runId = [string]$response.Headers["X-Agent-Run-Id"]
    if (-not $runId) { throw "Help SSE 响应缺少 runId。" }
    $runIds.Add($runId)
    if ($response.Content -notmatch '"terminalStatus"\s*:\s*"COMPLETED"') {
        throw "Help run 未产生 COMPLETED 终态。"
    }
    if ($response.Content -match 'highPrecisionRules|currentFile|/internal/|/api/|system prompt|OPENAI_API_KEY') {
        throw "浏览器 SSE 泄露了内部帮助资源或提示信息。"
    }
    return $runId
}

function Wait-Usage {
    param([Parameter(Mandatory)][string]$RunId)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $rows = @(Invoke-MySql "SELECT CONCAT(model_calls,',',tool_attempts) FROM ai_usage_record WHERE run_id='$RunId';")
        if ($rows.Count -eq 1 -and $rows[0]) { return ([string]$rows[0]).Split(',') }
        Start-Sleep -Milliseconds 250
    }
    throw "未在限定时间内观察到 run 用量事实。"
}

function Remove-AcceptanceFacts {
    # 所有删除均由本次随机 owner 锁定；顺序遵守外键，绝不清空共享表或触碰其他领域数据。
    $sql = @"
START TRANSACTION;
DELETE FROM ai_agent_index_outbox WHERE aggregate_id IN (SELECT id FROM ai_memory WHERE owner_user_id='$ownerId');
DELETE FROM ai_memory_evidence WHERE owner_user_id='$ownerId';
DELETE FROM ai_memory WHERE owner_user_id='$ownerId';
DELETE FROM ai_conversation_summary WHERE owner_user_id='$ownerId';
DELETE FROM ai_preference WHERE owner_user_id='$ownerId';
DELETE FROM ai_profile_topic WHERE owner_user_id='$ownerId';
DELETE FROM ai_usage_record WHERE owner_user_id='$ownerId';
DELETE FROM ai_safety_event WHERE owner_user_id='$ownerId';
DELETE FROM ai_tool_call WHERE run_id IN (SELECT id FROM ai_run WHERE owner_user_id='$ownerId');
DELETE FROM ai_run_cancellation WHERE run_id IN (SELECT id FROM ai_run WHERE owner_user_id='$ownerId');
DELETE FROM ai_run_event WHERE run_id IN (SELECT id FROM ai_run WHERE owner_user_id='$ownerId');
DELETE FROM ai_message_media_reference WHERE message_id IN (
    SELECT id FROM ai_message WHERE conversation_id IN (SELECT id FROM ai_conversation WHERE owner_user_id='$ownerId')
);
DELETE FROM ai_run WHERE owner_user_id='$ownerId';
DELETE FROM ai_message WHERE conversation_id IN (SELECT id FROM ai_conversation WHERE owner_user_id='$ownerId');
DELETE FROM ai_conversation WHERE owner_user_id='$ownerId';
COMMIT;
"@
    Invoke-MySql $sql | Out-Null
    $remaining = @(Invoke-MySql @"
SELECT
  (SELECT COUNT(*) FROM ai_conversation WHERE owner_user_id='$ownerId') +
  (SELECT COUNT(*) FROM ai_run WHERE owner_user_id='$ownerId') +
  (SELECT COUNT(*) FROM ai_usage_record WHERE owner_user_id='$ownerId') +
  (SELECT COUNT(*) FROM ai_memory WHERE owner_user_id='$ownerId') +
  (SELECT COUNT(*) FROM ai_profile_topic WHERE owner_user_id='$ownerId') +
  (SELECT COUNT(*) FROM ai_preference WHERE owner_user_id='$ownerId') +
  (SELECT COUNT(*) FROM ai_safety_event WHERE owner_user_id='$ownerId');
"@
    )
    return [int]([string]$remaining[0])
}

try {
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $env:SERVER_PORT = [string]$AgentAccessPort
    $env:AGENT_RUNTIME_BASE_URL = "http://127.0.0.1:$runtimePort"
    $env:AGENT_ACCESS_BASE_URL = "http://127.0.0.1:$AgentAccessPort"
    $env:AGENT_ONLINE_RETRIEVAL_ENABLED = "false"
    $env:AGENT_INDEX_PUBLISH_ENABLED = "false"
    $env:AGENT_MESSAGE_RETENTION_ENABLED = "false"
    $env:AGENT_MEDIA_OUTBOX_PUBLISH_ENABLED = "false"
    $script:browserHeaders = @{
        "X-User-Id" = $ownerId
        "X-User-Permissions" = "learning:access"
    }

    $javaProcess = Start-Process -FilePath "java" -ArgumentList @(
        "-jar", "java-platform/agent-access-service/target/agent-access-service-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=local", "--platform.agent-governance.enabled=false",
        "--platform.agent-index.publish-enabled=false", "--platform.agent-retention.enabled=false"
    ) -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $javaLog -RedirectStandardError $javaError
    Wait-Health "http://127.0.0.1:$AgentAccessPort/actuator/health" "Agent Access"

    $pythonProcess = Start-Process -FilePath $PythonPath -ArgumentList @("-m", "edu_agent_app") `
        -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $pythonLog -RedirectStandardError $pythonError
    Wait-Health "http://127.0.0.1:$runtimePort/health" "Agent runtime"

    $directRun = Send-HelpQuestion (New-Conversation) "如何登录"
    $directUsage = Wait-Usage $directRun
    if ([int]$directUsage[0] -ne 0 -or [int]$directUsage[1] -ne 1) {
        throw "高精度登录帮助未满足 0 次模型、1 次帮助工具调用。"
    }

    $complexRun = Send-HelpQuestion (New-Conversation) "课程学习进度和作业成绩分别什么时候能看到？"
    $complexUsage = Wait-Usage $complexRun
    if ([int]$complexUsage[0] -ne 1 -or [int]$complexUsage[1] -ne 1) {
        throw "复合帮助问题未满足 1 次模型、1 次帮助工具调用。"
    }

    [pscustomobject]@{
        agentAccessReady = $true
        runtimeReady = $true
        directRunStatus = "COMPLETED"
        directModelCalls = [int]$directUsage[0]
        directToolAttempts = [int]$directUsage[1]
        complexRunStatus = "COMPLETED"
        complexModelCalls = [int]$complexUsage[0]
        complexToolAttempts = [int]$complexUsage[1]
        helpEvidenceLimit = 5
        onlineRetrievalEnabled = $false
        redisGovernanceEnabled = $false
        indexPublishEnabled = $false
        sensitivePayloadPrinted = $false
    } | ConvertTo-Json -Compress
}
finally {
    $cleanupRemaining = $null
    try { $cleanupRemaining = Remove-AcceptanceFacts }
    catch { Write-Error "T13.4 验收事实清理失败：$($_.Exception.Message)" }
    if ($pythonProcess -and -not $pythonProcess.HasExited) {
        Stop-Process -Id $pythonProcess.Id
        [void]$pythonProcess.WaitForExit(5000)
    }
    if ($javaProcess -and -not $javaProcess.HasExited) {
        Stop-Process -Id $javaProcess.Id
        [void]$javaProcess.WaitForExit(5000)
    }
    if ($null -ne $cleanupRemaining) {
        [pscustomobject]@{ cleanupRemainingFacts = $cleanupRemaining } | ConvertTo-Json -Compress
    }
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    Restore-ProcessVariable "MYSQL_PWD" $mysqlPasswordBefore
    foreach ($name in $managedEnvironment) { Restore-ProcessVariable $name $environmentBefore[$name] }
}
