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
$iamSecurityCredentialPath = Join-Path $ConfigDirectory "iam-security.credential.clixml"
$learningIamCredentialPath = Join-Path $ConfigDirectory "learning-iam-service.credential.clixml"
$iamSmtpCredentialPath = Join-Path $ConfigDirectory "iam-smtp.credential.clixml"
$commandExitCode = 0
$iamSmtpCredential = $null
$iamSmtpNetworkCredential = $null

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing local environment file. Run scripts/Initialize-LocalEnvironment.ps1 first."
}

if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
    throw "Missing encrypted MySQL credential. Run scripts/Initialize-LocalEnvironment.ps1 first."
}

if (-not (Test-Path -LiteralPath $serviceCredentialPath -PathType Leaf)) {
    throw "Missing encrypted course/media service authorization secret. Run scripts/Initialize-LocalEnvironment.ps1 to add it."
}

if (-not (Test-Path -LiteralPath $iamSecurityCredentialPath -PathType Leaf)) {
    throw "Missing encrypted IAM security secrets. Run scripts/Initialize-LocalEnvironment.ps1 to add them."
}

if (-not (Test-Path -LiteralPath $learningIamCredentialPath -PathType Leaf)) {
    throw "Missing encrypted Learning-to-IAM service secret. Run scripts/Initialize-LocalEnvironment.ps1 to add it."
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

    $iamSecurityCredential = Import-Clixml -LiteralPath $iamSecurityCredentialPath
    if ($iamSecurityCredential -isnot [PSCredential] -or $iamSecurityCredential.UserName -ne "iam-security") {
        throw "The encrypted IAM security credential has an unexpected format."
    }
    $iamSecurityNetworkCredential = $iamSecurityCredential.GetNetworkCredential()
    $iamSecurityPayload = $iamSecurityNetworkCredential.Password | ConvertFrom-Json
    if ($iamSecurityPayload.jwtSecret.Length -lt 32 `
            -or $iamSecurityPayload.verificationHmacSecret.Length -lt 32 `
            -or $iamSecurityPayload.localVerificationCode -notmatch "^[0-9]{6}$") {
        throw "The encrypted IAM security credential is incomplete."
    }
    Set-ScopedEnvironmentVariable -Name "IAM_JWT_SECRET" -Value $iamSecurityPayload.jwtSecret
    Set-ScopedEnvironmentVariable -Name "IAM_VERIFICATION_HMAC_SECRET" -Value $iamSecurityPayload.verificationHmacSecret
    Set-ScopedEnvironmentVariable -Name "IAM_VERIFICATION_FIXED_CODE" -Value $iamSecurityPayload.localVerificationCode

    $learningIamCredential = Import-Clixml -LiteralPath $learningIamCredentialPath
    if ($learningIamCredential -isnot [PSCredential] -or $learningIamCredential.UserName -ne "learning-iam-service") {
        throw "The encrypted Learning-to-IAM service credential has an unexpected format."
    }
    $learningIamNetworkCredential = $learningIamCredential.GetNetworkCredential()
    if ($learningIamNetworkCredential.Password.Length -lt 32) {
        throw "The encrypted Learning-to-IAM service credential is incomplete."
    }
    Set-ScopedEnvironmentVariable -Name "IAM_INTERNAL_SERVICE_TOKEN" -Value $learningIamNetworkCredential.Password

    if ($env:IAM_VERIFICATION_DELIVERY_MODE -eq "smtp") {
        if (-not (Test-Path -LiteralPath $iamSmtpCredentialPath -PathType Leaf)) {
            throw "SMTP mode requires the DPAPI-encrypted IAM SMTP credential. Run scripts/Initialize-LocalSmtp.ps1."
        }
        $iamSmtpCredential = Import-Clixml -LiteralPath $iamSmtpCredentialPath
        if ($iamSmtpCredential -isnot [PSCredential]) {
            throw "The encrypted IAM SMTP credential has an unexpected format."
        }
        $iamSmtpNetworkCredential = $iamSmtpCredential.GetNetworkCredential()
        Set-ScopedEnvironmentVariable -Name "IAM_SMTP_USERNAME" -Value $iamSmtpNetworkCredential.UserName
        Set-ScopedEnvironmentVariable -Name "IAM_SMTP_PASSWORD" -Value $iamSmtpNetworkCredential.Password
    }

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
    $iamSecurityPayload = $null
    $iamSecurityNetworkCredential = $null
    $iamSecurityCredential = $null
    $learningIamNetworkCredential = $null
    $learningIamCredential = $null
    $iamSmtpNetworkCredential = $null
    $iamSmtpCredential = $null
}

if ($null -ne $commandExitCode -and $commandExitCode -ne 0) {
    exit $commandExitCode
}
