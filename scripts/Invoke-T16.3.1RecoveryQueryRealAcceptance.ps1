#requires -Version 7.0
[CmdletBinding()]
param([switch]$CleanupOnly)

# T16.3.1真实恢复验收：凭据只经DPAPI包装器；只启动本轮IAM/Gateway，精确清理固定合成账号。
# CleanupOnly用于强制中断后清理同一Fixture；不修改个人local.env，不发送邮件或启动跨域Worker。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repositoryRoot '.m2/t16-3-1-recovery-acceptance'
$mavenPom = Join-Path $repositoryRoot 'java-platform/pom.xml'
$mavenRepository = Join-Path $repositoryRoot '.m2/repository'

& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if ($env:IAM_MYSQL_DATABASE -ne 'edu_platform_iam_dev') { throw 'Dedicated IAM development schema required.' }
    foreach ($port in @(18080,18081)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
            throw "Acceptance port $port is occupied."
        }
    }
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
    & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl gateway-service,iam-service -am -DskipTests package `
        *> (Join-Path $logDirectory 'package.log')
    if ($LASTEXITCODE -ne 0) { throw 'Package failed; inspect .m2/t16-3-1-recovery-acceptance/package.log.' }
    $previousEnvironment = @{}
    $scopedSettings = @{
        IAM_SERVICE_URL='http://127.0.0.1:18081'
        IAM_REVIEW_MESSAGING_ENABLED='false'
        IAM_REVIEW_RETENTION_ENABLED='false'
        IAM_SEED_ENABLED='false'
        IAM_ACCOUNT_COORDINATION_ENABLED='false'
        IAM_SECURITY_STORE='redis'
        IAM_SESSION_VERSION_KEY_PREFIX='edu:t1631:test:session-version'
        GATEWAY_RATE_LIMIT_KEY_PREFIX='edu:t1631:test:gateway:rate'
        GATEWAY_RATE_LIMIT_ENABLED='true'
        GATEWAY_SESSION_VALIDATION_ENABLED='true'
        # 两个本轮服务共享IAM Redis逻辑库以校验真实会话；仅自有键前缀，不动原业务键。
        GATEWAY_REDIS_DATABASE=$env:IAM_REDIS_DATABASE
        T1631_RECOVERY_REAL_ACCEPTANCE='true'
        T1631_CLEANUP_ONLY=([string]$CleanupOnly.IsPresent).ToLowerInvariant()
    }
    if (-not $scopedSettings.GATEWAY_REDIS_DATABASE) { $scopedSettings.GATEWAY_REDIS_DATABASE='1' }
    $processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
    try {
        foreach ($name in $scopedSettings.Keys) {
            $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
            [Environment]::SetEnvironmentVariable($name,$scopedSettings[$name],'Process')
        }
        if (-not $CleanupOnly) {
            $java = (Get-Command java -ErrorAction Stop).Source
            foreach ($service in @(@{Name='iam';Module='iam-service';Port=18081},
                    @{Name='gateway';Module='gateway-service';Port=18080})) {
                $jar = Join-Path $repositoryRoot "java-platform/$($service.Module)/target/$($service.Module)-0.1.0-SNAPSHOT.jar"
                $process = Start-Process -FilePath $java -ArgumentList @('-jar',$jar,
                    '--spring.profiles.active=local',"--server.port=$($service.Port)") -WorkingDirectory $repositoryRoot `
                    -RedirectStandardOutput (Join-Path $logDirectory "$($service.Name).log") `
                    -RedirectStandardError (Join-Path $logDirectory "$($service.Name)-error.log") `
                    -WindowStyle Hidden -PassThru
                $processes.Add($process)
                $ready = $false
                for ($attempt=0;$attempt -lt 80;$attempt++) {
                    Start-Sleep -Milliseconds 500
                    if ($process.HasExited) { throw "Acceptance $($service.Name) exited before readiness." }
                    try {
                        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$($service.Port)/api/v1/auth/channel-capabilities" -TimeoutSec 2
                        if ($response.StatusCode -eq 200) { $ready=$true;break }
                    } catch { }
                }
                if (-not $ready) { throw "Acceptance $($service.Name) not ready; inspect logs." }
            }
        }
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl iam-service -am `
            '-Dtest=T1631RecoveryQueryRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test `
            *> (Join-Path $logDirectory 'real-test.log')
        if ($LASTEXITCODE -ne 0) { throw 'Recovery acceptance failed; inspect .m2/t16-3-1-recovery-acceptance/real-test.log.' }
        if ($CleanupOnly) { Write-Output 'T16.3.1 exact fixture cleanup passed.' }
        else { Write-Output 'T16.3.1 real Gateway/IAM/MySQL/Redis recovery passed; exact fixtures cleaned.' }
    } finally {
        foreach ($process in $processes) {
            if (-not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                $process.WaitForExit(10000) | Out-Null
            }
        }
        foreach ($name in $previousEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process')
        }
    }
}
