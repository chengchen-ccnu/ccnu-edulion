[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $PSScriptRoot "Invoke-WithLocalEnvironment.ps1"
$mavenPom = Join-Path $repositoryRoot "java-platform/pom.xml"
$mavenRepository = Join-Path $repositoryRoot ".m2/repository"

$scriptBlock = {
    if ($env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev") {
        throw "T12G.1 real acceptance refuses non-development learning Schema."
    }
    if ($env:LEARNING_REDIS_DATABASE -ne "4") {
        throw "T12G.1 real acceptance requires Learning Redis DB 4."
    }
    $env:T12G1_REAL_ACCEPTANCE_ENABLED = "true"
    $env:RABBITMQ_ENABLED = "false"
    $arguments = @(
        "-Dmaven.repo.local=$mavenRepository", "-f", $mavenPom,
        "-pl", "learning-service", "-am",
        "-Dtest=T12G1ProgressBufferRealEnvironmentAcceptanceTest",
        "-Dsurefire.failIfNoSpecifiedTests=false", "test"
    )
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "T12G.1 real acceptance failed with exit code $LASTEXITCODE."
    }
}

# 凭据只由统一包装器注入子进程；测试自身拒绝任何非专用 Schema/Redis DB。
& $wrapper -ScriptBlock $scriptBlock
