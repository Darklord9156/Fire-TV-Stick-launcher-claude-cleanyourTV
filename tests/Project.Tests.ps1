$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$script:Passed = 0
$script:Failed = 0

function Assert-True([bool]$Condition, [string]$Message) {
    if ($Condition) { $script:Passed++; Write-Host "PASS $Message" -ForegroundColor Green }
    else { $script:Failed++; Write-Host "FAIL $Message" -ForegroundColor Red }
}
function Read-ProjectFile([string]$RelativePath) {
    $path = Join-Path $Root $RelativePath
    if (-not (Test-Path $path)) { throw "Required file missing: $RelativePath" }
    return Get-Content -LiteralPath $path -Raw
}

$manifest = Read-ProjectFile 'liquid-home\AndroidManifest.xml'
$build = Read-ProjectFile 'liquid-home\build.ps1'
$install = Read-ProjectFile 'install.ps1'
$preflight = Read-ProjectFile 'scripts\preflight.ps1'
$verify = Read-ProjectFile 'verify.ps1'
$ignore = Read-ProjectFile '.gitignore'
$readme = Read-ProjectFile 'README.md'
$homeSource = Read-ProjectFile 'liquid-home\src\tv\rajra\liquidhome\Home.java'
$transferServer = Read-ProjectFile 'liquid-home\src\tv\rajra\liquidhome\WallpaperTransferServer.java'
$sync = Read-ProjectFile 'sync-wallpapers.ps1'

