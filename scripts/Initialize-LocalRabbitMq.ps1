[CmdletBinding()]
param(
    [string]$ConfigDirectory = (
        Join-Path ([Environment]::GetFolderPath("UserProfile")) ".ccnu-edulion"
    ),
    [string]$DefaultUsername = "guest",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "This initializer requires Windows DPAPI."
}

$credentialPath = Join-Path $ConfigDirectory "rabbitmq.credential.clixml"
if ((Test-Path -LiteralPath $credentialPath) -and -not $Force) {
    throw "The encrypted RabbitMQ credential already exists. Use -Force only to rotate it."
}

# 只将 SecureString 交给 Windows DPAPI；用户名与密码不会打印或写入仓库配置。
$credential = Get-Credential `
    -UserName $DefaultUsername `
    -Message "Enter the local RabbitMQ credential for ccnu-edulion."
if ($null -eq $credential -or -not $credential.UserName) {
    throw "RabbitMQ credential initialization was cancelled."
}

New-Item -ItemType Directory -Path $ConfigDirectory -Force | Out-Null
$credential | Export-Clixml -LiteralPath $credentialPath -Force
$credential = $null

Write-Host "The RabbitMQ credential was encrypted for the current Windows user."
Write-Host "Remove RABBITMQ_PASSWORD from local.env after verifying the wrapper command."
