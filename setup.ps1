# Agent Friday TV setup for Windows: finds Python, then runs the guided setup.
#   Right-click > Run with PowerShell, or:  powershell -ExecutionPolicy Bypass -File .\setup.ps1
# Any arguments are passed on (for example: .\setup.ps1 verify --device 192.168.0.20:41234).
$ErrorActionPreference = 'Stop'
$py = $null
foreach ($c in @('py -3', 'python', 'python3')) {
    $exe, $arg = $c.Split(' ', 2)
    if (Get-Command $exe -ErrorAction SilentlyContinue) {
        $v = & $exe $arg -c "import sys; print(sys.version_info >= (3, 9))" 2>$null
        if ($v -eq 'True') { $py = @($exe) + @($arg | Where-Object { $_ }); break }
    }
}
if (-not $py) {
    Write-Host "Python 3.9 or later is needed. Install it with:  winget install Python.Python.3.12"
    Write-Host "or from https://www.python.org/downloads/ , then run this again."
    exit 1
}
Push-Location (Join-Path $PSScriptRoot 'wizard')
$pre = @(); if ($py.Length -gt 1) { $pre = $py[1..($py.Length - 1)] }
try { & $py[0] @pre -m fridaytv_setup @args } finally { Pop-Location }
