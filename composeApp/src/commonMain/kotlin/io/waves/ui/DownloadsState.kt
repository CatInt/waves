package io.waves.ui

import io.waves.data.LibraryRepository
import io.waves.data.SettingsStore
import io.waves.download.DownloadInfo
import io.waves.download.DownloadManager
import io.waves.download.DownloadStatus
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.offline.PlaybackCache
import io.waves.player.PlaybackState
import io.waves.ui.fake.InMemorySettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the per-episode download control shows. */
sealed interface DownloadControl {
    /** Not downloaded: download icon. */
    data object NotDownloaded : DownloadControl
    /** Queued or downloading: circular progress ([progress] null = indeterminate); tap cancels. */
    data class InProgress(val progress: Float?) : DownloadControl
    /** Downloaded: tap turns it into a delete button for a moment; long-press offers "Remove download". */
    data object Downloaded : DownloadControl
    /** Fully cached by the player, not an explicit download. Tap downloads it; long-press offers "Remove cached episode". */
    data object Cached : DownloadControl
    /** Failed: error icon; tap retries. */
    data class Failed(val error: String?) : DownloadControl
}

fun downloadControlFor(info: DownloadInfo?, fullyCached: Boolean = false): DownloadControl {
    if (info == null && fullyCached) return DownloadControl.Cached
    return when (info?.status) {
        null -> DownloadControl.NotDownloaded
        DownloadStatus.Queued -> DownloadControl.InProgress(null)
        DownloadStatus.Downloading -> DownloadControl.InProgress(info.progress)
        DownloadStatus.Done -> DownloadControl.Downloaded
        DownloadStatus.Failed -> DownloadControl.Failed(info.error)
    }
}

data class DownloadsUiState(
    val items: List<DownloadInfo> = emptyList(),
    val autoDeleteAfterFinishing: Boolean = false,
)

/**
 * App-wide download actions and state.
 *
 * Auto-delete: while enabled, the download of an episode is removed once it becomes completed (played to the
 * end / marked played), but not while it is still the episode loaded in the player (until it has ended or the
 * player moves on). Episodes already completed when the holder starts are left alone. While offline, those
 * deletions wait and run once the connection is back. Removing a download by hand always deletes it, and
 * also drops a playback-cache entry for that episode.
 *
 * With a [deviceId], each finished download is added to History at the time it finished, including downloads
 * that finished in the background while the app was closed. A never-played episode also gets progress at 0:00.
 */
