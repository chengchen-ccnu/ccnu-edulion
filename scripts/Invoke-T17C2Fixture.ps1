# T17C.2 dev浏览器夹具：随机密码只写当前用户DPAPI，业务数据按UUID清理，禁止改变唯一既有管理员。
[CmdletBinding()]
param([ValidateSet('create','browser','settle','cleanup','verify','inspect')][string]$Action='create')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$fixtureCredentialPath=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.ccnu-edulion/t17c2-fixture.credential.clixml'
if($Action -eq 'cleanup' -and -not (Test-Path -LiteralPath (Join-Path $repositoryRoot '.m2/t17c2-browser/fixture.json'))) {
    if(Test-Path -LiteralPath $fixtureCredentialPath){Remove-Item -LiteralPath $fixtureCredentialPath}
    Write-Output 'No active T17C.2 fixture; cleanup already completed'; return
}
if($Action -eq 'create' -and -not (Test-Path -LiteralPath $fixtureCredentialPath)) {
    $fixturePassword=ConvertTo-SecureString ([Guid]::NewGuid().ToString('N')+'Aa9!') -AsPlainText -Force
    [PSCredential]::new('t17c2-fixture',$fixturePassword) | Export-Clixml -LiteralPath $fixtureCredentialPath
    $fixturePassword=$null
}
if(-not (Test-Path -LiteralPath $fixtureCredentialPath)){throw 'Missing T17C.2 encrypted fixture credential.'}
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    $fixtureCredential=Import-Clixml -LiteralPath $fixtureCredentialPath
    if($fixtureCredential -isnot [PSCredential] -or $fixtureCredential.UserName -ne 't17c2-fixture'){throw 'Invalid T17C.2 fixture credential.'}
    $fixtureNodePath=[Environment]::GetEnvironmentVariable('NODE_PATH','Process')
    $fixturePythonPath=[Environment]::GetEnvironmentVariable('PYTHONPATH','Process')
    try {
        $env:T17C2_FIXTURE_PASSWORD=$fixtureCredential.GetNetworkCredential().Password
        # 沿用已安装的独立验收SDK，仅用于精确删除本轮对象，不进入Agent或业务服务依赖。
        $env:PYTHONPATH=Join-Path $repositoryRoot '.m2/t17c1-python-deps'
        if($Action -eq 'browser') {
            $env:NODE_PATH=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
            & node (Join-Path $PSScriptRoot 't17c2_browser_acceptance.cjs')
        } else { & python (Join-Path $PSScriptRoot 't17c2_browser_fixture.py') $Action }
        if($LASTEXITCODE -ne 0){throw 'T17C.2 fixture command failed; retain manifest for exact cleanup.'}
    } finally {
        Remove-Item Env:T17C2_FIXTURE_PASSWORD -ErrorAction SilentlyContinue
        [Environment]::SetEnvironmentVariable('NODE_PATH',$fixtureNodePath,'Process')
        [Environment]::SetEnvironmentVariable('PYTHONPATH',$fixturePythonPath,'Process'); $fixtureCredential=$null
    }
}
if($Action -eq 'cleanup'){Remove-Item -LiteralPath $fixtureCredentialPath}
