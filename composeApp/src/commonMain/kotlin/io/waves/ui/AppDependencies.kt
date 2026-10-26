package io.waves.ui

import io.waves.data.ArtworkColorStore
import io.waves.data.FeedFetchLog
import io.waves.data.InMemoryArtworkColorStore
import io.waves.data.InMemoryFeedFetchLog
import io.waves.data.LibraryRepository
import io.waves.data.PlaybackSessionStore
import io.waves.data.SettingsStore
import io.waves.download.DownloadManager
import io.waves.feed.FeedRepository
import io.waves.model.EpisodeId
import io.waves.offline.PlaybackCache
import io.waves.player.AudioPlayer
import io.waves.search.PodcastDirectory
import io.waves.search.parseApplePodcastsUrl
import io.waves.youtube.YoutubeChannels
import io.waves.sync.GoogleAuth
import io.waves.sync.SyncService
import io.waves.ui.fake.InMemorySettingsStore
import io.waves.update.GitHubReleases
import io.waves.update.UpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the UI needs, supplied by the platform entry point.
 *
 * @param deviceId stable id of this installation, recorded in history entries.
 * @param parseAppleUrl extracts the Apple collection id from a podcasts.apple.com URL
 *   (defaults to [parseApplePodcastsUrl]; injectable for tests).
 * @param onLocalChange invoked after the UI changes syncable state (subscriptions, progress, history),
 *   e.g. to schedule a debounced sync.
 * @param appScope lifetime of the app-wide state holders (playback progress saving, library, ...). They
 *   outlive any single composition, so they must not run in a composition's scope.
 * @param downloads offline episode downloads; when null, all download UI is hidden.
 * @param settings per-device user preferences (store country, default speed, skip intervals, auto-delete,
 *   sync on/off); platforms pass `SqlSettingsStore(libraryRepository.driver)`. The default is not persistent.
 */
class AppDependencies(
    val directory: PodcastDirectory,
    /** Null in tests and previews: pasted YouTube links then report that they aren't available. */
    val youtube: YoutubeChannels? = null,
    val feeds: FeedRepository,
    val library: LibraryRepository,
    val player: AudioPlayer,
    val auth: GoogleAuth,
    val sync: SyncService,
    val deviceId: String,
    val parseAppleUrl: (String) -> Long? = ::parseApplePodcastsUrl,
    val onLocalChange: () -> Unit = {},
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    val downloads: DownloadManager? = null,
    val settings: SettingsStore = InMemorySettingsStore(),
    /** Null: no auto-resume after an interrupted session. */
    val playbackSession: PlaybackSessionStore? = null,
    /** Both null: no in-app updates (tests, unsupported platforms). */
    val releases: GitHubReleases? = null,
    val updateInstaller: UpdateInstaller? = null,
    /** Streamed audio kept on disk. Null hides fully-cached episodes from offline Home. */
    val playbackCache: PlaybackCache? = null,
    /** Episodes that disappeared from a non-empty feed refresh. Empty by default. */
    val onEpisodesAbsent: (Set<EpisodeId>) -> Unit = {},
    /** True while the Internet is unreachable (offline mode). Always online by default. */
    val offline: StateFlow<Boolean> = MutableStateFlow(false),
    /** Colours picked from cover art. In memory by default. */
    val artworkColors: ArtworkColorStore = InMemoryArtworkColorStore(),
    /** When show pages last fetched each feed. In memory by default. */
    val feedFetches: FeedFetchLog = InMemoryFeedFetchLog(),
)

internal fun looksLikeApplePodcastsUrl(text: String): Boolean =
    text.contains("podcasts.apple.com", ignoreCase = true) || text.contains("itunes.apple.com", ignoreCase = true)
