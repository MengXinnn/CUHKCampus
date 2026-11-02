$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command exited with code $LASTEXITCODE" }
}

function Import-CampusEnvironment {
    $path = Join-Path $ProjectRoot '.env'
    if (!(Test-Path $path)) { throw 'Run scripts\setup.cmd first.' }
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    if (!$env:CAMPUS_ENCRYPTION_KEY) { throw 'CAMPUS_ENCRYPTION_KEY is missing in .env.' }
}

function Set-CampusExecutable {
    param([string]$Variable, [string]$Command, [string[]]$Candidates)
    $configured = [Environment]::GetEnvironmentVariable($Variable, 'Process')
    if ($configured) {
        if (!(Get-Command $configured -ErrorAction SilentlyContinue)) { throw "$Variable does not point to an executable." }
        return
    }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            [Environment]::SetEnvironmentVariable($Variable, $candidate, 'Process')
            return
        }
    }
    $found = Get-Command $Command -ErrorAction SilentlyContinue
    if (!$found) { throw "Install $Command or set $Variable in .env." }
    [Environment]::SetEnvironmentVariable($Variable, $found.Source, 'Process')
}

function Initialize-CampusRuntime {
    Import-CampusEnvironment
    Invoke-Checked 'java' @('-version')
    Set-CampusExecutable 'CAMPUS_CHROME_BINARY' 'chrome.exe' @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
        "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
    )
    Set-CampusExecutable 'CAMPUS_SOFFICE_BINARY' 'soffice.exe' @(
        "$env:ProgramFiles\LibreOffice\program\soffice.exe",
        "${env:ProgramFiles(x86)}\LibreOffice\program\soffice.exe"
    )
    Set-CampusExecutable 'CAMPUS_CURL_BINARY' 'curl.exe' @("$env:SystemRoot\System32\curl.exe")
}

function Start-CampusDatabase {
    param([switch]$SkipDocker)
    if ($SkipDocker) { return }
    Invoke-Checked 'docker.exe' @('compose', '--env-file', (Join-Path $ProjectRoot '.env'), '-f', (Join-Path $ProjectRoot 'deploy\compose.yml'), 'up', '-d', '--wait')
}
