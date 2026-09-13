[CmdletBinding()]
param(
    [int]$GatewayPort = 28080,
    [int]$LearningPort = 28084,
    [int]$AgentAccessPort = 28086,
    [int]$RuntimePort = 8000,
    [string]$PythonPath = "D:\Anaconda\envs\langgraph\python.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $env:MYSQL_USERNAME -or -not $env:MYSQL_PASSWORD -or -not $env:IAM_JWT_SECRET -or
    -not $env:AGENT_RUNTIME_CALLBACK_TOKEN -or -not $env:AGENT_ACCESS_DOMAIN_TOKEN -or
    -not $env:OPENAI_API_KEY) {
    throw "T14.3 真实验收必须通过 Invoke-WithLocalEnvironment.ps1 注入现有凭据。"
}
if ($env:AGENT_MYSQL_DATABASE -ne "edu_platform_agent_dev" -or
    $env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev") {
    throw "T14.3 只允许专用 Agent/Learning dev Schema。"
}

$ownerId = [guid]::NewGuid().ToString()
$conversationId = $null
$actionIds = [Collections.Generic.List[string]]::new()
$assessmentId = $null
$attemptId = $null
$runIds = [Collections.Generic.List[string]]::new()
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("edu-t143-" + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $tempRoot
$processes = [Collections.Generic.List[Diagnostics.Process]]::new()
$mysqlPasswordBefore = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")

function Wait-Health([string]$Url, [string]$Name) {
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        try { if ((Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2).StatusCode -eq 200) { return } }
        catch { Start-Sleep -Milliseconds 500 }
    }
    throw "$Name 未在限定时间内就绪。"
}

function Invoke-MySql([string]$Database, [string]$Sql) {
    $hostName = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "127.0.0.1" }
    $port = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "3306" }
    $rows = & mysql --batch --skip-column-names "--host=$hostName" "--port=$port" `
        "--user=$($env:MYSQL_USERNAME)" "--database=$Database" "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw "MySQL 验收操作失败。" }
    return @($rows)
}

function Start-ServiceProcess([string]$Name, [string]$File, [string[]]$Arguments) {
    $out = Join-Path $tempRoot "$Name.out.log"
    $err = Join-Path $tempRoot "$Name.err.log"
    $process = Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory (Get-Location) `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $processes.Add($process)
}

function ConvertTo-Base64Url([byte[]]$Bytes) {
    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Invoke-Api([string]$Method, [string]$Path, [AllowNull()][object]$Body) {
    $arguments = @{ Uri = "http://127.0.0.1:$GatewayPort$Path"; Method = $Method
        Headers = $script:headers; TimeoutSec = 120 }
    if ($null -ne $Body) {
        $arguments.ContentType = "application/json"
        $arguments.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    return Invoke-RestMethod @arguments
}

function Invoke-Recommendation([string]$Text) {
    $requestId = [guid]::NewGuid().ToString()
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$GatewayPort/api/v1/ai/conversations/$conversationId/messages" `
        -Method Post -Headers $script:headers -ContentType "application/json; charset=utf-8" `
        -Body (@{ clientRequestId = $requestId; content = $Text } | ConvertTo-Json -Compress) -TimeoutSec 120
    $runId = [string]$response.Headers["X-Agent-Run-Id"]
    if (-not $runId) { throw "组题 SSE 缺少 runId。" }
    $runIds.Add($runId)
    $events = @($response.Content -split "`n" | Where-Object { $_ -match '^data:' } |
        ForEach-Object { ($_ -replace '^data:\s*', '') | ConvertFrom-Json })
    $final = @($events | Where-Object eventType -eq "FINAL_RESULT")[-1]
    if (-not $final.payload.pendingPracticeAction) { throw "最终事件没有 Java 持久化的 pendingPracticeAction。" }
    $actionIds.Add([string]$final.payload.pendingPracticeAction.actionId)
    return $final.payload.pendingPracticeAction
}

function Remove-RedisFacts {
    $prefix = if ($env:AGENT_REDIS_KEY_PREFIX) { $env:AGENT_REDIS_KEY_PREFIX } else { "edu:agent:runtime:v1" }
    $args = @("-h", $(if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "127.0.0.1" }), "-p",
        $(if ($env:REDIS_PORT) { $env:REDIS_PORT } else { "6379" }), "-n", $env:AGENT_REDIS_DATABASE)
    if ($env:REDIS_USERNAME) { $args += @("--user", $env:REDIS_USERNAME) }
    if ($env:REDIS_PASSWORD) { $args += @("--pass", $env:REDIS_PASSWORD) }
    foreach ($pattern in @("$prefix`:idem`:$ownerId`:*", "$prefix`:user`:$ownerId`:*", "$prefix`:quota`:*`:$ownerId`:*")) {
        foreach ($key in @(& redis-cli @args --scan --pattern $pattern)) {
            if ($key) { & redis-cli @args DEL $key | Out-Null }
        }
    }
    return @(& redis-cli @args --scan --pattern "$prefix`:*`:$ownerId`:*").Count
}

