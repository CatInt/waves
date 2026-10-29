package io.waves

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.CacheStorage
import io.waves.data.SettingsStore
import io.waves.data.SqlArtworkColorStore
import io.waves.data.SqlFeedFetchLog
import io.waves.data.SqlLibraryRepository
import io.waves.data.SqlPlaybackSessionStore
import io.waves.data.SqlDirectoryCacheStore
import io.waves.data.SqlSettingsStore
import io.waves.download.DownloadManager
import io.waves.download.PlaybackCache as ListeningCache
import io.waves.model.EpisodeId
import io.waves.offline.LocalMediaCleaner
import io.waves.offline.PlaybackCache
import io.waves.offline.SqlPlaybackCache
import io.waves.youtube.YoutubeChannels
import io.waves.youtube.YoutubeFeedRepository
import io.waves.player.AudioPlayer
import io.waves.search.ApplePodcastDirectory
import io.waves.search.CachingPodcastDirectory
import io.waves.sync.DefaultSyncService
import io.waves.sync.DriveCloudStore
import io.waves.sync.GoogleAuth
import io.waves.sync.RateLimitedSyncService
import io.waves.sync.SyncScheduler
import io.waves.ui.AppDependencies
import io.waves.update.GitHubReleases
import io.waves.update.UpdateInstaller
import io.waves.ui.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Shared Ktor setup for the one per-process [HttpClient]; the platform supplies the engine and [httpCache]
 * (on-disk, so feeds are revalidated with ETag / If-Modified-Since by both the UI and background refresh).
 * Only publicly cacheable responses are stored; private ones (Google Drive / OAuth) are never cached.
 */
fun HttpClientConfig<*>.wavesDefaults(httpCache: CacheStorage? = null) {
    followRedirects = true
    expectSuccess = false // callers inspect status codes themselves
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 120_000 // large feeds on slow networks
    }
    if (httpCache != null) {
        install(HttpCache) {
            publicStorage(httpCache)
            privateStorage(CacheStorage.Disabled)
        }
    }
}

/** What the UI needs once the graph is ready: the dependencies and the process-wide state holders. */
class AppSession(val deps: AppDependencies, val state: AppState)

/**
 * Composition root shared by Android and desktop: built once per process from the platform pieces.
 *
 * Local changes reach the sync scheduler only through [AppDependencies.onLocalChange] (the UI calls it after
 * every write to subscriptions, progress and history). [SyncScheduler.watchLibrary] is deliberately not used:
 * it would fire for the same writes a second time and additionally after every import done by sync itself.
 */
class AppGraph(
    val http: HttpClient,
    val library: SqlLibraryRepository,
    /** Offline downloads, sharing [library]'s database connection. */
    val downloads: DownloadManager,
    /** The platform player, already wrapped in a DownloadAwareAudioPlayer. */
    val player: AudioPlayer,
    val auth: GoogleAuth,
    val appScope: CoroutineScope,
    /** Null where the app can't update itself. */
    private val updateInstaller: UpdateInstaller? = null,
    private val closeResources: () -> Unit = {},
    /** Per-device preferences, stored in [library]'s database (read once, synchronously, on construction). */
    val settings: SettingsStore = SqlSettingsStore(library.driver),
    /** Streamed audio. Android passes one that also deletes ExoPlayer cache bytes. */
    val playbackCache: PlaybackCache = SqlPlaybackCache(library.driver),
    /** Whether the Internet is reachable, from the platform's detector (`androidOnline` / `desktopOnline`). */
    online: StateFlow<Boolean> = MutableStateFlow(true),
) {
    val directory = CachingPodcastDirectory(ApplePodcastDirectory(http), SqlDirectoryCacheStore(library.driver))
    val youtube = YoutubeChannels(http)
    val feeds = YoutubeFeedRepository(http)
    val sync = RateLimitedSyncService(DefaultSyncService(library, DriveCloudStore(http, auth)))
    /**
     * Offline mode, entered and left with the connection: Home lists only downloaded and fully cached episodes,
     * feed refresh and sync wait, and local audio is not cleaned up automatically.
     */
    val offline: StateFlow<Boolean> = online.map { !it }.stateIn(appScope, SharingStarted.Eagerly, !online.value)
    private val activeEpisode = player.state
        .map { playback -> playback.episode?.id?.takeUnless { playback.isEnded } }
        .stateIn(
            appScope, SharingStarted.Eagerly,
            player.state.value.episode?.id?.takeUnless { player.state.value.isEnded },
        )
    /** Drops cached audio and downloads of episodes that left a feed, except while offline. */
    val cleaner = LocalMediaCleaner(
        downloads = downloads,
        cache = playbackCache,
        offline = offline,
        activeEpisode = activeEpisode,
        scope = appScope,
    )
    val scheduler = SyncScheduler(
        sync, auth, appScope,
        enabled = combine(settings.settings, offline) { s, off -> s.syncEnabled && !off },
    )

    /** Feed refresh reports episodes a non-empty fetch no longer lists. */
    fun noteEpisodesAbsent(ids: Set<EpisodeId>) = cleaner.noteRemoved(ids)

    private val _session = MutableStateFlow<AppSession?>(null)
    /** Null until the device id has been read from the database (a few ms after [start]). */
    val session: StateFlow<AppSession?> = _session.asStateFlow()

    private var started = false

    /** Call once, on the main thread. */
    fun start() {
        if (started) return
        started = true
        cleaner.start()
        scheduler.start()
        // Whole-file cache after a minute of listening. Its eviction waits while offline.
        ListeningCache(player.state, downloads, appScope, enabled = offline.map { !it }).start()
        appScope.launch {
            val deps = AppDependencies(
                directory = directory,
                youtube = youtube,
                feeds = feeds,
                library = library,
                player = player,
                auth = auth,
                sync = sync,
                deviceId = library.deviceId(),
                onLocalChange = scheduler::requestSync,
                appScope = appScope,
                downloads = downloads,
                settings = settings,
                playbackSession = SqlPlaybackSessionStore(library.driver),
                artworkColors = SqlArtworkColorStore(library.driver),
                feedFetches = SqlFeedFetchLog(library.driver),
                releases = updateInstaller?.let { GitHubReleases(http) },
                updateInstaller = updateInstaller,
                playbackCache = playbackCache,
                onEpisodesAbsent = ::noteEpisodesAbsent,
                offline = offline,
            )
            _session.value = AppSession(deps, AppState(deps, appScope))
        }
    }

    /** App returned to the foreground. */
    fun onForeground() = scheduler.onResume()

    /** App went to the background: Home re-sorts now, while nobody is looking, rather than on return. */
    fun onBackground() {
        _session.value?.state?.home?.releaseOrder()
    }

    /** App went to the background (or is closing): persist the playback position. */
    suspend fun saveProgress() {
        _session.value?.state?.playback?.saveNow()
    }

    /** Desktop exit: save, stop background work, release the player and close resources. */
    suspend fun shutdown() {
        runCatching { _session.value?.state?.playback?.onShutdown() }
        scheduler.stop()
        runCatching { player.release() }
        appScope.cancel()
        runCatching { http.close() }
        runCatching { closeResources() }
    }
}
