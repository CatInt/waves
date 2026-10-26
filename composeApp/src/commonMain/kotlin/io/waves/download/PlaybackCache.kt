package io.waves.download

import io.waves.currentTimeMillis
import io.waves.model.EpisodeId
import io.waves.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Caches whatever is being listened to: once an episode has been playing for [after] in total (pauses don't reset
 * the count), its whole file is cached through [DownloadManager.cache], so seeking and later plays don't depend on
 * the network. Cached files nobody played for [keepFor] are evicted, at start and then every [evictEvery],
 * but only while [enabled] is true (offline mode turns it off, so cached audio stays until the user removes it).
 */
class PlaybackCache(
    private val playback: Flow<PlaybackState>,
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
    private val after: Duration = 1.minutes,
    private val keepFor: Duration = 3.days,
    private val evictEvery: Duration = 6.hours,
    private val clock: () -> Long = ::currentTimeMillis,
    private val enabled: Flow<Boolean> = flowOf(true),
) {
    /** Listening time per episode in this process, in ms; [after] once caching was requested. */
    private val listened = mutableMapOf<EpisodeId, Long>()

    fun start(): Job = scope.launch {
        launch {
            enabled.collectLatest { on ->
                if (!on) return@collectLatest
                while (true) {
                    downloads.evictCache(keepFor)
                    delay(evictEvery)
                }
            }
        }
        playback
            .distinctUntilChanged { a, b -> a.episode?.id == b.episode?.id && a.isPlaying == b.isPlaying }
            .collectLatest { state ->
                val episode = state.episode?.takeIf { state.isPlaying } ?: return@collectLatest
                val id = episode.id
                val before = listened[id] ?: 0L
                val target = after.inWholeMilliseconds
                if (before >= target) return@collectLatest
                val startedAt = clock()
                try {
                    delay(target - before)
                    listened[id] = target
                    withContext(NonCancellable) { downloads.cache(episode) }
                } finally {
                    if ((listened[id] ?: 0L) < target) listened[id] = before + (clock() - startedAt)
                }
            }
    }
}
