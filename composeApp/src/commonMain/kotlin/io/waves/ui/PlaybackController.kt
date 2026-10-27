package io.waves.ui

import io.waves.currentTimeMillis
import io.waves.data.LibraryRepository
import io.waves.data.PlaybackSession
import io.waves.data.PlaybackSessionStore
import io.waves.data.UserSettings
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.player.AudioPlayer
import io.waves.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Glue between [AudioPlayer] and [LibraryRepository]:
 * - resumes episodes from saved progress,
 * - saves progress every [saveIntervalMs] while playing, and on pause / stop / episode switch,
 * - marks episodes completed when the player reports the end, within 30s of the end, or past 95%,
 * - marks episodes complete or not when the user says so ([setCompleted]),
 * - records a [HistoryEntry] whenever playback of an episode starts,
 * - calls [onLocalChange] after each of those writes,
 * - applies [settings]: the player starts at (and follows changes of) `defaultSpeed`; [skipBack] / [skipForward]
 *   use the skip intervals. Without [settings], the [UserSettings] defaults apply and the speed is left alone,
 * - with [restoreLastSession], shows the most recently saved unfinished episode, paused at its position, until
 *   something plays; [togglePlayPause] (or seeking) then loads it into the player,
 * - with [session], keeps a [PlaybackSession] while audio plays; if the app comes back within [resumeWindowMs] of
 *   playback being interrupted (quit, crash, killed), the restored episode starts playing by itself.
 */
