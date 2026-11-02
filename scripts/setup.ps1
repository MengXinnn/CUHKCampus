param([switch]$SkipDocker)
. "$PSScriptRoot\windows-common.ps1"
. "$PSScriptRoot\windows-install.ps1"
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host 'Setup needs administrator permission to install runtime software. Windows will ask for approval.'
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath + '"'
    if ($SkipDocker) { $arguments += ' -SkipDocker' }
    $process = Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments -Wait -PassThru
    exit $process.ExitCode
}
Push-Location $ProjectRoot
try {
    if (!(Test-Path '.env')) {
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $password = New-Object byte[] 24
            $key = New-Object byte[] 32
            $rng.GetBytes($password)
            $rng.GetBytes($key)
            $text = [IO.File]::ReadAllText((Join-Path $ProjectRoot '.env.example'))
            $text = $text.Replace('CAMPUS_DATABASE_PASSWORD=', 'CAMPUS_DATABASE_PASSWORD=' + ([BitConverter]::ToString($password).Replace('-', '').ToLowerInvariant()))
            $text = $text.Replace('CAMPUS_ENCRYPTION_KEY=', 'CAMPUS_ENCRYPTION_KEY=' + [Convert]::ToBase64String($key))
            [IO.File]::WriteAllText((Join-Path $ProjectRoot '.env'), $text, (New-Object Text.UTF8Encoding($false)))
        } finally { $rng.Dispose() }
    }
    Import-CampusEnvironment
    Install-CampusRequirements -SkipDocker:$SkipDocker
    Initialize-CampusRuntime
    $javaVersion = (& java --version | Out-String)
    if ($javaVersion -notmatch '(?:openjdk|java) (\d+)' -or [int]$Matches[1] -lt 21) { throw 'Java 21 or newer is required.' }
    $nodeVersion = & node.exe -p 'parseInt(process.versions.node)'
    if ($LASTEXITCODE -ne 0 -or [int]$nodeVersion -lt 22) { throw 'Node.js 22 or newer is required.' }
    Write-Host 'Downloading PostgreSQL container image and starting the database ...'
    Start-CampusDatabase -SkipDocker:$SkipDocker
    Push-Location 'frontend'
    try {
        Write-Host 'Downloading frontend dependencies ...'
        Invoke-Checked 'npm.cmd' @('ci')
        Invoke-Checked 'npm.cmd' @('run', 'build')
    } finally { Pop-Location }
    Push-Location 'backend'
    try { Write-Host 'Downloading Maven and Java dependencies; building the application ...'; Invoke-Checked '.\mvnw.cmd' @('-B', '-ntp', '-Dmaven.repo.local=../.tools/m2', 'package') }
    finally { Pop-Location }
    Write-Host 'Setup complete. Run scripts\start.cmd.'
} finally { Pop-Location }
