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
$previousEnabled = [Environment]::GetEnvironmentVariable("T12B_DEMO_SEED_ENABLED", "Process")
$previousAction = [Environment]::GetEnvironmentVariable("T12B_DEMO_SEED_ACTION", "Process")
$previousRabbit = [Environment]::GetEnvironmentVariable("RABBITMQ_ENABLED", "Process")

function Restore-ProcessVariable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [AllowNull()]
        [string]$Value
    )

    if ($null -eq $Value) {
        [Environment]::SetEnvironmentVariable($Name, $null, "Process")
    }
    else {
        [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
    }
}

$seedCommand = {
    Write-Host "Running T12B assessment demo seed against the dedicated learning development Schema."
    if ($env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev") {
        throw "T12B demo seed refuses non-development learning Schema."
    }
    $mavenArguments = @(
        "-Dmaven.repo.local=$mavenRepository",
        "-f", $mavenPom,
        "-pl", "learning-service",
        "-am",
        "-Dtest=T12BAssessmentDemoSeedTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test"
    )
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "T12B demo seed Maven execution failed with exit code $LASTEXITCODE."
    }
}

try {
    $env:T12B_DEMO_SEED_ENABLED = "true"
    $env:T12B_DEMO_SEED_ACTION = $Action.ToUpperInvariant()
    $env:RABBITMQ_ENABLED = "false"

    # 数据库命令始终位于本地凭据包装器内；密码不会进入参数、日志或仓库。
    Write-Host "Starting T12B demo seed action $($env:T12B_DEMO_SEED_ACTION)."
    & $wrapper -ScriptBlock $seedCommand
} finally {
    Restore-ProcessVariable -Name "T12B_DEMO_SEED_ENABLED" -Value $previousEnabled
    Restore-ProcessVariable -Name "T12B_DEMO_SEED_ACTION" -Value $previousAction
    Restore-ProcessVariable -Name "RABBITMQ_ENABLED" -Value $previousRabbit
}
