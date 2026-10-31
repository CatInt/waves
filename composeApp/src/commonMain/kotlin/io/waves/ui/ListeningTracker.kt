package io.waves.ui

import io.waves.currentTimeMillis
import io.waves.data.LibraryRepository
import io.waves.player.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Adds up how long audio plays on this device into [LibraryRepository.addListening], by local day: the time it plays,
 * not how much of the episodes (a faster speed listens to more in the same time). Stored every [flushEvery] while
 * playing and when it stops, so a quit loses at most that much.
 */
class ListeningTracker(
    private val playback: Flow<PlaybackState>,
    private val library: LibraryRepository,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val flushEvery: Duration = 30.seconds,
) {
    fun start(): Job = scope.launch {
        playback.map { it.isPlaying && !it.isBuffering }.distinctUntilChanged().collectLatest { playing ->
            if (!playing) return@collectLatest
            var last = clock()
            try {
                while (true) {
                    delay(flushEvery)
                    last = record(last)
                }
            } finally {
                withContext(NonCancellable) { record(last) }
            }
        }
    }

    /** Stores the time since [since], on the day it is now; returns now. */
    private suspend fun record(since: Long): Long {
        val now = clock()
        val day = HistoryState.localDate(now, timeZone()).toString()
        try {
            library.addListening(deviceId, day, now - since)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The database may be closing; a few seconds of listening go uncounted.
        }
        return now
    }
}
