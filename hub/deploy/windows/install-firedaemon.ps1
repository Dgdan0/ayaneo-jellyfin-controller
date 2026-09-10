[CmdletBinding()]
param(
    [string]$SourceBinary = '',
    [string]$InstallDirectory = 'C:\Program Files\AyaneoHub',
    [string]$ConfigPath = 'C:\ProgramData\AyaneoHub\hub.yaml'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$transcriptPath = 'C:\ProgramData\AyaneoHub\firedaemon-install.log'
$transcriptStarted = $false
try {
    Start-Transcript -Path $transcriptPath -Append | Out-Null
    $transcriptStarted = $true
} catch {
    # Installation can continue if PowerShell transcription is unavailable.
}
trap {
    Write-Error $_
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
    exit 1
}

if ([string]::IsNullOrWhiteSpace($SourceBinary)) {
    $SourceBinary = Join-Path $PSScriptRoot '..\..\hub.exe'
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an Administrator PowerShell session.'
}

$source = (Resolve-Path -LiteralPath $SourceBinary).Path
$target = Join-Path $InstallDirectory 'hub.exe'
$definition = Join-Path $PSScriptRoot 'AyaneoHub.firedaemon.xml'
$fireDaemon = 'C:\Program Files\FireDaemon Pro\FireDaemonCLI.exe'

foreach ($required in @($source, $ConfigPath, $definition, $fireDaemon)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}

& $source --check --config $ConfigPath
if ($LASTEXITCODE -ne 0) {
    throw "Hub configuration validation failed with exit code $LASTEXITCODE."
}

$installedService = Get-Service -Name 'AyaneoHub' -ErrorAction SilentlyContinue
if ($null -ne $installedService -and $installedService.Status -ne 'Stopped') {
    & $fireDaemon control stop AyaneoHub
    if ($LASTEXITCODE -ne 0) {
        throw "Could not stop the existing AyaneoHub service (exit code $LASTEXITCODE)."
    }
}

# Retire only copies of this hub. This removes the temporary development
# process that otherwise keeps 127.0.0.1:8791 occupied during the handoff.
foreach ($process in Get-Process -Name 'hub' -ErrorAction SilentlyContinue) {
    $path = $null
    try { $path = $process.Path } catch { continue }
    if ($path -eq $source -or $path -eq $target) {
        Stop-Process -Id $process.Id -Force
        Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue
    }
}

New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
Copy-Item -LiteralPath $source -Destination $target -Force

& $fireDaemon install $definition --update --no-restart
if ($LASTEXITCODE -ne 0) {
    throw "FireDaemon service installation failed with exit code $LASTEXITCODE."
}

& $fireDaemon control start AyaneoHub
if ($LASTEXITCODE -ne 0) {
    throw "FireDaemon service start failed with exit code $LASTEXITCODE."
}

$deadline = (Get-Date).AddSeconds(15)
do {
    Start-Sleep -Milliseconds 500
    $service = Get-Service -Name 'AyaneoHub'
    $listener = Get-NetTCPConnection -LocalAddress '127.0.0.1' -LocalPort 8791 -State Listen -ErrorAction SilentlyContinue
} while (($service.Status -ne 'Running' -or $null -eq $listener) -and (Get-Date) -lt $deadline)

if ($service.Status -ne 'Running') {
    throw "AyaneoHub did not remain running; current state: $($service.Status)."
}
if ($null -eq $listener) {
    throw 'AyaneoHub is running but did not open 127.0.0.1:8791 within 15 seconds.'
}

$program = Get-Process -Id $listener.OwningProcess -ErrorAction Stop
if ($program.Path -ne $target) {
    throw "Port 8791 is owned by an unexpected executable: $($program.Path)"
}

Write-Host "AyaneoHub is running under FireDaemon (PID $($program.Id))."
Write-Host "Listening on 127.0.0.1:8791."
if ($transcriptStarted) { Stop-Transcript | Out-Null }