class DownloadsState(
    private val downloads: DownloadManager,
    private val library: LibraryRepository,
    playback: StateFlow<PlaybackState>,
    private val scope: CoroutineScope,
    private val settings: SettingsStore = InMemorySettingsStore(),
    private val playbackCache: PlaybackCache? = null,
    /** True while offline (no Internet). */
    private val offline: StateFlow<Boolean> = MutableStateFlow(false),
    private val deviceId: String? = null,
    private val onLocalChange: () -> Unit = {},
) {
    val autoDeleteAfterFinishing: StateFlow<Boolean> = settings.settings.map { it.autoDeleteDownloads }
        .stateIn(scope, SharingStarted.Eagerly, settings.settings.value.autoDeleteDownloads)

    private val fullyCachedIds: StateFlow<Set<EpisodeId>> =
        combine(
            (playbackCache?.entries ?: flowOf(emptyList()))
                .map { list -> list.filter { it.isFullyCached }.map { it.episodeId }.toSet() },
            downloads.cachedEpisodeIds,
        ) { streamed, files -> streamed + files }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val state: StateFlow<DownloadsUiState> =
        combine(downloads.downloads, autoDeleteAfterFinishing) { items, auto -> DownloadsUiState(items, auto) }
            .stateIn(scope, SharingStarted.Eagerly, DownloadsUiState(autoDeleteAfterFinishing = autoDeleteAfterFinishing.value))

    fun control(episodeId: EpisodeId): Flow<DownloadControl> =
        combine(downloads.state(episodeId), fullyCachedIds) { info, cached -> downloadControlFor(info, episodeId in cached) }

    init {
        // Episode ids are the only stable key: the player may report a file:// audioUrl for downloaded episodes.
        val activeEpisode = playback.map { s -> s.episode?.id?.takeIf { !s.isEnded } }.distinctUntilChanged()
        val completed = library.allProgress.map { list -> list.filter { it.completed }.map { it.episodeId }.toSet() }
            .distinctUntilChanged()
        scope.launch {
            var baseline: Set<EpisodeId>? = null
            val pending = mutableSetOf<EpisodeId>()
            combine(completed, activeEpisode, autoDeleteAfterFinishing, offline) { done, active, auto, off ->
                AutoDeleteTick(done, active, auto, off)
            }.collect { (done, active, auto, off) ->
                val previous = baseline
                baseline = done
                if (previous != null && auto) pending += done - previous
                pending.retainAll(done) // marked unplayed again: keep the download
                if (!auto) pending.clear()
                if (off) return@collect // keep pending; delete once back online
                val ready = pending.filter { it != active }
                if (ready.isNotEmpty()) {
                    pending -= ready.toSet()
                    guarded { downloads.deleteDownloads(ready) }
                }
            }
        }
        if (deviceId != null) scope.launch { recordFinishedDownloads(deviceId) }
    }

    private suspend fun recordFinishedDownloads(deviceId: String) {
        val recorded = mutableSetOf<Pair<EpisodeId, Long>>()
        var loaded = false
        downloads.downloads
            .map { list -> list.mapNotNull { d -> d.completedAtEpochMs?.takeIf { d.status == DownloadStatus.Done }?.let { d to it } } }
            .collect { done ->
                guarded {
                    if (!loaded) {
                        library.history.first().filter { it.deviceId == deviceId }
                            .forEach { recorded += it.episodeId to it.playedAtEpochMs }
                        loaded = true
                    }
                    val fresh = done.filter { (d, at) -> (d.episodeId to at) !in recorded }
                    for ((d, at) in fresh) {
                        library.addHistory(
                            HistoryEntry(
                                episodeId = d.episodeId,
                                episodeTitle = d.episodeTitle.ifBlank { library.episode(d.episodeId)?.title.orEmpty() },
                                podcastTitle = library.podcast(d.episodeId.feedUrl)?.title.orEmpty(),
                                playedAtEpochMs = at,
                                deviceId = deviceId,
                            ),
                        )
                        recorded += d.episodeId to at
                        if (library.progress(d.episodeId).first() == null) {
                            val durationMs = (library.episode(d.episodeId)?.durationSeconds ?: 0L) * 1000
                            // Timestamp 0: any real progress, from this or another device, wins over this one.
                            library.saveProgress(PlaybackProgress(d.episodeId, 0, durationMs, completed = false, updatedAtEpochMs = 0))
                        }
                    }
                    if (fresh.isNotEmpty()) onLocalChange()
                }
            }
    }

    /** Download [episode], or retry it when it failed. */
    fun download(episode: Episode) = launch { downloads.enqueue(canonical(episode)) }

    fun cancel(episodeId: EpisodeId) = launch { downloads.cancel(episodeId) }

    fun remove(episodeId: EpisodeId) = launch {
        downloads.delete(episodeId)
        playbackCache?.remove(episodeId)
    }

    /** The tap action of the per-episode control for its current [control] state. */
    fun onControlClick(episode: Episode, control: DownloadControl) {
        when (control) {
            DownloadControl.NotDownloaded, DownloadControl.Cached, is DownloadControl.Failed -> download(episode)
            is DownloadControl.InProgress -> cancel(episode.id)
            DownloadControl.Downloaded -> Unit // removal goes through the menu
        }
    }

    fun setAutoDeleteAfterFinishing(enabled: Boolean) = launch {
        settings.update { it.copy(autoDeleteDownloads = enabled) }
    }

    /** Episodes from the player may carry a file:// audioUrl; prefer the stored episode with its remote URL. */
    private suspend fun canonical(episode: Episode): Episode =
        if (episode.audioUrl.startsWith("file:")) library.episode(episode.id) ?: episode else episode

    private fun launch(block: suspend () -> Unit) {
        scope.launch { guarded(block) }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Transfer failures surface through the episode's Failed download control.
        }
    }
}

private data class AutoDeleteTick(
    val done: Set<EpisodeId>,
    val active: EpisodeId?,
    val auto: Boolean,
    val offline: Boolean,
)
