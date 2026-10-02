#requires -Version 7.0
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repositoryRoot '.m2/t16-2-acceptance'
$mavenPom = Join-Path $repositoryRoot 'java-platform/pom.xml'
$mavenRepository = Join-Path $repositoryRoot '.m2/repository'

# PowerShell 7 reads this UTF-8 script as UTF-8 on Windows; no Windows PowerShell 5.1 fallback is claimed.
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if ($env:IAM_MYSQL_DATABASE -ne 'edu_platform_iam_dev' -or
            $env:MEDIA_MYSQL_DATABASE -ne 'edu_platform_media_dev') {
        throw 'T16.2 acceptance requires the dedicated IAM and Media development schemas.'
    }
    if ([string]::IsNullOrWhiteSpace($env:COURSE_MEDIA_AUTH_SECRET) -or
            [string]::IsNullOrWhiteSpace($env:OSS_ACCESS_KEY_ID) -or
            [string]::IsNullOrWhiteSpace($env:OSS_ACCESS_KEY_SECRET) -or
            [string]::IsNullOrWhiteSpace($env:RABBITMQ_PASSWORD)) {
        throw 'T16.2 acceptance requires encrypted local Media authorization, OSS and RabbitMQ credentials.'
    }
    foreach ($port in @(18080, 18081, 18083)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
            throw "T16.2 acceptance port $port is already occupied."
        }
    }
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl gateway-service,iam-service -am -DskipTests package *> (Join-Path $logDirectory 'package.log')
    if ($LASTEXITCODE -ne 0) { throw 'Gateway/IAM package failed; inspect .m2/t16-2-acceptance/package.log.' }

    $iamJar = Join-Path $repositoryRoot 'java-platform/iam-service/target/iam-service-0.1.0-SNAPSHOT.jar'
    $gatewayJar = Join-Path $repositoryRoot 'java-platform/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar'
    $java = (Get-Command java -ErrorAction Stop).Source
    $previousEnvironment = @{}
    foreach ($name in @('IAM_SERVICE_URL','MEDIA_SERVICE_URL','MEDIA_SERVICE_BASE_URL',
            'IAM_REVIEW_MESSAGING_ENABLED','IAM_REVIEW_RETENTION_ENABLED','IAM_SEED_ENABLED',
            'RABBITMQ_ENABLED','OSS_ENABLED','T162_REAL_ACCEPTANCE_ENABLED')) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $iamProcess = $null
    $gatewayProcess = $null
    try {
        $env:IAM_SERVICE_URL = 'http://127.0.0.1:18081'
        $env:MEDIA_SERVICE_URL = 'http://127.0.0.1:18083'
        $env:MEDIA_SERVICE_BASE_URL = 'http://127.0.0.1:18083'
        $env:IAM_REVIEW_MESSAGING_ENABLED = 'true'
        $env:IAM_REVIEW_RETENTION_ENABLED = 'false'
        $env:IAM_SEED_ENABLED = 'false'
        $env:RABBITMQ_ENABLED = 'true'
        $env:OSS_ENABLED = 'true'
        $env:T162_REAL_ACCEPTANCE_ENABLED = 'true'
        $iamProcess = Start-Process -FilePath $java -ArgumentList @('-jar', $iamJar,
            '--spring.profiles.active=local', '--server.port=18081') -WorkingDirectory $repositoryRoot `
            -RedirectStandardOutput (Join-Path $logDirectory 'iam.log') `
            -RedirectStandardError (Join-Path $logDirectory 'iam-error.log') -WindowStyle Hidden -PassThru
        $ready = $false
        for ($attempt = 0; $attempt -lt 80; $attempt++) {
            Start-Sleep -Milliseconds 500
            if ($iamProcess.HasExited) { throw 'IAM stopped before readiness; inspect the acceptance logs.' }
            try {
                $health = Invoke-WebRequest -Uri 'http://127.0.0.1:18081/api/v1/auth/channel-capabilities' -TimeoutSec 2
                if ($health.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
        }
        if (-not $ready) { throw 'IAM was not healthy within 40 seconds.' }
        $gatewayProcess = Start-Process -FilePath $java -ArgumentList @('-jar', $gatewayJar,
            '--spring.profiles.active=local', '--server.port=18080') -WorkingDirectory $repositoryRoot `
            -RedirectStandardOutput (Join-Path $logDirectory 'gateway.log') `
            -RedirectStandardError (Join-Path $logDirectory 'gateway-error.log') -WindowStyle Hidden -PassThru
        $ready = $false
        for ($attempt = 0; $attempt -lt 80; $attempt++) {
            Start-Sleep -Milliseconds 500
            if ($gatewayProcess.HasExited) { throw 'Gateway stopped before readiness; inspect the acceptance logs.' }
            try {
                $health = Invoke-WebRequest -Uri 'http://127.0.0.1:18080/api/v1/auth/channel-capabilities' -TimeoutSec 2
                if ($health.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
        }
        if (-not $ready) { throw 'Gateway was not healthy within 40 seconds.' }
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl media-service -am `
            '-Dtest=T162TeacherReviewRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test `
            *> (Join-Path $logDirectory 'real-test.log')
        if ($LASTEXITCODE -ne 0) { throw 'T16.2 real acceptance failed; inspect .m2/t16-2-acceptance logs.' }
        Write-Output 'T16.2 real Gateway/IAM/Media/OSS/RabbitMQ acceptance passed.'
    }
    finally {
        foreach ($process in @($gatewayProcess, $iamProcess)) {
            if ($null -ne $process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                $process.WaitForExit(10000) | Out-Null
            }
        }
        foreach ($name in $previousEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
}
