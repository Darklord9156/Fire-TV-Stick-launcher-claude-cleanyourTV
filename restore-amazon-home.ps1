param([string]$TvIp = '')
$ErrorActionPreference='Stop'; $Root=$PSScriptRoot
if ([string]::IsNullOrWhiteSpace($TvIp)) { $TvIp=Read-Host 'Fire TV IP address (same Wi-Fi network)' }
$ipAddress = $null
if (-not [System.Net.IPAddress]::TryParse($TvIp, [ref]$ipAddress) -or $ipAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { throw "Enter a valid Fire TV IPv4 address, for example 192.168.0.100. Got '$TvIp'." }
$Serial="$TvIp`:5555"; $Adb=Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'setup-tools.ps1'); if ($LASTEXITCODE -ne 0) { throw 'Could not set up ADB.' } }
& $Adb connect $Serial | Out-Host
$State=(& $Adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $State -ne 'device') { throw "Fire TV is not authorized/online at $Serial. Accept the ADB prompt if shown." }
$Package='tv.rajra.liquidhome'; & $Adb -s $Serial shell am stopservice "$Package/.Keeper" | Out-Host
$StateFile=Join-Path $Root ('.local\device-state-' + ($TvIp -replace '\.','_') + '.json')
if (Test-Path $StateFile) {
  $saved=Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
  $appOp=[string]$saved.appOp; if ($appOp -notin @('allow','deny','ignore','default','foreground')) {$appOp='default'}
  & $Adb -s $Serial shell appops set $Package GET_USAGE_STATS $appOp | Out-Host
  if (-not [bool]$saved.wasDozeWhitelisted) { & $Adb -s $Serial shell dumpsys deviceidle whitelist -- "-$Package" | Out-Host }
}
& $Adb -s $Serial shell am force-stop $Package
& $Adb -s $Serial shell pm enable --user 0 com.amazon.tv.launcher | Out-Host
& $Adb -s $Serial shell cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext | Out-Host
& $Adb -s $Serial shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
Write-Host 'Amazon Home opened. Liquid Home was not uninstalled.'
