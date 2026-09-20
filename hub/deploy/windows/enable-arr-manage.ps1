[CmdletBinding()]
param(
    [string]$SourceBinary = '',
    [string]$ConfigPath = 'C:\ProgramData\AyaneoHub\hub.yaml',
    [string]$TailnetIP = ''
)

# Adds Prowlarr and Readarr as health-and-dashboard entries. Their API keys are
# copied from the local *arr configurations into Hub's existing secrets overlay;
# they never reach the APK or command output.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Utf8([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function Has-Service([string]$Text, [string]$Name) {
    return [regex]::IsMatch($Text, "(?m)^  " + [regex]::Escape($Name) + ":\s*$")
}

function Add-PrimaryService([string]$Path, [string]$Name, [string]$Block) {
    $text = [IO.File]::ReadAllText($Path)
    if (Has-Service $text $Name) { return $false }
    $anchor = [regex]::Match($text, '(?m)^log:\s*$')
    if (-not $anchor.Success) { throw "Could not find the log section in $Path" }
    Write-Utf8 $Path ($text.Insert($anchor.Index, $Block + [Environment]::NewLine))
    return $true
}

function Add-SecretService([string]$Path, [string]$Name, [string]$Block) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Utf8 $Path ("services:" + [Environment]::NewLine + $Block + [Environment]::NewLine)
        return $true
    }
    $text = [IO.File]::ReadAllText($Path)
    if (Has-Service $text $Name) { return $false }
    $services = [regex]::Match($text, '(?m)^services:\s*$')
    if (-not $services.Success) {
        Write-Utf8 $Path ($text.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + 'services:' + [Environment]::NewLine + $Block + [Environment]::NewLine)
        return $true
    }
    $tail = $text.Substring($services.Index + $services.Length)
    $nextTop = [regex]::Match($tail, '(?m)^[^\s#][^:\r\n]*:\s*$')
    $insertAt = if ($nextTop.Success) { $services.Index + $services.Length + $nextTop.Index } else { $text.Length }
    $prefix = if ($insertAt -gt 0 -and $text[$insertAt - 1] -eq "`n") { '' } else { [Environment]::NewLine }
    Write-Utf8 $Path ($text.Insert($insertAt, $prefix + $Block + [Environment]::NewLine))
    return $true
}

function Read-ArrConfig([string]$Name) {
    $path = "C:\ProgramData\$Name\config.xml"
    if (-not (Test-Path -LiteralPath $path)) { throw "$Name is not installed at $path" }
    [xml]$xml = Get-Content -LiteralPath $path -Raw
    $port = [string]$xml.Config.Port
    $apiKey = [string]$xml.Config.ApiKey
    if ([string]::IsNullOrWhiteSpace($port) -or [string]::IsNullOrWhiteSpace($apiKey)) {
        throw "$Name does not have a usable port and API key"
    }
    return @{ Port = $port; APIKey = $apiKey }
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an Administrator PowerShell session.'
}

if ([string]::IsNullOrWhiteSpace($SourceBinary)) {
    $SourceBinary = Join-Path $PSScriptRoot '..\..\hub.exe'
}
$source = (Resolve-Path -LiteralPath $SourceBinary).Path
if (-not (Test-Path -LiteralPath $ConfigPath)) { throw "Hub config not found: $ConfigPath" }

if ([string]::IsNullOrWhiteSpace($TailnetIP)) {
    $tailscale = Get-Command tailscale.exe -ErrorAction SilentlyContinue
    if ($null -eq $tailscale) { $tailscale = Get-Command tailscale -ErrorAction SilentlyContinue }
    if ($null -eq $tailscale) { throw 'Tailscale CLI was not found; pass -TailnetIP with this PC''s Tailscale IPv4 address.' }
    $TailnetIP = (& $tailscale.Source ip -4 | Select-Object -First 1).Trim()
}
if ([string]::IsNullOrWhiteSpace($TailnetIP)) { throw 'Could not determine this PC''s Tailscale IPv4 address.' }

$prowlarr = Read-ArrConfig 'Prowlarr'
$readarr = Read-ArrConfig 'Readarr'
$dataDir = Split-Path -Parent $ConfigPath
$secretsPath = Join-Path $dataDir 'hub.secrets.yaml'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$configBackup = "$ConfigPath.before-arr-$stamp.bak"
$secretsExisted = Test-Path -LiteralPath $secretsPath
$secretsBackup = "$secretsPath.before-arr-$stamp.bak"
Copy-Item -LiteralPath $ConfigPath -Destination $configBackup -Force
if ($secretsExisted) { Copy-Item -LiteralPath $secretsPath -Destination $secretsBackup -Force }

try {
    $prowlarrBlock = "  prowlarr:`n    enabled: true`n    base_url: `"http://127.0.0.1:$($prowlarr.Port)`"`n    web_url: `"http://$TailnetIP`:$($prowlarr.Port)`"`n"
    $readarrBlock = "  readarr:`n    enabled: true`n    base_url: `"http://127.0.0.1:$($readarr.Port)`"`n    web_url: `"http://$TailnetIP`:$($readarr.Port)`"`n"
    Add-PrimaryService $ConfigPath 'prowlarr' $prowlarrBlock | Out-Null
    Add-PrimaryService $ConfigPath 'readarr' $readarrBlock | Out-Null
    Add-SecretService $secretsPath 'prowlarr' ("  prowlarr:`n    api_key: `"$($prowlarr.APIKey)`"") | Out-Null
    Add-SecretService $secretsPath 'readarr' ("  readarr:`n    api_key: `"$($readarr.APIKey)`"") | Out-Null

    & $source --check --config $ConfigPath
    if ($LASTEXITCODE -ne 0) { throw "Hub configuration validation failed with exit code $LASTEXITCODE." }

    & (Join-Path $PSScriptRoot 'install-firedaemon.ps1') -SourceBinary $source -ConfigPath $ConfigPath
    if ($LASTEXITCODE -ne 0) { throw "FireDaemon installation failed with exit code $LASTEXITCODE." }

    Write-Host 'Prowlarr and Readarr are enabled in Ayaneo Hub Manage.'
    Write-Host "Backups: $configBackup"
} catch {
    Copy-Item -LiteralPath $configBackup -Destination $ConfigPath -Force
    if ($secretsExisted) { Copy-Item -LiteralPath $secretsBackup -Destination $secretsPath -Force }
    elseif (Test-Path -LiteralPath $secretsPath) { Remove-Item -LiteralPath $secretsPath -Force }
    throw
}
