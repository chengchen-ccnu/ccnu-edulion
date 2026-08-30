param(
    [Parameter(Mandatory = $true)]
    [string]$HostName,
    [Parameter(Mandatory = $false)]
    [string]$FromAddress,
    [int]$Port = 587,
    [string]$ConfigDirectory = (
        Join-Path ([Environment]::GetFolderPath("UserProfile")) ".ccnu-edulion"
    )
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "This SMTP initializer requires Windows DPAPI."
}
$configPath = Join-Path $ConfigDirectory "local.env"
$smtpCredentialPath = Join-Path $ConfigDirectory "iam-smtp.credential.clixml"
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing local.env. Run scripts/Initialize-LocalEnvironment.ps1 first."
}

if ([string]::IsNullOrWhiteSpace($FromAddress)) {
    foreach ($line in (Get-Content -LiteralPath $configPath -Encoding UTF8)) {
        if ($line -match '^IAM_SMTP_FROM=(.+)$') {
            $FromAddress = $Matches[1].Trim()
            break
        }
    }
}
if ($Port -lt 1 -or $Port -gt 65535 -or $FromAddress -notmatch "^[^@\s]+@[^@\s]+$") {
    throw "SMTP port or sender address is invalid."
}

# 用户名和密码只进入当前 Windows 用户可解密的 DPAPI 文件；脚本不打印这两个值。
$credential = $null
$credential = Get-Credential -Message "Enter the SMTP username and password for IAM verification email."
if ($null -eq $credential -or [string]::IsNullOrWhiteSpace($credential.UserName)) {
    throw "SMTP credential initialization was cancelled or the username is empty."
}
$credential | Export-Clixml -LiteralPath $smtpCredentialPath -Force

# host、port、from 和模式不是秘密，可进入 local.env；先写临时文件再原子替换，避免中途失败破坏配置。
Select-String -LiteralPath $configPath -Pattern "^(IAM_VERIFICATION_DELIVERY_MODE|IAM_SMTP_HOST|IAM_SMTP_PORT|IAM_SMTP_FROM)=" -NotMatch |
    Select-Object -ExpandProperty Line |
    Set-Content -LiteralPath ($configPath + ".t12d1.tmp") -Encoding UTF8
Add-Content -LiteralPath ($configPath + ".t12d1.tmp") -Value @(
    "IAM_VERIFICATION_DELIVERY_MODE=smtp"
    "IAM_SMTP_HOST=$HostName"
    "IAM_SMTP_PORT=$Port"
    "IAM_SMTP_FROM=$FromAddress"
)
Move-Item -LiteralPath ($configPath + ".t12d1.tmp") -Destination $configPath -Force

$credential = $null
Write-Host "SMTP configuration saved. Username and password are DPAPI-encrypted for the current Windows user."
