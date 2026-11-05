param([int]$Port = 8081)
$ErrorActionPreference = 'Stop'
$pythonPath = (Get-Command python.exe -ErrorAction Stop).Source
$healthUrl = "http://127.0.0.1:$Port/api/health"
Add-Type -AssemblyName System.Net.Http
$localHandler = [System.Net.Http.HttpClientHandler]::new()
$localHandler.UseProxy = $false
$localClient = [System.Net.Http.HttpClient]::new($localHandler)
$localClient.Timeout = [TimeSpan]::FromSeconds(2)
function Read-LocalHealth {
    $json = $localClient.GetStringAsync($healthUrl).GetAwaiter().GetResult()
    return $json | ConvertFrom-Json
}
try {
    $existing = Read-LocalHealth
    if ($existing.service -eq 'try-to-batter') { Write-Output "电脑服务已运行：http://127.0.0.1:$Port"; exit 0 }
    throw "端口 $Port 已被其他服务占用"
} catch {
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $Port 已被其他服务占用" }
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$logRoot = Join-Path $projectRoot 'artifacts\logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$serverPath = Join-Path $PSScriptRoot 'server.py'
$process = Start-Process -FilePath $pythonPath -ArgumentList @('-u', ('"' + $serverPath + '"'), '--port', $Port) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'lan-server.log') -RedirectStandardError (Join-Path $logRoot 'lan-server-error.log')
foreach ($attempt in 1..10) {
    Start-Sleep -Milliseconds 500
    try { $health = Read-LocalHealth; if ($health.service -eq 'try-to-batter') { Write-Output "电脑控制台：http://127.0.0.1:$Port（进程 $($process.Id)）"; exit 0 } } catch {}
}
throw '服务未就绪，请查看 artifacts/logs/lan-server-error.log'
