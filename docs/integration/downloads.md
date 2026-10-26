# Offline downloads — integration guide

Package `io.waves.download`. API in `commonMain/.../download/DownloadManager.kt`.

```kotlin
interface DownloadManager {
    val downloads: Flow<List<DownloadInfo>>          // all downloads, newest first
    fun state(episodeId: EpisodeId): Flow<DownloadInfo?>  // null = not downloaded
    suspend fun enqueue(episode: Episode)            // idempotent; retries Failed (resumes via Range)
    suspend fun cancel(episodeId: EpisodeId)         // stop + discard partial; keeps Done downloads
    suspend fun delete(episodeId: EpisodeId)         // remove in any state, incl. the file
    fun localUri(episodeId: EpisodeId): String?      // file:// URI when Done and the file exists
    suspend fun deleteCompleted(olderThan: Duration): Int
    suspend fun deleteDownloads(episodeIds: Collection<EpisodeId>): Int
}
suspend fun DownloadManager.deleteDownloadsOfCompletedEpisodes(library: LibraryRepository): Int
```

`DownloadInfo` = `episodeId, status (Queued | Downloading | Done | Failed), bytesDownloaded, totalBytes (null if
unknown), filePath, error`, plus `episodeTitle`, `audioUrl`, `createdAtEpochMs`, `completedAtEpochMs`, and a
derived `progress: Float?` (0..1, null = indeterminate).

## Creating it (one instance per process, sharing the library DB connection)

```kotlin
// Android (MainActivity.onCreate or an Application) — create it early: WorkManager workers that were
// rescheduled after process death wait for this instance.
val library = createLibraryRepository(context)
val downloads = createDownloadManager(context, library)      // or createDownloadManager(context, driver)

// Desktop (main.kt)
val library = createLibraryRepository()
val downloads = createDownloadManager(library)               // or createDownloadManager(driver, directory)
```

The Android factory is idempotent (returns the first instance). Files go to
`getExternalFilesDir("episodes")` (fallback `filesDir/episodes`) on Android and
`~/Library/Application Support/Waves/episodes/` on macOS (`~/.waves/episodes` elsewhere).

## Playing downloaded files

Wrap the platform player once; `play()` substitutes the `file://` URI when the episode is downloaded:

```kotlin
val player: AudioPlayer = DownloadAwareAudioPlayer(createPlatformPlayer(...), downloads)
```

`player.state.value.episode?.audioUrl` is then the `file://` URI, so compare episodes by `episode.id`, never by URL.
Progress saving etc. is unaffected (it keys on `EpisodeId`).

## Download button per episode (Compose)

```kotlin
@Composable
fun DownloadButton(episode: Episode, downloads: DownloadManager) {
    val info by remember(episode.id) { downloads.state(episode.id) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    when (info?.status) {
        null -> IconButton(onClick = { scope.launch { downloads.enqueue(episode) } }) {
            Icon(Icons.Default.Download, "Download")
        }
        DownloadStatus.Queued, DownloadStatus.Downloading -> Box(contentAlignment = Alignment.Center) {
            val p = info?.progress
            if (p == null || info?.status == DownloadStatus.Queued) CircularProgressIndicator(Modifier.size(28.dp))
            else CircularProgressIndicator(progress = { p }, modifier = Modifier.size(28.dp))
            IconButton(onClick = { scope.launch { downloads.cancel(episode.id) } }) { Icon(Icons.Default.Close, "Cancel") }
        }
        DownloadStatus.Done -> IconButton(onClick = { scope.launch { downloads.delete(episode.id) } }) {
            Icon(Icons.Default.DownloadDone, "Downloaded – tap to remove")
        }
        DownloadStatus.Failed -> IconButton(onClick = { scope.launch { downloads.enqueue(episode) } }) {
            Icon(Icons.Default.ErrorOutline, "Download failed: ${info?.error} – tap to retry")
        }
    }
}
```

A "Downloads" screen can list `downloads.downloads` (title is in `DownloadInfo.episodeTitle`; show
`bytesDownloaded / totalBytes`). Progress is persisted at most every 500 ms.

## Auto-delete

Call on app start and/or after marking an episode played, e.g.:

```kotlin
scope.launch {
    downloads.deleteCompleted(olderThan = 30.days)            // if the user enabled it
    downloads.deleteDownloadsOfCompletedEpisodes(library)     // "delete played episodes"
}
```

## Behaviour notes

- Streams to `<file>.part`, then renames atomically; at most 2 transfers run at once, the rest are `Queued`.
- Resumes with `Range: bytes=N-` (Failed retries, process restarts); a `200` reply restarts from zero, a `416`
  for an already-complete part finalizes it. Sends `Accept-Encoding: identity` so byte offsets are stable.
- Follows redirects, including https -> http (tracking prefixes). Failures (HTTP errors, dropped connections)
  become `Failed` with `error`; retrying is the user's (or UI's) call via `enqueue`.
- Restart: rows left `Queued`/`Downloading` are re-queued when the manager is created.
- Android: one unique WorkManager `DownloadWorker` per episode (constraint: network connected) running as a
  foreground `dataSync` worker with a progress notification, so downloads continue in the background and are
  rescheduled after the system stops them. On Android 13+ the UI should request `POST_NOTIFICATIONS` (already in
  the manifest) for the notification to be visible; downloads work without it.
- Desktop: downloads run in-process while the app is open.
- Storage: `download` table in `Download.sq` (same `waves.db`). Databases created before it get the table at
  runtime (`CREATE TABLE IF NOT EXISTS`), so no schema version bump/migration was needed.
