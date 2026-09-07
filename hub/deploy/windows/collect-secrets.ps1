<#
.SYNOPSIS
  Builds hub.secrets.yaml from the API keys the services already hold.

.DESCRIPTION
  Every *arr app keeps its API key in a config file on this machine, so there is
  no reason to make anyone copy six of them out of six admin panels by hand.
  This reads them directly and writes hub.secrets.yaml, which the hub merges over
  hub.yaml at load time.

  Jellyfin is the exception: its keys live in jellyfin.db rather than a flat
  file, and a dedicated key you can revoke is better practice anyway. Create one
  under Dashboard -> API Keys and pass it as -JellyfinApiKey.

  Nothing is printed. The script reports found/missing per service and writes the
  values straight to disk, so no credential passes through a console, a scroll
  buffer, or a screenshot.

.EXAMPLE
  .\collect-secrets.ps1 -JellyfinApiKey "abc123..." -OutFile "C:\ProgramData\AyaneoHub\hub.secrets.yaml"
#>
[CmdletBinding()]
param(
    [string]$JellyfinApiKey = "",
    [string]$OutFile = "$env:ProgramData\AyaneoHub\hub.secrets.yaml",
    [string]$RadarrConfig = "$env:ProgramData\Radarr\config.xml",
    [string]$SonarrConfig = "$env:ProgramData\Sonarr\config.xml",
    [string]$BazarrConfig = "$env:ProgramData\Bazarr\config\config.yaml",
    [string]$JellyseerrConfig = "C:\jellyseerr\config\settings.json"
)

$ErrorActionPreference = 'Stop'

function Get-ArrKey([string]$path) {
    if (-not (Test-Path $path)) { return $null }
    try { return ([xml](Get-Content $path -Raw)).Config.ApiKey } catch { return $null }
}

function Get-BazarrKey([string]$path) {
    if (-not (Test-Path $path)) { return $null }
    # Deliberately not a YAML parser: this is one scalar in a large file and
    # adding a module dependency to read it would be silly.
    $line = Select-String -Path $path -Pattern '^\s*apikey:\s*(.+)$' | Select-Object -First 1
    if (-not $line) { return $null }
    return $line.Matches[0].Groups[1].Value.Trim().Trim("'").Trim('"')
}

function Get-JellyseerrKey([string]$path) {
    if (-not (Test-Path $path)) { return $null }
    try {
        $json = Get-Content $path -Raw | ConvertFrom-Json
        if ($json.main -and $json.main.apiKey) { return $json.main.apiKey }
        if ($json.apiKey) { return $json.apiKey }
    } catch { return $null }
    return $null
}

$keys = [ordered]@{
    radarr     = Get-ArrKey        $RadarrConfig
    sonarr     = Get-ArrKey        $SonarrConfig
    bazarr     = Get-BazarrKey     $BazarrConfig
    jellyseerr = Get-JellyseerrKey $JellyseerrConfig
    jellyfin   = if ($JellyfinApiKey) { $JellyfinApiKey } else { $null }
}

Write-Host ""
Write-Host "service      key"
Write-Host "----------------------------"
foreach ($name in $keys.Keys) {
    $state = if ($keys[$name]) { "found ({0} chars)" -f $keys[$name].Length } else { "MISSING" }
    "{0,-12} {1}" -f $name, $state | Write-Host
}
Write-Host ""

$missing = @($keys.Keys | Where-Object { -not $keys[$_] })
if ($missing -contains 'jellyfin') {
    Write-Host "Jellyfin: create a key under Dashboard -> API Keys, then re-run with" -ForegroundColor Yellow
    Write-Host "  -JellyfinApiKey ""<the key>""" -ForegroundColor Yellow
    Write-Host ""
}

$dir = Split-Path -Parent $OutFile
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }

# qBittorrent is absent on purpose: WebUI\LocalHostAuth=false on this machine,
# so a connection from loopback needs no credential at all. If that is ever
# turned on, add username/password here.
$lines = @(
    "# Written by collect-secrets.ps1. Merged over hub.yaml at load time.",
    "# Never commit this file. Never paste it anywhere.",
    "services:"
)
foreach ($name in $keys.Keys) {
    if ($keys[$name]) {
        $lines += "  {0}:" -f $name
        $lines += "    api_key: `"{0}`"" -f $keys[$name]
    }
}
Set-Content -Path $OutFile -Value ($lines -join "`n") -Encoding utf8 -NoNewline

# Only SYSTEM, Administrators and the owner. Inheritance off, or ProgramData's
# default "Users can read" would apply to a file full of API keys.
#
# Re-applying a protected ACL needs SeSecurityPrivilege, which an unelevated
# shell does not have -- so if the file already carries the right ACL from a
# previous run, leave it alone rather than failing the whole script over
# permissions that are already correct.
$acl = Get-Acl $OutFile
$alreadyLocked = $acl.AreAccessRulesProtected -and
    -not ($acl.Access | Where-Object { $_.IdentityReference -match 'Users|Everyone' })

if ($alreadyLocked) {
    Write-Host "wrote $OutFile" -ForegroundColor Green
    Write-Host "  permissions already restricted; left unchanged"
} else {
    $acl.SetAccessRuleProtection($true, $false)
    $acl.Access | ForEach-Object { [void]$acl.RemoveAccessRule($_) }
    foreach ($who in @("NT AUTHORITY\SYSTEM", "BUILTIN\Administrators", "$env:USERDOMAIN\$env:USERNAME")) {
        try {
            $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
                $who, "FullControl", "Allow")))
        } catch { Write-Warning "could not grant $who" }
    }
    try {
        Set-Acl -Path $OutFile -AclObject $acl -ErrorAction Stop
        Write-Host "wrote $OutFile" -ForegroundColor Green
        Write-Host "  readable by SYSTEM, Administrators and $env:USERNAME only"
    } catch {
        Write-Warning "wrote $OutFile but could not restrict its permissions."
        Write-Warning "Re-run this script from an elevated PowerShell, or set them by hand:"
        Write-Warning "  icacls `"$OutFile`" /inheritance:r /grant `"$env:USERNAME`:F`" `"SYSTEM:F`" `"Administrators:F`""
    }
}
Write-Host ""
Write-Host "next: hub.exe --check --config `"$dir\hub.yaml`""
