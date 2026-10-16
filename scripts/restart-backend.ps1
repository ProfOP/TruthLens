$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.truthlens-pids.txt'

function Test-Port([string]$Computer, [int]$Port) {
  try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect($Computer, $Port); $c.Close(); return $true } catch { return $false }
}

function Stop-ProcessTree([int]$ParentId) {
  Get-CimInstance Win32_Process -Filter "ParentProcessId=$ParentId" -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-ProcessTree -ParentId $_.ProcessId
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }
}

$backendId = $null
if (Test-Path $pidFile) {
  Get-Content -LiteralPath $pidFile | ForEach-Object { if ($_ -match '^backend=(\d+)$') { $backendId = [int]$Matches[1] } }
}
if ($null -eq $backendId) { throw 'No backend= record in .truthlens-pids.txt; refusing to guess which java process to stop.' }

$proc = Get-Process -Id $backendId -ErrorAction SilentlyContinue
if ($proc) {
  Write-Host "Stopping recorded backend PID $backendId ($($proc.ProcessName)) and its children"
  Stop-ProcessTree -ParentId $backendId
  Stop-Process -Id $backendId -Force -ErrorAction SilentlyContinue
} else {
  Write-Host "Recorded backend PID $backendId is no longer alive."
}

$deadline = (Get-Date).AddSeconds(30)
while ((Test-Port '127.0.0.1' 8080) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
if (Test-Port '127.0.0.1' 8080) { throw 'Port 8080 is still in use after stopping the recorded backend.' }
Write-Host 'Port 8080 free.'

# AI and frontend stay up: start-local.ps1 skips components whose ports are already listening.
& (Join-Path $root 'scripts\start-local.ps1')
