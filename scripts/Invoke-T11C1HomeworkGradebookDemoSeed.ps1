[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Initialize", "Cleanup", "Rebuild")]
    [string]$Action
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $PSScriptRoot "Invoke-WithLocalEnvironment.ps1"
$mavenRepository = Join-Path $repositoryRoot ".m2/repository"
$mavenPom = Join-Path $repositoryRoot "java-platform/pom.xml"
$previousEnabled = [Environment]::GetEnvironmentVariable("T11C1_DEMO_SEED_ENABLED", "Process")
$previousAction = [Environment]::GetEnvironmentVariable("T11C1_DEMO_SEED_ACTION", "Process")
$previousRabbit = [Environment]::GetEnvironmentVariable("RABBITMQ_ENABLED", "Process")

function Restore-ProcessVariable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [AllowNull()]
        [string]$Value
    )

    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

$seedCommand = {
    if ($env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev") {
        throw "T11C.1 demo seed refuses non-development learning Schema."
    }
    $mavenArguments = @(
        "-Dmaven.repo.local=$mavenRepository",
        "-f", $mavenPom,
        "-pl", "learning-service",
        "-am",
        "-Dtest=T11C1HomeworkGradebookDemoSeedTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test"
    )
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "T11C.1 demo seed Maven execution failed with exit code $LASTEXITCODE."
    }
}

try {
    $env:T11C1_DEMO_SEED_ENABLED = "true"
    $env:T11C1_DEMO_SEED_ACTION = $Action.ToUpperInvariant()
    $env:RABBITMQ_ENABLED = "false"
    # 所有数据库命令都在 DPAPI 包装器内运行；密码和服务令牌不会进入参数或日志。
    & $wrapper -ScriptBlock $seedCommand
}
finally {
    Restore-ProcessVariable -Name "T11C1_DEMO_SEED_ENABLED" -Value $previousEnabled
    Restore-ProcessVariable -Name "T11C1_DEMO_SEED_ACTION" -Value $previousAction
    Restore-ProcessVariable -Name "RABBITMQ_ENABLED" -Value $previousRabbit
}
