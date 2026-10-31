param(
    [string]$DisplayName = '',
    [switch]$AcceptGoogleSdkTerms,
    [switch]$Release,
    [string]$ReleaseKeystorePath,
    [string]$ReleaseAlias = 'liquid-home'
)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Final = Split-Path -Parent $Root
$Sdk = Join-Path $Final 'tools\android-sdk'
$AndroidJar = Join-Path $Sdk 'platforms\android-25\android.jar'
$BuildTools = Join-Path $Sdk 'build-tools\34.0.0'
$Work = Join-Path $Root 'build\work'
$Output = Join-Path $Root 'build\liquid-home.apk'

if ($Release -and (Test-Path -LiteralPath $Output)) {
    try { Remove-Item -LiteralPath $Output -Force -ErrorAction Stop }
    catch { throw 'Could not invalidate the previous final APK; refusing to start a release invocation.' }
    if (Test-Path -LiteralPath $Output) {
        throw 'Could not invalidate the previous final APK; refusing to start a release invocation.'
    }
}

if ($Release -and -not ('LiquidHomePathCanonicalizer' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class LiquidHomePathCanonicalizer {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CreateFileW")]
    private static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true, EntryPoint = "GetFinalPathNameByHandleW", CharSet = CharSet.Unicode)]
    private static extern uint GetFinalPathNameByHandle(IntPtr handle, StringBuilder path, uint length, uint flags);
    [DllImport("kernel32.dll", SetLastError = true, EntryPoint = "CloseHandle")]
    private static extern bool CloseHandle(IntPtr handle);

    public static string NormalizeFinalPath(string result) {
        if (result.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) return @"\\" + result.Substring(8);
        if (result.StartsWith(@"\\?\", StringComparison.OrdinalIgnoreCase)) return result.Substring(4);
        return result;
    }

    public static string GetFinalPath(string path) {
        const uint ShareReadWriteDelete = 0x00000001 | 0x00000002 | 0x00000004;
        const uint OpenExisting = 3;
        const uint BackupSemantics = 0x02000000;
        IntPtr handle = CreateFile(path, 0, ShareReadWriteDelete, IntPtr.Zero, OpenExisting, BackupSemantics, IntPtr.Zero);
        if (handle == new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not resolve the filesystem target.");
        try {
            StringBuilder buffer = new StringBuilder(32768);
            uint length = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0);
            if (length == 0 || length >= buffer.Capacity) throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not resolve the final filesystem path.");
            return NormalizeFinalPath(buffer.ToString());
        } finally { CloseHandle(handle); }
    }
}
'@
}

function Get-CanonicalExistingPath([string]$Path) {
    $resolvedPath = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    return [LiquidHomePathCanonicalizer]::GetFinalPath([IO.Path]::GetFullPath($resolvedPath))
}

function Test-PathWithinDirectory([string]$CandidatePath, [string]$DirectoryPath) {
    $directoryPrefix = $DirectoryPath.TrimEnd('\')
    if (-not $directoryPrefix.EndsWith('\')) { $directoryPrefix += '\' }
    return $CandidatePath.StartsWith($directoryPrefix, [StringComparison]::OrdinalIgnoreCase)
}

function Invoke-ReleaseApkSigning {
    param(
        [string]$SignerPath,
        [string]$KeystorePath,
        [string]$Alias,
        [string]$AlignedPath,
        [string]$OutputPath,
        [scriptblock]$PasswordPrompt = { param($Prompt) Read-Host -Prompt $Prompt -AsSecureString },
        [scriptblock]$SignerInvoker = {
            param($Path, $Command, $Arguments)
            & $Path $Command @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
            return $LASTEXITCODE
        },
        [scriptblock]$VerifierInvoker = {
            param($Path, $Target)
            $output = @(& $Path verify --verbose --print-certs $Target 2>&1)
            return [PSCustomObject]@{ ExitCode = $LASTEXITCODE; Output = $output }
        }
    )

    $releaseComplete = $false
    $storePassword = $null
    $keyPassword = $null
    $storePointer = [IntPtr]::Zero
    $keyPointer = [IntPtr]::Zero
    $storeText = $null
    $keyText = $null
    $runId = [guid]::NewGuid().ToString('N')
    $storeEnvName = "LIQUID_HOME_STOREPASS_$runId"
    $keyEnvName = "LIQUID_HOME_KEYPASS_$runId"
    $temporaryOutputPath = Join-Path (Split-Path -Parent $OutputPath) ("liquid-home.release.$runId.tmp.apk")
    try {
        # The final APK was invalidated at the beginning of this release invocation.
        if (Test-Path -LiteralPath $OutputPath) {
            Remove-Item -LiteralPath $OutputPath -Force -ErrorAction Stop
        }
        if (Test-Path -LiteralPath $OutputPath) {
            throw 'Could not remove the previous final APK; refusing to continue release signing.'
        }

        $storePassword = & $PasswordPrompt 'Release keystore password'
        if ($null -eq $storePassword -or -not ($storePassword -is [Security.SecureString])) {
            throw 'Release keystore password prompt was cancelled or returned no secure password.'
        }
        $keyPassword = & $PasswordPrompt 'Release signing key password'
        if ($null -eq $keyPassword -or -not ($keyPassword -is [Security.SecureString])) {
            throw 'Release signing key password prompt was cancelled or returned no secure password.'
        }
        if ($storePassword.Length -eq 0 -or $keyPassword.Length -eq 0) {
            throw 'Release keystore and key passwords cannot be empty.'
        }

        $storePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePassword)
        $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keyPassword)
        $storeText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($storePointer)
        $keyText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
        [Environment]::SetEnvironmentVariable($storeEnvName, $storeText, 'Process')
        [Environment]::SetEnvironmentVariable($keyEnvName, $keyText, 'Process')

        $signArguments = @('--ks', $KeystorePath, '--ks-key-alias', $Alias, '--ks-pass', "env:$storeEnvName", '--key-pass', "env:$keyEnvName", '--out', $temporaryOutputPath, $AlignedPath)
        $signExitCode = & $SignerInvoker $SignerPath 'sign' $signArguments

        # SignerInvoker is synchronous: the apksigner process has exited here.
        # Remove credentials before launching the verifier, which does not need them.
        Remove-Item -LiteralPath "Env:\$storeEnvName" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath "Env:\$keyEnvName" -ErrorAction SilentlyContinue
        if ($storePointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storePointer); $storePointer = [IntPtr]::Zero }
        if ($keyPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer); $keyPointer = [IntPtr]::Zero }
        $storeText = $null
        $keyText = $null
        if ($storePassword) { $storePassword.Dispose(); $storePassword = $null }
        if ($keyPassword) { $keyPassword.Dispose(); $keyPassword = $null }

        if ([int]$signExitCode -ne 0) { throw 'Release APK signing failed.' }

        $verification = & $VerifierInvoker $SignerPath $temporaryOutputPath
        foreach ($line in $verification.Output) { Write-Host $line }
        if ([int]$verification.ExitCode -ne 0) { throw 'Release APK signature verification failed.' }
        $fingerprintLine = $verification.Output | Where-Object { "$_" -match 'certificate SHA-256 digest:' } | Select-Object -First 1
        if (-not $fingerprintLine) { throw 'APK verified, but apksigner did not report a signing certificate SHA-256 fingerprint.' }
        Write-Host "Release signing certificate SHA-256 fingerprint: $($fingerprintLine.ToString().Trim())"

        # Publish only the verified artifact. Both paths are in the same directory,
        # so the final rename stays on one volume.
        [IO.File]::Move($temporaryOutputPath, $OutputPath)
        $releaseComplete = $true
    } finally {
        Remove-Item -LiteralPath "Env:\$storeEnvName" -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath "Env:\$keyEnvName" -ErrorAction SilentlyContinue
        if ($storePointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storePointer) }
        if ($keyPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer) }
        $storeText = $null
        $keyText = $null
        if ($storePassword) { $storePassword.Dispose() }
        if ($keyPassword) { $keyPassword.Dispose() }
        $storePassword = $null
        $keyPassword = $null
        if (-not $releaseComplete) {
            $cleanupFailed = $false
            foreach ($failedOutput in @($temporaryOutputPath, $OutputPath)) {
                if (Test-Path -LiteralPath $failedOutput) {
                    try { Remove-Item -LiteralPath $failedOutput -Force -ErrorAction Stop }
                    catch { $cleanupFailed = $true }
                }
            }
            if ($cleanupFailed) {
                throw 'Release signing or verification failed, and an APK output could not be removed. Do not distribute any remaining output; remove it manually.'
            }
        }
    }
}

