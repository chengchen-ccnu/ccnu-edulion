[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$mavenPom = Join-Path $repositoryRoot "java-platform/pom.xml"
$mavenRepository = Join-Path $repositoryRoot ".m2/repository"

# 仅允许明确的开发库与 Redis 4；DPAPI 凭据由统一包装器注入两个 JVM，不拼入命令参数。
& (Join-Path $PSScriptRoot "Invoke-WithLocalEnvironment.ps1") -ScriptBlock {
    if ($env:COURSE_MYSQL_DATABASE -ne "edu_platform_course_dev" `
            -or $env:LEARNING_MYSQL_DATABASE -ne "edu_platform_learning_dev" `
            -or $env:LEARNING_REDIS_DATABASE -ne "4") {
        throw "T16.1 acceptance requires the dedicated Course/Learning development schemas and Redis DB 4."
    }
    if ([string]::IsNullOrWhiteSpace($env:COURSE_SUSPENSION_IAM_TOKEN)) {
        throw "Initialize the encrypted IAM-to-Course suspension secret with Initialize-LocalEnvironment.ps1."
    }
    $previousEnabled = $env:T161_REAL_ACCEPTANCE_ENABLED
    try {
        $env:T161_REAL_ACCEPTANCE_ENABLED = "true"
        & mvn "-Dmaven.repo.local=$mavenRepository" -f $mavenPom -pl course-service -am `
            "-Dtest=T161CourseRealEnvironmentAcceptanceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
        if ($LASTEXITCODE -ne 0) { throw "T16.1 real acceptance failed; inspect the Course report and .m2/t16-1-learning-real.log." }
    }
    finally { $env:T161_REAL_ACCEPTANCE_ENABLED = $previousEnabled }
}
