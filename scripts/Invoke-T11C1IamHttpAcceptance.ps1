[CmdletBinding()]
param(
    [int]$Port = 18081,
    [switch]$RunLearningClientTest,
    [switch]$RunCapacityTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $env:IAM_INTERNAL_SERVICE_TOKEN) {
    throw "IAM_INTERNAL_SERVICE_TOKEN must be provided by Invoke-WithLocalEnvironment.ps1."
}

$logPath = Join-Path ([IO.Path]::GetTempPath()) "edu-t11c1-iam-$Port.log"
$errorPath = Join-Path ([IO.Path]::GetTempPath()) "edu-t11c1-iam-$Port.err.log"
$process = $null
$originalServerPort = [Environment]::GetEnvironmentVariable("SERVER_PORT", "Process")
$originalSeedEnabled = [Environment]::GetEnvironmentVariable("IAM_SEED_ENABLED", "Process")
$originalIamBaseUrl = [Environment]::GetEnvironmentVariable("IAM_SERVICE_BASE_URL", "Process")
$originalRealIamBaseUrl = [Environment]::GetEnvironmentVariable("T11C1_REAL_IAM_HTTP_BASE_URL", "Process")
$originalCapacityEnabled = [Environment]::GetEnvironmentVariable("T11C1_GRADEBOOK_CAPACITY_ENABLED", "Process")

function Restore-ProcessVariable {
    param([string]$Name, [string]$Value)
    if ($null -eq $Value) { Remove-Item -Path "Env:$Name" -ErrorAction SilentlyContinue }
    else { Set-Item -Path "Env:$Name" -Value $Value }
}

try {
    $env:SERVER_PORT = [string]$Port
    $env:IAM_SEED_ENABLED = "false"
    $process = Start-Process -FilePath "java" `
        -ArgumentList @(
            "-jar",
            "java-platform/iam-service/target/iam-service-0.1.0-SNAPSHOT.jar",
            "--spring.profiles.active=local"
        ) `
        -WorkingDirectory (Get-Location) `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $logPath `
        -RedirectStandardError $errorPath

    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
            $ready = $true
            break
        }
    }
    if (-not $ready) { throw "Fresh IAM service did not start listening." }

    $requestBody = @{ learnerIds = @(
            "00000000-0000-4000-8000-000000000101",
            "00000000-0000-4000-8000-000000000999"
        ) } | ConvertTo-Json -Compress
    $validHeaders = @{
        "X-Internal-Caller" = "learning-service"
        "X-Internal-Service-Token" = $env:IAM_INTERNAL_SERVICE_TOKEN
        "X-Trace-Id" = "t11c1-real-iam-success"
    }
    $valid = Invoke-WebRequest -UseBasicParsing `
        -Uri "http://127.0.0.1:$Port/internal/v1/users/nicknames" `
        -Method Post -Headers $validHeaders -ContentType "application/json" -Body $requestBody

    $wrongCallerHeaders = @{
        "X-Internal-Caller" = "teacher-service"
        "X-Internal-Service-Token" = $env:IAM_INTERNAL_SERVICE_TOKEN
    }
    try {
        Invoke-WebRequest -UseBasicParsing `
            -Uri "http://127.0.0.1:$Port/internal/v1/users/nicknames" `
            -Method Post -Headers $wrongCallerHeaders -ContentType "application/json" -Body $requestBody | Out-Null
        $wrongCallerStatus = 200
    }
    catch {
        $wrongCallerStatus = [int]$_.Exception.Response.StatusCode
    }

    $wrongTokenHeaders = @{
        "X-Internal-Caller" = "learning-service"
        "X-Internal-Service-Token" = "invalid-local-test-token"
    }
    try {
        Invoke-WebRequest -UseBasicParsing `
            -Uri "http://127.0.0.1:$Port/internal/v1/users/nicknames" `
            -Method Post -Headers $wrongTokenHeaders -ContentType "application/json" -Body $requestBody | Out-Null
        $wrongTokenStatus = 200
    }
    catch {
        $wrongTokenStatus = [int]$_.Exception.Response.StatusCode
    }

    [pscustomobject]@{
        listenerReady = $ready
        validStatus = [int]$valid.StatusCode
        validBody = $valid.Content
        wrongCallerStatus = $wrongCallerStatus
        wrongTokenStatus = $wrongTokenStatus
    } | ConvertTo-Json -Compress

    if ($RunLearningClientTest) {
        $env:T11C1_REAL_IAM_HTTP_BASE_URL = "http://127.0.0.1:$Port"
        $mavenArguments = @(
            "-Dmaven.repo.local=.m2/repository",
            "-f", "java-platform/pom.xml",
            "-pl", "learning-service",
            "-am",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "-Dtest=HttpLearnerDirectoryClientRealAcceptanceTest",
            "test"
        )
        & mvn @mavenArguments
        if ($LASTEXITCODE -ne 0) { throw "Real IAM HTTP client acceptance failed." }
    }

    if ($RunCapacityTest) {
        $env:IAM_SERVICE_BASE_URL = "http://127.0.0.1:$Port"
        $env:T11C1_GRADEBOOK_CAPACITY_ENABLED = "true"
        $mavenArguments = @(
            "-Dmaven.repo.local=.m2/repository",
            "-f", "java-platform/pom.xml",
            "-pl", "learning-service",
            "-am",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "-Dtest=T11C1GradebookCapacityAcceptanceTest",
            "test"
        )
        & mvn @mavenArguments
        if ($LASTEXITCODE -ne 0) { throw "Gradebook capacity acceptance failed." }
    }
}
finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id
        [void]$process.WaitForExit(5000)
    }
    Remove-Item -LiteralPath $logPath, $errorPath -Force -ErrorAction SilentlyContinue
    Restore-ProcessVariable "SERVER_PORT" $originalServerPort
    Restore-ProcessVariable "IAM_SEED_ENABLED" $originalSeedEnabled
    Restore-ProcessVariable "IAM_SERVICE_BASE_URL" $originalIamBaseUrl
    Restore-ProcessVariable "T11C1_REAL_IAM_HTTP_BASE_URL" $originalRealIamBaseUrl
    Restore-ProcessVariable "T11C1_GRADEBOOK_CAPACITY_ENABLED" $originalCapacityEnabled
}
