[CmdletBinding()]
param(
    [int]$GatewayPort = 28080,
    [int]$MediaPort = 28083,
    [int]$AgentAccessPort = 28086,
    [string]$PythonPath = "D:\Anaconda\envs\langgraph\python.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $env:MYSQL_USERNAME -or -not $env:MYSQL_PASSWORD -or -not $env:IAM_JWT_SECRET -or
    -not $env:AGENT_RUNTIME_CALLBACK_TOKEN -or -not $env:OPENAI_API_KEY -or
    -not $env:OSS_ENDPOINT -or -not $env:OSS_BUCKET -or -not $env:OSS_ACCESS_KEY_ID -or
    -not $env:OSS_ACCESS_KEY_SECRET) {
    throw "T14.2.4 真实验收必须通过 Invoke-WithLocalEnvironment.ps1 注入既有本地配置。"
}
if ($env:AGENT_MYSQL_DATABASE -ne "edu_platform_agent_dev" -or
    $env:MEDIA_MYSQL_DATABASE -ne "edu_platform_media_dev") {
    throw "T14.2.4 真实验收只允许专用 Agent/Media dev Schema。"
}
if (-not (Test-Path -LiteralPath $PythonPath -PathType Leaf)) {
    throw "未找到用于启动 Agent runtime 的 Python。"
}

$runtimePort = 8000
$ownerId = "00000000-0000-4000-8000-000000000101"
$runMarker = [guid]::NewGuid().ToString()
$conversationId = $null
$userMessageId = $null
$normalRunId = $null
$regeneratedRunId = $null
$mediaId = $null
$uploadId = $null
$extraConversationId = $null
$baselineConfirmed = $false
$preferenceRequestId = [guid]::NewGuid().ToString()
$regenerationRequestId = [guid]::NewGuid().ToString()
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("edu-t1424-" + $runMarker)
$null = New-Item -ItemType Directory -Path $tempRoot
$gatewayLog = Join-Path $tempRoot "gateway.log"
$gatewayError = Join-Path $tempRoot "gateway.err.log"
$mediaLog = Join-Path $tempRoot "media.log"
$mediaError = Join-Path $tempRoot "media.err.log"
$agentLog = Join-Path $tempRoot "agent-access.log"
$agentError = Join-Path $tempRoot "agent-access.err.log"
$pythonLog = Join-Path $tempRoot "agent-runtime.log"
$pythonError = Join-Path $tempRoot "agent-runtime.err.log"
$gatewayProcess = $null
$mediaProcess = $null
$agentProcess = $null
$pythonProcess = $null
$mysqlPasswordBefore = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")
$environmentBefore = @{}
$managedEnvironment = @(
    "SERVER_PORT", "MEDIA_SERVICE_URL", "AGENT_ACCESS_SERVICE_URL", "AGENT_RUNTIME_BASE_URL",
    "AGENT_ACCESS_BASE_URL", "AGENT_ONLINE_RETRIEVAL_ENABLED", "AGENT_INDEX_PUBLISH_ENABLED",
    "AGENT_MESSAGE_RETENTION_ENABLED", "AGENT_MEDIA_OUTBOX_PUBLISH_ENABLED", "OSS_ENABLED",
    "OSS_KEY_PREFIX", "MEDIA_WORKER_ENABLED", "MEDIA_MAINTENANCE_ENABLED", "RABBITMQ_ENABLED",
    "GATEWAY_SESSION_VALIDATION_ENABLED", "GATEWAY_RATE_LIMIT_ENABLED", "T1424_MEDIA_REAL_ACCEPTANCE",
    "T1424_MEDIA_ACTION", "T1424_MEDIA_OWNER_ID", "T1424_MEDIA_UPLOAD_ID", "T1424_MEDIA_ID"
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
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        }
        catch { Start-Sleep -Milliseconds 500 }
    }
    throw "$Service 未在限定时间内就绪。"
}

