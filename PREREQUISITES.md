# Prerequisites and toolchain

This document describes the requirements used by the current PowerShell build, verification, test, and Fire TV installation scripts. Start with the [README](README.md) for the project overview and user workflow.

## Supported development host

The checked-in automation is written for **Windows 10 or Windows 11** and **Windows PowerShell 5.1**. The project workflows are provided as PowerShell scripts; Linux and macOS host workflows are not provided or verified. PowerShell 7 may run some scripts, but it is not the documented or validated baseline.

The intended device class is Fire TV. The app declares minSdk 25 and targetSdk 25 and includes Android HOME and Leanback launcher entry points. The current APK's package is `tv.rajra.liquidhome`, version code 1, version name 1.0. One actual Fire TV was used for manual testing; this does not establish compatibility across all Fire TV models or Fire OS versions. General Android TV compatibility has not been established.

## Required for a local build

| Requirement | Exact requirement | Used for |
|---|---|---|
| Windows PowerShell | Windows PowerShell 5.1 baseline | Running `.ps1` scripts for setup, build, verification, tests, and device workflows. |
| JDK | A working **JDK 17**, including `java`, `javac`, `jar`, and `keytool` | Java checks, compilation, packaging, signing support, and tests. A JRE alone is insufficient. |
| Android platform | Android SDK Platform **25**, including `platforms/android-25/android.jar` | Compile-time Android API and APK manifest minimum/target API. |
| Android Build Tools | **34.0.0**; `aapt2.exe`, `d8.bat`, `zipalign.exe`, `apksigner.bat`, and `aapt.exe` | Resource/manifest processing, dex conversion, APK alignment/signing, and APK verification. |

The build invokes `aapt2`, `javac --release 8`, `d8`, `jar`, `zipalign`, and `apksigner` directly. `verify.ps1` also invokes `aapt.exe`. **Gradle, Android Studio, and Android SDK Command-line Tools (`sdkmanager`) are not required by these scripts.** `setup-tools.ps1` fetches the SDK packages directly from Google's repository when components are missing.

Provisioning-only commands are Windows `curl.exe` and PowerShell's built-in `Expand-Archive`; they are used only when setup must download/extract JDK or SDK archives. These are standard Windows components, not separate project downloads.

The SDK is expected under the local project path `tools/android-sdk/`. It is ignored by `.gitignore`; do not treat the machine's local SDK directory as a source file or assume it is part of a public checkout. `setup-tools.ps1` reuses complete required components and provisions missing SDK packages after the Google SDK terms have been presented and accepted.

## JDK 17 selection and provisioning

The build does not require a JDK bundled in or committed with the repository. `setup-tools.ps1` looks for a compatible JDK 17 in this order: `LIQUID_HOME_JDK_HOME`, `JAVA_HOME`, JDKs discoverable from `javac` on `PATH`, then common Windows installation locations. It checks that Java and the compiler run and that the release metadata identifies Java 17.

If no compatible JDK is found, setup downloads the pinned **Eclipse Temurin 17.0.20.1+1** Windows archive and verifies its configured SHA-256 before provisioning it under:

```text
%LOCALAPPDATA%\LiquidHome\toolchains\jdk-17.0.20.1+1
```

That managed JDK location is outside the project. The downloaded archive is staged under the Windows temporary directory; it is not a project dependency to commit. An already installed compatible JDK 17 is reused instead.

To select a JDK explicitly, set `LIQUID_HOME_JDK_HOME` to the **JDK root directory** (the directory containing `bin/java.exe` and `bin/javac.exe`), not its `bin` directory. For example:

```powershell
$env:LIQUID_HOME_JDK_HOME = 'D:\Java\jdk-17'
```

The scripts set `JAVA_HOME`, prepend that JDK's `bin` to `PATH`, and set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to this project's SDK location for the current process. These variables do not need to be permanently configured when setup can discover the tools.

## Optional tools and services

- **ADB/device access** is optional for compiling and running source-only checks, but required for preflight, installation, wallpaper sync, and recovery. Fire TV and computer must be reachable on the same network for network ADB. Enable ADB Debugging and approve the computer's host key on the TV.
- **TMDB API Read Access Token** is optional. Search uses TMDB and requires internet access and a user-provided token. The app encrypts the saved token with Android Keystore. Do not put a real token in source, the example token file, or a command that may be recorded.
- **Phone/browser** is optional for the local wallpaper upload. It must be able to reach the Fire TV on the same trusted LAN/Wi-Fi network.
- **Android Studio, Gradle, emulator, screenshots, and an external image editor** are not prerequisites for the checked-in build/test scripts.

