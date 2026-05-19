$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

if (-not (Test-Path ".venv")) {
  python -m venv .venv
}

. .\.venv\Scripts\Activate.ps1
python -m pip install -U pip
python -m pip install -r requirements.txt

if (Test-Path ".\config\.env") {
  Get-Content ".\config\.env" | ForEach-Object {
    if ($_ -match "^\s*#") { return }
    if ($_ -match "^\s*$") { return }
    $kv = $_ -split "=", 2
    if ($kv.Count -eq 2) {
      [Environment]::SetEnvironmentVariable($kv[0], $kv[1], "Process")
    }
  }
}

python -m uvicorn backend.app:app --host 0.0.0.0 --port 8088

