# Waves — podcast app (Android + macOS), Kotlin Multiplatform + Compose Multiplatform

- JDK: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before any `./gradlew`.
- Shared code in `composeApp/src/commonMain/kotlin/io/waves/`; platform code in `androidMain` / `desktopMain`.
- Contracts (stable, change only with good reason, keep backwards compatible):
  `model/Podcast.kt`, `search/PodcastDirectory.kt`, `feed/FeedRepository.kt`, `data/LibraryRepository.kt`,
  `sync/Sync.kt`, `player/AudioPlayer.kt`.
- Packages: `search` (Apple Podcasts directory), `feed` (RSS), `data` (SQLDelight persistence), `sync` (Google Drive),
  `player` (playback), `ui` (screens). Keep work inside your package to avoid merge conflicts.
- Verify: `./gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:compileKotlinDesktop`
- Timestamps are epoch ms (`currentTimeMillis()` in `Platform.kt`).
