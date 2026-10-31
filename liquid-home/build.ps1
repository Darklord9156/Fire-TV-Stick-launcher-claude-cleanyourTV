param([string]$DisplayName = '',[switch]$AcceptGoogleSdkTerms)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Final = Split-Path -Parent $Root
$Sdk = Join-Path $Final 'tools\android-sdk'
$AndroidJar = Join-Path $Sdk 'platforms\android-25\android.jar'
$BuildTools = Join-Path $Sdk 'build-tools\34.0.0'
$Work = Join-Path $Root 'build\work'
$Output = Join-Path $Root 'build\liquid-home.apk'
& { . (Join-Path $Final 'setup-tools.ps1') -JdkOnly }
$Jdk = $env:JAVA_HOME
if (-not (Test-Path $AndroidJar) -or -not (Test-Path (Join-Path $BuildTools 'aapt2.exe'))) {
    if ($AcceptGoogleSdkTerms) { & { . (Join-Path $Final 'setup-tools.ps1') -AcceptGoogleSdkTerms } }
    else { & { . (Join-Path $Final 'setup-tools.ps1') } }
    $Jdk = $env:JAVA_HOME
}
foreach ($tool in @('aapt2.exe','d8.bat','zipalign.exe','apksigner.bat')) { if (-not (Test-Path (Join-Path $BuildTools $tool))) { throw "Missing $tool. Run setup-tools.ps1." } }
if (-not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) { throw "Configured JDK 17 is missing its compiler: $Jdk" }
$env:JAVA_HOME = $Jdk; $env:ANDROID_SDK_ROOT = $Sdk; $env:ANDROID_HOME = $Sdk
$env:PATH = (Join-Path $Jdk 'bin') + ';' + $env:PATH
New-Item -ItemType Directory -Force -Path $Work,(Split-Path -Parent $Output) | Out-Null
$FullRoot = (Resolve-Path $Root).Path.TrimEnd('\') + '\'
$FullWork = (Resolve-Path $Work).Path.TrimEnd('\') + '\'
if (-not $FullWork.StartsWith($FullRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "Build work folder escaped project: $FullWork" }
Get-ChildItem -LiteralPath $Work -Force | Remove-Item -Force -Recurse
$BuildResources = Join-Path $Work 'resources'
New-Item -ItemType Directory -Force -Path $BuildResources | Out-Null
Get-ChildItem -LiteralPath (Join-Path $Root 'res') -Force | Copy-Item -Destination $BuildResources -Recurse -Force
$DefaultWallpaper = Join-Path $Final 'wallpapers\doom.jpg'
if (-not (Test-Path -LiteralPath $DefaultWallpaper)) { throw "Default wallpaper is missing: $DefaultWallpaper" }
$DrawableResources = Join-Path $BuildResources 'drawable-nodpi'
New-Item -ItemType Directory -Force -Path $DrawableResources | Out-Null
Copy-Item -LiteralPath $DefaultWallpaper -Destination (Join-Path $DrawableResources 'doom.jpg') -Force
$Values = Join-Path $BuildResources 'values'
New-Item -ItemType Directory -Force -Path $Values | Out-Null
$EscapedName = [System.Security.SecurityElement]::Escape($DisplayName.Trim())
$config = "<?xml version=`"1.0`" encoding=`"utf-8`"?><resources><string name=`"configured_display_name`" translatable=`"false`">$EscapedName</string></resources>"
[IO.File]::WriteAllText((Join-Path $Values 'liquid_home_build_config.xml'),$config,[Text.UTF8Encoding]::new($false))
$ResZip = Join-Path $Work 'res.zip'; $AppApk = Join-Path $Work 'app.apk'; $Aligned = Join-Path $Work 'aligned.apk'
$Classes = Join-Path $Work 'classes'; $Generated = Join-Path $Work 'generated'
New-Item -ItemType Directory -Force -Path $Classes,$Generated | Out-Null
& (Join-Path $BuildTools 'aapt2.exe') compile --dir $BuildResources -o $ResZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 resource compilation failed.' }
& (Join-Path $BuildTools 'aapt2.exe') link -I $AndroidJar --manifest (Join-Path $Root 'AndroidManifest.xml') $ResZip --min-sdk-version 25 --target-sdk-version 25 --version-code 1 --version-name 1.0 --java $Generated -o $AppApk
if ($LASTEXITCODE -ne 0) { throw 'aapt2 resource/manifest linking failed.' }
$Sources = @(Get-ChildItem (Join-Path $Root 'src') -Filter '*.java' -Recurse | ForEach-Object FullName) + @(Get-ChildItem $Generated -Filter '*.java' -Recurse | ForEach-Object FullName)
if ($Sources.Count -eq 0) { throw 'No Java sources found.' }
& (Join-Path $Jdk 'bin\javac.exe') --release 8 -encoding UTF-8 -classpath $AndroidJar -d $Classes $Sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
Push-Location -LiteralPath $Classes
try {
    # Pass short paths relative to the classes folder. Absolute class paths can
    # exceed cmd.exe's 8191-character limit in d8.bat on Windows checkouts.
    $ClassFiles = @(Get-ChildItem -LiteralPath . -Filter '*.class' -Recurse | ForEach-Object {
        Resolve-Path -LiteralPath $_.FullName -Relative
    })
    & (Join-Path $BuildTools 'd8.bat') --release --min-api 25 --lib $AndroidJar --output $Work $ClassFiles
    $D8ExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($D8ExitCode -ne 0) { throw 'D8 dex compilation failed.' }
& (Join-Path $Jdk 'bin\jar.exe') uf $AppApk -C $Work classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Could not package classes.dex.' }
& (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $AppApk $Aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
$Key = Join-Path $Root 'liquid-home.keystore'
$SecretDir = Join-Path $Final '.local'; $SecretFile = Join-Path $SecretDir 'signing-password.txt'
New-Item -ItemType Directory -Force -Path $SecretDir | Out-Null
if (-not (Test-Path $Key)) {
    if (-not (Test-Path $SecretFile)) {
        $bytes = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $password = ([BitConverter]::ToString($bytes) -replace '-','')
        [IO.File]::WriteAllText($SecretFile,$password,[Text.UTF8Encoding]::new($false))
    }
    $SigningPassword = [IO.File]::ReadAllText($SecretFile).Trim()
    & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair -noprompt -keystore $Key -alias liquid-home -keyalg RSA -keysize 2048 -validity 10000 -storepass $SigningPassword -keypass $SigningPassword -dname 'CN=Liquid Home'
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a local signing key.' }
}
if (-not (Test-Path $SecretFile)) { throw 'Signing password missing. Preserve the keystore and .local/signing-password.txt for updates.' }
$SigningPassword = [IO.File]::ReadAllText($SecretFile).Trim()
& (Join-Path $BuildTools 'apksigner.bat') sign --ks $Key --ks-pass "pass:$SigningPassword" --key-pass "pass:$SigningPassword" --out $Output $Aligned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& (Join-Path $BuildTools 'apksigner.bat') verify --verbose $Output
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$apk = Get-Item $Output
Write-Host "Built and verified: $Output"
Write-Host "Size: $($apk.Length) bytes; modified: $($apk.LastWriteTime.ToString('o'))"

