# 本轮夹具仅local/dev；初始化密码由操作者安全输入，DPAPI保存，不输出密码或受邀邮件凭证。
[CmdletBinding()]
param([ValidateSet('create','inspect','verify','negative','cleanup')][string]$Action='create')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$fixtureCredentialPath=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.ccnu-edulion/t17c3-fixture.credential.clixml'
if($Action -eq 'cleanup' -and -not (Test-Path -LiteralPath (Join-Path $repositoryRoot '.m2/t17c3-browser/fixture.json'))) {
    if(Test-Path -LiteralPath $fixtureCredentialPath){Remove-Item -LiteralPath $fixtureCredentialPath}
    Write-Output 'No active T17C.3 fixture; cleanup already completed'; return
}
if($Action -eq 'create' -and -not (Test-Path -LiteralPath $fixtureCredentialPath)) {
    $fixturePassword=Read-Host 'Set temporary fixture login password (12+ characters; do not send it to chat)' -AsSecureString
    if($fixturePassword.Length -lt 12){throw 'Fixture password requires at least 12 characters.'}
    [PSCredential]::new('t17c3-fixture',$fixturePassword) | Export-Clixml -LiteralPath $fixtureCredentialPath
    $fixturePassword=$null
}
if(-not (Test-Path -LiteralPath $fixtureCredentialPath)){throw 'Missing encrypted T17C.3 fixture credential.'}
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    $fixtureCredential=Import-Clixml -LiteralPath $fixtureCredentialPath
    if($fixtureCredential -isnot [PSCredential] -or $fixtureCredential.UserName -ne 't17c3-fixture'){throw 'Unexpected fixture credential.'}
    try {
        $env:T17C3_FIXTURE_PASSWORD=$fixtureCredential.GetNetworkCredential().Password
        & python (Join-Path $PSScriptRoot 't17c3_browser_fixture.py') $Action
        if($LASTEXITCODE -ne 0){throw 'T17C.3 fixture failed; retain manifest for exact cleanup.'}
    } finally {Remove-Item Env:T17C3_FIXTURE_PASSWORD -ErrorAction SilentlyContinue;$fixtureCredential=$null}
}
if($Action -eq 'cleanup'){Remove-Item -LiteralPath $fixtureCredentialPath}
