# T17B.1 独立测试凭据：随机密码只在 DPAPI 与子进程环境中存在；不写 local.env 或仓库。
[CmdletBinding()]
param([ValidateSet('create','cleanup','browser','settle')][string]$Action='create')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$fixtureCredentialPath=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.ccnu-edulion/t17b1-fixture.credential.clixml'
if($Action -eq 'cleanup' -and -not (Test-Path -LiteralPath (Join-Path $repositoryRoot '.m2/t17b1-browser/fixture.json'))) {
    if(Test-Path -LiteralPath $fixtureCredentialPath){Remove-Item -LiteralPath $fixtureCredentialPath}
    Write-Output 'No active T17B.1 fixture; cleanup already completed'
    return
}
if($Action -eq 'create' -and -not (Test-Path -LiteralPath $fixtureCredentialPath)) {
    $fixturePassword=ConvertTo-SecureString ([Guid]::NewGuid().ToString('N') + 'Aa9!') -AsPlainText -Force
    [PSCredential]::new('t17b1-fixture',$fixturePassword) | Export-Clixml -LiteralPath $fixtureCredentialPath
    $fixturePassword=$null
}
if(-not (Test-Path -LiteralPath $fixtureCredentialPath)){throw 'Missing T17B.1 encrypted fixture credential.'}
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    $fixtureCredential=Import-Clixml -LiteralPath $fixtureCredentialPath
    if($fixtureCredential -isnot [PSCredential] -or $fixtureCredential.UserName -ne 't17b1-fixture'){throw 'Invalid T17B.1 fixture credential.'}
    $fixtureNodePath=[Environment]::GetEnvironmentVariable('NODE_PATH','Process')
    try {
        $env:T17B1_FIXTURE_PASSWORD=$fixtureCredential.GetNetworkCredential().Password
        if($Action -eq 'browser') {
            $env:NODE_PATH=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
            & node (Join-Path $PSScriptRoot 't17b1_browser_acceptance.cjs')
        } else { & python (Join-Path $PSScriptRoot 't17b1_browser_fixture.py') $Action }
        if($LASTEXITCODE -ne 0){throw 'T17B.1 fixture command failed; keep manifest for exact cleanup.'}
    } finally {
        Remove-Item Env:T17B1_FIXTURE_PASSWORD -ErrorAction SilentlyContinue
        [Environment]::SetEnvironmentVariable('NODE_PATH',$fixtureNodePath,'Process')
        $fixtureCredential=$null
    }
}
if($Action -eq 'cleanup'){Remove-Item -LiteralPath $fixtureCredentialPath}
