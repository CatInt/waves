package io.waves.sync

import io.waves.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decides *when* to sync. While signed in and [enabled]: once on sign-in/start/enabling, every [period], shortly
 * after local changes ([requestSync], debounced by [debounce] but never delayed more than [maxWait] after the
 * first pending request, so a steady stream of changes such as playback progress still syncs) and on app resume
 * ([onResume]). Does nothing while signed out or disabled (e.g. `UserSettings.syncEnabled` off).
 *
 * Usage: create once per app process with an app-lifetime scope, call [start]; call [requestSync] after local
 * edits (or [watchLibrary] to do so automatically) and [onResume] when the app returns to the foreground.
 */
class SyncScheduler(
    private val service: SyncService,
    private val auth: GoogleAuth,
    private val scope: CoroutineScope,
    private val debounce: Duration = 10.seconds,
    private val period: Duration = 15.minutes,
    private val enabled: Flow<Boolean> = flowOf(true),
    private val maxWait: Duration = 60.seconds,
) {
    val status: StateFlow<SyncStatus> get() = service.status

    /** Pending local-change requests; conflated, so changes made during a sync trigger one more. */
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val immediate = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var job: Job? = null

    /** Starts scheduling; idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(auth.state.map { it is AuthState.SignedIn }, enabled) { signedIn, on -> signedIn && on }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    // Requests made while inactive are covered by the sync that starts now.
                    while (requests.tryReceive().isSuccess) Unit
                    coroutineScope {
                        launch {
                            while (true) {
                                sync()
                                delay(period)
                            }
                        }
                        launch {
                            while (true) {
                                requests.receive()
                                // Wait for a quiet [debounce], but at most [maxWait] in total.
                                withTimeoutOrNull(maxWait) {
                                    while (withTimeoutOrNull(debounce) { requests.receive() } != null) Unit
                                }
                                sync()
                            }
                        }
                        launch { immediate.collect { sync() } }
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** A local change happened; sync after things have been quiet for [debounce] (at most [maxWait] from now). */
    fun requestSync() {
        requests.trySend(Unit)
    }

    /** App came back to the foreground; sync right away. */
    fun onResume() {
        immediate.tryEmit(Unit)
    }

    /**
     * Calls [requestSync] whenever subscriptions, progress or history change locally. Imports done by sync
     * itself also trigger this; that follow-up sync is cheap (no remote write when nothing changed).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchLibrary(library: LibraryRepository): Job = scope.launch {
        combine(library.subscriptions, library.allProgress, library.history) { s, p, h -> Triple(s, p, h) }
            .distinctUntilChanged()
            .drop(1)
            .collect { requestSync() }
    }

    // SyncService serializes concurrent calls, so overlapping triggers simply queue up.
    private suspend fun sync() = service.syncNow()
}
