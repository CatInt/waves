package io.waves.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.jvm.Synchronized

/**
 * Calls [onTick] every [intervalMs] while running. Players start it when playback starts and stop it
 * when playback pauses/ends, so position updates cost nothing while idle.
 *
 * Thread-safe: [start]/[stop] may be called from any thread (and re-entrantly from [onTick]); at most one
 * ticking loop runs at a time.
 */
class PositionTicker(
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val onTick: () -> Unit,
) {
    // Guarded by this object's monitor.
    private var job: Job? = null

    val isRunning: Boolean
        @Synchronized get() = job.isLive()

    /** Starts ticking (immediately, then every [intervalMs]); no-op if already running. */
    fun start() {
        // Started outside the lock: with an immediate dispatcher the first tick runs inside start(), and
        // onTick must not run while holding the monitor (it may block on a thread that calls stop()).
        claimNewJob()?.start()
    }

    /**
     * Publishes a new, not-yet-started job if none is live. Publishing before the first tick matters: an
     * onTick that calls start() again (immediate dispatcher) must see the ticker as running.
     */
    @Synchronized
    private fun claimNewJob(): Job? {
        if (job.isLive()) return null
        return scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                onTick()
                delay(intervalMs)
            }
        }.also { job = it }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Convenience: run exactly while [shouldRun] is true. */
    fun setRunning(shouldRun: Boolean) = if (shouldRun) start() else stop()

    /** A lazily-created job counts as live until it completes or is cancelled (e.g. with its scope). */
    private fun Job?.isLive(): Boolean = this != null && !isCompleted && !isCancelled

    companion object {
        const val DEFAULT_INTERVAL_MS = 500L
    }
}