function Invoke-MySql {
    param([Parameter(Mandatory)][string]$Database, [Parameter(Mandatory)][string]$Sql)
    $hostName = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "127.0.0.1" }
    $port = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "3306" }
    $result = & mysql --batch --skip-column-names "--host=$hostName" "--port=$port" `
        "--user=$($env:MYSQL_USERNAME)" "--database=$Database" "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw "MySQL 验收操作失败。" }
    return @($result)
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [AllowNull()][object]$Body)
    $arguments = @{
        Uri = "http://127.0.0.1:$GatewayPort$Path"
        Method = $Method
        Headers = $script:browserHeaders
        TimeoutSec = 120
    }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    return Invoke-RestMethod @arguments
}

function Invoke-Sse {
    param([string]$Path, [object]$Body)
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$GatewayPort$Path" -Method Post `
        -Headers $script:browserHeaders -ContentType "application/json; charset=utf-8" `
        -Body ($Body | ConvertTo-Json -Depth 8 -Compress) -TimeoutSec 120
    $content = $response.Content
    $runId = [string]$response.Headers["X-Agent-Run-Id"]
        if (-not $runId) { throw "Agent SSE 响应缺少 runId。" }
        if ($content -notmatch '"eventType"\s*:\s*"STAGE_CHANGED"' -or
            $content -notmatch '"eventType"\s*:\s*"TEXT_DELTA"' -or
            $content -notmatch '"eventType"\s*:\s*"FINAL_RESULT"') {
            throw "Agent SSE 缺少阶段、文本增量或终态事件。"
        }
        if ($content -match 'prompt|chainOfThought|rawToolResult|OPENAI_API_KEY|https?://') {
            throw "Agent SSE 包含禁止公开的信息。"
        }
    return @{ RunId = $runId; Content = [string]$content }
}

function Invoke-MediaSupport {
    param([ValidateSet("PROCESS", "CLEANUP")][string]$Action)
    $env:T1424_MEDIA_REAL_ACCEPTANCE = "true"
    $env:T1424_MEDIA_ACTION = $Action
    $env:T1424_MEDIA_OWNER_ID = $ownerId
    $env:T1424_MEDIA_UPLOAD_ID = $uploadId
    $env:T1424_MEDIA_ID = $mediaId
    & mvn --% -q -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml -pl media-service -am -Dtest=T1424AiImageRealAcceptanceSupportTest -Dsurefire.failIfNoSpecifiedTests=false test
    if ($LASTEXITCODE -ne 0) { throw "Media $Action 辅助步骤失败。" }
}

function Remove-AgentFacts {
    $sql = @"
START TRANSACTION;
DELETE FROM ai_media_outbox WHERE aggregate_id='$mediaId' OR aggregate_id IN (SELECT id FROM ai_conversation WHERE owner_user_id='$ownerId');
DELETE FROM ai_message_media_reference WHERE media_id='$mediaId' OR message_id IN (SELECT id FROM ai_message WHERE conversation_id='$conversationId');
DELETE FROM ai_agent_index_outbox WHERE aggregate_id IN (SELECT id FROM ai_memory WHERE owner_user_id='$ownerId');
DELETE FROM ai_memory_evidence WHERE owner_user_id='$ownerId';
DELETE FROM ai_memory WHERE owner_user_id='$ownerId';
DELETE FROM ai_conversation_summary WHERE owner_user_id='$ownerId';
DELETE FROM ai_preference_command WHERE owner_user_id='$ownerId';
DELETE FROM ai_preference WHERE owner_user_id='$ownerId';
DELETE FROM ai_profile_topic WHERE owner_user_id='$ownerId';
DELETE FROM ai_usage_record WHERE owner_user_id='$ownerId';
DELETE FROM ai_safety_event WHERE owner_user_id='$ownerId';
DELETE FROM ai_tool_call WHERE run_id IN (SELECT id FROM ai_run WHERE owner_user_id='$ownerId');
DELETE FROM ai_run_cancellation WHERE run_id IN (SELECT id FROM ai_run WHERE owner_user_id='$ownerId');
DELETE FROM ai_run_event WHERE run_id IN (SELECT id FROM ai_run WHERE owner_user_id='$ownerId');
DELETE FROM ai_run WHERE owner_user_id='$ownerId';
DELETE FROM ai_message WHERE conversation_id IN (SELECT id FROM ai_conversation WHERE owner_user_id='$ownerId');
DELETE FROM ai_conversation WHERE owner_user_id='$ownerId';
COMMIT;
"@
    Invoke-MySql $env:AGENT_MYSQL_DATABASE $sql | Out-Null
    $remaining = Invoke-MySql $env:AGENT_MYSQL_DATABASE @"
SELECT
 (SELECT COUNT(*) FROM ai_conversation WHERE owner_user_id='$ownerId') +
 (SELECT COUNT(*) FROM ai_run WHERE owner_user_id='$ownerId') +
 (SELECT COUNT(*) FROM ai_preference WHERE owner_user_id='$ownerId') +
 (SELECT COUNT(*) FROM ai_preference_command WHERE owner_user_id='$ownerId') +
 (SELECT COUNT(*) FROM ai_usage_record WHERE owner_user_id='$ownerId');
"@
    return [int]([string]$remaining[0])
}

function Get-OwnerRedisKeyCount {
    $hostName = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "127.0.0.1" }
    $port = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { "6379" }
    $database = if ($env:AGENT_REDIS_DATABASE) { $env:AGENT_REDIS_DATABASE } else { "6" }
    $prefix = if ($env:AGENT_REDIS_KEY_PREFIX) { $env:AGENT_REDIS_KEY_PREFIX } else { "edu:agent:runtime:v1" }
    $arguments = @("-h", $hostName, "-p", $port, "-n", $database)
    if ($env:REDIS_USERNAME) { $arguments += @("--user", $env:REDIS_USERNAME) }
    if ($env:REDIS_PASSWORD) { $arguments += @("--pass", $env:REDIS_PASSWORD) }
    $total = 0
    foreach ($pattern in @("$prefix`:idem`:$ownerId`:*", "$prefix`:user`:$ownerId`:active",
            "$prefix`:quota`:minute`:$ownerId`:*", "$prefix`:quota`:day`:$ownerId`:*")) {
        $total += @(& redis-cli @arguments --scan --pattern $pattern).Count
    }
    return $total
}

function Remove-RedisFacts {
    $hostName = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "127.0.0.1" }
    $port = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { "6379" }
    $database = if ($env:AGENT_REDIS_DATABASE) { $env:AGENT_REDIS_DATABASE } else { "6" }
    $prefix = if ($env:AGENT_REDIS_KEY_PREFIX) { $env:AGENT_REDIS_KEY_PREFIX } else { "edu:agent:runtime:v1" }
    $arguments = @("-h", $hostName, "-p", $port, "-n", $database)
    if ($env:REDIS_USERNAME) { $arguments += @("--user", $env:REDIS_USERNAME) }
    if ($env:REDIS_PASSWORD) { $arguments += @("--pass", $env:REDIS_PASSWORD) }
    $keys = @(
        "$prefix`:idem`:$ownerId`:$conversationId`:*",
        "$prefix`:user`:$ownerId`:active",
        "$prefix`:quota`:minute`:$ownerId`:*",
        "$prefix`:quota`:day`:$ownerId`:*",
        "$prefix`:run`:$normalRunId`:*",
        "$prefix`:run`:$regeneratedRunId`:*"
    )
    foreach ($pattern in $keys) {
        $matched = @(& redis-cli @arguments --scan --pattern $pattern)
        foreach ($key in $matched) { if ($key) { & redis-cli @arguments DEL $key | Out-Null } }
    }
    $remaining = 0
    foreach ($pattern in $keys) { $remaining += @(& redis-cli @arguments --scan --pattern $pattern).Count }
    return $remaining
}

