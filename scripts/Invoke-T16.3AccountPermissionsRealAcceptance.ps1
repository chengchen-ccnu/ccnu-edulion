#requires -Version 7.0
[CmdletBinding()]
param()

# 真正Gateway/IAM/Course/Learning/Media验收。所有凭据仅由DPAPI包装器注入，生产数据/无关待发事件存在时停止。
# IAM/Media由JUnit受控上下文启动以注入时序/未知结果故障；其他服务独立JAR。finally只停止本轮进程。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repositoryRoot '.m2/t16-3-acceptance'
$mavenPom = Join-Path $repositoryRoot 'java-platform/pom.xml'
$mavenRepository = Join-Path $repositoryRoot '.m2/repository'

& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    foreach ($schema in @(@{ Variable='IAM_MYSQL_DATABASE'; Required='edu_platform_iam_dev' },
            @{ Variable='COURSE_MYSQL_DATABASE'; Required='edu_platform_course_dev' },
            @{ Variable='LEARNING_MYSQL_DATABASE'; Required='edu_platform_learning_dev' },
            @{ Variable='MEDIA_MYSQL_DATABASE'; Required='edu_platform_media_dev' })) {
        if ([Environment]::GetEnvironmentVariable($schema.Variable, 'Process') -ne $schema.Required) {
            throw 'T16.3 requires the explicitly named development schemas.'
        }
    }
    if ($env:IAM_VERIFICATION_DELIVERY_MODE -ne 'smtp' -or [string]::IsNullOrWhiteSpace($env:IAM_SMTP_PASSWORD) -or
            [string]::IsNullOrWhiteSpace($env:COURSE_SUSPENSION_IAM_TOKEN) -or
            [string]::IsNullOrWhiteSpace($env:OSS_ACCESS_KEY_SECRET) -or $env:LEARNING_REDIS_DATABASE -ne '4') {
        throw 'T16.3 requires encrypted SMTP/OSS/internal credentials and Learning Redis DB 4.'
    }
    # 先读实际迁移和待发事实，再启动会执行Flyway/发布器的服务；不打印凭据或任何身份值。
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    try {
        foreach ($check in @(@{Schema='edu_platform_iam_dev'; Versions=@('4','5'); Outbox='iam_outbox_event'},
                @{Schema='edu_platform_course_dev'; Versions=@('7'); Outbox='outbox_event'},
                @{Schema='edu_platform_learning_dev'; Versions=@('19'); Outbox='learning_outbox_event'})) {
            $version = & mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME "--database=$($check.Schema)" --batch --skip-column-names --execute='SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1'
            if ($LASTEXITCODE -ne 0 -or $version -notin $check.Versions) { throw 'Unexpected physical migration version; no data changes allowed.' }
            $pending = & mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME "--database=$($check.Schema)" --batch --skip-column-names --execute="SELECT COUNT(*) FROM $($check.Outbox) WHERE status='PENDING'"
            if ($LASTEXITCODE -ne 0) { throw 'Outbox inspection failed; no data changes allowed.' }
            if ($check.Schema -eq 'edu_platform_learning_dev') {
                # 已观察到本机Learning旧Outbox索引/主键不一致。关闭全量发布，只经既有发布器发布本轮精确报名事件。
                Write-Output "$($check.Schema) physical V$version checked; global publisher remains disabled."
            }
            elseif ($pending -ne '0') { throw 'Unrelated pending outbox facts exist; preserve them and stop acceptance.' }
            else { Write-Output "$($check.Schema) physical V$version checked; no pending outbox." }
        }
    }
    finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    foreach ($port in @(18080,18081,18082,18083,18084)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Acceptance port $port is occupied." }
    }
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl media-service,course-service -am -DskipTests package *> (Join-Path $logDirectory 'package.log')
    if ($LASTEXITCODE -ne 0) { throw 'Package failed; inspect .m2/t16-3-acceptance/package.log.' }
    $previous = @{}
    $settings = @{
        IAM_SERVICE_URL='http://127.0.0.1:18081'; IAM_SERVICE_BASE_URL='http://127.0.0.1:18081'
        COURSE_SERVICE_URL='http://127.0.0.1:18082'; COURSE_SERVICE_BASE_URL='http://127.0.0.1:18082'
        MEDIA_SERVICE_URL='http://127.0.0.1:18083'; MEDIA_SERVICE_BASE_URL='http://127.0.0.1:18083'
        LEARNING_SERVICE_URL='http://127.0.0.1:18084'; LEARNING_SERVICE_BASE_URL='http://127.0.0.1:18084'
        IAM_SEED_ENABLED='false'; COURSE_SEED_ENABLED='false'; IAM_REVIEW_RETENTION_ENABLED='false'
        IAM_FIRST_ADMINISTRATOR_ENABLED='false'; RABBITMQ_ENABLED='true'; OSS_ENABLED='true'
        T163_REAL_ACCEPTANCE_ENABLED='true'; GATEWAY_RATE_LIMIT_KEY_PREFIX=("edu:gateway:rate:t163:"+[guid]::NewGuid().ToString())
    }
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        [Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')
    }
    $processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
    try {
        $java = (Get-Command java -ErrorAction Stop).Source
        foreach ($service in @(@{Module='course-service';Port=18082;Args=@()},
                @{Module='learning-service';Port=18084;Args=@('--platform.learning.import.messaging-enabled=false',
                    '--platform.learning.course-content-messaging.enabled=false','--platform.learning.progress-buffer.worker-delay=1h',
                    '--platform.learning.assessment-settlement-poll-delay=1h')},
                @{Module='gateway-service';Port=18080;Args=@()})) {
            $jar = Join-Path $repositoryRoot "java-platform/$($service.Module)/target/$($service.Module)-0.1.0-SNAPSHOT.jar"
            $process = Start-Process -FilePath $java -ArgumentList (@('-jar',$jar,'--spring.profiles.active=local',"--server.port=$($service.Port)")+$service.Args) `
                -WorkingDirectory $repositoryRoot -RedirectStandardOutput (Join-Path $logDirectory "$($service.Module).log") `
                -RedirectStandardError (Join-Path $logDirectory "$($service.Module)-error.log") -WindowStyle Hidden -PassThru
            $processes.Add($process)
            $ready = $false
            for ($attempt=0; $attempt -lt 100; $attempt++) {
                Start-Sleep -Milliseconds 500
                if ($process.HasExited) { throw "Acceptance $($service.Module) stopped before readiness." }
                try {
                    $health = Invoke-WebRequest -Uri "http://127.0.0.1:$($service.Port)/actuator/health" -TimeoutSec 2
                    if ($health.StatusCode -eq 200) { $ready=$true;break }
                } catch { }
            }
            if (-not $ready) { throw "Acceptance $($service.Module) not healthy within bounded wait." }
        }
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl media-service -am '-Dtest=T163AccountPermissionsRealAcceptanceTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test *> (Join-Path $logDirectory 'real-test.log')
        if ($LASTEXITCODE -ne 0) { throw 'T16.3 real acceptance failed; inspect .m2/t16-3-acceptance logs, preserve unrelated data.' }
        Write-Output 'T16.3 real Gateway/IAM/Course/Learning/MySQL/Redis/RabbitMQ/SMTP acceptance passed.'
    }
    finally {
        foreach ($process in $processes) {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue;$process.WaitForExit(10000) | Out-Null }
        }
        foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    }
}
