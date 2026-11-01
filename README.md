# Liquid Home

**A remote-friendly Android TV launcher project for Fire TV.** Liquid Home organizes installed apps, lets you choose a home-screen background, and includes optional catalog search and device personalization.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android API 25](https://img.shields.io/badge/Android%20API-25-green.svg)](#compatibility)

## About the project

Liquid Home is a Java launcher with Android HOME and Leanback launcher entry points. It is intended for use with a TV remote: the home screen has an app row, an All Apps view, and pages for search and settings.

The project may interest Fire TV owners who want to explore an alternative launcher, and Android developers interested in a small, direct Android SDK build.

## Important: Fire TV Limitations & Setup

Fire TV imposes system-level restrictions on third-party launchers. As a result, Liquid Home cannot provide the same seamless experience as launchers running on unrestricted Android TV devices. Liquid Home is designed to provide the best possible launcher experience within Fire TV's restrictions.

Pressing the Fire TV remote's **Home** button may open Amazon's default Home screen. Liquid Home should automatically return to the foreground after approximately 1–2 seconds. This behavior is caused by a Fire TV system limitation, not a Liquid Home bug.

For security, disable **Wireless Debugging** after you finish installing and configuring Liquid Home.

## Features

- **App organization:** browse launchable installed apps, move apps between All Apps and a six-item Main Row, and reorder the Main Row with the remote.
- **Backgrounds:** select the built-in background or custom images. The built-in `R.drawable.doom` resource is generated from the project-author-supplied `wallpapers/dooom.png`. Transfer images from a phone/browser over the local network, or copy images from a computer to the TV with ADB.
- **Personalization:** set the greeting name and select a focus-effect color in Settings.
**Custom Wallpapers via QR Code:** Upload your own background wallpapers easily by scanning a QR code.
**Dynamic Wallpapers:** Enjoy a more lively home screen with wallpapers featuring subtle motion effects.
- **Fire TV shortcuts:** open Fire TV settings or return to Amazon Home from the app. **Quick Settings Shortcuts:** Dedicated home screen icons provide direct access to Wi-Fi and Bluetooth settings.
- **Optional Home Keeper:** a best-effort service for certain older Fire OS versions. Its behavior depends on device firmware.

## Screenshots

These screenshots were captured from Liquid Home running on a Fire TV.

### Home screen

![Liquid Home home screen](docs/screenshots/home-screen.png)

### All Apps deck

![Liquid Home All Apps deck](docs/screenshots/all-apps-deck.png)

### Settings

![Liquid Home Settings screen](docs/screenshots/settings.png)

## Compatibility

| Item | Configuration or status |
|---|---|
| Android package | `tv.rajra.liquidhome` |
| Minimum SDK | API 25 |
| Target SDK | API 25 |
| Launcher intents | Android HOME and Leanback launcher |
| Intended device | Fire TV |
| Verified compatibility | One Fire TV was manually exercised; Tested on Amazon Fire TV Stick 4K (1st Generation, AFTMM) running Fire OS 6. |
| Other devices | Compatibility across other Fire TV models, Fire OS versions, and Android TV devices has not been established. |

The manifest marks the Leanback feature as optional. That declaration does not establish compatibility with every Android TV device. The installation script specifically identifies Fire TV devices.

## Installation on Fire TV

Installation is performed from Windows PowerShell using the project scripts. Review [Prerequisites](PREREQUISITES.md) before starting.

1. On Fire TV, enable **ADB Debugging** in **Settings → My Fire TV → Developer Options**. If Developer Options are hidden, open **About**, highlight the device name, and press Select seven times; menu wording varies by firmware.
2. Connect the computer and TV to the same network, wake the TV, and find its current IPv4 address.
3. From PowerShell in the project directory, check the device:

   ```powershell
   .\scripts\preflight.ps1 -TvIp "192.168.1.50"
   ```

4. Install and launch Liquid Home:

   ```powershell
   .\install.ps1 -TvIp "192.168.1.50" -DisplayName "Alex"
   ```

   Replace the example address and name with your own. The script builds the APK, installs it with `adb install -r`, and launches the app. It does not uninstall an existing installation. If prompted on the TV, approve the ADB authorization and choose **Always allow from this computer**.

The installer saves device settings used by the optional Keeper workflow. To stop Keeper, restore saved settings when a snapshot is available, and open Amazon Home, run:

```powershell
.\restore-amazon-home.ps1 -TvIp "192.168.1.50"
```

See [Troubleshooting](docs/TROUBLESHOOTING.md) for ADB, signing, installation, and recovery guidance.

## Build & Install Using AI

You can use **Claude Code, OpenAI Codex or Cursor** to build and install Liquid Home without manually handling the development setup.

1. Copy and paste this GitHub repository URL into your AI coding assistant, or clone/download the repository and open the project folder.
2. Copy and paste the below prompt.

**Suggested prompt:**

```text
Analyse this project, set up all required dependencies, build the Liquid Home APK and install it on my connected Fire TV or Android TV. Do not check Fire TV, Android API or any other compatibility.

Follow the repository instructions. Do not modify existing source code or release credentials.

Download or configure all required dependencies automatically, including JDK 17, Android SDK Command-line Tools, Android SDK Platform 25, Android Build Tools 34.0.0, ADB and any other required components.

Use the project's setup scripts wherever possible. You are authorised to download and install all required tools and dependencies without asking for confirmation for each download or installation.

Do not skip dependencies or leave the development environment partially configured. Verify that everything required for building is installed.

Do not skip, disable or exclude any existing functionality, including the optional Home Keeper service.  Before configuring Home Keeper: 1. Save the current Usage Stats access state and Doze-whitelist state so they can be restored later. 2. Grant Liquid Home the required Usage Stats access and Doze-whitelist exemption. 3. Start Liquid Home's Home Keeper service. 4. Verify that both privileges are active and Home Keeper is running. 

```

Some steps may require your approval or manual action.

## Build from source

The build uses Windows PowerShell 5.1, JDK 17, Android SDK Platform 25, and Android Build Tools 34.0.0. It uses the Android SDK tools directly; Gradle and Android Studio are not required. `setup-tools.ps1` can find or provision a JDK and the required SDK components. SDK setup may download files and asks you to review and accept Google's Android SDK terms before installing missing SDK packages.

Clone the repository and enter its directory:

```powershell
git clone https://github.com/Darklord9156/Liquid-Home.git
cd Liquid-Home
```

Set up the toolchain and build:

```powershell
.\setup-tools.ps1
.\liquid-home\build.ps1 -DisplayName "Alex"
```

The display name is optional; it sets the first-use greeting default. The build script writes the APK to `liquid-home/build/liquid-home.apk` and signs it with a developer-local key. This key is for local development; it is not the persistent key for public releases.

For a public release, create and securely back up a dedicated release keystore outside the repository. Do not commit or upload it. Pass its path and alias with `-Release`; the script prompts for the keystore and key passwords without echoing them:

```powershell
.\liquid-home\build.ps1 -Release -ReleaseKeystorePath "D:\Secure\LiquidHome-release.jks" -ReleaseAlias "liquid-home" -DisplayName "Alex"
```

**Release Signing Security**

Release builds are signed using a dedicated release keystore. The build script signs the APK, verifies its signature, and only then places it at the final output location.

Keep your release keystore and encrypted backups in secure locations outside the project. Never upload them to GitHub or share your passwords.

**Important:** Always use the same release keystore for future updates. Android will reject updates signed with a different key. Losing your original release keystore can prevent you from publishing updates to existing installations.

See [Prerequisites](PREREQUISITES.md#signing-credentials) for details.

To verify a built APK, run:

```powershell
.\verify.ps1 -ExpectedDisplayName "Alex"
```

## Project structure

| Path | Purpose |
|---|---|
| `liquid-home/src/tv/rajra/liquidhome/` | Launcher activity and Java helper classes. |
| `liquid-home/AndroidManifest.xml` | Package ID, launcher entry points, permissions, service, and boot receiver. |
| `liquid-home/res/` | App icons, theme values, and XML resources. |
| `liquid-home/build.ps1` | Direct Android SDK compilation, packaging, alignment, and signing workflow. |
| `setup-tools.ps1` | JDK selection/provisioning and local Android SDK setup. |
| `install.ps1`, `scripts/preflight.ps1` | Fire TV checks and installation. |
| `verify.ps1` | APK signature, package, resource, and input-freshness checks. |
| `restore-amazon-home.ps1` | Recovery workflow for returning to Amazon Home. |
| `sync-wallpapers.ps1`, `wallpapers/` | Computer-to-TV wallpaper sync script and source images. |
| `tests/` | PowerShell project checks and Java wallpaper/QR tests. |
| `docs/`, `PREREQUISITES.md` | Troubleshooting and toolchain documentation. |
| `LICENSE`, `liquid-home/THIRD_PARTY_NOTICES.txt` | Project license and third-party notices. |

## Automated checks

The project check script is:

```powershell
.\tests\Project.Tests.ps1
```

It runs source-pattern checks, PowerShell parser checks, and Java loopback tests. It does not test the rendered TV interface or verify compatibility across devices. No test result is claimed in this README; use the output from your own run.

## Known limitations

- Physical Home-button routing is controlled by Fire OS. Home Keeper is optional, firmware-dependent, and best-effort.
- Only one Fire TV has been manually exercised; broader device compatibility is unknown.
- Phone/browser wallpaper transfer uses local HTTP without TLS. Use a trusted network and keep the temporary upload URL private.
- Existing installations migrate the saved selection for the former built-in wallpaper to the current built-in image automatically.
- Release signing requires a developer-managed external keystore; none is included in the repository.

## Contributing

Contributions are welcome. Before opening a pull request, describe the change and how you checked it, including device and Fire OS details when relevant. Keep signing files, ADB keys, device snapshots, API tokens, and private network details out of commits and issue reports. Do not report tests as passing unless you ran them and confirmed the result.

## License

Liquid Home's original project code is licensed under the [MIT License](LICENSE). Third-party components retain their own notices and terms; see [`liquid-home/THIRD_PARTY_NOTICES.txt`](liquid-home/THIRD_PARTY_NOTICES.txt). The project license does not grant redistribution rights to third-party artwork or other material.

## More information

- [Prerequisites and toolchain](PREREQUISITES.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Change map](CHANGE_MAP.md)
