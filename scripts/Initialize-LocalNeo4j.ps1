[CmdletBinding()]
param(
    [string]$ConfigDirectory = (
        Join-Path ([Environment]::GetFolderPath("UserProfile")) ".ccnu-edulion"
    ),
    [string]$DefaultUsername = "neo4j",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "This initializer requires Windows DPAPI."
}

$credentialPath = Join-Path $ConfigDirectory "neo4j.credential.clixml"
if ((Test-Path -LiteralPath $credentialPath) -and -not $Force) {
    throw "The encrypted Neo4j credential already exists. Use -Force only to rotate it."
}

# Get-Credential 将密码保留为 SecureString；Export-Clixml 使用当前 Windows 用户的 DPAPI 加密，
# 输出文件只能由同一用户在同一计算机解密，脚本不会打印或写入明文密码。
$credential = Get-Credential `
    -UserName $DefaultUsername `
    -Message "Enter the local Neo4j credential for ccnu-edulion."
if ($null -eq $credential -or -not $credential.UserName) {
    throw "Neo4j credential initialization was cancelled."
}

New-Item -ItemType Directory -Path $ConfigDirectory -Force | Out-Null
$credential | Export-Clixml -LiteralPath $credentialPath -Force
$credential = $null

Write-Host "The Neo4j credential was encrypted for the current Windows user."
Write-Host "Remove NEO4J_PASSWORD from local.env after verifying the wrapper command."
