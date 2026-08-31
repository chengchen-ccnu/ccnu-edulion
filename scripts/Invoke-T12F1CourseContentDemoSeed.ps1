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
$mavenPom = Join-Path $repositoryRoot "java-platform/pom.xml"
$mavenRepository = Join-Path $repositoryRoot ".m2/repository"
$previousEnabled = [Environment]::GetEnvironmentVariable("T12F1_DEMO_SEED_ENABLED", "Process")
$previousAction = [Environment]::GetEnvironmentVariable("T12F1_DEMO_SEED_ACTION", "Process")
$previousRabbit = [Environment]::GetEnvironmentVariable("RABBITMQ_ENABLED", "Process")

function Restore-ProcessVariable {
    param([string]$Name, [AllowNull()][string]$Value)
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Invoke-DomainSeed {
    param([string]$Module, [string]$TestClass, [string]$SeedAction)
    $env:T12F1_DEMO_SEED_ACTION = $SeedAction.ToUpperInvariant()
    $arguments = @(
        "-Dmaven.repo.local=$mavenRepository", "-f", $mavenPom,
        "-pl", $Module, "-am", "-Dtest=$TestClass",
        "-Dsurefire.failIfNoSpecifiedTests=false", "test"
    )
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "T12F.1 $Module demo seed failed with exit code $LASTEXITCODE."
    }
}

$seedCommand = {
    # local.env 可能为日常联调启用 RabbitMQ；Seed 本身不发布消息，必须在凭据加载后再次关闭监听。
    $env:RABBITMQ_ENABLED = "false"
    if ($env:MEDIA_MYSQL_DATABASE -ne "edu_platform_media_dev" -or
        $env:COURSE_MYSQL_DATABASE -ne "edu_platform_course_dev" -or
        $env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev") {
        throw "T12F.1 demo seed refuses any non-development domain Schema."
    }
    foreach ($name in @("OSS_ENDPOINT", "OSS_REGION", "OSS_BUCKET", "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET")) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            throw "T12F.1 demo seed requires the existing real OSS configuration before changing any domain data."
        }
    }

    function Initialize-All {
        Invoke-DomainSeed "media-service" "T12F1MediaDemoSeedTest" "Initialize"
        Invoke-DomainSeed "course-service" "T12F1CourseContentDemoSeedTest" "Initialize"
        Invoke-DomainSeed "learning-service" "T12F1LearningDemoSeedTest" "Initialize"
    }
    function Cleanup-All {
        Invoke-DomainSeed "learning-service" "T12F1LearningDemoSeedTest" "Cleanup"
        Invoke-DomainSeed "course-service" "T12F1CourseContentDemoSeedTest" "Cleanup"
        Invoke-DomainSeed "media-service" "T12F1MediaDemoSeedTest" "Cleanup"
    }

    switch ($Action) {
        "Initialize" { Initialize-All }
        "Cleanup" { Cleanup-All }
        "Rebuild" { Cleanup-All; Initialize-All }
    }
}

try {
    $env:T12F1_DEMO_SEED_ENABLED = "true"
    $env:RABBITMQ_ENABLED = "false"
    # 所有需要数据库与 OSS 凭据的命令都位于统一凭据包装器内，秘密不会进入参数或日志。
    & $wrapper -ScriptBlock $seedCommand
} finally {
    Restore-ProcessVariable "T12F1_DEMO_SEED_ENABLED" $previousEnabled
    Restore-ProcessVariable "T12F1_DEMO_SEED_ACTION" $previousAction
    Restore-ProcessVariable "RABBITMQ_ENABLED" $previousRabbit
}