Signing material does not have to exist before the first local build: the build script can generate a developer-local key and password. A key and matching password are needed to update an existing install with the same signing certificate. See [Signing credentials](#signing-credentials).

## Network requirements

No network access is needed to build if a compatible JDK and all required SDK components are already present. Setup may need:

- `github.com` to fetch the pinned Temurin JDK archive when no compatible JDK is available. The script verifies the archive's pinned SHA-256.
- `dl.google.com` to fetch Google's SDK repository manifest and any missing Platform 25, Build Tools 34.0.0, or Platform Tools archive. Setup asks the developer to review/accept the Android SDK terms before SDK downloads and checks the archive checksum against Google's manifest.
- Internet access from the Fire TV for optional TMDB catalog/image requests.
- A local network path between the computer and Fire TV for ADB; or between the Fire TV and browser device for wallpaper uploads. The upload server uses HTTP without TLS and should only be used on a trusted network.

The normal setup path does not download anything when a compatible JDK and all required Android SDK files are already available. ADB startup can also be subject to host security/sandbox restrictions; if the local server reports a socket permission error, see [Troubleshooting](docs/TROUBLESHOOTING.md).

## Signing credentials

The build has separate developer and release signing modes.

### Developer builds

The normal command (`.\liquid-home\build.ps1`) keeps the existing local workflow. It uses `liquid-home/liquid-home.keystore` and `.local/signing-password.txt`, generating a developer-local key and random password file if needed. The developer password is stored in that ignored local file as plaintext and is also passed to signing tools in command-line arguments. This is for local development convenience, not production release signing. Release builds use a separate secure password-prompt workflow. Do not reuse your release keystore password as the developer signing password, and do not reuse the automatically generated developer key as the public release identity.

### Release builds

Create a dedicated release keystore once, using a trusted JDK `keytool`, and store it outside the project directory. Run this command yourself when ready; `keytool` prompts for passwords instead of putting them in the command:

```powershell
keytool -genkeypair -v -keystore "D:\Secure\LiquidHome-release.jks" -alias liquid-home -keyalg RSA -keysize 2048 -validity 10000
```

Keep an encrypted backup in a separate secure location. Do not commit it, put it in the project, or upload it to GitHub. No release keystore is provided by this repository, and the release build never generates one.

Build a release APK by providing the existing external keystore and its alias:

```powershell
.\liquid-home\build.ps1 -Release -ReleaseKeystorePath "D:\Secure\LiquidHome-release.jks" -ReleaseAlias "liquid-home" -DisplayName "Alex"
```

The release command removes the old final APK at the start, signs to a unique temporary APK in the build directory, verifies that temporary APK and its certificate, then atomically renames the verified file to `liquid-home/build/liquid-home.apk`. On ordinary signing or verification failure, it removes the temporary and final APK outputs while preserving unrelated build files. Forced termination can bypass cleanup and leave a temporary APK or a still-running signer child; the final APK remains unpublished until verification succeeds.

The script prompts for the keystore password and the private-key password using secure, non-echoing prompts. It passes them to `apksigner` through unique temporary process environment variables, then removes those variables immediately after the signer exits, before verification. It also frees temporary unmanaged buffers and disposes the secure prompt values, with `finally` cleanup as a fallback. Passwords are not placed in command arguments or release password files. Forced termination of PowerShell or the machine can bypass in-process cleanup; a still-running signer child may retain its inherited environment until it exits. The alias defaults to `liquid-home`; set `-ReleaseAlias` if your keystore uses a different alias.

Keep the same release keystore, alias, and access to the signing key for every published update. Android requires an update to use a compatible signing certificate; losing or replacing the release key prevents normal updates to installations signed by it. Keep the keystore and encrypted backups outside the project in separate protected locations, and protect its passwords with a password manager. Never store the keystore or passwords in the repository, GitHub, source-control history, or shared logs. The script rejects keystore paths inside the Git repository, including paths resolved through directory junctions or symbolic links.

## Build, install, verify, and test

From Windows PowerShell at the project root:

```powershell
.\setup-tools.ps1
.\liquid-home\build.ps1 -DisplayName "Alex"
.\verify.ps1 -ExpectedDisplayName "Alex"
```

The `-DisplayName` argument is optional. The generated APK is `liquid-home/build/liquid-home.apk`. The build script regenerates its files under `liquid-home/build/`; `verify.ps1` checks APK signing, package ID, configured greeting resource, bundled default wallpaper, and freshness against source inputs.

To check and install on a Fire TV, use its current IPv4 address:

```powershell
.\scripts\preflight.ps1 -TvIp "192.168.1.50"
.\install.ps1 -TvIp "192.168.1.50" -DisplayName "Alex"
```

`preflight.ps1` checks local inputs and the authorized Fire TV without installing. `install.ps1` builds again, installs with `adb install -r`, grants the optional UsageStats app-op and doze whitelist used by Keeper, launches `.Home`, and checks the running foreground activity. It records the original device settings in ignored `.local/` state so `restore-amazon-home.ps1 -TvIp "192.168.1.50"` can restore them when a snapshot exists.

Run the automated project checks with:

```powershell
.\tests\Project.Tests.ps1
```

The suite checks source patterns and PowerShell parsing, then compiles/runs Java QR and wallpaper-upload loopback tests. It does not test the TV UI or connect to a device. It invokes JDK selection, so a missing JDK may trigger JDK provisioning and network access. No current test result is reported here. Run the suite and use its actual output when reporting status; see the README's [automated checks and manual testing](README.md#automated-checks-and-manual-testing).

## Known limitations and items to resolve

- Only one actual Fire TV has been manually tested; support across models, Fire OS releases, and Android TV implementations is not established.
- Fire OS controls physical Home-key routing. Keeper is best-effort, firmware-dependent, and only started by `Keeper.start` below Android API 26.
- TMDB search depends on the user's token, network availability, and provider terms.
- Wallpaper browser transfer is local HTTP without TLS; use a trusted network and keep its temporary URL/token private.
- A green build or `verify.ps1` does not establish that automated tests pass.
- `wallpapers/dooom.png` is artwork supplied and identified by the project author and is used as the built-in wallpaper resource `R.drawable.doom`. The project author created the custom `liquid-home/res/drawable-nodpi/tmdb_logo.png` artwork. The app's Credits section retains the notice “This product uses the TMDB API but is not endorsed or certified by TMDB.” This custom logo is not claimed to be an officially approved TMDB mark; TMDB's guidance separately requests an approved logo. See [TMDB attribution guidance](https://developer.themoviedb.org/docs/faq). Original project code is licensed under the MIT License in `LICENSE`; the Keeper adaptation retains its separate notice in `liquid-home/THIRD_PARTY_NOTICES.txt`.
