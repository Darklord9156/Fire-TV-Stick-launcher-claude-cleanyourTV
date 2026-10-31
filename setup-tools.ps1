param(
    [switch]$AcceptGoogleSdkTerms,
    [switch]$JdkOnly
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Root = $PSScriptRoot
$Sdk = Join-Path $Root 'tools\android-sdk'
$Local = Join-Path $Root '.local\sdk-setup'
$JdkVersion = '17.0.20.1+1'
$JdkDirectoryName = 'jdk-17.0.20.1+1'
$JdkArchiveName = 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$JdkDownloadUrl = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$JdkSha256 = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
$ManagedJdkRoot = Join-Path $env:LOCALAPPDATA 'LiquidHome\toolchains'
$ManagedJdk = Join-Path $ManagedJdkRoot $JdkDirectoryName
$Terms = 'https://developer.android.com/studio/terms'

function Test-Jdk17([string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $false }
    $jdkHomeCandidate = $Candidate.Trim().Trim('"')
    if (-not (Test-Path -LiteralPath (Join-Path $jdkHomeCandidate 'bin\java.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $jdkHomeCandidate 'bin\javac.exe'))) { return $false }
    $releaseInfo = Get-Content -LiteralPath (Join-Path $jdkHomeCandidate 'release') -Raw -ErrorAction SilentlyContinue
    if ($releaseInfo -notmatch '(?m)^JAVA_VERSION="17(?:\.|")') { return $false }
    if ((Test-JdkExecutable (Join-Path $jdkHomeCandidate 'bin\javac.exe')) -ne 0) { return $false }
    if ((Test-JdkExecutable (Join-Path $jdkHomeCandidate 'bin\java.exe')) -ne 0) { return $false }
    return $true
}

function Test-JdkExecutable([string]$Executable) {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Executable
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { return 1 }
    $null = $process.StandardOutput.ReadToEnd()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    $process.Dispose()
    return $exitCode
}

function Get-Jdk17Home {
    $candidates = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($env:LIQUID_HOME_JDK_HOME)) { $candidates.Add($env:LIQUID_HOME_JDK_HOME) }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates.Add($env:JAVA_HOME) }

    foreach ($compiler in @(Get-Command javac.exe -All -ErrorAction SilentlyContinue)) {
        if (-not [string]::IsNullOrWhiteSpace($compiler.Source)) {
            $candidates.Add((Split-Path -Parent (Split-Path -Parent $compiler.Source)))
        }
    }

    $installRoots = @(
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Microsoft'),
        $ManagedJdkRoot
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_) }
    foreach ($installRoot in $installRoots) {
        Get-ChildItem -LiteralPath $installRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    $seen = @()
    foreach ($candidate in $candidates) {
        $jdkHomeCandidate = $candidate.Trim().Trim('"')
        if ([string]::IsNullOrWhiteSpace($jdkHomeCandidate) -or $seen -contains $jdkHomeCandidate) { continue }
        $seen += $jdkHomeCandidate
        if (Test-Jdk17 $jdkHomeCandidate) { return $jdkHomeCandidate }
        Write-Host "Skipping non-JDK-17 or incomplete Java installation: $jdkHomeCandidate"
    }
    return $null
}

function Install-PinnedJdk17 {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA is not set; set LIQUID_HOME_JDK_HOME to an existing JDK 17 installation.' }
    if (Test-Path -LiteralPath $ManagedJdk) {
        throw "The managed JDK directory exists but is not a valid JDK 17: $ManagedJdk. Move or remove that incomplete directory, then retry."
    }

    $stage = $null
    foreach ($previousStage in (Get-ChildItem -LiteralPath $env:TEMP -Directory -Filter 'liquid-home-jdk-*' -ErrorAction SilentlyContinue)) {
        $previousArchive = Join-Path $previousStage.FullName $JdkArchiveName
        if ((Test-Path -LiteralPath $previousArchive) -and
            (Get-FileHash -LiteralPath $previousArchive -Algorithm SHA256).Hash.ToLowerInvariant() -eq $JdkSha256) {
            $stage = $previousStage.FullName
            break
        }
    }
    if (-not $stage) {
        $stage = Join-Path $env:TEMP ('liquid-home-jdk-' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force -Path $stage | Out-Null
    }
    $archive = Join-Path $stage $JdkArchiveName
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host "No compatible JDK 17 was found. Downloading pinned Eclipse Temurin $JdkVersion..."
        & curl.exe --fail --location --silent --show-error --output $archive $JdkDownloadUrl
        if ($LASTEXITCODE -ne 0) { throw 'Temurin JDK download failed.' }
    }
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $JdkSha256) { throw "Temurin JDK SHA-256 mismatch (expected $JdkSha256, got $actualHash)." }

    $extract = Join-Path $stage 'extract'
    Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
    $payload = Get-ChildItem -LiteralPath $extract -Directory | Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe'))
    } | Select-Object -First 1
    if (-not $payload -or -not (Test-Jdk17 $payload.FullName)) { throw 'The downloaded archive did not contain a working JDK 17.' }

    New-Item -ItemType Directory -Force -Path $ManagedJdkRoot | Out-Null
    Move-Item -LiteralPath $payload.FullName -Destination $ManagedJdk
    if (-not (Test-Jdk17 $ManagedJdk)) { throw 'The provisioned JDK failed its Java 17 check.' }
    Remove-Item -LiteralPath $stage -Recurse -Force
    Write-Host "Provisioned JDK 17 outside the repository: $ManagedJdk"
    return $ManagedJdk
}

