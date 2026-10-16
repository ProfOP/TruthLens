$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.truthlens-pids.txt'

function Stop-ProcessTree([int]$ParentId) {
  $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ParentId" -ErrorAction SilentlyContinue
  foreach ($child in $children) {
    Stop-ProcessTree -ParentId $child.ProcessId
    Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
  }
}

if (-not (Test-Path $pidFile)) {
  Write-Host 'No .truthlens-pids.txt file was found. Only processes started by start-local.ps1 are stopped here.'
  exit 0
}
Get-Content -LiteralPath $pidFile | ForEach-Object {
  if ($_ -match '=(\d+)$') {
    $procId = [int]$Matches[1]
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($proc) {
      Write-Host "Stopping PID $procId ($($proc.ProcessName)) and its children"
      Stop-ProcessTree -ParentId $procId
      Stop-Process -Id $procId -ErrorAction SilentlyContinue
    }
  }
}
Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
Write-Host 'Requested stop for TruthLens processes recorded by start-local.ps1. Unrelated Java/Python processes were not touched.'
