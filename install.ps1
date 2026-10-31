param(
    [string]$TvIp = '',
    [string]$DisplayName = '',
    [switch]$AcceptGoogleSdkTerms
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Package = 'tv.rajra.liquidhome'
$Serial = $null

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = & $script:Adb @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw "ADB command failed ($code): adb $($Arguments -join ' ')`n$($output | Out-String)"
    }
    return ,$output
}

function Require-AuthorizedDevice {
    $devices = & $Adb devices -l 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not list ADB devices: $($devices | Out-String)" }
    $entry = @($devices | ForEach-Object { [string]$_ } | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+" }) | Select-Object -First 1
    if (-not $entry) { throw "No connected Fire TV found at $Serial. Confirm the IP, same Wi-Fi, and ADB Debugging, then retry." }
    if ($entry -match '\sunauthorized(?:\s|$)') {
        throw "ADB reports unauthorized for $Serial. On the TV, accept 'Allow USB debugging?' and select 'Always allow from this computer'. Retry after authorization; no install was attempted."
    }
    if ($entry -match '\soffline(?:\s|$)') { throw "ADB reports offline for $Serial. Wake the TV, reconnect Wi-Fi, then retry." }
    if ($entry -notmatch '\sdevice(?:\s|$)') { throw "Unexpected ADB state for ${Serial}: $entry" }

    $props = @{}
    foreach ($property in @('ro.product.manufacturer','ro.product.model','ro.product.device','ro.build.version.release','ro.build.version.sdk')) {
        $value = (Invoke-Adb @('-s',$Serial,'shell','getprop',$property) | Out-String).Trim()
        $props[$property] = $value
    }
    $identity = "$($props['ro.product.manufacturer']) $($props['ro.product.model']) $($props['ro.product.device'])"
    if ($identity -notmatch '(?i)amazon' -or $identity -notmatch '(?i)(AFT[A-Z0-9]+|Fire\s?TV)') {
        throw "Connected device does not identify as Fire TV (reported: $identity). No installation was attempted."
    }
    Write-Host "Authorized Fire TV: $identity; Fire OS/Android $($props['ro.build.version.release']) (API $($props['ro.build.version.sdk']))"
}

if ([string]::IsNullOrWhiteSpace($DisplayName)) {
    do { $DisplayName = Read-Host 'What name would you like to see on your Fire TV home screen?' } while ([string]::IsNullOrWhiteSpace($DisplayName))
}
if ([string]::IsNullOrWhiteSpace($TvIp)) { $TvIp = Read-Host 'Fire TV IPv4 address (same Wi-Fi network)' }
$ip = $null
if (-not [System.Net.IPAddress]::TryParse($TvIp,[ref]$ip) -or $ip.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw "Enter a valid Fire TV IPv4 address, for example 192.168.0.100. Got '$TvIp'."
}
$Serial = "$TvIp`:5555"

$Adb = Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) {
    $setupArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $Root 'setup-tools.ps1'))
    if ($AcceptGoogleSdkTerms) { $setupArgs += '-AcceptGoogleSdkTerms' }
    & powershell.exe @setupArgs
    if ($LASTEXITCODE -ne 0) { throw 'ADB setup failed.' }
}
# Use the normal Windows ADB host key so the Fire TV's existing "Always allow"
# authorization remains valid. Never rotate keys or kill the shared ADB server.
& $Adb start-server | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not start ADB server.' }
$connect = & $Adb connect $Serial 2>&1
Write-Host ($connect | Out-String).Trim()
Require-AuthorizedDevice

$Apk = Join-Path $Root 'liquid-home\build\liquid-home.apk'
$BuildScript = Join-Path $Root 'liquid-home\build.ps1'
$buildArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$BuildScript,'-DisplayName',$DisplayName)
if ($AcceptGoogleSdkTerms) { $buildArgs += '-AcceptGoogleSdkTerms' }
& powershell.exe @buildArgs
if ($LASTEXITCODE -ne 0) { throw 'Build failed; the existing TV installation was not changed.' }
if (-not (Test-Path $Apk)) { throw "Build completed without producing expected APK: $Apk" }

# Save original optional Keeper settings only once, before the first change.
$Local = Join-Path $Root '.local'
$StateFile = Join-Path $Local ("device-state-" + ($TvIp -replace '\.','_') + '.json')
if (-not (Test-Path $StateFile)) {
    $previous = 'default'
    $wasWhitelisted = $false
    $existingPackage = (Invoke-Adb @('-s',$Serial,'shell','pm','list','packages',$Package) | Out-String)
    if ($existingPackage -match "(?m)^package:$([regex]::Escape($Package))\s*$") {
        $appOpLine = (Invoke-Adb @('-s',$Serial,'shell','appops','get',$Package,'GET_USAGE_STATS') | Out-String)
        if ($appOpLine -match 'GET_USAGE_STATS:\s*(\w+)') { $previous = $Matches[1].ToLowerInvariant() }
        $whitelist = (Invoke-Adb @('-s',$Serial,'shell','dumpsys','deviceidle','whitelist') | Out-String)
        $wasWhitelisted = $whitelist.Contains($Package)
    }
    [ordered]@{ appOp=$previous; wasDozeWhitelisted=$wasWhitelisted; capturedAt=(Get-Date).ToString('o') } |
        ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding UTF8
}

# -r updates in place and preserves app data. A signer mismatch stops here; never
# uninstall automatically because that would erase preferences and app data.
Invoke-Adb @('-s',$Serial,'install','-r',$Apk) | ForEach-Object { Write-Host $_ }
Invoke-Adb @('-s',$Serial,'shell','appops','set',$Package,'GET_USAGE_STATS','allow') | ForEach-Object { Write-Host $_ }
Invoke-Adb @('-s',$Serial,'shell','dumpsys','deviceidle','whitelist',"+$Package") | ForEach-Object { Write-Host $_ }
$launch = Invoke-Adb @('-s',$Serial,'shell','am','start','-W','-n',"$Package/.Home",'--es','page','home')
Write-Host ($launch | Out-String).Trim()
if (($launch | Out-String) -notmatch '(?m)^Status:\s*ok\s*$') { throw 'The activity did not report a successful launch.' }

$installed = (Invoke-Adb @('-s',$Serial,'shell','pm','path',$Package) | Out-String)
if ($installed -notmatch 'package:') { throw 'Install verification failed: package manager returned no installed APK path.' }
$foreground = $false
for ($attempt = 0; $attempt -lt 8; $attempt++) {
    $activity = (Invoke-Adb @('-s',$Serial,'shell','dumpsys','activity','activities') | Out-String)
    if ($activity -match "(?im)(mResumedActivity|ResumedActivity|topResumedActivity).*$([regex]::Escape($Package))") { $foreground = $true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $foreground) { throw "APK is installed and am start returned success, but the launcher activity was not visible in ActivityManager. Check the TV screen and run scripts\verify.ps1 -TvIp $TvIp." }
Write-Host "PASS: installed $Package on $Serial, preserved app data with install -r, and verified its activity is running."
Write-Host "Installed APK path: $(($installed -split "`n" | Where-Object { $_ -match '^package:' } | Select-Object -First 1).Trim())"
Write-Host 'The bundled ADB reused the Windows host key. The TV needs to approve it once with Always allow; this installer never resets ADB authorization.'
Write-Host 'Fire OS owns physical Home-key routing; the optional UsageStats Keeper remains best effort.'