$Jdk = Get-Jdk17Home
if (-not $Jdk) { $Jdk = Install-PinnedJdk17 }
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_HOME = $Sdk
Write-Host "Using JDK 17: $Jdk"

if ($JdkOnly) { return }

$existingRequired = @(
    (Join-Path $Sdk 'platforms\android-25\android.jar'),
    (Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe'),
    (Join-Path $Sdk 'build-tools\34.0.0\d8.bat'),
    (Join-Path $Sdk 'build-tools\34.0.0\zipalign.exe'),
    (Join-Path $Sdk 'build-tools\34.0.0\apksigner.bat'),
    (Join-Path $Sdk 'platform-tools\adb.exe')
)
if (@($existingRequired | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -eq 0) {
    & (Join-Path $Sdk 'platform-tools\adb.exe') version
    if ($LASTEXITCODE -ne 0) { throw 'ADB could not run.' }
    Write-Host 'Required Android SDK components already exist and were reused; no downloads were made.'
    return
}

if (-not $AcceptGoogleSdkTerms) {
    Write-Host "Review Google's Android SDK terms: $Terms" -ForegroundColor Yellow
    $answer = Read-Host 'After reviewing and accepting the terms, type ACCEPT to continue'
    if ($answer -cne 'ACCEPT') { throw 'Google Android SDK terms were not accepted; no SDK packages were installed.' }
}
New-Item -ItemType Directory -Force -Path $Local | Out-Null
$Manifest = Join-Path $Local 'repository2-3.xml'
& curl.exe --fail --location --silent --show-error --output $Manifest 'https://dl.google.com/android/repository/repository2-3.xml'
if ($LASTEXITCODE -ne 0) { throw 'Could not download the Android package manifest from Google.' }
[xml]$Repo = [IO.File]::ReadAllText($Manifest)
$packages = @(
    @{ Path='platforms;android-25'; Host='any'; Destination=(Join-Path $Sdk 'platforms\android-25'); Required=@('platforms\android-25\android.jar') },
    @{ Path='build-tools;34.0.0'; Host='windows'; Destination=(Join-Path $Sdk 'build-tools\34.0.0'); Required=@('build-tools\34.0.0\aapt2.exe','build-tools\34.0.0\d8.bat','build-tools\34.0.0\zipalign.exe','build-tools\34.0.0\apksigner.bat') },
    @{ Path='platform-tools'; Host='windows'; Destination=(Join-Path $Sdk 'platform-tools'); Required=@('platform-tools\adb.exe') }
)
foreach ($definition in $packages) {
    $alreadyInstalled = @($definition.Required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $Sdk $_)) }).Count -eq 0
    if ($alreadyInstalled) { Write-Host "Reusing installed $($definition.Path)."; continue }
    $package = $Repo.SelectSingleNode("//remotePackage[@path='$($definition.Path)']")
    if ($null -eq $package) { throw "Google's official package manifest does not list $($definition.Path)." }
    $archiveInfo = $null
    foreach ($candidate in $package.SelectNodes('./archives/archive')) {
        $hostNode = $candidate.SelectSingleNode('host-os')
        $hostValue = if ($null -eq $hostNode) { 'any' } else { $hostNode.InnerText }
        if ($hostValue -eq $definition.Host) { $archiveInfo = $candidate; break }
    }
    if ($null -eq $archiveInfo) { throw "No compatible Windows archive for $($definition.Path)." }
    $complete = $archiveInfo.SelectSingleNode('complete')
    $name = $complete.SelectSingleNode('url').InnerText
    $expected = $complete.SelectSingleNode('checksum').InnerText.ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{40}$') { throw "Unexpected checksum metadata for $name." }
    $zip = Join-Path $Local $name
    $valid = (Test-Path -LiteralPath $zip) -and ((Get-FileHash -LiteralPath $zip -Algorithm SHA1).Hash.ToLowerInvariant() -eq $expected)
    if (-not $valid) {
        if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
        $url = "https://dl.google.com/android/repository/$name"
        Write-Host "Downloading $($definition.Path) from the official Google repository..."
        & curl.exe --fail --location --silent --show-error --output $zip $url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
        $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA1).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw "Checksum mismatch for $name (expected $expected, got $actual)." }
    }
    $extract = Join-Path $Local ('extract-' + ($definition.Path -replace '[^a-zA-Z0-9]+','-'))
    New-Item -ItemType Directory -Force -Path $extract | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force
    $entries = @(Get-ChildItem -LiteralPath $extract -Force)
    if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) { $payload = $entries[0].FullName } else { $payload = $extract }
    New-Item -ItemType Directory -Force -Path $definition.Destination | Out-Null
    Get-ChildItem -LiteralPath $payload -Force | Copy-Item -Destination $definition.Destination -Recurse -Force
}

foreach ($required in @(
    (Join-Path $Sdk 'platforms\android-25\android.jar'),
    (Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe'),
    (Join-Path $Sdk 'build-tools\34.0.0\d8.bat'),
    (Join-Path $Sdk 'build-tools\34.0.0\zipalign.exe'),
    (Join-Path $Sdk 'build-tools\34.0.0\apksigner.bat'),
    (Join-Path $Sdk 'platform-tools\adb.exe')
)) { if (-not (Test-Path -LiteralPath $required)) { throw "Required Android package file missing: $required" } }
& (Join-Path $Sdk 'platform-tools\adb.exe') version
if ($LASTEXITCODE -ne 0) { throw 'ADB could not run.' }
Write-Host 'SDK platform, build tools, and ADB installed and verified. Downloads remain in .local/sdk-setup for local reuse.'
