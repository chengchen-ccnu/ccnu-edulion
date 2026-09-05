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
        throw "T12G.1 capacity acceptance refuses non-development learning Schema."
    }
    if ($env:LEARNING_REDIS_DATABASE -ne "4") {
        throw "T12G.1 capacity acceptance requires Learning Redis DB 4."
    }
    $env:T12G1_CAPACITY_ENABLED = "true"
    $env:T12G1_CAPACITY_RUN_ID = [guid]::NewGuid().ToString()
    $env:RABBITMQ_ENABLED = "false"
    $arguments = @(
        "-Dmaven.repo.local=$mavenRepository", "-f", $mavenPom,
        "-pl", "learning-service", "-am",
        "-Dtest=T12G1ProgressBufferCapacityRealEnvironmentTest",
        "-Dsurefire.failIfNoSpecifiedTests=false", "test"
    )
    $capacityExitCode = 0
    $cleanupExitCode = 0
    try {
        & mvn @arguments
        $capacityExitCode = $LASTEXITCODE
    } finally {
        $env:T12G1_CAPACITY_ENABLED = "false"
        $env:T12G1_CAPACITY_CLEANUP_ENABLED = "true"
        $cleanupArguments = @(
            "-Dmaven.repo.local=$mavenRepository", "-f", $mavenPom,
            "-pl", "learning-service", "-am",
            "-Dtest=T12G1ProgressBufferCapacityCleanupTest",
            "-Dsurefire.failIfNoSpecifiedTests=false", "test"
        )
        & mvn @cleanupArguments
        $cleanupExitCode = $LASTEXITCODE
    }
    if ($cleanupExitCode -ne 0) {
        throw "T12G.1 capacity cleanup failed with exit code $cleanupExitCode."
    }
    if ($capacityExitCode -ne 0) {
        throw "T12G.1 capacity acceptance failed with exit code $capacityExitCode."
    }
}

# 运行凭据只存在于包装器子进程；容量验收不调用 OSS/HLS，只访问 progress-events。
& $wrapper -ScriptBlock $scriptBlock
