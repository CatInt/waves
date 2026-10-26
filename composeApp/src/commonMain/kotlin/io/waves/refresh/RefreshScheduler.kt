package io.waves.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * In-process refresh loop (used on desktop; Android uses WorkManager): refreshes on [start] after
 * [initialDelay], then every [period], and on [refreshNow]. New episodes go to [notifier].
 * While [enabled] is false (offline mode) the loop waits and nothing is fetched.
 */
class RefreshScheduler(
    val refresher: FeedRefresher,
    private val scope: CoroutineScope,
    private val notifier: RefreshNotifier? = null,
    private val period: Duration = 1.hours,
    private val initialDelay: Duration = Duration.ZERO,
    enabled: Flow<Boolean> = flowOf(true),
    initiallyEnabled: Boolean = true,
) {
    val status: StateFlow<RefreshStatus> get() = refresher.status

    private val now = Channel<Unit>(Channel.CONFLATED)
    private val enabledState = MutableStateFlow(initiallyEnabled)
    private var job: Job? = null

    init {
        scope.launch { enabled.collect { enabledState.value = it } }
    }

    /** Starts the loop; idempotent. While [enabled] is false the loop waits and [refreshNow] does nothing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var first = true
            enabledState.collectLatest { on ->
                if (!on) return@collectLatest
                if (first && initialDelay > Duration.ZERO) delay(initialDelay)
                first = false
                now.tryReceive() // drop requests made before this run
                while (true) {
                    runOnce()
                    // Wait for the period to elapse or an explicit refreshNow(), whichever comes first.
                    select {
                        now.onReceive {}
                        onTimeout(period) {}
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Refresh as soon as possible (restarts the period when the loop is running). No-op while disabled. */
    fun refreshNow() {
        if (!enabledState.value) return
        if (job?.isActive == true) now.trySend(Unit) else scope.launch { runOnce() }
    }

    private suspend fun runOnce() {
        try {
            val result = refresher.refreshAll()
            if (result.updates.isNotEmpty()) notifier?.notifyNewEpisodes(result.updates)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Per-feed errors are already in the result; anything else (e.g. DB) is retried next period.
        }
    }
}
