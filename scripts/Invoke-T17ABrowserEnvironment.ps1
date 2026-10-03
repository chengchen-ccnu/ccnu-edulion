[CmdletBinding()]
param([ValidateSet('START','STOP')][string]$Action='START', [ValidateRange(1,65535)][int]$OperationRedisPort=6379)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repositoryRoot=Split-Path -Parent $PSScriptRoot
$stateDirectory=Join-Path $repositoryRoot '.m2/t17a-browser'
$pidFile=Join-Path $stateDirectory 'pids.json'
# 本任务只持有自己的PID；STOP验证启动时间，不能因PID复用停止其他工作。
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
if(Test-Path -LiteralPath $pidFile){throw 'T17A process manifest already exists; STOP it before restarting.'}
New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null
& (Join-Path $PSScriptRoot 'Invoke-WithLocalEnvironment.ps1') -ScriptBlock {
    # 所有凭据只由加密加载器注入；启动发布器前拒绝存量PENDING，禁止重放历史FAILED。
    $previousPassword=[Environment]::GetEnvironmentVariable('MYSQL_PWD','Process')
    try {
        $env:MYSQL_PWD=$env:MYSQL_PASSWORD
        foreach($entry in @(@('iam','iam_outbox_event'),@('course','outbox_event'),@('learning','learning_outbox_event'),@('operation','operation_outbox_event'),@('media','outbox_event'))) {
            $schema=[Environment]::GetEnvironmentVariable("$($entry[0].ToUpper())_MYSQL_DATABASE",'Process')
            if($schema -ne "edu_platform_$($entry[0])_dev"){throw 'T17A only starts against explicitly named development databases.'}
            $pending=& mysql --host=$env:MYSQL_HOST --port=$env:MYSQL_PORT --user=$env:MYSQL_USERNAME --batch --skip-column-names --execute="SELECT COUNT(*) FROM $schema.$($entry[1]) WHERE status='PENDING'"
            if($LASTEXITCODE -ne 0 -or $pending -ne '0'){throw 'Existing PENDING events block publisher startup; no data changed.'}
        }
    } finally {[Environment]::SetEnvironmentVariable('MYSQL_PWD',$previousPassword,'Process')}
    $env:IAM_SEED_ENABLED='false';$env:COURSE_SEED_ENABLED='false';$env:RABBITMQ_ENABLED='true'
    $env:VITE_GATEWAY_BASE_URL='http://localhost:8080'
    $started=@()
    try {
        foreach($entry in @(@('iam',8081),@('course',8082),@('media',8083),@('learning',8084),@('operation',8085),@('gateway',8080),@('student',5173),@('teacher',5174),@('admin',5175))) {
            $name=$entry[0];$port=$entry[1]
            if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Port $port is occupied."}
            if($port -ge 8080) {
                $file='java';$arguments=@('-jar',"java-platform/$name-service/target/$name-service-0.1.0-SNAPSHOT.jar",'--spring.profiles.active=local')
                if($name -eq 'iam'){$arguments+='--management.health.mail.enabled=false'}
                # 只改变本轮Operation连接端口，可用未监听端口验证UNAVAILABLE，不停止共享Redis。
                if($name -eq 'operation'){$arguments+="--spring.data.redis.port=$OperationRedisPort"}
            } else {$file='pnpm.cmd';$arguments=@('--dir','web-apps','--filter',"@edu/$name-web",'run','dev','--','--host','127.0.0.1','--port',"$port")}
            $process=Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stateDirectory "$name.log") -RedirectStandardError (Join-Path $stateDirectory "$name.err.log")
            $started+= [pscustomobject]@{name=$name;port=$port;pid=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('O')}
            $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
        }
        foreach($entry in $started) {
            for($attempt=0;$attempt -lt 100;$attempt++) {
                $listener=Get-NetTCPConnection -State Listen -LocalPort $entry.port -ErrorAction SilentlyContinue | Select-Object -First 1
                if($listener){break};Start-Sleep -Milliseconds 400
            }
            if(-not $listener){throw "$($entry.name) startup timeout."}
            if($listener.OwningProcess -ne $entry.pid){
                $child=Get-Process -Id $listener.OwningProcess
                $started+= [pscustomobject]@{name="$($entry.name)-listener";port=$entry.port;pid=$child.Id;startedAt=$child.StartTime.ToUniversalTime().ToString('O')}
            }
        }
        $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
        $started | Select-Object name,port
    } catch { foreach($entry in $started){Stop-Process -Id $entry.pid -ErrorAction SilentlyContinue};Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue;throw }
}
