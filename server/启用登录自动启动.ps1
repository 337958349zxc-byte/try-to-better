$ErrorActionPreference = 'Stop'
$serverPath = Join-Path $PSScriptRoot '启动电脑服务.ps1'
$startupRoot = [Environment]::GetFolderPath('Startup')
if (-not (Test-Path -LiteralPath $startupRoot -PathType Container)) { throw '找不到当前用户启动目录' }
$shortcutPath = Join-Path $startupRoot 'try-to-batter-电脑连接.lnk'
$shellObject = New-Object -ComObject WScript.Shell
$shortcut = $shellObject.CreateShortcut($shortcutPath)
if ((Test-Path -LiteralPath $shortcutPath) -and -not $shortcut.Arguments.Contains($serverPath)) { throw '同名启动项已存在，请先检查' }
$shortcut.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$shortcut.Arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $serverPath + '"'
$shortcut.WorkingDirectory = Split-Path -Parent $PSScriptRoot
$shortcut.WindowStyle = 7
$shortcut.Description = '登录 Windows 后启动 try to batter 局域网连接服务'
$shortcut.Save()
Write-Output $shortcutPath
