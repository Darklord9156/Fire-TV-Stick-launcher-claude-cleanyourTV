param(
    [string]$TvIp = '',
    [Parameter(Mandatory = $true)]
    [string]$BackupPath
)

$ErrorActionPreference = 'Stop'
$Adb = Join-Path $PSScriptRoot 'tools\android-sdk\platform-tools\adb.exe'
$AllowedPackages = @(
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

function Get-PackageNames {
    @(Invoke-Adb @('-s', $script:Serial, 'shell', 'pm', 'list', 'packages') | ForEach-Object { ([string]$_ -replace '^package:', '').Trim() } | Where-Object { $_ })
}

if (-not (Test-Path -LiteralPath $BackupPath -PathType Container)) { throw "Backup folder not found: $BackupPath" }
$stateFile = Join-Path (Resolve-Path -LiteralPath $BackupPath).Path 'device-state.json'
if (-not (Test-Path -LiteralPath $stateFile -PathType Leaf)) { throw "Missing device-state.json in $BackupPath" }
$snapshot = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($TvIp)) { $TvIp = ([string]$snapshot.serial -split ':')[0] }
$serialFromArgument = "$TvIp`:5555"
if ($snapshot.serial -ne $serialFromArgument) { throw "Backup is for $($snapshot.serial), not $serialFromArgument." }
if (-not (Test-Path -LiteralPath $Adb)) { throw "ADB is missing: $Adb. Run .\setup-tools.ps1 first." }
& $Adb start-server | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not start ADB.' }
& $Adb connect $serialFromArgument | Out-Host
$connection = ((Invoke-Adb @('-s', $serialFromArgument, 'get-state')) -join '').Trim()
if ($connection -ne 'device') { throw "Fire TV is not authorized/online at $serialFromArgument (state: $connection)." }
$model = ((Invoke-Adb @('-s', $serialFromArgument, 'shell', 'getprop', 'ro.product.model')) -join '').Trim()
if ($model -ne $snapshot.model) { throw "Backup model $($snapshot.model) does not match connected model $model." }
$Serial = $serialFromArgument
$installedNow = Get-PackageNames

foreach ($entry in $snapshot.packages) {
    $package = [string]$entry.package
    $originalState = [string]$entry.state
    if ($AllowedPackages -notcontains $package) { throw "Refusing package not in this script's allowlist: $package" }
    if ($originalState -eq 'absent') {
        Write-Host "Unchanged (absent in backup): $package"
        continue
    }
    if ($installedNow -notcontains $package) {
        Write-Warning "Package is no longer installed; left untouched: $package"
        continue
    }
    if ($originalState -eq 'enabled') {
        $null = Invoke-Adb @('-s', $Serial, 'shell', 'pm', 'enable', '--user', '0', $package)
    } elseif ($originalState -eq 'disabled') {
        $null = Invoke-Adb @('-s', $Serial, 'shell', 'pm', 'disable-user', '--user', '0', $package)
    } else {
        throw "Unknown saved state '$originalState' for $package"
    }
    $disabledNow = @(Invoke-Adb @('-s', $Serial, 'shell', 'pm', 'list', 'packages', '-d') | ForEach-Object { ([string]$_ -replace '^package:', '').Trim() })
    $actual = if ($disabledNow -contains $package) { 'disabled' } else { 'enabled' }
    if ($actual -ne $originalState) { Write-Warning "Restore verification mismatch for ${package}: wanted ${originalState}, got $actual" }
    else { Write-Host "RESTORED ${originalState}: $package" }
}

Write-Host 'Package states restored to the snapshot. No device settings or package data were changed by the debloat scripts.'
