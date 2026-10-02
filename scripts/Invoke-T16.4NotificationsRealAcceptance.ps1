#requires -Version 7.0
[CmdletBinding()]
param([switch]$Cleanup)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$logDirectory=Join-Path $repositoryRoot '.m2/t16-4-acceptance'
$mavenPom=Join-Path $repositoryRoot 'java-platform/pom.xml'
$mavenRepository=Join-Path $repositoryRoot '.m2/repository'
# 显式local初始化后才运行V1；秘密仅由DPAPI包装器进入子进程环境，不拼进启动参数或日志。
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    foreach($domain in @('IAM','COURSE','LEARNING','OPERATION')) {
        if([Environment]::GetEnvironmentVariable("${domain}_MYSQL_DATABASE",'Process') -ne "edu_platform_$($domain.ToLower())_dev") {throw 'T16.4 requires explicitly named dedicated development schemas.'}
    }
    foreach($secretName in @('OPERATION_INTERNAL_SERVICE_TOKEN','COURSE_SUSPENSION_IAM_TOKEN','RABBITMQ_PASSWORD')) {
        if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($secretName,'Process'))) {throw 'Initialize encrypted local service credentials before T16.4 acceptance.'}
    }
    $requiredPorts=if($Cleanup){@(18085)}else{@(18080,18081,18082,18084,18085)}
    foreach($port in $requiredPorts) {if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Acceptance port $port is occupied."}}
    $previousPassword=[Environment]::GetEnvironmentVariable('MYSQL_PWD','Process')
    try {
        $env:MYSQL_PWD=$env:MYSQL_PASSWORD
        # Cleanup不启动发布器，允许精确删除中断Fixture的PENDING；正常验收仍必须先证明没有历史PENDING。
        if(-not $Cleanup){foreach($entry in @(@('course','outbox_event'),@('learning','learning_outbox_event'),@('iam','iam_outbox_event'))) {
            $sql="SELECT COUNT(*) FROM edu_platform_$($entry[0])_dev.$($entry[1]) WHERE status='PENDING'"
            $pending=& mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME --batch --skip-column-names --execute=$sql
            if($LASTEXITCODE -ne 0 -or $pending -ne '0'){throw 'Acceptance stops before starting publishers when preexisting PENDING events exist.'}
        }}
    } finally {[Environment]::SetEnvironmentVariable('MYSQL_PWD',$previousPassword,'Process')}
    & (Join-Path $PSScriptRoot 'Initialize-OperationLocalDatabase.ps1') -Profile local
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl gateway-service,iam-service,course-service,learning-service -am -DskipTests package *> (Join-Path $logDirectory 'package.log')
    if($LASTEXITCODE -ne 0){throw 'Acceptance package failed; inspect .m2/t16-4-acceptance/package.log.'}
    $settings=@{
        IAM_SERVICE_URL='http://127.0.0.1:18081';IAM_SERVICE_BASE_URL='http://127.0.0.1:18081'
        COURSE_SERVICE_URL='http://127.0.0.1:18082';COURSE_SERVICE_BASE_URL='http://127.0.0.1:18082'
        LEARNING_SERVICE_URL='http://127.0.0.1:18084';LEARNING_SERVICE_BASE_URL='http://127.0.0.1:18084'
        OPERATION_SERVICE_URL='http://127.0.0.1:18085';RABBITMQ_ENABLED='true'
        IAM_REVIEW_MESSAGING_ENABLED='true';IAM_REVIEW_RETENTION_ENABLED='false';IAM_SEED_ENABLED='false';COURSE_SEED_ENABLED='false'
        LEARNING_SUSPENSION_MESSAGING_ENABLED='false';LEARNING_COURSE_CONTENT_MESSAGING_ENABLED='false';LEARNING_PROGRESS_BUFFER_ENABLED='false'
        OPERATION_NOTIFICATION_QUEUE="edu.operation.t164.$([Guid]::NewGuid().ToString('N'))";T164_REAL_ACCEPTANCE_ENABLED='true'
        GATEWAY_RATE_LIMIT_KEY_PREFIX="edu:gateway:rate:t164:$([Guid]::NewGuid().ToString('N'))"
    }
    $previous=@{};$processes=[System.Collections.Generic.List[object]]::new()
    try {
        foreach($name in $settings.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
        $previous['T164_CLEANUP_ONLY']=$env:T164_CLEANUP_ONLY
        $env:T164_CLEANUP_ONLY=if($Cleanup){'true'}else{'false'}
        $serviceEntries=if($Cleanup){@()}else{@(@('iam',18081),@('course',18082),@('learning',18084),@('gateway',18080))}
        foreach($entry in $serviceEntries) {
            $service=$entry[0];$port=$entry[1];$jar=Join-Path $repositoryRoot "java-platform/$service-service/target/$service-service-0.1.0-SNAPSHOT.jar"
            $arguments=@('-jar',$jar,'--spring.profiles.active=local',"--server.port=$port")
            if($service -eq 'iam'){$arguments+='--management.health.mail.enabled=false';$arguments+='--platform.iam.account-coordination.enabled=false'}
            # 原生产默认仍消费；此次验收只启用本轮Operation独立队列，禁止抢占既有消费者。
            if($service -eq 'course'){$arguments+='--platform.course.enrollment-messaging.enabled=false'}
            if($service -eq 'learning'){$arguments+='--platform.learning.import.consumer-enabled=false';$arguments+='--platform.learning.assessment-settlement-poll-delay=1h'}
            $process=Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $repositoryRoot -RedirectStandardOutput (Join-Path $logDirectory "$service.log") -RedirectStandardError (Join-Path $logDirectory "$service-error.log") -WindowStyle Hidden -PassThru
            $processes.Add($process);$ready=$false
            if($service -eq 'learning'){$previous['T164_LEARNING_PID']=$env:T164_LEARNING_PID;$env:T164_LEARNING_PID=$process.Id.ToString()}
            for($attempt=0;$attempt -lt 100;$attempt++) {
                Start-Sleep -Milliseconds 400
                if($process.HasExited){throw "$service stopped before readiness; inspect acceptance logs."}
                try {$readiness=if($service -in @('iam','gateway')){'/api/v1/auth/channel-capabilities'}else{'/actuator/health'};$response=Invoke-WebRequest -Uri "http://127.0.0.1:$port$readiness" -TimeoutSec 2;if($response.StatusCode -eq 200){$ready=$true;break}}catch{}
            }
            if(-not $ready){throw "$service readiness timeout; inspect acceptance logs."}
        }
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl operation-service -am '-Dtest=T164NotificationsRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $logDirectory 'real-test.log')
        if($LASTEXITCODE -ne 0){throw 'T16.4 real acceptance failed; inspect .m2/t16-4-acceptance/real-test.log.'}
        if($Cleanup){Write-Output 'T16.4 interrupted exact-ID fixture cleanup passed; this does not count as business acceptance.'}
        else{Write-Output 'T16.4 real Gateway/domain API/MySQL/RabbitMQ acceptance passed with exact fixture cleanup.'}
    } finally {
        foreach($process in $processes){if(-not $process.HasExited){Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue;$process.WaitForExit(10000)|Out-Null}}
        foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    }
}
