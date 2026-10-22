# 显式Mock浏览器环境；不加载任何本地凭据，不连接真实Gateway/SMTP/数据库。
[CmdletBinding()]
param([ValidateSet('START','STOP')][string]$Action='START')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$directory=Join-Path $repositoryRoot '.m2/t17c3-browser'
$pidFile=Join-Path $directory 'mock-pids.json'
if($Action -eq 'STOP') {
    if(Test-Path -LiteralPath $pidFile) {
        foreach($entry in @(Get-Content -Raw -LiteralPath $pidFile | ConvertFrom-Json)) {
            $process=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
            if($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.startedAt).ToUniversalTime().Ticks){Stop-Process -Id $entry.pid}
        }
        Remove-Item -LiteralPath $pidFile
    }
    return
}
if(Test-Path -LiteralPath $pidFile){throw 'Existing Mock process manifest; STOP first.'}
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$env:VITE_GATEWAY_BASE_URL='http://127.0.0.1:14010'
$env:VITE_STUDENT_WEB_URL='http://127.0.0.1:15174'
$env:VITE_ADMIN_WEB_URL='http://127.0.0.1:15173'
$env:VITE_TEACHER_WEB_URL='http://127.0.0.1:15175'
$started=@()
try {
    foreach($entry in @(@('mock',14010),@('student',15174),@('admin',15173))) {
        $name=$entry[0];$port=$entry[1]
        if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Port $port occupied."}
        if($name -eq 'mock'){$file='node';$arguments=@('scripts/t17c3_browser_mock.cjs')}
        else {$file='pnpm.cmd';$arguments=@('--dir','web-apps','--filter',"@edu/$name-web",'run','dev','--host','127.0.0.1','--port',"$port",'--strictPort')}
        $process=Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory "$name-mock.log") -RedirectStandardError (Join-Path $directory "$name-mock.err.log")
        $started+=[pscustomobject]@{pid=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('O')}
        $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
        for($attempt=0;$attempt -lt 100;$attempt++) {
            $listener=Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
            if($listener){break};Start-Sleep -Milliseconds 200
        }
        if(-not $listener){throw 'Mock startup timeout.'}
        if($listener.OwningProcess -ne $process.Id) {$child=Get-Process -Id $listener.OwningProcess;$started+=[pscustomobject]@{pid=$child.Id;startedAt=$child.StartTime.ToUniversalTime().ToString('O')}}
        $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
    }
    Write-Output 'Explicit Mock ready: 14010 API / 15173 admin / 15174 student'
} catch {
    foreach($entry in $started){$process=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue;if($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.startedAt).ToUniversalTime().Ticks){Stop-Process -Id $entry.pid}}
    Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue;throw
}
