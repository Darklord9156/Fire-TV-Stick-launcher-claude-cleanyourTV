# Troubleshooting and recovery

## ADB does not find the TV

Check that the computer and Fire TV are on the same network, the address is current, the TV is awake, and ADB Debugging is enabled. Run `scripts/preflight.ps1 -TvIp <IPv4>` to see the device state. Do not use an address copied from an older session.

## ADB says `unauthorized`

Accept **Allow USB debugging?** on the Fire TV and select **Always allow from this computer**. Retry the preflight. Do not delete or rotate the Windows ADB private key unless you intend to approve a new computer key on the TV. If the prompt was dismissed, revoke USB debugging authorizations in Fire TV developer settings and reconnect; the TV will ask again.

## ADB says `offline`

Wake the TV, confirm Wi-Fi, and retry. If needed, disconnect/reconnect the target from the same project with `adb disconnect <IPv4>:5555` and `adb connect <IPv4>:5555`. The project scripts use the SDK's ADB client and normal Windows ADB host key.

## Signing conflict during update

The app was signed with a different key. Stop; the installer intentionally does not uninstall it. Restore the original `liquid-home/liquid-home.keystore` and `.local/signing-password.txt` from a secure backup, then rebuild. Uninstalling the installed app would erase its preferences and app data.

## Build or APK verification fails

Run `setup-tools.ps1` to select or provision JDK 17 and set up missing SDK components. It checks `LIQUID_HOME_JDK_HOME`, `JAVA_HOME`, `PATH`, and common install folders; if none contains a compatible JDK 17, it downloads and checksum-verifies the pinned Eclipse Temurin build into `%LOCALAPPDATA%\LiquidHome\toolchains\`, outside the repo. Run `scripts/preflight.ps1` to check local build inputs and the Fire TV, then `tests/Project.Tests.ps1` for project/syntax checks, build, and run `verify.ps1`. Existing SDK components and a valid managed JDK are reused. Never copy a password or token into a command recorded in shared logs.

## App installs but does not open

Confirm that the installer reports `Status: ok`, a Package Manager APK path, and a running activity. Check the Fire TV display for a dialog or app error and retry from `install.ps1`. `restore-amazon-home.ps1 -TvIp <IPv4>` can return to Amazon Home without removing Liquid Home or its data.

## Restore Amazon Home

From the project root in PowerShell, run `.\restore-amazon-home.ps1 -TvIp "192.168.1.50"` with the Fire TV's current IPv4 address. It stops Liquid Home's optional Keeper, restores saved UsageStats/doze values if present, and starts Amazon Home. This does not uninstall Liquid Home. The physical remote Home key is controlled by Fire OS, and the Keeper may not work on every firmware.

## Device state snapshot

The installer saves the original UsageStats app-op and doze-whitelist state once per IP in ignored `.local/device-state-*.json`. Keep this local file until you have restored the device or no longer need the snapshot. It contains a private network address and must not be committed.

## Phone/PC wallpaper upload

The Fire TV and upload device must be on the same Wi-Fi network. Keep the transfer dialog open while uploading, use the exact QR URL, and close it when done. The URL contains a temporary bearer token; anyone who can read it on the local network can upload until the session expires. The server stops when you close the dialog, after 15 minutes without requests, or after two hours. Start another session if it expires.

Only decodable `.jpg`, `.jpeg`, `.png`, and `.webp` files with matching image signatures are accepted, up to 64 MB. If the browser cannot reach the Fire TV, check client isolation or Wi-Fi guest-network settings. The upload uses local HTTP without TLS, so use a trusted network. Uploaded images are saved but not applied; close the session to refresh the wallpaper collection, select a card to apply it, or long-press a photo card to delete it.

For PC ADB sync, place images in the root `wallpapers/` folder and run `sync-wallpapers.ps1 -TvIp <IPv4>`. The script does not delete files on the TV or change the current selection.
