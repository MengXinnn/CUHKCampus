param([switch]$SkipDocker)
. "$PSScriptRoot\windows-common.ps1"
Push-Location $ProjectRoot
try {
    Initialize-CampusRuntime
    if (!(Test-Path 'backend\target\cuhkcampus-0.1.0.jar')) { throw 'Run scripts\setup.cmd first.' }
    Start-CampusDatabase -SkipDocker:$SkipDocker
    Push-Location 'backend'
    try {
        Write-Host 'CUHKCampus: http://localhost:8080 (Ctrl+C to stop)'
        Invoke-Checked 'java' @('-jar', 'target\cuhkcampus-0.1.0.jar')
    } finally { Pop-Location }
} finally { Pop-Location }
