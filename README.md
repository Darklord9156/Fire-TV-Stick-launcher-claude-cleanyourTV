# Liquid Home for Fire TV

Liquid Home is a Java home-screen launcher for Fire TV. It brings installed apps, a configurable favorites row, wallpaper controls, and optional movie and TV catalog search into one remote-friendly interface. It declares Android HOME and Leanback launcher entry points; Fire OS still controls how the physical Home button is routed.

This project uses a direct Android SDK build and does not use Gradle.

## Compatibility and current validation

- The manifest and APK currently use package `tv.rajra.liquidhome`, version `1.0`, minimum SDK/API 25, and target SDK/API 25.
- The current APK was built, passed `verify.ps1`, installed as an update, and manually exercised on one Fire TV. This confirms that device only; other models and Fire OS releases have not been established as compatible.
- Automated test results are not stated here because they have not been freshly verified for this checkout. See **Automated checks and manual testing** for the command to run the suite and what it covers.

## Features

- A home screen with a six-app Main Row and an All Apps grid, plus Apps, Search, Settings, and Background pages.
- Streaming-app discovery and a Main Row that can be reordered with the remote. Apps can be moved between All Apps and the Main Row; installed-app changes refresh the app lists.
- Optional TMDB search for movies, television, and people. It requires a user-provided TMDB API Read Access Token and an internet connection. The token is encrypted using Android Keystore on the device. TMDB search is optional and is not needed to use the launcher.
- A built-in wallpaper and selection of custom backgrounds. Custom images can be sent from a phone/browser over the local network or copied from the computer over ADB.
- A greeting name and selectable focus-effect colors, saved on the device.
- A best-effort Home Keeper on supported older Fire OS/Android versions. Fire OS controls Home-key routing; Keeper behavior is firmware-dependent.
- Shortcuts to Fire TV settings and Amazon Home.

## Screenshots

No user-interface screenshots or recordings are currently included. The bundled artwork is not a screenshot of the application.

## Project structure

| Path | Purpose |
|---|---|
| `liquid-home/src/tv/rajra/liquidhome/` | Java activity and helper classes for the launcher, TMDB, local wallpaper upload, QR generation, and Keeper. |
| `liquid-home/res/` | Android resources, including app icons and theme resources. |
| `liquid-home/AndroidManifest.xml` | Package ID, launcher entry points, permissions, service, and boot receiver. |
| `liquid-home/build.ps1` | Direct `aapt2`, `javac`, `d8`, `jar`, `zipalign`, and `apksigner` build. |
| `setup-tools.ps1` | JDK 17 selection/provisioning and local Android SDK component setup. |
| `scripts/preflight.ps1` | Read-only build prerequisite and Fire TV ADB checks. |
| `install.ps1` | Builds, installs in place, launches, and checks the foreground activity. |
| `verify.ps1` | APK signature, package, resource, and freshness checks. |
| `tests/Project.Tests.ps1` | PowerShell/source-pattern checks and Java wallpaper/QR loopback tests. |
| `wallpapers/` | Built-in wallpaper source and images for computer-to-TV sync. |
| `sync-wallpapers.ps1` | Sends supported images from `wallpapers/` to the connected Fire TV. |
| `restore-amazon-home.ps1` | Stops Keeper, restores saved device settings when available, and opens Amazon Home. |
| `docs/TROUBLESHOOTING.md` | ADB, signing, install, recovery, and wallpaper-transfer troubleshooting. |
| `PREREQUISITES.md` | Detailed host, SDK, JDK, signing, and network requirements. |
| `CHANGE_MAP.md` | Developer reference to the files associated with project areas; it is not a release changelog. |
| `LICENSE` | MIT license for Liquid Home's original project code; third-party material remains under its own notices and terms. |
| `liquid-home/THIRD_PARTY_NOTICES.txt` | MIT notice for the UsageStats-based Keeper adaptation. |

## Build and verify

Use Windows PowerShell from the project root. See [PREREQUISITES.md](PREREQUISITES.md) for the exact toolchain and setup behavior.

```powershell
.\setup-tools.ps1
.\liquid-home\build.ps1 -DisplayName "Alex"
.\verify.ps1 -ExpectedDisplayName "Alex"
```

The optional display name is written into the APK as the first-use greeting default. An existing saved greeting on a device takes precedence; change it in **Settings → Greeting name**. The build writes `liquid-home/build/liquid-home.apk`.

The first build creates a local signing keystore and password file if they are absent. Keep them private and together. A later update must be signed by the same certificate as the installed app. Do not share a personal signing key or expect a fresh key from another developer to update your installation.

## Install and first use

