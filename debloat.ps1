param(
    [string]$TvIp = '',
    [ValidateRange(1, 10)]
    [int]$BatchSize = 3
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Adb = Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
$Packages = @(
    'com.amazon.hybridadidservice',
    'com.amazon.perfcollection',
    'com.amazon.logan',
    'com.amazon.kso.blackbird',
    'com.amazon.shoptv.client',
    'com.amazon.bueller.photos',
    'com.amazon.gamehub',
    'com.amazon.minitv.android.app',
    'com.amazon.ods.kindleconnect',
    'com.amazon.sneakpeek'
)

function Invoke-Adb([string[]]$Arguments) {
    $output = & $script:Adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed ($LASTEXITCODE): adb $($Arguments -join ' ')`n$($output | Out-String)"
    }
    return $output
}

function Invoke-Tv([string[]]$ShellArguments) {
    Invoke-Adb (@('-s', $script:Serial, 'shell') + $ShellArguments)
}

function Get-PackageNames([string[]]$Arguments) {
    @(Invoke-Tv $Arguments | ForEach-Object { ([string]$_ -replace '^package:', '').Trim() } | Where-Object { $_ })
}

if ([string]::IsNullOrWhiteSpace($TvIp)) { $TvIp = Read-Host 'Fire TV IPv4 address' }
$ip = $null
if (-not [System.Net.IPAddress]::TryParse($TvIp, [ref]$ip) -or
    $ip.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw "Enter a valid Fire TV IPv4 address. Got '$TvIp'."
}
if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB is missing: $Adb. Run .\setup-tools.ps1 first." }
$Serial = "$TvIp`:5555"
& $Adb start-server | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not start ADB.' }
& $Adb connect $Serial | Out-Host
$state = ((Invoke-Adb @('-s', $Serial, 'get-state')) -join '').Trim()
if ($state -ne 'device') { throw "Fire TV is not authorized/online at $Serial (state: $state)." }
$manufacturer = ((Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.manufacturer')) -join '').Trim()
$model = ((Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')) -join '').Trim()
if ($manufacturer -notmatch '(?i)amazon' -or $model -notmatch '(?i)^AFT') {
    throw "Connected device is not identified as an Amazon Fire TV: $manufacturer $model. No packages changed."
}
$api = ((Invoke-Adb @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
Write-Host "Connected to $manufacturer $model, Android API $api ($Serial)."
Write-Warning 'This list includes com.amazon.logan (VoiceView) and user-facing Amazon apps. VoiceView, Photos, Game Hub, miniTV, shopping, and Kindle Connect features may stop working.'

# Capture a complete local recovery snapshot before the first package change.
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$backup = Join-Path $Root ('.local\debloat\' + $model + '_' + ($TvIp -replace '\.', '_') + '_' + $stamp)
New-Item -ItemType Directory -Force -Path $backup | Out-Null
$installed = Get-PackageNames @('pm', 'list', 'packages')
$disabled = Get-PackageNames @('pm', 'list', 'packages', '-d')
$installed | Set-Content -LiteralPath (Join-Path $backup 'installed-packages.txt') -Encoding UTF8
$disabled | Set-Content -LiteralPath (Join-Path $backup 'disabled-packages.txt') -Encoding UTF8
(Invoke-Adb @('-s', $Serial, 'shell', 'getprop')) | Set-Content -LiteralPath (Join-Path $backup 'device-properties.txt') -Encoding UTF8
foreach ($namespace in @('global', 'secure', 'system')) {
    (Invoke-Tv @('settings', 'list', $namespace)) | Set-Content -LiteralPath (Join-Path $backup "settings-$namespace.txt") -Encoding UTF8
}
$targetStates = @(
    foreach ($package in $Packages) {
        $status = if ($installed -notcontains $package) { 'absent' }
                  elseif ($disabled -contains $package) { 'disabled' }
                  else { 'enabled' }
        [ordered]@{ package = $package; state = $status }
    }
)
[ordered]@{
    serial = $Serial
    model = $model
    androidApi = $api
    capturedAt = (Get-Date).ToString('o')
    packages = $targetStates
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $backup 'device-state.json') -Encoding UTF8
Write-Host "Pre-change package/settings backup saved: $backup"

# Disable only packages that were enabled in the snapshot. Verify each small batch.
$toDisable = @($targetStates | Where-Object { $_.state -eq 'enabled' } | ForEach-Object { $_.package })
for ($start = 0; $start -lt $toDisable.Count; $start += $BatchSize) {
    $end = [Math]::Min($start + $BatchSize - 1, $toDisable.Count - 1)
    $batch = @($toDisable[$start..$end])
    Write-Host "Disabling batch: $($batch -join ', ')"
    foreach ($package in $batch) {
        $result = Invoke-Tv @('pm', 'disable-user', '--user', '0', $package)
        Write-Host (($result | Out-String).Trim())
    }
    $disabledNow = Get-PackageNames @('pm', 'list', 'packages', '-d')
    foreach ($package in $batch) {
        if ($disabledNow -contains $package) { Write-Host "VERIFIED disabled: $package" }
        else { Write-Warning "Not disabled (possibly protected or failed): $package" }
    }
}

$finalDisabled = Get-PackageNames @('pm', 'list', 'packages', '-d')
$results = @(
    foreach ($package in $Packages) {
        $original = ($targetStates | Where-Object package -eq $package | Select-Object -First 1).state
        $now = if ($installed -notcontains $package) { 'absent' }
              elseif ($finalDisabled -contains $package) { 'disabled' }
              else { 'enabled' }
        [ordered]@{ package = $package; before = $original; after = $now; verified = ($original -eq 'absent' -or $now -eq 'disabled') }
    }
)
$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $backup 'debloat-results.json') -Encoding UTF8
$results | ForEach-Object { '{0,-42} {1,-9} -> {2}' -f $_.package, $_.before, $_.after } | Write-Host
Write-Host "Restore with: .\restore-debloat.ps1 -TvIp `"$TvIp`" -BackupPath `"$backup`""