try {
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $env:RABBITMQ_ENABLED = "false"
    $env:AGENT_INDEX_PUBLISH_ENABLED = "false"
    $env:AGENT_MESSAGE_RETENTION_ENABLED = "false"
    $env:AGENT_MEDIA_OUTBOX_PUBLISH_ENABLED = "false"
    $env:GATEWAY_SESSION_VALIDATION_ENABLED = "false"
    $env:GATEWAY_RATE_LIMIT_ENABLED = "false"
    $env:AGENT_ONLINE_RETRIEVAL_ENABLED = "true"

    $env:SERVER_PORT = [string]$LearningPort
    Start-ServiceProcess "learning" "java" @("-jar", "java-platform/learning-service/target/learning-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local", "--platform.learning.progress-buffer.enabled=false")
    Wait-Health "http://127.0.0.1:$LearningPort/actuator/health" "Learning"

    $env:SERVER_PORT = [string]$AgentAccessPort
    $env:LEARNING_SERVICE_BASE_URL = "http://127.0.0.1:$LearningPort"
    $env:AGENT_RUNTIME_BASE_URL = "http://127.0.0.1:$RuntimePort"
    $env:AGENT_ACCESS_BASE_URL = "http://127.0.0.1:$AgentAccessPort"
    Start-ServiceProcess "agent" "java" @("-jar", "java-platform/agent-access-service/target/agent-access-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local")
    Wait-Health "http://127.0.0.1:$AgentAccessPort/actuator/health" "Agent Access"

    Start-ServiceProcess "runtime" $PythonPath @("-m", "edu_agent_app")
    Wait-Health "http://127.0.0.1:$RuntimePort/health" "Python Agent"

    $env:SERVER_PORT = [string]$GatewayPort
    $env:LEARNING_SERVICE_URL = "http://127.0.0.1:$LearningPort"
    $env:AGENT_ACCESS_SERVICE_URL = "http://127.0.0.1:$AgentAccessPort"
    Start-ServiceProcess "gateway" "java" @("-jar", "java-platform/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local", "--platform.gateway.session-validation.enabled=false", "--platform.gateway.rate-limit.enabled=false")
    Wait-Health "http://127.0.0.1:$GatewayPort/actuator/health" "Gateway"

    $now = [DateTimeOffset]::UtcNow
    $claims = [ordered]@{ sub = $ownerId; iss = "edu-platform-iam"; aud = @("edu-platform-gateway")
        iat = $now.ToUnixTimeSeconds(); exp = $now.AddMinutes(20).ToUnixTimeSeconds()
        roles = @("STUDENT"); permissions = @("learning:access"); sessionVersion = 0 }
    $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes(($claims | ConvertTo-Json -Compress)))
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($env:IAM_JWT_SECRET))
    try { $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::ASCII.GetBytes("$header.$payload"))) }
    finally { $hmac.Dispose() }
    $script:headers = @{ Authorization = "Bearer $header.$payload.$signature" }

    $conversation = Invoke-Api "POST" "/api/v1/ai/conversations" @{
        clientRequestId = [guid]::NewGuid().ToString(); entryPoint = "INDEPENDENT_CHAT"; title = "T14.3 验收" }
    $conversationId = [string]$conversation.id
    $action = Invoke-Recommendation "推荐一元一次方程练习题"
    if ($action.criteria.difficulty -ne "MEDIUM" -or $action.requestedCount -ne 5 -or $action.actualCount -lt 1) {
        throw "默认难度/数量或真实候选数量不符合契约。"
    }
    $before = Invoke-MySql $env:LEARNING_MYSQL_DATABASE "SELECT COUNT(*) FROM autonomous_practice WHERE source_action_id='$($action.actionId)';"
    if ([int]([string]$before) -ne 0) { throw "确认前已创建练习。" }
    $redisAfterLoss = Remove-RedisFacts
    if ($redisAfterLoss -ne 0) { throw "随机 owner 的 Redis 运行事实未精确清理。" }
    $confirmed = Invoke-Api "POST" "/api/v1/ai/pending-practice-actions/$($action.actionId):confirm" @{
        requestId = [guid]::NewGuid().ToString(); expectedRevision = $action.revision }
    $assessmentId = [string]$confirmed.executionResult.assessmentId
    $replayed = Invoke-Api "POST" "/api/v1/ai/pending-practice-actions/$($action.actionId):confirm" @{
        requestId = [guid]::NewGuid().ToString(); expectedRevision = $action.revision }
    if ([string]$replayed.executionResult.assessmentId -ne $assessmentId) { throw "重复确认未恢复相同 assessmentId。" }
    $queried = Invoke-Api "GET" "/api/v1/ai/pending-practice-actions/$($action.actionId)?requestId=$([guid]::NewGuid())" $null
    if ([string]$queried.executionResult.assessmentId -ne $assessmentId) { throw "查询未返回稳定执行结果。" }
    $entry = Invoke-Api "GET" "/api/v1/learning/autonomous-practices/$($confirmed.executionResult.autonomousPracticeId)/entry?requestId=$([guid]::NewGuid())" $null
    if ([string]$entry.assessmentId -ne $assessmentId -or [string]$entry.startRoute -ne "/practice/$assessmentId") {
        throw "本人自主练习入口不稳定。"
    }

    $paper = Invoke-Api "GET" "/api/v1/learning/assessments/$assessmentId" $null
    if ($null -ne $paper.courseId -or (($paper | ConvertTo-Json -Depth 12) -match 'correctAnswer|analysis')) {
        throw "自主练习归属或提交前答案安全策略不正确。"
    }
    $draft = Invoke-Api "POST" "/api/v1/learning/assessments/$assessmentId/attempts" @{
        clientRequestId = [guid]::NewGuid().ToString() }
    $attemptId = [string]$draft.id
    $answer = @(@{ assessmentQuestionId = [string]$paper.questions[0].id; answer = @("A") })
    $saved = Invoke-Api "PUT" "/api/v1/learning/attempts/$attemptId/draft" @{
        clientRequestId = [guid]::NewGuid().ToString(); expectedRevision = $draft.revision; answers = $answer }
    $result = Invoke-Api "POST" "/api/v1/learning/attempts/$attemptId/submission" @{
        clientRequestId = [guid]::NewGuid().ToString(); expectedRevision = $saved.revision; answers = $answer }
    if (-not $result.gradeVisible -or -not $result.answerVisible) { throw "提交后自动评分或答案公布未完成。" }

    $courseIsolation = Invoke-MySql $env:LEARNING_MYSQL_DATABASE "SELECT CONCAT((SELECT COUNT(*) FROM assessment WHERE id='$assessmentId' AND ownership_type='AUTONOMOUS' AND course_id IS NULL AND owner_teacher_id IS NULL),',',(SELECT COUNT(*) FROM assessment_assignment WHERE assessment_id='$assessmentId'));"
    if ([string]$courseIsolation -ne "1,0") { throw "自主练习泄漏到课程归属或任务分配。" }

    $cancelAction = Invoke-Recommendation "推荐函数练习题"
    $replacementAction = Invoke-Recommendation "推荐几何练习题"
    $superseded = Invoke-Api "GET" "/api/v1/ai/pending-practice-actions/$($cancelAction.actionId)?requestId=$([guid]::NewGuid())" $null
    if ($superseded.status -ne "CANCELLED") { throw "新条件没有使旧待确认动作失效。" }
    $cancelled = Invoke-Api "POST" "/api/v1/ai/pending-practice-actions/$($replacementAction.actionId):cancel" @{
        requestId = [guid]::NewGuid().ToString(); expectedRevision = $replacementAction.revision }
    if ($cancelled.status -ne "CANCELLED") { throw "取消状态不正确。" }
    $expiredAction = Invoke-Recommendation "推荐概率练习题"
    Invoke-MySql $env:AGENT_MYSQL_DATABASE "UPDATE ai_pending_action SET expires_at=DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id='$($expiredAction.actionId)';" | Out-Null
    try {
        Invoke-Api "POST" "/api/v1/ai/pending-practice-actions/$($expiredAction.actionId):confirm" @{
            requestId = [guid]::NewGuid().ToString(); expectedRevision = $expiredAction.revision } | Out-Null
        throw "过期动作被错误确认。"
    } catch { if ($_.Exception.Response.StatusCode.value__ -ne 409) { throw } }

    $concurrentAction = Invoke-Recommendation "推荐一元一次方程练习题"
    $confirmUrl = "http://127.0.0.1:$GatewayPort/api/v1/ai/pending-practice-actions/$($concurrentAction.actionId):confirm"
    $jobs = 1..2 | ForEach-Object {
        $body = @{ requestId = [guid]::NewGuid().ToString(); expectedRevision = $concurrentAction.revision } |
            ConvertTo-Json -Compress
        Start-Job -ScriptBlock {
            param($Url, $Headers, $Body)
            try {
                $value = Invoke-RestMethod -Uri $Url -Method Post -Headers $Headers -ContentType "application/json" `
                    -Body $Body -TimeoutSec 120
                [pscustomobject]@{ statusCode = 200; status = [string]$value.status }
            } catch {
                $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
                [pscustomobject]@{ statusCode = $code; status = "ERROR" }
            }
        } -ArgumentList $confirmUrl, $script:headers, $body
    }
    $concurrentResults = @($jobs | Receive-Job -Wait -AutoRemoveJob)
    if (-not ($concurrentResults.statusCode -contains 200) -or
        @($concurrentResults | Where-Object { $_.statusCode -notin @(200, 409) }).Count -gt 0) {
        throw "并发确认没有收敛为成功或 revision 冲突。"
    }
    $concurrentCount = Invoke-MySql $env:LEARNING_MYSQL_DATABASE "SELECT COUNT(*) FROM autonomous_practice WHERE source_action_id='$($concurrentAction.actionId)';"
    if ([int]([string]$concurrentCount) -ne 1) { throw "并发确认创建了零个或多个练习。" }

    # 精确模拟 Learning 已提交事务但 Agent Access 未收到响应：动作停在 EXECUTING，再由公开确认 API 查询恢复。
    $unknownAction = Invoke-Recommendation "推荐一元一次方程练习题"
    $unknownRequestId = [guid]::NewGuid().ToString()
    Invoke-MySql $env:AGENT_MYSQL_DATABASE "UPDATE ai_pending_action SET status='EXECUTING',pending_slot=NULL,confirmation_request_id='$unknownRequestId',revision=revision+1,updated_at=NOW(6) WHERE id='$($unknownAction.actionId)' AND status='PENDING';" | Out-Null
    $internalHeaders = @{ "X-Internal-Caller" = "agent-access-service";
        "X-Internal-Service-Token" = $env:AGENT_ACCESS_DOMAIN_TOKEN; "X-Trace-Id" = "trace-t143-unknown" }
    $createdButUnknown = Invoke-RestMethod -Uri "http://127.0.0.1:$LearningPort/internal/v1/agent-access/autonomous-practices" `
        -Method Post -Headers $internalHeaders -ContentType "application/json" -TimeoutSec 120 -Body (@{
            actionId = $unknownAction.actionId; ownerUserId = $ownerId; criteria = $unknownAction.criteria
            candidates = @($unknownAction.candidateQuestions | ForEach-Object {
                @{ questionId = $_.questionId; questionVersionId = $_.questionVersionId }
            })
        } | ConvertTo-Json -Depth 8 -Compress)
    $recoveredUnknown = Invoke-Api "POST" "/api/v1/ai/pending-practice-actions/$($unknownAction.actionId):confirm" @{
        requestId = [guid]::NewGuid().ToString(); expectedRevision = $unknownAction.revision }
    if ([string]$recoveredUnknown.executionResult.assessmentId -ne [string]$createdButUnknown.assessmentId) {
        throw "依赖响应未知后未按 actionId 恢复 Learning 已创建结果。"
    }

    [pscustomobject]@{ runtimeSkill = "public-practice-generation@1.0.0"; defaultDifficulty = "MEDIUM"
        requestedCount = 5; actualCount = [int]$action.actualCount; pendingProjected = $true
        confirmedOnce = $true; replayedAssessment = $true; autonomousCourseIdNull = $true
        attemptSavedSubmittedAndScored = $true; answerVisibleOnlyAfterSubmission = $true
        stableQueries = $true; redisLossTolerated = $true; shortagePreserved = ($action.actualCount -lt 5)
        courseAndGradebookIsolated = $true; superseded = $true; cancelled = $true; expiredRejected = $true
        concurrentCreatedOnce = $true; unknownResultRecovered = $true; sensitivePayloadPrinted = $false } |
        ConvertTo-Json -Compress
}
finally {
    foreach ($process in $processes) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id; [void]$process.WaitForExit(5000) }
    }
    $learningRemaining = $null; $agentRemaining = $null; $redisRemaining = $null
    try {
        if ($actionIds.Count -gt 0) {
            $quotedActionIds = ($actionIds | ForEach-Object { "'$_'" }) -join ","
            $createdAssessmentIds = @(Invoke-MySql $env:LEARNING_MYSQL_DATABASE "SELECT assessment_id FROM autonomous_practice WHERE source_action_id IN ($quotedActionIds);")
            foreach ($createdAssessmentId in $createdAssessmentIds) {
                Invoke-MySql $env:LEARNING_MYSQL_DATABASE @"
START TRANSACTION;
DELETE FROM assessment_participant WHERE assessment_id='$createdAssessmentId';
DELETE FROM assessment_answer WHERE attempt_id IN (SELECT id FROM assessment_attempt WHERE assessment_id='$createdAssessmentId');
DELETE FROM assessment_attempt WHERE assessment_id='$createdAssessmentId';
DELETE FROM autonomous_practice WHERE assessment_id='$createdAssessmentId';
DELETE FROM assessment_question WHERE assessment_id='$createdAssessmentId';
DELETE FROM assessment WHERE id='$createdAssessmentId';
COMMIT;
"@ | Out-Null
            }
            $learningRemaining = [int]([string](Invoke-MySql $env:LEARNING_MYSQL_DATABASE "SELECT COUNT(*) FROM autonomous_practice WHERE source_action_id IN ($quotedActionIds);"))
        }
        if ($conversationId) {
            Invoke-MySql $env:AGENT_MYSQL_DATABASE @"
START TRANSACTION;
DELETE FROM ai_pending_action_audit WHERE action_id IN (SELECT id FROM ai_pending_action WHERE conversation_id='$conversationId');
DELETE FROM ai_pending_action WHERE conversation_id='$conversationId';
DELETE FROM ai_tool_call WHERE run_id IN (SELECT id FROM ai_run WHERE conversation_id='$conversationId');
DELETE FROM ai_run_cancellation WHERE run_id IN (SELECT id FROM ai_run WHERE conversation_id='$conversationId');
DELETE FROM ai_run_event WHERE run_id IN (SELECT id FROM ai_run WHERE conversation_id='$conversationId');
DELETE FROM ai_usage_record WHERE run_id IN (SELECT id FROM ai_run WHERE conversation_id='$conversationId');
DELETE FROM ai_run WHERE conversation_id='$conversationId';
DELETE FROM ai_message WHERE conversation_id='$conversationId';
DELETE FROM ai_conversation WHERE id='$conversationId';
COMMIT;
"@ | Out-Null
            $agentRemaining = [int]([string](Invoke-MySql $env:AGENT_MYSQL_DATABASE "SELECT COUNT(*) FROM ai_conversation WHERE id='$conversationId';"))
        }
        $redisRemaining = Remove-RedisFacts
    } catch { Write-Error "T14.3 精确清理失败：$($_.Exception.Message)" }
    [pscustomobject]@{ learningRemaining = $learningRemaining; agentRemaining = $agentRemaining
        redisRemaining = $redisRemaining } | ConvertTo-Json -Compress
    if ($null -eq $mysqlPasswordBefore) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    else { $env:MYSQL_PWD = $mysqlPasswordBefore }
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