try {
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $env:OSS_ENABLED = "true"
    $env:OSS_KEY_PREFIX = "t1424-acceptance/$runMarker"
    $env:RABBITMQ_ENABLED = "false"
    $env:MEDIA_WORKER_ENABLED = "false"
    $env:MEDIA_MAINTENANCE_ENABLED = "false"
    $env:AGENT_ONLINE_RETRIEVAL_ENABLED = "false"
    $env:AGENT_INDEX_PUBLISH_ENABLED = "false"
    $env:AGENT_MESSAGE_RETENTION_ENABLED = "false"
    $env:AGENT_MEDIA_OUTBOX_PUBLISH_ENABLED = "false"
    $env:GATEWAY_SESSION_VALIDATION_ENABLED = "false"
    $env:GATEWAY_RATE_LIMIT_ENABLED = "false"

    $env:SERVER_PORT = [string]$MediaPort
    $mediaProcess = Start-Process -FilePath "java" -ArgumentList @(
        "-jar", "java-platform/media-service/target/media-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local"
    ) -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $mediaLog -RedirectStandardError $mediaError
    Wait-Health "http://127.0.0.1:$MediaPort/actuator/health" "Media"

    $env:SERVER_PORT = [string]$AgentAccessPort
    $env:MEDIA_SERVICE_BASE_URL = "http://127.0.0.1:$MediaPort"
    $env:AGENT_RUNTIME_BASE_URL = "http://127.0.0.1:$runtimePort"
    $env:AGENT_ACCESS_BASE_URL = "http://127.0.0.1:$AgentAccessPort"
    $agentProcess = Start-Process -FilePath "java" -ArgumentList @(
        "-jar", "java-platform/agent-access-service/target/agent-access-service-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=local", "--platform.agent-index.publish-enabled=false",
        "--platform.agent-retention.enabled=false", "--platform.agent-retention.media-publish-enabled=false"
    ) -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $agentLog -RedirectStandardError $agentError
    Wait-Health "http://127.0.0.1:$AgentAccessPort/actuator/health" "Agent Access"

    # 固定学习账号仅在 Agent/Redis 尚无事实时使用；否则停止，避免验收清理覆盖用户既有数据。
    $existingFacts = Invoke-MySql $env:AGENT_MYSQL_DATABASE @"
SELECT
 (SELECT COUNT(*) FROM ai_conversation WHERE owner_user_id='$ownerId') +
 (SELECT COUNT(*) FROM ai_preference WHERE owner_user_id='$ownerId') +
 (SELECT COUNT(*) FROM ai_preference_command WHERE owner_user_id='$ownerId') +
 (SELECT COUNT(*) FROM ai_usage_record WHERE owner_user_id='$ownerId');
"@
    if ([int]([string]$existingFacts[0]) -ne 0 -or (Get-OwnerRedisKeyCount) -ne 0) {
        throw "固定 local/test 学习账号已有 Agent 或 Redis 事实，已停止以避免覆盖。"
    }
    $baselineConfirmed = $true

    $pythonProcess = Start-Process -FilePath $PythonPath -ArgumentList @("-m", "edu_agent_app") `
        -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $pythonLog -RedirectStandardError $pythonError
    Wait-Health "http://127.0.0.1:$runtimePort/health" "Agent runtime"

    $env:SERVER_PORT = [string]$GatewayPort
    $env:MEDIA_SERVICE_URL = "http://127.0.0.1:$MediaPort"
    $env:AGENT_ACCESS_SERVICE_URL = "http://127.0.0.1:$AgentAccessPort"
    $gatewayProcess = Start-Process -FilePath "java" -ArgumentList @(
        "-jar", "java-platform/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=local", "--platform.gateway.session-validation.enabled=false",
        "--platform.gateway.rate-limit.enabled=false"
    ) -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $gatewayLog -RedirectStandardError $gatewayError
    Wait-Health "http://127.0.0.1:$GatewayPort/actuator/health" "Gateway"

    $now = [DateTimeOffset]::UtcNow
    $claims = [ordered]@{
        sub = $ownerId; iss = "edu-platform-iam"; aud = @("edu-platform-gateway")
        iat = $now.ToUnixTimeSeconds(); exp = $now.AddMinutes(20).ToUnixTimeSeconds()
        roles = @("STUDENT"); permissions = @("learning:access"); sessionVersion = 0
    }
    function ConvertTo-Base64Url([byte[]]$Bytes) {
        return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    }
    $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes(($claims | ConvertTo-Json -Compress)))
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($env:IAM_JWT_SECRET))
    try { $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes("$header.$payload"))) }
    finally { $hmac.Dispose() }
    $script:browserHeaders = @{ Authorization = "Bearer $header.$payload.$signature" }

    $image = [byte[]](137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,196,137,0,0,0,13,73,68,65,84,8,215,99,248,207,192,240,31,0,5,0,1,255,137,153,61,29,0,0,0,0,73,69,78,68,174,66,96,130)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { $sha = ([BitConverter]::ToString($sha256.ComputeHash($image))).Replace("-", "").ToLowerInvariant() }
    finally { $sha256.Dispose() }
    $upload = Invoke-Api "POST" "/api/v1/media/uploads" @{
        clientRequestId = [guid]::NewGuid().ToString(); purpose = "AI_QUESTION_IMAGE"; fileName = "t1424.png"
        size = $image.Length; mimeType = "image/png"; sha256 = $sha
    }
    $uploadId = [string]$upload.uploadId
    $putHeaders = @{}
    foreach ($property in $upload.uploadGrant.headers.PSObject.Properties) { $putHeaders[$property.Name] = [string]$property.Value }
    $put = Invoke-WebRequest -UseBasicParsing -Uri ([string]$upload.uploadGrant.url) -Method Put `
        -Headers $putHeaders -Body $image -ContentType "image/png" -TimeoutSec 60
    if ($put.StatusCode -notin 200,201) { throw "开发 OSS 直传失败。" }
    $asset = Invoke-Api "POST" "/api/v1/media/uploads/$uploadId/complete" @{ sha256 = $sha; parts = @() }
    $mediaId = [string]$asset.mediaId
    if ([string]$asset.scope -ne "USER_PRIVATE") { throw "AI 图片未保存为 USER_PRIVATE。" }
    Invoke-MediaSupport "PROCESS"

    $conversation = Invoke-Api "POST" "/api/v1/ai/conversations" @{
        clientRequestId = [guid]::NewGuid().ToString(); entryPoint = "INDEPENDENT_CHAT"; title = "T14.2.4 验收"
    }
    $conversationId = [string]$conversation.id
    $normal = Invoke-Sse "/api/v1/ai/conversations/$conversationId/messages" @{
        clientRequestId = [guid]::NewGuid().ToString(); content = "请识别图片中的数学内容并简要说明"
        context = @{ aiQuestionMediaIds = @($mediaId); questionMediaIds = @() }
    }
    $normalRunId = [string]$normal.RunId
    $rows = @(Invoke-MySql $env:AGENT_MYSQL_DATABASE "SELECT CONCAT(id,',',JSON_UNQUOTE(JSON_EXTRACT(context_json,'$.mediaIds[0]'))) FROM ai_message WHERE conversation_id='$conversationId' AND role='USER';")
    if ($rows.Count -ne 1) { throw "正常运行未保留唯一用户消息。" }
    $parts = ([string]$rows[0]).Split(',')
    $userMessageId = $parts[0]
    if ($parts[1] -ne $mediaId) { throw "Agent 未保存当前 owner 的稳定 mediaId。" }

    $regenerated = Invoke-Sse "/api/v1/ai/conversations/$conversationId/messages/$userMessageId/regenerations" @{
        clientRequestId = $regenerationRequestId
    }
    $regeneratedRunId = [string]$regenerated.RunId
    $replay = Invoke-Sse "/api/v1/ai/conversations/$conversationId/messages/$userMessageId/regenerations" @{
        clientRequestId = $regenerationRequestId
    }
    if ([string]$replay.RunId -ne $regeneratedRunId) { throw "重新生成幂等重试返回了不同 run。" }
    $counts = @(Invoke-MySql $env:AGENT_MYSQL_DATABASE "SELECT CONCAT((SELECT COUNT(*) FROM ai_message WHERE conversation_id='$conversationId' AND role='USER'),',',(SELECT COUNT(*) FROM ai_message WHERE conversation_id='$conversationId' AND role='ASSISTANT'),',',(SELECT COUNT(*) FROM ai_run WHERE conversation_id='$conversationId'),',',(SELECT COUNT(*) FROM ai_run WHERE conversation_id='$conversationId' AND regeneration_request_id='$regenerationRequestId'));" )
    if ([string]$counts[0] -ne "1,2,2,1") {
        throw "重新生成事实数量不正确：$([string]$counts[0])"
    }

    $before = Invoke-Api "GET" "/api/v1/ai/preferences/explanation" $null
    if ($before.value -ne "GUIDED_STEP_BY_STEP") { throw "未设置偏好的默认值不正确。" }
    $updated = Invoke-Api "PUT" "/api/v1/ai/preferences/explanation" @{
        clientRequestId = $preferenceRequestId; value = "COMPLETE_SOLUTION"; expectedRevision = $before.revision
    }
    $replayedPreference = Invoke-Api "PUT" "/api/v1/ai/preferences/explanation" @{
        clientRequestId = $preferenceRequestId; value = "COMPLETE_SOLUTION"; expectedRevision = $before.revision
    }
    if ($updated.revision -ne $replayedPreference.revision) { throw "偏好幂等重试推进了 revision。" }
    $nextConversation = Invoke-Api "POST" "/api/v1/ai/conversations" @{
        clientRequestId = [guid]::NewGuid().ToString(); entryPoint = "INDEPENDENT_CHAT"; title = "偏好验收"
    }
    $nextConversationId = [string]$nextConversation.id
    $conversationIdForCleanup = $conversationId
    $conversationId = $nextConversationId
    $preferenceRun = Invoke-Sse "/api/v1/ai/conversations/$nextConversationId/messages" @{
        clientRequestId = [guid]::NewGuid().ToString(); content = "请计算一加一"; context = $null
    }
    $trusted = Get-Content -LiteralPath $pythonLog -Raw -ErrorAction SilentlyContinue
    if ($trusted -match 'COMPLETE_SOLUTION') { throw "运行日志不应输出可信偏好正文。" }
    $conversationId = $conversationIdForCleanup
    $extraConversationId = $nextConversationId

    [pscustomobject]@{
        gatewayReady = $true; mediaReady = $true; runtimeReady = $true
        ownerId = $ownerId; mediaId = $mediaId; mediaScope = "USER_PRIVATE"; mediaStatus = "READY"
        conversationId = $conversationId; userMessages = 1; assistantMessages = 2; runs = 2
        regenerationRunId = $regeneratedRunId; regenerationReplaySameRun = $true
        preferenceValue = [string]$updated.value; preferenceRevision = [int64]$updated.revision
        nextRunCompletedAfterPreferenceUpdate = $true; sseStageTextAndTerminal = $true
        sensitivePayloadPrinted = $false
    } | ConvertTo-Json -Compress
}
finally {
    foreach ($process in @($gatewayProcess, $pythonProcess, $agentProcess, $mediaProcess)) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id; [void]$process.WaitForExit(5000) }
    }
    $agentRemaining = $null
    $redisRemaining = $null
    $mediaRemaining = $null
    try {
        if ($baselineConfirmed -and $conversationId) {
            $agentRemaining = Remove-AgentFacts
        }
    } catch { Write-Error "T14.2.4 Agent 验收事实清理失败：$($_.Exception.Message)" }
    try { if ($normalRunId -or $regeneratedRunId) { $redisRemaining = Remove-RedisFacts } }
    catch { Write-Error "T14.2.4 Redis 验收 Key 清理失败：$($_.Exception.Message)" }
    try {
        if ($uploadId -and $mediaId) { Invoke-MediaSupport "CLEANUP"; $mediaRemaining = 0 }
    } catch { Write-Error "T14.2.4 Media/OSS 验收事实清理失败：$($_.Exception.Message)" }
    if ($null -ne $agentRemaining -or $null -ne $redisRemaining -or $null -ne $mediaRemaining) {
        [pscustomobject]@{ agentRemainingFacts = $agentRemaining; redisRemainingKeys = $redisRemaining;
            mediaRemainingFacts = $mediaRemaining } | ConvertTo-Json -Compress
    }
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    Restore-ProcessVariable "MYSQL_PWD" $mysqlPasswordBefore
    foreach ($name in $managedEnvironment) { Restore-ProcessVariable $name $environmentBefore[$name] }
}
