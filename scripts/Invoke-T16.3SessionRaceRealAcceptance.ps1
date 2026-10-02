#requires -Version 7.0
[CmdletBinding()]
param()

# T16.3 会话前置专项；从 DPAPI 包装器加载凭据，启动本轮 IAM/Gateway，测试精确清理随机账号及 Redis。
# 不发送邮件、不改 Course/Learning 数据；这不是完整 T16.3 撤权/邀请验收。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repositoryRoot '.m2/t16-3-session-acceptance'
$mavenPom = Join-Path $repositoryRoot 'java-platform/pom.xml'
$mavenRepository = Join-Path $repositoryRoot '.m2/repository'

& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if ($env:IAM_MYSQL_DATABASE -ne 'edu_platform_iam_dev') { throw 'Dedicated IAM development schema required.' }
    foreach ($port in @(18080, 18081)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
            throw "Acceptance port $port is occupied."
        }
    }
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl gateway-service,iam-service -am -DskipTests package `
        *> (Join-Path $logDirectory 'package.log')
    if ($LASTEXITCODE -ne 0) { throw 'Package failed; inspect .m2/t16-3-session-acceptance/package.log.' }
    $previousEnvironment = @{}
    foreach ($name in @('IAM_SERVICE_URL', 'IAM_REVIEW_MESSAGING_ENABLED', 'IAM_REVIEW_RETENTION_ENABLED',
            'IAM_SEED_ENABLED', 'IAM_ACCOUNT_COORDINATION_ENABLED', 'T163_SESSION_REAL_ACCEPTANCE')) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
    try {
        $env:IAM_SERVICE_URL = 'http://127.0.0.1:18081'
        $env:IAM_REVIEW_MESSAGING_ENABLED = 'false'
        $env:IAM_REVIEW_RETENTION_ENABLED = 'false'
        $env:IAM_SEED_ENABLED = 'false'
        $env:IAM_ACCOUNT_COORDINATION_ENABLED = 'false'
        $env:T163_SESSION_REAL_ACCEPTANCE = 'true'
        $java = (Get-Command java -ErrorAction Stop).Source
        foreach ($service in @(@{ Name='iam'; Module='iam-service'; Port=18081 },
                @{ Name='gateway'; Module='gateway-service'; Port=18080 })) {
            $jar = Join-Path $repositoryRoot "java-platform/$($service.Module)/target/$($service.Module)-0.1.0-SNAPSHOT.jar"
            $process = Start-Process -FilePath $java -ArgumentList @('-jar', $jar,
                '--spring.profiles.active=local', "--server.port=$($service.Port)") -WorkingDirectory $repositoryRoot `
                -RedirectStandardOutput (Join-Path $logDirectory "$($service.Name).log") `
                -RedirectStandardError (Join-Path $logDirectory "$($service.Name)-error.log") `
                -WindowStyle Hidden -PassThru
            $processes.Add($process)
            $ready = $false
            for ($attempt = 0; $attempt -lt 80; $attempt++) {
                Start-Sleep -Milliseconds 500
                if ($process.HasExited) { throw "Acceptance $($service.Name) exited before readiness." }
                try {
                    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$($service.Port)/api/v1/auth/channel-capabilities" -TimeoutSec 2
                    if ($response.StatusCode -eq 200) { $ready = $true; break }
                } catch { }
            }
            if (-not $ready) { throw "Acceptance $($service.Name) not ready; inspect logs." }
        }
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl iam-service -am `
            '-Dtest=T163SessionRaceRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test `
            *> (Join-Path $logDirectory 'real-test.log')
        if ($LASTEXITCODE -ne 0) { throw 'Real session races failed; inspect .m2/t16-3-session-acceptance/real-test.log.' }
        Write-Output 'T16.3 session prerequisite: real Gateway/IAM/MySQL/Redis races passed; random fixtures cleaned.'
    } finally {
        foreach ($process in $processes) {
            if (-not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                $process.WaitForExit(10000) | Out-Null
            }
        }
        foreach ($name in $previousEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
}
