#requires -Version 7.0
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$logDirectory=Join-Path $repositoryRoot '.m2/t16a-real'
$mavenPom=Join-Path $repositoryRoot 'java-platform/pom.xml'
$mavenRepository=Join-Path $repositoryRoot '.m2/repository'
# 所有秘密由DPAPI包装器进入环境；只允许已存在专用开发Schema，验收不自动创建库/重放历史。
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    foreach($domain in @('COURSE','LEARNING','OPERATION','MEDIA')) {
        if([Environment]::GetEnvironmentVariable("${domain}_MYSQL_DATABASE",'Process') -ne "edu_platform_$($domain.ToLower())_dev") {throw 'T16A requires explicitly named dedicated development schemas.'}
    }
    foreach($name in @('OPERATION_INTERNAL_SERVICE_TOKEN','COURSE_SUSPENSION_IAM_TOKEN','RABBITMQ_PASSWORD','OSS_ACCESS_KEY_ID')) {
        if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))) {throw 'Initialize encrypted local credentials before T16A acceptance.'}
    }
    foreach($port in @(18080,18082,18083,18084,18085)) {if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Acceptance port $port occupied."}}
    $previousPassword=[Environment]::GetEnvironmentVariable('MYSQL_PWD','Process')
    try {
        $env:MYSQL_PWD=$env:MYSQL_PASSWORD
        foreach($entry in @(@('course','outbox_event'),@('learning','learning_outbox_event'))) {
            $pending=& mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME --batch --skip-column-names --execute="SELECT COUNT(*) FROM edu_platform_$($entry[0])_dev.$($entry[1]) WHERE status='PENDING'"
            if($LASTEXITCODE -ne 0 -or $pending -ne '0'){throw 'Stop before publishers: historical PENDING events exist.'}
        }
    } finally {[Environment]::SetEnvironmentVariable('MYSQL_PWD',$previousPassword,'Process')}
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl gateway-service,course-service,media-service,learning-service -am -DskipTests package *> (Join-Path $logDirectory 'package.log')
    if($LASTEXITCODE -ne 0){throw 'T16A acceptance package failed.'}
    $random=[Guid]::NewGuid().ToString('N')
    $settings=@{
        COURSE_SERVICE_URL='http://127.0.0.1:18082';COURSE_SERVICE_BASE_URL='http://127.0.0.1:18082'
        LEARNING_SERVICE_URL='http://127.0.0.1:18084';LEARNING_SERVICE_BASE_URL='http://127.0.0.1:18084'
        MEDIA_SERVICE_URL='http://127.0.0.1:18083';MEDIA_SERVICE_BASE_URL='http://127.0.0.1:18083'
        OPERATION_SERVICE_URL='http://127.0.0.1:18085';RABBITMQ_ENABLED='true';COURSE_SEED_ENABLED='false'
        LEARNING_SUSPENSION_MESSAGING_ENABLED='false';LEARNING_COURSE_CONTENT_MESSAGING_ENABLED='false';LEARNING_PROGRESS_BUFFER_ENABLED='false'
        OPERATION_MESSAGING_ENABLED='false';OPERATION_HOT_MESSAGING_ENABLED='true';OPERATION_HOT_WORKER_ENABLED='false'
        OPERATION_HOT_QUEUE="edu.operation.t16a.$random";OPERATION_HOT_NAMESPACE="edu:operation:t16a:$random"
        OPERATION_HOT_TRACE_PREFIX="t16a_$($random.Substring(0,8))";T16A_REAL_ACCEPTANCE_ENABLED='true'
        COURSE_MEDIA_OUTBOX_MAX_ATTEMPTS='3';LEARNING_OUTBOX_MAX_ATTEMPTS='3';MEDIA_ACCESS_GRANT_TTL='20s'
        MEDIA_MESSAGING_ENABLED='false';MEDIA_WORKER_ENABLED='false';MEDIA_MAINTENANCE_ENABLED='false';OSS_ENABLED='true'
    }
    $previous=@{};$processes=[System.Collections.Generic.List[object]]::new()
    try {
        foreach($name in $settings.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
        foreach($entry in @(@('media',18083),@('course',18082),@('learning',18084),@('gateway',18080))) {
            $service=$entry[0];$port=$entry[1];$jar=Join-Path $repositoryRoot "java-platform/$service-service/target/$service-service-0.1.0-SNAPSHOT.jar"
            $arguments=@('-jar',$jar,'--spring.profiles.active=local',"--server.port=$port")
            if($service -eq 'course'){$arguments+='--platform.course.enrollment-messaging.enabled=false'}
            if($service -eq 'learning'){$arguments+='--platform.learning.import.consumer-enabled=false';$arguments+='--platform.learning.assessment-settlement-poll-delay=1h'}
            $process=Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $repositoryRoot -RedirectStandardOutput (Join-Path $logDirectory "$service.log") -RedirectStandardError (Join-Path $logDirectory "$service-error.log") -WindowStyle Hidden -PassThru
            $processes.Add($process);$ready=$false
            for($attempt=0;$attempt -lt 100;$attempt++) {
                Start-Sleep -Milliseconds 400
                if($process.HasExited){throw "$service stopped before readiness; inspect .m2/t16a-real."}
                try {$response=Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2;if($response.StatusCode -eq 200){$ready=$true;break}}catch{}
            }
            if(-not $ready){throw "$service readiness timeout; inspect .m2/t16a-real."}
        }
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl operation-service -am '-Dtest=T16AHotCoursesRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $logDirectory 'real-test.log')
        if($LASTEXITCODE -ne 0){throw 'T16A real acceptance failed; inspect .m2/t16a-real/real-test.log.'}
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl learning-service -am '-Dtest=HotLearningProducerRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $logDirectory 'producer-test.log')
        if($LASTEXITCODE -ne 0){throw 'T16A real producer acceptance failed; inspect .m2/t16a-real/producer-test.log.'}
        Write-Output 'T16A real Gateway/Course/Learning/Media/MySQL/Redis/RabbitMQ acceptance passed, precise cleanup completed.'
    } finally {
        foreach($process in $processes){if(-not $process.HasExited){Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue;$process.WaitForExit(10000)|Out-Null}}
        foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    }
}
