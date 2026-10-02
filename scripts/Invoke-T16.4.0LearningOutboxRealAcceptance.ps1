#requires -Version 7.0
[CmdletBinding()]
param([switch]$MaintenanceConfirmed)

# 显式维护窗口内执行真实 Learning 批量发布回归。仅加载 DPAPI 凭据并启动 JUnit，
# 不启动后台服务。五条随机事件、一个终态任务和隔离 MQ 拓扑由测试 finally 精确清理。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $MaintenanceConfirmed) { throw 'Confirm a quiet Learning maintenance window before real acceptance.' }
$root = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $root '.m2/t16-4-0'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if ($env:LEARNING_MYSQL_DATABASE -ne 'edu_platform_learning_dev') { throw 'Only the Learning development schema is allowed.' }
    if (Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'learning-service' }) {
        throw 'Existing Learning JVM found; never terminate user services or race their publisher.'
    }
    if (-not $env:RABBITMQ_USERNAME -or -not $env:RABBITMQ_PASSWORD) { throw 'Initialize encrypted RabbitMQ credentials first.' }
    $previousEnabled = $env:T1640_REAL_ACCEPTANCE
    $previousWindow = $env:T1640_MAINTENANCE_CONFIRMED
    $previousFixtureDirectory = $env:T1640_FIXTURE_DIRECTORY
    try {
        $env:T1640_REAL_ACCEPTANCE = 'true'
        $env:T1640_MAINTENANCE_CONFIRMED = 'true'
        $env:T1640_FIXTURE_DIRECTORY = $logDirectory
        & mvn "-Dmaven.repo.local=$root/.m2/repository" -f "$root/java-platform/pom.xml" -pl learning-service -am `
            '-Dtest=T1640LearningOutboxRealAcceptanceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test `
            *> (Join-Path $logDirectory 'real-test.log')
        if ($LASTEXITCODE -ne 0) { throw 'Real batch acceptance failed; inspect .m2/t16-4-0/real-test.log.' }
        Write-Output 'Real Learning publishPending ACK/return/NACK/retry/idempotency acceptance passed; exact fixtures cleaned.'
    } finally {
        [Environment]::SetEnvironmentVariable('T1640_REAL_ACCEPTANCE', $previousEnabled, 'Process')
        [Environment]::SetEnvironmentVariable('T1640_MAINTENANCE_CONFIRMED', $previousWindow, 'Process')
        [Environment]::SetEnvironmentVariable('T1640_FIXTURE_DIRECTORY', $previousFixtureDirectory, 'Process')
    }
}