# Release signing always uses an existing keystore outside the checkout. Validate
# this before setup or cleanup so a bad release invocation fails without side effects.
if ($Release) {
    if ([string]::IsNullOrWhiteSpace($ReleaseKeystorePath)) {
        throw 'Release signing requires -ReleaseKeystorePath pointing to an existing external keystore.'
    }
    if ([string]::IsNullOrWhiteSpace($ReleaseAlias)) {
        throw 'Release signing requires a non-empty -ReleaseAlias.'
    }
    $ResolvedReleaseKey = Resolve-Path -LiteralPath $ReleaseKeystorePath -ErrorAction Stop
    if (Test-Path -LiteralPath $ResolvedReleaseKey.Path -PathType Container) {
        throw 'ReleaseKeystorePath must identify a keystore file, not a directory.'
    }
    $RepoRootOutput = & git -C $Final rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($RepoRootOutput | Out-String).Trim())) {
        throw 'Could not determine the Git repository root; refusing release signing.'
    }
    $RepoRoot = Get-CanonicalExistingPath (($RepoRootOutput | Out-String).Trim())
    $FullReleaseKey = Get-CanonicalExistingPath $ResolvedReleaseKey.Path
    if (Test-PathWithinDirectory $FullReleaseKey $RepoRoot) {
        throw "Release keystore must be stored outside the Git repository. Rejected path: $ReleaseKeystorePath"
    }
    $ReleaseKeystorePath = $FullReleaseKey
} elseif (-not [string]::IsNullOrWhiteSpace($ReleaseKeystorePath) -or $ReleaseAlias -ne 'liquid-home') {
    throw 'Use -ReleaseKeystorePath and -ReleaseAlias only together with -Release.'
}

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
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'
if ($Release) {
    Invoke-ReleaseApkSigning -SignerPath $ApkSigner -KeystorePath $ReleaseKeystorePath -Alias $ReleaseAlias -AlignedPath $Aligned -OutputPath $Output
} else {
    # Preserve the existing developer-local signing workflow.
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
    & $ApkSigner sign --ks $Key --ks-pass "pass:$SigningPassword" --key-pass "pass:$SigningPassword" --out $Output $Aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $ApkSigner verify --verbose $Output
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
}
$apk = Get-Item $Output
Write-Host "Built and verified: $Output"
Write-Host "Size: $($apk.Length) bytes; modified: $($apk.LastWriteTime.ToString('o'))"

