param(
  [int]$BackendPort = 8080,
  [int]$AiPort = 8001,
  [int]$FrontendPort = 5173
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Import-DotEnv([string]$Path) {
  if (-not (Test-Path $Path)) { return }
  Get-Content -LiteralPath $Path | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { return }
    $name = $line.Substring(0, $idx).Trim()
    $value = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
    if (-not [string]::IsNullOrWhiteSpace($name) -and -not [Environment]::GetEnvironmentVariable($name, 'Process')) {
      Set-Item -Path "Env:$name" -Value $value
    }
  }
}

Import-DotEnv (Join-Path $root '.env')
if (-not $env:TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD) { $env:TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD = '0.70' }
if (-not $env:TRUTHLENS_IMAGE_UNCERTAIN_MARGIN) { $env:TRUTHLENS_IMAGE_UNCERTAIN_MARGIN = '0.05' }
# Optional candidate-evidence discovery path; '0' keeps /v1/verify on the deployed FAISS path alone.
if (-not $env:TRUTHLENS_RELATED_PASSAGES) { $env:TRUTHLENS_RELATED_PASSAGES = '0' }
if (-not $env:AI_SERVICE_URL) { $env:AI_SERVICE_URL = "http://localhost:$AiPort" }
if (-not $env:SPRING_DATASOURCE_URL) { $env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/truthlens' }
if (-not $env:SPRING_DATASOURCE_USERNAME) { $env:SPRING_DATASOURCE_USERNAME = 'truthlens' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $root '.tools\jdk21\jdk-21.0.12.1+1' }
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
$env:PORT = "$BackendPort"

if (-not $env:JWT_SECRET -or $env:JWT_SECRET.Length -lt 32) {
  throw 'JWT_SECRET must be set in the environment or .env (at least 32 characters). The value is not printed.'
}
if (-not $env:SPRING_DATASOURCE_PASSWORD) {
  throw 'SPRING_DATASOURCE_PASSWORD must be set in the environment or .env. The value is not printed.'
}

$python = Join-Path $root 'ai-service\.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { throw "Missing $python" }
$logDir = Join-Path $root 'verification-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$pidFile = Join-Path $root '.truthlens-pids.txt'
# Timestamped logs so a new launch never overwrites logs a running instance holds open.
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Test-Port([string]$Computer, [int]$Port) {
  try {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect($Computer, $Port)
    $client.Close()
    return $true
  } catch { return $false }
}

function Wait-Ready([string]$Label, [scriptblock]$Probe, [int]$TimeoutSec) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    try { if (& $Probe) { Write-Host "  $Label ready." -ForegroundColor Green; return $true } } catch {}
    Start-Sleep -Milliseconds 500
  }
  Write-Host "  $Label NOT ready within ${TimeoutSec}s." -ForegroundColor Yellow
  return $false
}

function Test-Alive([int]$Id) {
  return $null -ne (Get-Process -Id $Id -ErrorAction SilentlyContinue)
}

$records = @()
$anyFailure = $false

# --- AI service (Python / uvicorn) ---
if (Test-Port '127.0.0.1' $AiPort) {
  Write-Host "AI port $AiPort is already in use by an existing listener; NOT launching a duplicate. If it is a stale TruthLens process, run scripts\stop-local.ps1 or free the port first." -ForegroundColor Yellow
} else {
  $aiOut = Join-Path $logDir "ai-service.$stamp.out.log"; $aiErr = Join-Path $logDir "ai-service.$stamp.err.log"
  $ai = Start-Process -FilePath $python -ArgumentList '-m','uvicorn','app:app','--app-dir','ai-service','--port',"$AiPort",'--host','127.0.0.1' -WorkingDirectory $root -RedirectStandardOutput $aiOut -RedirectStandardError $aiErr -PassThru -WindowStyle Hidden
  $records += "ai=$($ai.Id)"
  if (-not (Test-Alive $ai.Id)) {
    Write-Host "  AI process (PID $($ai.Id)) exited immediately. See $aiErr" -ForegroundColor Red; $anyFailure = $true
  } elseif (Wait-Ready 'AI' { (Test-Port '127.0.0.1' $AiPort) } 30) {
    if (-not (Test-Alive $ai.Id)) { Write-Host "  AI exited after binding. See $aiErr" -ForegroundColor Red; $anyFailure = $true }
  } else { $anyFailure = $true }
}

