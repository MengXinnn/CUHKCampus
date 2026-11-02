function Update-CampusPath {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    $dockerBin = "$env:ProgramFiles\Docker\Docker\resources\bin"
    if (Test-Path $dockerBin) { $env:Path += ';' + $dockerBin }
}

function Test-CampusJava {
    if (!(Get-Command java -ErrorAction SilentlyContinue)) { return $false }
    $version = (& java --version | Out-String)
    return ($LASTEXITCODE -eq 0 -and $version -match '(?:openjdk|java) (\d+)' -and [int]$Matches[1] -ge 21)
}

function Test-CampusNode {
    if (!(Get-Command node.exe -ErrorAction SilentlyContinue) -or !(Get-Command npm.cmd -ErrorAction SilentlyContinue)) { return $false }
    $version = & node.exe -p 'parseInt(process.versions.node)'
    return ($LASTEXITCODE -eq 0 -and [int]$version -ge 22)
}

function Install-CampusRequirements {
    param([switch]$SkipDocker)
    Write-Host 'Checking runtime software. Missing software will be downloaded and installed automatically.'
    $packages = @()
    if (!(Test-CampusJava)) { $packages += @{ Name='Java 21 JDK'; Id='EclipseAdoptium.Temurin.21.JDK' } }
    if (!(Test-CampusNode)) { $packages += @{ Name='Node.js LTS (includes npm)'; Id='OpenJS.NodeJS.LTS' } }
    if (!$env:CAMPUS_CHROME_BINARY -and !(Get-Command chrome.exe -ErrorAction SilentlyContinue) -and !(Test-Path "$env:ProgramFiles\Google\Chrome\Application\chrome.exe") -and !(Test-Path "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe") -and !(Test-Path "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe")) { $packages += @{ Name='Google Chrome'; Id='Google.Chrome' } }
    if (!$env:CAMPUS_SOFFICE_BINARY -and !(Get-Command soffice.exe -ErrorAction SilentlyContinue) -and !(Test-Path "$env:ProgramFiles\LibreOffice\program\soffice.exe") -and !(Test-Path "${env:ProgramFiles(x86)}\LibreOffice\program\soffice.exe")) { $packages += @{ Name='LibreOffice'; Id='TheDocumentFoundation.LibreOffice' } }
    if (!$env:CAMPUS_CURL_BINARY -and !(Get-Command curl.exe -ErrorAction SilentlyContinue)) { $packages += @{ Name='curl'; Id='cURL.cURL' } }
    if (!$SkipDocker -and !(Get-Command docker.exe -ErrorAction SilentlyContinue) -and !(Test-Path "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe")) { $packages += @{ Name='Docker Desktop (includes Compose)'; Id='Docker.DockerDesktop' } }
    if ($packages.Count -gt 0) {
        Write-Host 'The following software will be downloaded:'
        foreach ($package in $packages) { Write-Host ('  - ' + $package.Name) }
        if (!(Get-Command winget.exe -ErrorAction SilentlyContinue)) {
            Write-Host 'Downloading Microsoft WinGet package manager from Microsoft.'
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force | Out-Null
            Install-Module -Name Microsoft.WinGet.Client -Repository PSGallery -Scope CurrentUser -Force -AllowClobber
            Import-Module Microsoft.WinGet.Client
            Repair-WinGetPackageManager -AllUsers
            Update-CampusPath
        }
        foreach ($package in $packages) {
            Write-Host ('Downloading and installing ' + $package.Name + ' ...')
            & winget.exe install --exact --id $package.Id --source winget --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
            $code = $LASTEXITCODE
            if ($code -eq 3010) {
                Write-Host 'Windows restart required. Restart and run scripts\setup.cmd again.'
                exit 3010
            }
            if ($code -ne 0) { throw ($package.Name + ' installation failed (exit ' + $code + '). See installer output above.') }
            Update-CampusPath
        }
    }
    Update-CampusPath
    if (!$SkipDocker) {
        $features = @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')
        $restart = $false
        foreach ($feature in $features) {
            $state = Get-WindowsOptionalFeature -Online -FeatureName $feature
            if ($state.State -ne 'Enabled') {
                Write-Host ('Enabling Windows component: ' + $feature)
                $result = Enable-WindowsOptionalFeature -Online -FeatureName $feature -All -NoRestart
                if ($result.RestartNeeded) { $restart = $true }
            }
        }
        if ($restart) {
            Write-Host 'WSL components installed. Restart Windows and run scripts\setup.cmd again.'
            exit 3010
        }
        Write-Host 'Checking WSL; its runtime update may need a download.'
        Invoke-Checked 'wsl.exe' @('--update')
        Invoke-Checked 'wsl.exe' @('--set-default-version', '2')
        & docker.exe info --format '{{.OSType}}'
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'Starting Docker Desktop. Complete any first-launch agreement shown by Docker.'
            Start-Process "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
            $ready = $false
            for ($attempt = 0; $attempt -lt 60; $attempt++) {
                Start-Sleep -Seconds 3
                $engine = & docker.exe info --format '{{.OSType}}'
                if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            }
            if (!$ready) { throw 'Docker did not start. Check its window and rerun setup after completing initialization.' }
        } else { $engine = & docker.exe info --format '{{.OSType}}' }
        if ($engine -ne 'linux') { throw 'Switch Docker Desktop to Linux containers, then rerun setup.' }
        Invoke-Checked 'docker.exe' @('compose', 'version')
    }
}
