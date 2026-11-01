param([string]$ExpectedDisplayName = '')

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
. (Join-Path $Root 'setup-tools.ps1') -JdkOnly
$Jdk = $env:JAVA_HOME
$Apk = Join-Path $Root 'liquid-home\build\liquid-home.apk'
$Required = @(
    'liquid-home\AndroidManifest.xml',
    'liquid-home\build.ps1',
    'liquid-home\src\tv\rajra\liquidhome\Home.java',
    'liquid-home\res',
    'wallpapers\dooom.png',
    'tools\android-sdk\platforms\android-25\android.jar',
    'tools\android-sdk\build-tools\34.0.0\apksigner.bat',
    'tools\android-sdk\build-tools\34.0.0\aapt.exe'
)
foreach ($relative in $Required) { if (-not (Test-Path (Join-Path $Root $relative))) { throw "Required project input missing: $relative" } }
if (-not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) { throw "Configured JDK 17 is missing: $Jdk" }
$manifest = Get-Content -LiteralPath (Join-Path $Root 'liquid-home\AndroidManifest.xml') -Raw
if ($manifest -notmatch 'package="tv\.rajra\.liquidhome"' -or $manifest -notmatch 'android:name="\.Home"') { throw 'Manifest package or launcher activity changed unexpectedly.' }
$source = Get-Content -LiteralPath (Join-Path $Root 'liquid-home\src\tv\rajra\liquidhome\Home.java') -Raw
if ($source -match 'Hello,\s*Raj') { throw 'Developer-specific greeting is hardcoded.' }
if ($source -notmatch 'DISPLAY_NAME_KEY' -or $source -notmatch 'promptForGreetingName') { throw 'User-configurable greeting behavior is missing.' }
if (-not (Test-Path $Apk)) { throw "APK missing: $Apk. Build it with .\liquid-home\build.ps1." }

$Signer = Join-Path $Root 'tools\android-sdk\build-tools\34.0.0\apksigner.bat'
& $Signer verify --verbose $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$Aapt = Join-Path $Root 'tools\android-sdk\build-tools\34.0.0\aapt.exe'
$badging = & $Aapt dump badging $Apk 2>&1
if ($LASTEXITCODE -ne 0 -or ($badging | Out-String) -notmatch "package: name='tv\.rajra\.liquidhome'") { throw 'APK package metadata does not match the application ID.' }
$resources = & $Aapt dump --values resources $Apk 2>&1
$resourceDump = $resources | Out-String
if ($LASTEXITCODE -ne 0 -or $resourceDump -notmatch 'configured_display_name') { throw 'APK is missing its generated greeting configuration resource.' }
if ($resourceDump -notmatch 'drawable/doom') { throw 'APK is missing the built-in wallpaper resource.' }
if ($resourceDump -notmatch 'drawable/tmdb_logo') { throw 'APK is missing the TMDB logo resource.' }
if (-not [string]::IsNullOrWhiteSpace($ExpectedDisplayName)) {
    $escaped = [System.Security.SecurityElement]::Escape($ExpectedDisplayName.Trim())
    if ($resourceDump -notmatch [regex]::Escape($escaped)) { throw 'APK greeting configuration does not match the requested display name.' }
}

$apkTime = (Get-Item -LiteralPath $Apk).LastWriteTimeUtc
$inputs = @((Join-Path $Root 'liquid-home\AndroidManifest.xml'),(Join-Path $Root 'liquid-home\build.ps1'))
$inputs += (Join-Path $Root 'wallpapers\dooom.png')
$inputs += @(Get-ChildItem -LiteralPath (Join-Path $Root 'liquid-home\src') -File -Recurse | ForEach-Object FullName)
$inputs += @(Get-ChildItem -LiteralPath (Join-Path $Root 'liquid-home\res') -File -Recurse | ForEach-Object FullName)
$newestInput = ($inputs | ForEach-Object { (Get-Item -LiteralPath $_).LastWriteTimeUtc } | Measure-Object -Maximum).Maximum
if ($apkTime -lt $newestInput) { throw "APK is older than one or more build inputs. APK: $apkTime; newest input: $newestInput" }

Write-Host 'PASS: launcher package and source behavior, APK package metadata, generated greeting resource, signature, and freshness.'
Write-Host "APK: $Apk"
Write-Host "APK modified (UTC): $($apkTime.ToString('o'))"