class PlaybackController(
    private val player: AudioPlayer,
    private val library: LibraryRepository,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val onLocalChange: () -> Unit = {},
    private val clock: () -> Long = ::currentTimeMillis,
    private val saveIntervalMs: Long = 10_000,
    private val settings: StateFlow<UserSettings>? = null,
    restoreLastSession: Boolean = false,
    private val session: PlaybackSessionStore? = null,
    private val resumeWindowMs: Long = 5_000,
) {
    /** Set on shutdown, so the player stopping as it's released doesn't read as a deliberate stop. */
    @Volatile private var shuttingDown = false

    /** Paused stand-in for the last unfinished episode; only shown while the player itself is empty. */
    private val restored = MutableStateFlow<PlaybackState?>(null)

    /** The player's state, or the restored episode while nothing has been loaded into the player. */
    val state: StateFlow<PlaybackState> = MergedState(player.state, restored)

    private val _podcast = MutableStateFlow<Podcast?>(null)
    /** Podcast of the current episode (for artwork / titles). */
    val podcast: StateFlow<Podcast?> = _podcast.asStateFlow()

    /** The episode as passed to [play]; the player may report it with a file:// audioUrl (downloaded copy). */
    private var source: Episode? = null

    private val saveMutex = Mutex()
    private var lastSaved: PlaybackProgress? = null

    /** The loaded episode, marked complete by the user: saved as completed wherever it is until it plays on. */
    @Volatile private var markedComplete: EpisodeId? = null

    /** Skip intervals from [settings] (for the skip buttons' labels). */
    val skipIntervals: StateFlow<SkipIntervals> =
        settings?.map { SkipIntervals(it.skipBackSeconds, it.skipForwardSeconds) }
            ?.stateIn(scope, SharingStarted.Eagerly, SkipIntervals(settings.value.skipBackSeconds, settings.value.skipForwardSeconds))
            ?: MutableStateFlow(SkipIntervals()).asStateFlow()

    val skipBackMs: Long get() = skipIntervals.value.backSeconds * 1000L
    val skipForwardMs: Long get() = skipIntervals.value.forwardSeconds * 1000L

    init {
        if (restoreLastSession) scope.launch { restoreLast() }
        if (settings != null) {
            scope.launch {
                settings.map { it.defaultSpeed }.distinctUntilChanged().collect { setSpeed(it) }
            }
        }
        scope.launch {
            while (true) {
                delay(saveIntervalMs)
                val s = player.state.value
                if (s.isPlaying) save(s)
            }
        }
        if (session != null) {
            scope.launch {
                while (true) {
                    delay(SESSION_HEARTBEAT_MS)
                    val s = player.state.value
                    if (s.isPlaying && !shuttingDown) recordSession(s)
                }
            }
        }
        scope.launch {
            var previous = player.state.value
            player.state.collect { current ->
                if (current.isPlaying && !previous.isPlaying) recordSession(current)
                if (previous.isPlaying && !current.isPlaying && !shuttingDown) session?.write(null)
                val prevEpisode = previous.episode
                val sameEpisode = prevEpisode != null && current.episode?.id == prevEpisode.id
                // Played to the end, or stopped on its own (system media controls, errors, ...).
                if (sameEpisode && ((current.isEnded && !previous.isEnded) || (previous.isPlaying && !current.isPlaying))) {
                    save(current)
                }
                previous = current
            }
        }
    }

    /** Play [episode], resuming from its saved position unless it was completed. */
    fun play(episode: Episode, podcast: Podcast) {
        restored.value = null
        markedComplete = null
        scope.launch {
            val current = player.state.value
            if (current.episode != null && current.episode.id != episode.id) save(current)
            if (current.episode?.id == episode.id && !current.isEnded && current.error == null) {
                if (!current.isPlaying) player.resume()
                _podcast.value = podcast
                return@launch
            }
            val saved = library.progress(episode.id).first()
            val start = if (saved != null && !saved.completed) saved.positionMs else 0L
            _podcast.value = podcast
            source = episode
            player.play(episode, start, podcast.title)
            library.addHistory(
                HistoryEntry(
                    episodeId = episode.id,
                    episodeTitle = episode.title,
                    podcastTitle = podcast.title,
                    playedAtEpochMs = clock(),
                    deviceId = deviceId,
                )
            )
            onLocalChange()
        }
    }

    fun togglePlayPause() {
        if (playRestored()) return
        val s = player.state.value
        when {
            s.episode == null -> Unit
            s.isPlaying -> pause()
            // Replay the original episode (matched by id): the reported one may point at a since-deleted file.
            s.isEnded -> {
                markedComplete = null
                player.play(source?.takeIf { it.id == s.episode.id } ?: s.episode, 0, _podcast.value?.title)
            }
            else -> resume()
        }
    }

    fun pause() {
        player.pause()
        val s = player.state.value
        scope.launch { save(s.copy(isPlaying = false)) }
    }

    fun resume() {
        markedComplete = null
        player.resume()
    }

    /**
     * Marks [episode] complete, keeping its position (it starts over next time), or not complete, from the start.
     * The loaded episode, marked complete, keeps being saved so while it plays on, until it is played or resumed again.
     */
    fun setCompleted(episode: Episode, completed: Boolean) {
        scope.launch {
            saveMutex.withLock {
                val saved = library.progress(episode.id).first()
                val progress = PlaybackProgress(
                    episodeId = episode.id,
                    positionMs = if (completed) saved?.positionMs ?: 0L else 0L,
                    durationMs = saved?.durationMs?.takeIf { it > 0 } ?: ((episode.durationSeconds ?: 0L) * 1000),
                    completed = completed,
                    updatedAtEpochMs = clock(),
                )
                markedComplete = episode.id.takeIf { completed && player.state.value.episode?.id == it }
                library.saveProgress(progress)
                lastSaved = progress
            }
            onLocalChange()
        }
    }

    fun seekTo(positionMs: Long) {
        restored.value?.let { r ->
            val max = if (r.durationMs > 0) r.durationMs else Long.MAX_VALUE
            restored.value = r.copy(positionMs = positionMs.coerceIn(0, max))
            return
        }
        val s = player.state.value
        val max = if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0, max))
    }

    /** Playback speed, clamped to [AudioPlayer.MIN_SPEED]..[AudioPlayer.MAX_SPEED]. */
    fun setSpeed(speed: Float) {
        player.setSpeed(if (speed.isNaN()) 1f else speed.coerceIn(AudioPlayer.MIN_SPEED, AudioPlayer.MAX_SPEED))
    }

    fun skipBy(deltaMs: Long) = seekTo(state.value.positionMs + deltaMs)

    fun skipBack() = skipBy(-skipBackMs)

    fun skipForward() = skipBy(skipForwardMs)

    /** Pause and persist progress (e.g. app going to background / closing). */
    fun stop() = pause()

    /**
     * The app is closing: note that playback (if any) was interrupted rather than stopped, then persist the
     * position. Later player state changes no longer clear the session.
     */
    suspend fun onShutdown() {
        shuttingDown = true
        val s = player.state.value
        if (s.isPlaying) recordSession(s)
        saveNow()
    }

    private suspend fun recordSession(s: PlaybackState) {
        val store = session ?: return
        val episode = s.episode ?: return
        store.write(PlaybackSession(episode.id.feedUrl, episode.id.guid, s.positionMs, clock()))
    }

    /** Persist the current position now, if anything is loaded. */
    suspend fun saveNow() = save(player.state.value)

    private suspend fun restoreLast() {
        if (player.state.value.episode != null) return
        val interrupted = session?.read()?.also { session.write(null) }
            ?.takeIf { clock() - it.playingAtEpochMs in 0..resumeWindowMs }
        val last = interrupted
            ?.let { PlaybackProgress(it.episodeId, it.positionMs, library.progress(it.episodeId).first()?.durationMs ?: 0, false, it.playingAtEpochMs) }
            ?: lastPlayedUnfinished() ?: return
        val episode = library.episode(last.episodeId) ?: return
        val podcast = library.podcast(episode.podcastFeedUrl)
            ?: library.history.first().firstOrNull { it.episodeId == episode.id }
                ?.let { Podcast(feedUrl = episode.podcastFeedUrl, title = it.podcastTitle) }
            ?: Podcast(feedUrl = episode.podcastFeedUrl, title = "")
        // Something may have started playing meanwhile.
        if (player.state.value.episode != null) return
        _podcast.value = podcast
        source = episode
        restored.value = PlaybackState(
            episode = episode,
            positionMs = last.positionMs,
            durationMs = last.durationMs.takeIf { it > 0 } ?: (episode.durationSeconds ?: 0L) * 1000,
            speed = player.state.value.speed,
        )
        if (interrupted != null) playRestored()
    }

    /**
     * Progress of the episode played most recently, unless it was finished: then nothing is restored rather than
     * an older unfinished episode. Without history, the most recently saved unfinished progress. History entries
     * of episodes never listened to (a finished download, at 0:00) don't count as played.
     */
    private suspend fun lastPlayedUnfinished(): PlaybackProgress? {
        val progress = library.allProgress.first()
        val listened = progress.filter { it.completed || it.positionMs > 0 }.mapTo(HashSet()) { it.episodeId }
        val lastPlayed = library.history.first().filter { it.episodeId in listened }.maxByOrNull { it.playedAtEpochMs }
            ?: return progress.filter { !it.completed && it.positionMs > 0 }.maxByOrNull { it.updatedAtEpochMs }
        return progress.firstOrNull { it.episodeId == lastPlayed.episodeId }?.takeIf { !it.completed && it.positionMs > 0 }
    }

    /** Loads the restored episode into the player at its (possibly sought) position; false if there is none. */
    private fun playRestored(): Boolean {
        val r = restored.value ?: return false
        val episode = r.episode
        if (episode == null || player.state.value.episode != null) {
            restored.value = null
            return false
        }
        val podcast = _podcast.value ?: Podcast(feedUrl = episode.podcastFeedUrl, title = "")
        restored.value = null
        player.play(episode, r.positionMs, podcast.title)
        scope.launch {
            library.addHistory(
                HistoryEntry(
                    episodeId = episode.id,
                    episodeTitle = episode.title,
                    podcastTitle = podcast.title,
                    playedAtEpochMs = clock(),
                    deviceId = deviceId,
                )
            )
            onLocalChange()
        }
        return true
    }

    private suspend fun save(s: PlaybackState) {
        val episode = s.episode ?: return
        val durationMs = when {
            s.durationMs > 0 -> s.durationMs
            else -> (episode.durationSeconds ?: 0L) * 1000
        }
        val progress = PlaybackProgress(
            episodeId = episode.id,
            positionMs = s.positionMs,
            durationMs = durationMs,
            completed = s.isEnded || isCompleted(s.positionMs, durationMs) || episode.id == markedComplete,
            updatedAtEpochMs = clock(),
        )
        saveMutex.withLock {
            val last = lastSaved
            if (last != null && last.episodeId == progress.episodeId &&
                last.positionMs == progress.positionMs && last.completed == progress.completed
            ) return
            library.saveProgress(progress)
            lastSaved = progress
        }
        onLocalChange()
    }

    companion object {
        const val COMPLETION_TAIL_MS = 30_000L
        private const val SESSION_HEARTBEAT_MS = 1_000L

        fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && positionMs > 0 &&
                (positionMs >= durationMs - COMPLETION_TAIL_MS || positionMs > durationMs * 95 / 100)
    }
}

/** [player] once it has an episode, otherwise [restored] (when set); `value` is computed synchronously. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MergedState(
    private val player: StateFlow<PlaybackState>,
    private val restored: StateFlow<PlaybackState?>,
) : StateFlow<PlaybackState> {
    private fun merge(p: PlaybackState, r: PlaybackState?) = if (p.episode == null && r != null) r else p

    override val value: PlaybackState get() = merge(player.value, restored.value)
    override val replayCache: List<PlaybackState> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<PlaybackState>): Nothing {
        combine(player, restored, ::merge).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

data class SkipIntervals(
    val backSeconds: Int = UserSettings().skipBackSeconds,
    val forwardSeconds: Int = UserSettings().skipForwardSeconds,
)