# --- Backend (Spring Boot) ---
if (Test-Port '127.0.0.1' $BackendPort) {
  Write-Host "Backend port $BackendPort is already in use; NOT launching a duplicate. Stop the previous instance or pass -BackendPort." -ForegroundColor Yellow
} else {
  $bOut = Join-Path $logDir "backend.$stamp.out.log"; $bErr = Join-Path $logDir "backend.$stamp.err.log"
  $mvnw = Join-Path $root 'backend\mvnw.cmd'
  $backend = Start-Process -FilePath $mvnw -ArgumentList 'spring-boot:run' -WorkingDirectory (Join-Path $root 'backend') -RedirectStandardOutput $bOut -RedirectStandardError $bErr -PassThru -WindowStyle Hidden
  $records += "backend=$($backend.Id)"
  # Spring Boot startup is slow; readiness is the port, not the launcher PID.
  if (Wait-Ready 'Backend' { (Test-Port '127.0.0.1' $BackendPort) } 180) {
    Write-Host "  Backend listening on $BackendPort." -ForegroundColor Green
  } else { Write-Host "  Check $bErr / $bOut" -ForegroundColor Yellow; $anyFailure = $true }
}

# --- Frontend (Vite) ---
if (Test-Port '127.0.0.1' $FrontendPort) {
  Write-Host "Frontend port $FrontendPort is already in use; NOT launching a duplicate." -ForegroundColor Yellow
} else {
  $fOut = Join-Path $logDir "frontend.$stamp.out.log"; $fErr = Join-Path $logDir "frontend.$stamp.err.log"
  $npm = (Get-Command npm.cmd).Source
  $frontend = Start-Process -FilePath $npm -ArgumentList 'run','dev','--','--host','127.0.0.1','--port',"$FrontendPort" -WorkingDirectory (Join-Path $root 'frontend') -RedirectStandardOutput $fOut -RedirectStandardError $fErr -PassThru -WindowStyle Hidden
  $records += "frontend=$($frontend.Id)"
  if (Wait-Ready 'Frontend' { (Test-Port '127.0.0.1' $FrontendPort) } 60) { } else { Write-Host "  Check $fErr / $fOut" -ForegroundColor Yellow; $anyFailure = $true }
}

if ($records.Count -gt 0 -or (Test-Path $pidFile)) {
  # Merge by component so a partial (re)start does not drop records for
  # components that were already running and are still owned by this script.
  $merged = @{}
  if (Test-Path $pidFile) {
    Get-Content -LiteralPath $pidFile | ForEach-Object {
      if ($_ -match '^(\w+)=(\d+)$') {
        $name = $Matches[1]; $id = [int]$Matches[2]
        if (Test-Alive $id) { $merged[$name] = $id }
      }
    }
  }
  foreach ($r in $records) {
    if ($r -match '^(\w+)=(\d+)$') { $merged[$Matches[1]] = [int]$Matches[2] }
  }
  if ($merged.Count -gt 0) {
    $merged.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" } | Set-Content -LiteralPath $pidFile
    Write-Host "Recorded running components in .truthlens-pids.txt: $(($merged.GetEnumerator() | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', '). Stop with: powershell -File scripts\stop-local.ps1"
  } else {
    Write-Host "No TruthLens components are recorded as running."
  }
} else {
  Write-Host "No new processes were started (all target ports were already in use)."
}
if ($anyFailure) {
  Write-Host "Startup completed WITH problems: some services were not verified ready. Do not treat this as a healthy stack." -ForegroundColor Red
  exit 1
}
Write-Host "Startup complete. AI=http://localhost:$AiPort  Backend=http://localhost:$BackendPort  Frontend=http://localhost:$FrontendPort"
