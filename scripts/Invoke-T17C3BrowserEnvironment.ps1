# T17C.3 最小真实环境：仅本轮Gateway/IAM/两前端，禁用Seed和无关Worker，不发布历史Outbox。
[CmdletBinding()]
param([ValidateSet('START','STOP')][string]$Action='START')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$stateDirectory=Join-Path $repositoryRoot '.m2/t17c3-browser'
$pidFile=Join-Path $stateDirectory 'pids.json'
if($Action -eq 'STOP') {
    if(Test-Path -LiteralPath $pidFile) {
        foreach($entry in @(Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json)) {
            $process=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
            if($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.startedAt).ToUniversalTime().Ticks){Stop-Process -Id $entry.pid}
        }
        Remove-Item -LiteralPath $pidFile
    }
    return
}
if(Test-Path -LiteralPath $pidFile){throw 'Existing T17C.3 process manifest; STOP before restarting.'}
New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    if($env:IAM_MYSQL_DATABASE -ne 'edu_platform_iam_dev'){throw 'Only explicit IAM dev schema allowed.'}
    $env:IAM_SEED_ENABLED='false';$env:IAM_FIRST_ADMINISTRATOR_ENABLED='false'
    $env:IAM_ACCOUNT_COORDINATION_ENABLED='false';$env:IAM_REVIEW_MESSAGING_ENABLED='false';$env:IAM_REVIEW_RETENTION_ENABLED='false'
    $env:RABBITMQ_ENABLED='false'
    $env:VITE_GATEWAY_BASE_URL='http://localhost:8080'
    $env:VITE_STUDENT_WEB_URL='http://localhost:5174'
    $env:VITE_TEACHER_WEB_URL='http://localhost:5175'
    $env:VITE_ADMIN_WEB_URL='http://localhost:5173'
    $started=@()
    try {
        foreach($entry in @(@('iam',8081),@('gateway',8080),@('student',5174),@('admin',5173))) {
            $name=$entry[0];$port=$entry[1]
            if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Port $port is occupied."}
            if($port -ge 8080) {
                $file='java';$arguments=@('-jar',"java-platform/$name-service/target/$name-service-0.1.0-SNAPSHOT.jar",'--spring.profiles.active=local')
                if($name -eq 'iam'){$arguments+='--management.health.mail.enabled=false'}
            } else {$file='pnpm.cmd';$arguments=@('--dir','web-apps','--filter',"@edu/$name-web",'run','dev','--host','127.0.0.1','--port',"$port",'--strictPort')}
            $process=Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stateDirectory "$name.log") -RedirectStandardError (Join-Path $stateDirectory "$name.err.log")
            $started+= [pscustomobject]@{name=$name;port=$port;pid=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('O')}
            $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
        }
        foreach($entry in @($started)) {
            $listener=$null
            for($attempt=0;$attempt -lt 100;$attempt++) {
                $listener=Get-NetTCPConnection -State Listen -LocalPort $entry.port -ErrorAction SilentlyContinue | Select-Object -First 1
                if($listener){break};Start-Sleep -Milliseconds 400
            }
            if(-not $listener){throw "$($entry.name) startup timeout."}
            if($listener.OwningProcess -ne $entry.pid) {
                $child=Get-Process -Id $listener.OwningProcess
                $started+= [pscustomobject]@{name="$($entry.name)-listener";port=$entry.port;pid=$child.Id;startedAt=$child.StartTime.ToUniversalTime().ToString('O')}
                $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
            }
        }
        $started | Select-Object name,port
    } catch {
        foreach($entry in $started){$process=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue;if($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.startedAt).ToUniversalTime().Ticks){Stop-Process -Id $entry.pid}}
        Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue;throw
    }
}
