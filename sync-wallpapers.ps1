param([string]$TvIp = '')
$ErrorActionPreference='Stop'; $Root=$PSScriptRoot
if ([string]::IsNullOrWhiteSpace($TvIp)) { $TvIp=Read-Host 'Fire TV IP address (same Wi-Fi network)' }
$ipAddress = $null
if (-not [System.Net.IPAddress]::TryParse($TvIp, [ref]$ipAddress) -or $ipAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { throw "Enter a valid Fire TV IPv4 address, for example 192.168.0.100. Got '$TvIp'." }
$Serial="$TvIp`:5555"; $Adb=Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'setup-tools.ps1'); if ($LASTEXITCODE -ne 0) { throw 'Could not set up ADB.' } }
$Folder=Join-Path $Root 'wallpapers'
$Images=@(Get-ChildItem -LiteralPath $Folder -File | Where-Object { $_.Name -ine 'dooom.png' -and $_.Extension -match '^\.(jpg|jpeg|png|webp)$' })
if (-not $Images) { throw "Add JPG, JPEG, PNG, or WebP images to $Folder first." }
& $Adb start-server | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not start ADB.' }
& $Adb connect $Serial | Out-Host
$Devices=& $Adb devices -l 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Could not list ADB devices.' }
$Entry=@($Devices | ForEach-Object { [string]$_ } | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+" }) | Select-Object -First 1
if (-not $Entry) { throw "No device found at $Serial. Check the address, Wi-Fi and ADB Debugging." }
if ($Entry -match '\sunauthorized(?:\s|$)') { throw "ADB authorization is pending for $Serial. Accept the prompt and choose 'Always allow from this computer'." }
if ($Entry -notmatch '\sdevice(?:\s|$)') { throw "Fire TV is offline at $Serial. Wake it and reconnect." }
$Manufacturer=(& $Adb -s $Serial shell getprop ro.product.manufacturer | Out-String).Trim()
$Model=(& $Adb -s $Serial shell getprop ro.product.model | Out-String).Trim()
if ($Manufacturer -notmatch '(?i)amazon' -or $Model -notmatch '(?i)(AFT[A-Z0-9]+|Fire\s?TV)') { throw "Connected device is not identified as Fire TV: $Manufacturer $Model" }
$Remote='/sdcard/Android/data/tv.rajra.liquidhome/files/wallpapers'
& $Adb -s $Serial shell mkdir -p $Remote
if ($LASTEXITCODE -ne 0) { throw "Could not prepare wallpaper storage on $Serial." }
foreach ($image in $Images) { Write-Host "Copying $($image.Name)"; & $Adb -s $Serial push $image.FullName "$Remote/$($image.Name)"; if ($LASTEXITCODE -ne 0) { throw "Failed to copy $($image.Name)." } }
Write-Host "Synced $($Images.Count) image(s) from $Folder to $Serial. Open Liquid Home > Settings > Background to choose one."
