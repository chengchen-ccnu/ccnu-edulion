[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Initialize", "Cleanup", "Report")]
    [string]$Action
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $PSScriptRoot "Invoke-WithLocalEnvironment.ps1"
$mavenRepository = Join-Path $repositoryRoot ".m2/repository"
$mavenPom = Join-Path $repositoryRoot "java-platform/pom.xml"
$previousEnabled = [Environment]::GetEnvironmentVariable("T10C2_FIXTURE_ENABLED", "Process")
$previousAction = [Environment]::GetEnvironmentVariable("T10C2_FIXTURE_ACTION", "Process")
$previousRabbit = [Environment]::GetEnvironmentVariable("RABBITMQ_ENABLED", "Process")

function Restore-ProcessVariable([string]$Name, [AllowNull()][string]$Value) {
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

$fixtureCommand = {
    if ($env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev") {
        throw "T10C.2 fixture refuses non-development learning Schema."
    }
    $arguments = @("-Dmaven.repo.local=$mavenRepository", "-f", $mavenPom, "-pl", "learning-service", "-am",
        "-Dtest=T10C2CoursePracticeBrowserFixtureTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test")
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) { throw "T10C.2 fixture failed with exit code $LASTEXITCODE." }
}

try {
    $env:T10C2_FIXTURE_ENABLED = "true"
    $env:T10C2_FIXTURE_ACTION = $Action.ToUpperInvariant()
    $env:RABBITMQ_ENABLED = "false"
    # 所有数据库访问通过 DPAPI 包装器；固定 ID 与标题校验使初始化和清理均可重复且有界。
    & $wrapper -ScriptBlock $fixtureCommand
}
finally {
    Restore-ProcessVariable "T10C2_FIXTURE_ENABLED" $previousEnabled
    Restore-ProcessVariable "T10C2_FIXTURE_ACTION" $previousAction
    Restore-ProcessVariable "RABBITMQ_ENABLED" $previousRabbit
}