Assert-True ($manifest -match 'package="tv\.rajra\.liquidhome"' -and $manifest -match 'android:name="\.Home"') 'existing launcher package and activity are preserved'
Assert-True ($manifest -match 'android.intent.category.HOME' -and $manifest -match 'android.intent.category.LEANBACK_LAUNCHER') 'Fire TV Home and Leanback launch entry points remain declared'
Assert-True ($build -match 'javac\.exe' -and $build -match 'aapt2\.exe' -and $build -match 'd8\.bat' -and $build -notmatch '(?i)gradle') 'standalone Java/API build remains Gradle-free'
Assert-True ($install -match "'install','-r'" -and $install -notmatch "(?m)^\s*Invoke-Adb.*'uninstall'" -and $install -notmatch 'kill-server') 'updates preserve app data and never uninstall or reset ADB authorization'
Assert-True ($install -match 'unauthorized' -and $install -match 'Always allow' -and $install -match 'ro\.product\.manufacturer' -and $install -match 'ro\.product\.model') 'installer checks authorization and Fire TV identity'
Assert-True ($install -match "'am','start','-W'" -and $install -match "'pm','path'" -and $install -match "mResumedActivity|topResumedActivity") 'installer launches and verifies the installed foreground activity'
Assert-True ($install -match "'pm','list','packages'" -and $install.IndexOf("'pm','list','packages'",[StringComparison]::Ordinal) -lt $install.IndexOf("'appops','get'",[StringComparison]::Ordinal)) 'first-install settings snapshot uses a safe package-presence check before appops lookup'
Assert-True ($install -match 'start-server' -and $install -notmatch 'kill-server|adbkey') 'installer reuses the existing ADB host key without rotating or resetting authorization'
Assert-True ($install -match 'platform-tools\\adb\.exe' -and (Read-ProjectFile 'restore-amazon-home.ps1') -match 'platform-tools\\adb\.exe') 'install and recovery use the same bundled ADB client and host identity'
Assert-True ($preflight -match 'unauthorized' -and $preflight -match 'android-25' -and $preflight -match 'Fire TV') 'preflight checks local build prerequisites and authorized Fire TV'
Assert-True ($verify -match 'apksigner' -and $verify -match 'configured_display_name') 'verification checks generated APK signing and launcher greeting configuration'
Assert-True ($verify -match 'wallpapers\\doom\.jpg' -and $verify -match 'drawable/doom') 'APK verification requires the requested Doom default wallpaper'
Assert-True ($ignore -match '(?m)^/\.local/?$' -and $ignore -match '\*\.keystore' -and $ignore -match 'android-sdk') 'signing secrets, ADB keys, generated outputs, and SDK downloads are ignored'
Assert-True ($readme -match 'scripts\\preflight\.ps1' -and $readme -match 'tests\\Project\.Tests\.ps1') 'README documents the actual preflight and test commands'
Assert-True ($homeSource -match 'BUILTIN = "doom\.jpg"' -and $build -match 'wallpapers\\doom\.jpg' -and $sync -match "Join-Path .*'wallpapers'") 'Doom is the built-in default and root wallpapers folder is the sync source'
Assert-True ($homeSource -match 'Transfer from Phone/PC' -and $homeSource -match 'createQrBitmap' -and $homeSource -match 'confirmDeleteWallpaper') 'background settings expose upload, QR, and deletion without removing selection behavior'
Assert-True ($homeSource -match 'HOME_APPS_COLUMNS = 6' -and $homeSource -match 'countPerRow = HOME_APPS_COLUMNS') 'Home All Apps uses six aligned columns'
Assert-True ($homeSource -match 'homeAppTileWidthPixels' -and $homeSource -match 'screenWidth - nonTileSpace' -and $homeSource -match 'appTileHeightPixels' -and $homeSource -match 'rowTileHeight') 'Main Row and All Apps use responsive icon-based rectangular tile sizing'
Assert-True ($homeSource -match 'img\.setScaleType\(ImageView\.ScaleType\.FIT_CENTER\)' -and $homeSource -match 'imageLp\.setMargins\(dp\(4\), dp\(4\), dp\(4\), dp\(4\)\)' -and $homeSource -match 'Drawable artDrawable = item\.banner != null \? item\.banner : item\.icon' -and $homeSource -match 'img\.setImageDrawable\(artDrawable\)' -and $homeSource -match 'HOME_TILE_ASPECT_RATIO = 1\.695f' -and $homeSource -match 'tileWidth / HOME_TILE_ASPECT_RATIO' -and $homeSource -notmatch 'removeFlatIconBackground|matchesIconBackground') 'original launcher artwork is centered without modification inside reference-ratio landscape tiles'
Assert-True ($homeSource -match 'com\.android\.documentsui' -and $homeSource -match 'com\.amazon\.ftv\.screensaver' -and $homeSource -match '"files", "screen saver"' -and $homeSource -match '!isExcludedAllApp\(item\)') 'Files and Screen Saver are excluded from launcher application collections'
Assert-True ($homeSource -match 'showAllAppsRemoteMenu' -and $homeSource -match '"Move to Main Row"' -and $homeSource -match 'moveAllAppToMainRow\(index, selected\.pkg\)') 'remote context menu can move a focused All Apps entry into Main Row'
Assert-True ($homeSource -match 'replacementIndex = HOME_APPS_COLUMNS - 1' -and $homeSource -match 'favorites\.set\(replacementIndex, selected\)' -and $homeSource -match 'homeAllApps\.add\(displaced\)' -and $homeSource -match 'putString\(MAIN_ROW_KEY' -and $homeSource -match 'putString\(ALL_APPS_ORDER_KEY') 'All Apps transfer swaps the sixth tile and persists both lists without duplicates'
Assert-True ($homeSource -match 'STREAMING_PRIORITY' -and $homeSource -match 'isEligibleMainRowApp' -and $homeSource -match 'MAIN_ROW_KEY') 'Main Row candidates are discovered and user selections persist'
Assert-True ($homeSource -notmatch 'Manage Main Row|buildManageMainRow|showMainRowAppActions') 'Settings no longer exposes the redundant Main Row manager'
Assert-True ($homeSource -match 'favorites\.size\(\) >= HOME_APPS_COLUMNS' -and $homeSource -match 'private void removeMainRowApp' -and $homeSource -match 'eligibleMainRowApps') 'Main Row is capped at six and removal fills the slot from detected apps'
Assert-True ($homeSource -match 'FIRE_SETTINGS' -and $homeSource -match 'Fire TV Settings' -and $homeSource -match 'openFireSettings\(\)') 'native Fire TV Settings has its own top navigation icon'
Assert-True ($homeSource -match 'getContrastShadowColor' -and $homeSource -match 'setShadowLayer' -and $homeSource -match '180f - Math\.min\(255f, luminance\)') 'focus stroke contrast responds to the selected palette colour'
Assert-True ($homeSource -match 'KEYCODE_MENU' -and $homeSource -match 'Move Apps' -and $homeSource -match 'beginMainRowMove') 'remote Menu button opens the Main Row context actions and Move Apps mode'
Assert-True ($homeSource -match 'KEYCODE_DPAD_LEFT.*moveMainRowSelection' -and $homeSource -match 'KEYCODE_DPAD_RIGHT.*moveMainRowSelection' -and $homeSource -match 'confirmMainRowMove' -and $homeSource -match 'cancelMainRowMove') 'remote Move Apps mode supports directional movement, SELECT confirmation, and BACK cancellation'
Assert-True ($homeSource -match 'Delete selected wallpaper' -and $homeSource -match 'confirmDeleteWallpaper\(selectedWallpaper\)') 'selected custom wallpaper has an explicit delete action'
Assert-True ($homeSource -match 'spacerLp\.leftMargin = dp\(6\); spacerLp\.rightMargin = dp\(6\)' -and $homeSource -match 'for \(int col = 0; col < countPerRow; col\+\+\)') 'incomplete All Apps rows preserve fixed column gutters'
Assert-True ($homeSource -match 'ACTION_PACKAGE_ADDED' -and $homeSource -match 'refreshInstalledApps') 'launcher refreshes Home and Apps after package install or removal'
Assert-True ($homeSource -match 'PathMeasure edgeMeasure' -and $homeSource -match '1f - edgePhase' -and $homeSource -match 'edgeMeasure\.getPosTan' -and $homeSource -match 'segment < 2' -and $homeSource -match 'Path\.Direction\.CW') 'focused Home app tiles draw two counter-clockwise neon segments on a rounded path'
Assert-True ($homeSource -match 'edgePathLength \* \.308f' -and $homeSource -match 'private void drawNeonSegment' -and $homeSource -match 'buildTaperedSweep\(edgeCoreSweepPath, start, length' -and $homeSource -match 'progress / \.16f' -and $homeSource -match 'edgeLeftContour' -and $homeSource -notmatch 'edgeTaperPieces|drawTaperedNeonSegment') 'neon segments are 40 percent longer, continuous, and smoothly faded at both ends'
Assert-True ($homeSource -match 'dpF\(\.47311f\)' -and $homeSource -match 'dpF\(1\.60000f\)' -and $homeSource -match 'dpF\(4\.7000f\)' -and $homeSource -match 'private float dpF\(float x\)') 'focus center stroke and halo match the requested 3.2 px and 9.4 px widths at 320 dpi'
Assert-True ($homeSource -match 'getBrighterFocusEffectColor' -and $homeSource -match 'final float lift = \.14f' -and $homeSource -match '110 \* edgeAlpha' -and $homeSource -match 'edgeHaloPaint\.setAlpha\(Math\.round\(100 \* edgeAlpha\)\)' -and $homeSource -match 'edgePaint\.setAlpha\(Math\.round\(255 \* edgeAlpha\)\)') 'focused neon glow uses a brighter color and stronger halo opacity'
Assert-True ($homeSource -match 'edgeAlpha = 0f;\s*invalidate\(\);' -and $homeSource -notmatch 'edgeAlphaAnimator|ValueAnimator fadeOut') 'previous app glow clears immediately without a trailing glow fade'
Assert-True ($homeSource -match 'scaleX\(hasFocus \? 1\.08f : 1f\)' -and $homeSource -match 'scaleY\(hasFocus \? 1\.08f : 1f\)' -and $homeSource -match 'setDuration\(125L\)') 'Home app tiles lift smoothly and return to their original size on focus loss'
Assert-True ($homeSource -match 'animator\.setDuration\(350L\)' -and $homeSource -match 'new AccelerateDecelerateInterpolator' -and $homeSource -match 'event\.getAction\(\) == KeyEvent\.ACTION_DOWN && homeNavKeyHandled') 'vertical Home navigation uses a 350 ms eased motion and suppresses held-key repeats'
Assert-True ($homeSource -match 'WALLPAPER_BLUR_EXECUTOR\.execute' -and $homeSource -match 'boxBlur\(soft, 2\)' -and $homeSource -match 'applyWallpaperMotion\(fraction\)' -and $homeSource -match 'setLayerType\(View\.LAYER_TYPE_HARDWARE') 'wallpaper blur is cached off-thread and hardware transforms animate scale, parallax, and shade'
Assert-True ($homeSource -match 'Focus Effect Colour' -and $homeSource -match 'Doctor Doom Green' -and $homeSource -match 'Antique Gold' -and $homeSource -match 'FOCUS_EFFECT_COLORS' -and $homeSource -match 'SpannableString' -and $homeSource -match 'ForegroundColorSpan') 'Settings provides ten persistent focus colour choices with live colour previews'
Assert-True ($homeSource -match 'FOCUS_EFFECT_COLOR_KEY' -and $homeSource -match 'putInt\(FOCUS_EFFECT_COLOR_KEY, index\)' -and $homeSource -match 'refreshFocusEffectColor\(\)') 'focus colour persists and refreshes existing Home app tiles immediately'
Assert-True ($homeSource -match 'setSingleChoiceItems\(options, selected' -and $homeSource -match 'choiceDialog\.dismiss\(\)') 'native selectable colour list supports remote up/down and select controls'
Assert-True ($homeSource -match 'column\.getWidth\(\) \* \.42f' -and $homeSource -match '47\.6f, 29\.4f, 8\.4f') 'Settings bars, height, and icon lane are reduced by exactly 30 percent'
Assert-True ($homeSource -match 'if \(!appTile\)' -and $homeSource -match 'setAppTile\(false\)') 'custom neon border replaces the old border only for Home favourites and All Apps'
Assert-True ($transferServer -match 'SecureRandom' -and $transferServer -match 'IDLE_TIMEOUT_MS' -and $transferServer -match 'MAX_UPLOAD_BYTES' -and $transferServer -match "accept='.jpg,.jpeg,.png,.webp") 'temporary upload server uses a random token, expires, caps size, and filters formats'
Assert-True ($transferServer -notmatch 'SharedPreferences|putString\("wallpaper"' -and $homeSource -match 'Close this session to select it') 'uploads are saved for selection without automatically applying them'

