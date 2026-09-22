[CmdletBinding()]
param(
    [ValidateSet("START", "STOP")]
    [string]$Action = "START",
    [string]$PythonPath = "D:\Anaconda\envs\langgraph\python.exe"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$stateDirectory = Join-Path (Get-Location) ".m2\t15d-browser"
$pidFile = Join-Path $stateDirectory "pids.json"

if ($Action -eq "STOP") {
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) { return }
    $entries = @(Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json)
    $ownedProcessIds = @($entries | ForEach-Object { @([int]$_.pid, [int]$_.listenerPid) } | Select-Object -Unique)
    foreach ($ownedProcessId in $ownedProcessIds) {
        $process = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
        if ($process) { Stop-Process -Id $process.Id; [void]$process.WaitForExit(5000) }
    }
    Remove-Item -LiteralPath $pidFile -Force
    return
}

if (-not (Test-Path -LiteralPath $PythonPath -PathType Leaf)) {
    throw "未找到 T15D 浏览器验收所需的 Python Runtime。"
}
New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null
$specifications = @(
    @{ name = "iam"; port = 8081; file = "java"; arguments = @("-jar", "java-platform/iam-service/target/iam-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local") },
    @{ name = "media"; port = 8083; file = "java"; arguments = @("-jar", "java-platform/media-service/target/media-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local") },
    @{ name = "learning"; port = 8084; file = "java"; arguments = @("-jar", "java-platform/learning-service/target/learning-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local") },
    @{ name = "agent"; port = 8086; file = "java"; arguments = @("-jar", "java-platform/agent-access-service/target/agent-access-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local") },
    @{ name = "runtime"; port = 8000; file = $PythonPath; arguments = @("-m", "edu_agent_app") },
    @{ name = "gateway"; port = 8080; file = "java"; arguments = @("-jar", "java-platform/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local") }
)

$started = @()
try {
    foreach ($specification in $specifications) {
        if (Get-NetTCPConnection -State Listen -LocalPort $specification.port -ErrorAction SilentlyContinue) {
            throw "端口 $($specification.port) 已被占用；为避免停止非本次进程，验收环境未启动。"
        }
        $process = Start-Process -FilePath $specification.file -ArgumentList $specification.arguments `
            -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $stateDirectory "$($specification.name).log") `
            -RedirectStandardError (Join-Path $stateDirectory "$($specification.name).err.log")
        $started += [pscustomobject]@{ name = $specification.name; port = $specification.port;
            pid = $process.Id; listenerPid = $process.Id }
    }
    $env:VITE_GATEWAY_BASE_URL = "http://localhost:8080"
    $web = Start-Process -FilePath "pnpm.cmd" -ArgumentList @("--dir", "web-apps", "--filter", "@edu/student-web", "run", "dev", "--", "--host", "127.0.0.1", "--port", "5173") `
        -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $stateDirectory "student.log") `
        -RedirectStandardError (Join-Path $stateDirectory "student.err.log")
    $started += [pscustomobject]@{ name = "student"; port = 5173; pid = $web.Id; listenerPid = $web.Id }
    # 启动后立即落盘 launcher PID。即使调用终端在全部端口就绪前被打断，STOP 仍有精确目标，
    # 不会留下只能靠端口猜测归属的后台进程；监听子进程出现后再逐项覆盖 listenerPid。
    $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
    foreach ($entry in $started) {
        $ready = $false
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            if (Get-NetTCPConnection -State Listen -LocalPort $entry.port -ErrorAction SilentlyContinue) {
                $ready = $true
                break
            }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw "$($entry.name) 未在限定时间内监听端口 $($entry.port)。" }
        $listener = Get-NetTCPConnection -State Listen -LocalPort $entry.port -ErrorAction Stop | Select-Object -First 1
        $entry.listenerPid = [int]$listener.OwningProcess
        $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
    }
    $started | Select-Object name, port | ConvertTo-Json -Compress
} catch {
    $ownedProcessIds = @($started | ForEach-Object { @([int]$_.pid, [int]$_.listenerPid) } | Select-Object -Unique)
    foreach ($ownedProcessId in $ownedProcessIds) {
        $process = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
        if ($process) { Stop-Process -Id $process.Id }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    throw
}
