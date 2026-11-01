# Change map

| Change | File |
|---|---|
| Home UI, favourites, All Apps filters/focus/blur, greeting display | `liquid-home/src/tv/rajra/liquidhome/Home.java` |
| Greeting name (saved locally) | Liquid Home → Settings → Greeting name |
| Search endpoints, parsing, cache, images, India provider lookup | `liquid-home/src/tv/rajra/liquidhome/TmdbClient.java` |
| Encrypted TMDB token | `liquid-home/src/tv/rajra/liquidhome/TmdbKeyStore.java` |
| Permissions, app ID, HOME intent, boot receiver | `liquid-home/AndroidManifest.xml` |
| Build and APK signing | `liquid-home/build.ps1` |
| Local upload server and QR encoding | `liquid-home/src/tv/rajra/liquidhome/WallpaperTransferServer.java`, `QrMatrix.java` |
| JDK 17 selection/provisioning and official SDK bootstrap/checksum | `setup-tools.ps1` |
| ADB preflight and authorized Fire TV identification | `scripts/preflight.ps1` |
| Wi-Fi install, launch verification, Amazon Home restore, wallpaper sync | `install.ps1`, `verify.ps1`, `restore-amazon-home.ps1`, `sync-wallpapers.ps1` |
| Project-author-supplied built-in wallpaper and PC wallpaper sync source | `wallpapers/dooom.png`, `wallpapers/` (built as `R.drawable.doom`) |
| Offline project, QR, upload server, and PowerShell syntax tests | `tests/Project.Tests.ps1`, `tests/WallpaperFeatureTests.java` |
| Build/install and recovery instructions | `README.md`, `PREREQUISITES.md`, `docs/TROUBLESHOOTING.md` |
| Selectable backgrounds | `wallpapers/`, app private wallpaper storage, and `liquid-home/res/drawable-nodpi/` |
| Third-party notices | `liquid-home/THIRD_PARTY_NOTICES.txt` (Keeper adaptation) |
| Asset status | Wallpaper and custom TMDB logo are identified by the project author in `liquid-home/THIRD_PARTY_NOTICES.txt`; the custom TMDB artwork is not described as officially approved. |