$forbidden = @('AGENTS.md','CLAUDE.md','.cursorrules','.cursor\rules','.github\workflows')
foreach ($relative in $forbidden) { Assert-True (-not (Test-Path (Join-Path $Root $relative))) "no prohibited agent/workflow file: $relative" }

$parseErrors = @()
Get-ChildItem -LiteralPath $Root -Filter '*.ps1' -File -Recurse | Where-Object { $_.FullName -notmatch '[\\/](tools|\.local|build)[\\/]' } | ForEach-Object {
    $tokens = $null; $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($_.FullName,[ref]$tokens,[ref]$errors) | Out-Null
    foreach ($parseIssue in $errors) { $parseErrors += "$(Split-Path $_.FullName -Leaf): $($parseIssue.Message)" }
}
Assert-True ($parseErrors.Count -eq 0) 'all project PowerShell scripts parse without syntax errors'
if ($parseErrors.Count) { $parseErrors | ForEach-Object { Write-Host $_ -ForegroundColor Red } }

. (Join-Path $Root 'setup-tools.ps1') -JdkOnly
$JdkBin = Join-Path $env:JAVA_HOME 'bin'
$FeatureWork = Join-Path $Root ('.local\test-wallpaper-' + [guid]::NewGuid().ToString('N'))
$FeatureOutput = Join-Path $FeatureWork 'classes'
$UploadTestDir = Join-Path $FeatureWork 'uploads'
New-Item -ItemType Directory -Force -Path $FeatureOutput,$UploadTestDir | Out-Null
try {
    $javaSources = @(
        (Join-Path $Root 'liquid-home\src\tv\rajra\liquidhome\QrMatrix.java'),
        (Join-Path $Root 'liquid-home\src\tv\rajra\liquidhome\WallpaperTransferServer.java'),
        (Join-Path $Root 'tests\WallpaperFeatureTests.java')
    )
    & (Join-Path $JdkBin 'javac.exe') --release 8 -encoding UTF-8 -d $FeatureOutput $javaSources
    if ($LASTEXITCODE -ne 0) { throw 'Feature test Java compilation failed.' }
    & (Join-Path $JdkBin 'java.exe') -cp $FeatureOutput tv.rajra.liquidhome.WallpaperFeatureTests $UploadTestDir
    if ($LASTEXITCODE -ne 0) { throw 'Wallpaper server/QR feature tests failed.' }
    $script:Passed++
} catch {
    $script:Failed++
    Write-Host "FAIL Java feature tests: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    $fullWork = (Resolve-Path -LiteralPath $FeatureWork).Path
    $fullLocal = (Resolve-Path -LiteralPath (Join-Path $Root '.local')).Path.TrimEnd('\') + '\'
    if ($fullWork.StartsWith($fullLocal,[StringComparison]::OrdinalIgnoreCase)) { Remove-Item -LiteralPath $fullWork -Recurse -Force }
}

Write-Host "`nResults: $script:Passed passed; $script:Failed failed."
if ($script:Failed -gt 0) { exit 1 }
