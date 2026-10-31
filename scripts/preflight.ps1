param([string]$TvIp = '')

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $Root 'setup-tools.ps1') -JdkOnly
$Required = @(
    'liquid-home\AndroidManifest.xml',
    'liquid-home\build.ps1',
    'tools\android-sdk\platforms\android-25\android.jar',
    'tools\android-sdk\build-tools\34.0.0\aapt2.exe',
    'tools\android-sdk\build-tools\34.0.0\apksigner.bat',
    'tools\android-sdk\platform-tools\adb.exe'
)
foreach ($relative in $Required) {
    if (-not (Test-Path (Join-Path $Root $relative))) { throw "Preflight failed: required project input is missing: $relative" }
}
$manifest = Get-Content -LiteralPath (Join-Path $Root 'liquid-home\AndroidManifest.xml') -Raw
if ($manifest -notmatch 'package="tv\.rajra\.liquidhome"') { throw 'Preflight failed: unexpected application package ID.' }
if ($manifest -notmatch 'android:name="\.Home"') { throw 'Preflight failed: launcher activity is missing.' }
Write-Host "PASS: project source, JDK 17 at $env:JAVA_HOME, Android API 25 platform, Build Tools 34.0.0, and ADB are present."

if ([string]::IsNullOrWhiteSpace($TvIp)) { $TvIp = Read-Host 'Fire TV IPv4 address (same Wi-Fi network)' }
$address = $null
if (-not [System.Net.IPAddress]::TryParse($TvIp,[ref]$address) -or $address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw "Enter a valid IPv4 address; got '$TvIp'."
}
$serial = "$TvIp`:5555"
$adb = Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
& $adb start-server | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not start ADB.' }
& $adb connect $serial | Out-Host
$devices = & $adb devices -l 2>&1
if ($LASTEXITCODE -ne 0) { throw "Could not list ADB devices: $($devices | Out-String)" }
$entry = @($devices | ForEach-Object { [string]$_ } | Where-Object { $_ -match "^$([regex]::Escape($serial))\s+" }) | Select-Object -First 1
if (-not $entry) { throw "No Fire TV detected at $serial. Check the IP, Wi-Fi, and ADB Debugging." }
if ($entry -match '\sunauthorized(?:\s|$)') { throw "ADB authorization is pending for $serial. Accept the prompt on TV and choose 'Always allow from this computer', then retry." }
if ($entry -notmatch '\sdevice(?:\s|$)') { throw "ADB device is not ready ($entry). Wake the TV and retry." }
$identity = ((& $adb -s $serial shell getprop ro.product.manufacturer) -join '').Trim() + ' ' + ((& $adb -s $serial shell getprop ro.product.model) -join '').Trim() + ' ' + ((& $adb -s $serial shell getprop ro.product.device) -join '').Trim()
if ($identity -notmatch '(?i)amazon' -or $identity -notmatch '(?i)(AFT[A-Z0-9]+|Fire\s?TV)') { throw "Connected device is not identified as a Fire TV: $identity" }
Write-Host "PASS: authorized Fire TV detected: $identity ($serial)."
Write-Host 'Preflight is read-only on the TV and reuses the existing Windows ADB host key.'
