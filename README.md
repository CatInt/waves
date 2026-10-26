# Waves

A podcast app for **Android** and **macOS**, built with Kotlin Multiplatform + Compose Multiplatform.
UI and logic are shared in `commonMain`; each platform only supplies its entry point and platform services.

Waves is free software under the GNU General Public License v3.0 only. See [License](#license).

The app was called Waves until 1.3.0. The code keeps that name where changing it would break existing installs
or sync: the `io.waves` package and application ID, the `waves.db` database and its `Application Support/Waves`
folder, the Drive file `waves-sync.json`, and the `WAVES_*` / `waves.version` build settings.

## Layout

```
composeApp/src/
  commonMain/   shared UI (ui/), models, search, feed, data (SQLDelight), sync, player contracts
  commonTest/   shared tests (run on JVM via desktopTest)
  androidMain/  MainActivity, manifest, Android implementations
  desktopMain/  main() window, macOS/JVM implementations
```

## Requirements

- JDK 17+ — Android Studio's bundled one works for building and running:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Android SDK (path in `local.properties`, not committed)
- For macOS packaging only: a full JDK 17+ that ships `jpackage` (Android Studio's JBR doesn't, and Compose
  rejects Homebrew's), e.g. Microsoft OpenJDK, Temurin or Corretto. Point `packaging.javaHome` at it (see below);
  it is also the runtime bundled into the app.

## Commands

| Task | Command |
|---|---|
| Run on Mac | `./gradlew :composeApp:run` |
| Package Mac .app / .dmg | `./gradlew :composeApp:createDistributable` / `:composeApp:packageDmg` |
| Package Mac release (ProGuard-shrunk) | `./gradlew :composeApp:createReleaseDistributable` / `:composeApp:packageReleaseDmg` |
| Build Android debug APK | `./gradlew :composeApp:assembleDebug` |
| Build Android release APK / AAB (R8) | `./gradlew :composeApp:assembleRelease` / `:composeApp:bundleRelease` |
| Install on device | `./gradlew :composeApp:installDebug` |
| Tests | `./gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest` |
| List JDK modules for the Mac runtime | `./gradlew :composeApp:suggestRuntimeModules` |
| Regenerate app icons | `java art/IconGen.java` (from the repo root) |

Outputs: `composeApp/build/compose/binaries/{main,main-release}/{app/Waves.app,dmg/Waves-<version>.dmg}` and
`composeApp/build/outputs/apk/release/composeApp-release.apk`. The version comes from `-Pwaves.version=1.2.3`
(shared by both platforms; macOS requires a major version > 0). Builds without it are development builds
(version `1.0.0`) that only check for updates when asked.

## Releases and in-app updates

Push a tag to publish a release:

```sh
git tag v1.2.3 && git push origin v1.2.3
```

`.github/workflows/release.yml` builds the signed APK (`waves-1.2.3.apk`) and the Apple Silicon DMG
(`Waves-1.2.3-arm64.dmg`) and attaches them to a GitHub release. Release builds check
`https://api.github.com/repos/CatInt/waves/releases/latest` a few seconds after launch and every 12 hours
(falling back to the release pages on github.com when the API is rate limited);
**Settings → Updates** shows the result and installs the new version:

- Android downloads the APK and opens the system installer (the first time, the user allows Waves to install
  apps). It only installs over an APK signed with the same release key, so a debug build must be uninstalled
  once.
- macOS downloads the DMG, quits, replaces the running app bundle and relaunches it. When the app isn't in a
  writable `.app` bundle (for example `./gradlew run`), the DMG is opened for a manual install instead. The
  DMG isn't notarized, so a first install from a browser download needs right-click → Open.

The workflow needs these repository secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 < release.jks` |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | The keystore's password, alias and key password |
| `GOOGLE_DESKTOP_CLIENT_ID`, `GOOGLE_DESKTOP_CLIENT_SECRET` | Google OAuth Desktop client for Drive sync on macOS |

## Local configuration

Put these in `local.properties` (never committed), or pass them as `-Pkey=value` or environment variables:

| Key | Env var | Purpose |
|---|---|---|
| `packaging.javaHome` | `WAVES_PACKAGING_JAVA_HOME` | JDK with `jpackage` for the macOS packaging tasks |
| `release.storeFile` | `WAVES_RELEASE_STORE_FILE` | Android release keystore (path relative to the repo root, or absolute) |
| `release.storePassword` | `WAVES_RELEASE_STORE_PASSWORD` | Keystore password |
| `release.keyAlias` | `WAVES_RELEASE_KEY_ALIAS` | Key alias |
| `release.keyPassword` | `WAVES_RELEASE_KEY_PASSWORD` | Key password |
| `google.desktop.clientId` | `WAVES_GOOGLE_DESKTOP_CLIENT_ID` | Google OAuth Desktop client for Drive sync |
| `google.desktop.clientSecret` | `WAVES_GOOGLE_DESKTOP_CLIENT_SECRET` | Its client secret |

Without `release.storeFile`, release APKs are signed with the debug key so `assembleRelease` always builds
(fine for testing, not for distribution). Drive sync on Android matches the signing key's SHA-1, so register
the release key too. See [docs/google-drive-setup.md](docs/google-drive-setup.md) for the full OAuth setup.

## Next steps

- Re-check the ProGuard release build (`composeApp/proguard-desktop.pro`) after adding libraries that use
  reflection or `META-INF/services`.
- Downloads for offline listening.
- macOS distribution: Developer ID signing + notarization (`macOS { signing {}; notarization {} }`), and an
  Intel build (JavaFX natives are picked for the build host's architecture).
- Android distribution: upload key + Play App Signing, then `bundleRelease`; publish the OAuth consent screen
  so refresh tokens stop expiring weekly.

## License

Copyright (C) 2026 Waves contributors.

Waves is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3.
There is no warranty. The full text is in [LICENSE](LICENSE).

The Android build includes [youtubedl-android](https://github.com/yausername/youtubedl-android)
(GPL-3.0), which bundles Python and yt-dlp, so the app is distributed on these terms.
Source for a release is the git tag that built it: `https://github.com/CatInt/waves`.

## Icons

`art/IconGen.java` draws the icon (five sound-wave bars on an indigo-to-pink rounded square) and writes
`art/waves-icon.svg`, the Android adaptive icon layers (`res/drawable/ic_launcher_*`, including a monochrome
layer for themed icons), the notification icon `res/drawable/ic_stat_waves.xml`, the desktop window icon
`desktopMain/resources/waves-icon.png` and `composeApp/icons/waves.icns` (via `iconutil`).