1. On Fire TV, enable **ADB Debugging** under **Settings → My Fire TV → Developer Options**. If Developer Options are hidden, open **About**, highlight the device name, and press Select seven times. Menu wording can vary by firmware.
2. Put the computer and Fire TV on the same network, wake the TV, and note its current IPv4 address.
3. Check the target before installing:

   ```powershell
   .\scripts\preflight.ps1 -TvIp "192.168.1.50"
   ```

4. Build, install/update, and launch:

   ```powershell
   .\install.ps1 -TvIp "192.168.1.50" -DisplayName "Alex"
   ```

   `install.ps1` builds the APK itself, uses `adb install -r`, and checks that the package and foreground activity are present. It does not uninstall the existing package. If the signing certificate is incompatible, installation stops; do not uninstall to work around that error unless you intend to erase app data.

5. On the first ADB connection, accept the Fire TV authorization prompt and choose **Always allow from this computer**. The scripts use the normal Windows ADB host key; keep that private too.

The installer snapshots the app's existing UsageStats app-op and doze-whitelist state in ignored `.local/` state on first install, then grants the UsageStats access and doze whitelist used by the optional Keeper workflow. To stop Keeper, restore saved values where available, and open Amazon Home without uninstalling Liquid Home, use:

```powershell
.\restore-amazon-home.ps1 -TvIp "192.168.1.50"
```

Physical Home-key behavior remains controlled by Fire OS. See [troubleshooting](docs/TROUBLESHOOTING.md) for authorization, offline-device, signing, and recovery issues.

## Use and customize

- **Main Row:** use the remote Menu button on a tile to move/reorder or remove an app. In All Apps, use the Menu action to move an app into the Main Row. The row is capped at six entries; removing one allows an eligible discovered app to fill the open slot.
- **Greeting:** set a default at build time with `-DisplayName`, or update the saved value under **Settings → Greeting name**.
- **Focus effect:** choose a palette under **Settings → Focus Effect Colour**. The choice is saved on the Fire TV.
- **Backgrounds:** open **Settings → Background** and select the default or a custom image. Selecting an uploaded/copied photo applies it; long-press a custom photo or use the delete action to remove it.
- **Phone/browser wallpaper transfer:** choose **Transfer from Phone/PC**, scan the QR code or open its URL on a device on the same Wi-Fi, and upload a JPG, JPEG, PNG, or WebP image. Keep the session URL private: it contains a temporary upload token, and this local-network HTTP transfer has no TLS. The server accepts images up to 64 MB and stops when the session closes, after 15 minutes idle, or after two hours. Uploaded files are saved for selection and are not applied automatically.
- **Computer wallpaper sync:** add supported images to `wallpapers/` and run `sync-wallpapers.ps1 -TvIp "192.168.1.50"`. The script copies files over ADB; it does not change the selected background.
- **TMDB search:** open Search and follow **Set up TMDB**. Supply your own API Read Access Token or import a text file containing only that token on one line. The app encrypts it on-device. TMDB access and images require internet; review the provider's current terms before using the service.

## Automated checks and manual testing

Run the project checks with:

```powershell
.\tests\Project.Tests.ps1
```

This suite includes source-pattern assertions, PowerShell parser checks, and Java loopback tests for wallpaper upload/QR behavior. It does not connect to a Fire TV or exercise the rendered interface. No current result is reported here; run the command above and use its actual output when describing test status.

Build success, `verify.ps1` success, automated test results, and manual device testing are separate checks. The current APK was reported built and verified, then installed and manually tested on a Fire TV. That observation applies to the tested device and is not evidence of compatibility across every Fire TV model or Fire OS release.

## Licensing and third-party material

The original Liquid Home project code is released under the MIT License in `LICENSE`. `liquid-home/THIRD_PARTY_NOTICES.txt` preserves the separate MIT notice for the UsageStats-based Keeper adaptation from DumbTV contributors. The project license does not replace third-party notices or grant rights to third-party material. Redistribution permission for bundled wallpaper and logo artwork is not established by the project documentation; resolve those permissions before redistributing those assets. TMDB data and service use are subject to TMDB's terms, and the app states that it is not endorsed or certified by TMDB.

## Contributing

Before proposing a change, describe the user-visible behavior or build issue it addresses, the device/Fire OS version used to check it, and the commands or manual steps performed. Keep signing files, ADB host keys, device snapshots, TMDB credentials, and private-network details out of issues and pull requests. Review the license and bundled-asset permissions before redistributing a fork. No project-specific issue tracker or contribution policy is established in this handoff; use the hosting repository's process once one is selected.

## Further reading

- [Prerequisites and toolchain](PREREQUISITES.md)
- [Troubleshooting and recovery](docs/TROUBLESHOOTING.md)
- [Change map](CHANGE_MAP.md)
- [MIT License](LICENSE)
- [Third-party notices](liquid-home/THIRD_PARTY_NOTICES.txt)
