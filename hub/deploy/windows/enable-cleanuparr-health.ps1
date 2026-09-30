[CmdletBinding()]
param(
    [string]$SourceBinary = '',
    [string]$ConfigPath = 'C:\ProgramData\AyaneoHub\hub.yaml',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($SourceBinary)) {
    $SourceBinary = Join-Path $PSScriptRoot '..\..\hub.exe'
}

$source = (Resolve-Path -LiteralPath $SourceBinary).Path
$config = (Resolve-Path -LiteralPath $ConfigPath).Path

if (-not $SkipInstall) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this script from an Administrator PowerShell session.'
    }
}

$text = [System.IO.File]::ReadAllText($config)
$lineBreak = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
$changed = $false
$existing = [regex]::Match($text, '(?ms)^  cleanuparr:\r?\n(?<body>(?:^    [^\r\n]*(?:\r?\n|$))*)')

if ($existing.Success) {
    $body = $existing.Groups['body'].Value
    if ($body -match '(?m)^    enabled:[ \t]*false[ \t]*(?=\r?$)') {
        $updatedBody = [regex]::Replace($body, '(?m)^    enabled:[ \t]*false[ \t]*(?=\r?$)', '    enabled: true')
        $text = $text.Remove($existing.Groups['body'].Index, $body.Length).Insert($existing.Groups['body'].Index, $updatedBody)
        $changed = $true
    } elseif ($body -notmatch '(?m)^    enabled:') {
        $text = $text.Insert($existing.Groups['body'].Index, "    enabled: true$lineBreak")
        $changed = $true
    }
} else {
    $header = [regex]::Match($text, '(?m)^services:[ \t]*(?<break>\r?\n)')
    if (-not $header.Success) {
        throw 'The Hub configuration has no top-level services block.'
    }
    $lineBreak = $header.Groups['break'].Value
    $entry = @(
        '  cleanuparr:',
        '    enabled: true',
        '    base_url: "http://127.0.0.1:11011"',
        '    web_url: ""',
        ''
    ) -join $lineBreak
    $text = $text.Insert($header.Index + $header.Length, $entry)
    $changed = $true
}

$backup = "$config.before-cleanuparr"
if ($changed) {
    Copy-Item -LiteralPath $config -Destination $backup -Force
    [System.IO.File]::WriteAllText($config, $text)
}

& $source --check --config $config
if ($LASTEXITCODE -ne 0) {
    if ($changed) { Copy-Item -LiteralPath $backup -Destination $config -Force }
    throw "Hub configuration validation failed with exit code $LASTEXITCODE."
}

if ($SkipInstall) {
    Write-Host 'Cleanuparr health configuration validated; FireDaemon install skipped.'
    exit 0
}

& (Join-Path $PSScriptRoot 'install-firedaemon.ps1') -SourceBinary $source -ConfigPath $config
if ($LASTEXITCODE -ne 0) {
    throw "FireDaemon installation failed with exit code $LASTEXITCODE."
}

Write-Host 'Cleanuparr is enabled in Hub Manage as a read-only health status.'
