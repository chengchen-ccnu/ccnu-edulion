[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [scriptblock]$ScriptBlock,
    [string]$ConfigDirectory = (
        Join-Path ([Environment]::GetFolderPath("UserProfile")) ".ccnu-edulion"
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$configPath = Join-Path $ConfigDirectory "local.env"
$credentialPath = Join-Path $ConfigDirectory "mysql.credential.clixml"
$serviceCredentialPath = Join-Path $ConfigDirectory "course-media-auth.credential.clixml"
$commandExitCode = 0

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing local environment file. Run scripts/Initialize-LocalEnvironment.ps1 first."
}

if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
    throw "Missing encrypted MySQL credential. Run scripts/Initialize-LocalEnvironment.ps1 first."
}

if (-not (Test-Path -LiteralPath $serviceCredentialPath -PathType Leaf)) {
    throw "Missing encrypted course/media service authorization secret. Run scripts/Initialize-LocalEnvironment.ps1 to add it."
}

$originalEnvironment = @{}

function Set-ScopedEnvironmentVariable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [AllowEmptyString()]
        [string]$Value
    )

    if (-not $originalEnvironment.ContainsKey($Name)) {
        $environmentPath = "Env:$Name"
        $exists = Test-Path $environmentPath
        $originalEnvironment[$Name] = @{
            Exists = $exists
            Value = if ($exists) { (Get-Item $environmentPath).Value } else { $null }
        }
    }

    Set-Item -Path "Env:$Name" -Value $Value
}

try {
    foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }

        $separatorIndex = $trimmed.IndexOf("=")
        if ($separatorIndex -le 0) {
            throw "Invalid local environment entry. Expected NAME=value."
        }

        $name = $trimmed.Substring(0, $separatorIndex).Trim()
        $value = $trimmed.Substring($separatorIndex + 1)
        if ($name -notmatch "^[A-Z][A-Z0-9_]*$") {
            throw "Invalid environment variable name in local configuration."
        }

        Set-ScopedEnvironmentVariable -Name $name -Value $value
    }

    $credential = Import-Clixml -LiteralPath $credentialPath
    if ($credential -isnot [PSCredential]) {
        throw "The encrypted MySQL credential has an unexpected format."
    }

    $networkCredential = $credential.GetNetworkCredential()
    Set-ScopedEnvironmentVariable -Name "MYSQL_USERNAME" -Value $networkCredential.UserName
    Set-ScopedEnvironmentVariable -Name "MYSQL_PASSWORD" -Value $networkCredential.Password

    $serviceCredential = Import-Clixml -LiteralPath $serviceCredentialPath
    if ($serviceCredential -isnot [PSCredential] -or $serviceCredential.UserName -ne "course-media-service") {
        throw "The encrypted course/media service authorization credential has an unexpected format."
    }
    $serviceNetworkCredential = $serviceCredential.GetNetworkCredential()
    Set-ScopedEnvironmentVariable -Name "COURSE_MEDIA_AUTH_SECRET" -Value $serviceNetworkCredential.Password

    & $ScriptBlock
    if (Test-Path Variable:LASTEXITCODE) {
        $commandExitCode = $LASTEXITCODE
    }
}
finally {
    foreach ($entry in $originalEnvironment.GetEnumerator()) {
        $environmentPath = "Env:$($entry.Key)"
        if ($entry.Value.Exists) {
            Set-Item -Path $environmentPath -Value $entry.Value.Value
        }
        else {
            Remove-Item -Path $environmentPath -ErrorAction SilentlyContinue
        }
    }

    $networkCredential = $null
    $credential = $null
    $serviceNetworkCredential = $null
    $serviceCredential = $null
}

if ($null -ne $commandExitCode -and $commandExitCode -ne 0) {
    exit $commandExitCode
}
