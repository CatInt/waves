package io.waves.sync

import io.waves.model.EpisodeId
import io.waves.model.PlaybackProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private class CountingService : SyncService {
        override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
        var calls = 0
        override suspend fun syncNow() { calls++ }
    }

    private fun TestScope.scheduler(service: SyncService, auth: GoogleAuth) =
        SyncScheduler(service, auth, backgroundScope, debounce = 10.seconds, period = 15.minutes)

    @Test
    fun syncsOnStartAndPeriodically() = runTest {
        val service = CountingService()
        scheduler(service, FakeAuth()).start()
        runCurrent()
        assertEquals(1, service.calls)
        advanceTimeBy(15.minutes + 1.seconds)
        assertEquals(2, service.calls)
        advanceTimeBy(30.minutes)
        assertEquals(4, service.calls)
    }

    @Test
    fun noopWhileSignedOutAndStartsOnSignIn() = runTest {
        val service = CountingService()
        val auth = FakeAuth(signedIn = false)
        val s = scheduler(service, auth)
        s.start()
        s.requestSync()
        s.onResume()
        advanceTimeBy(1.minutes)
        assertEquals(0, service.calls)

        auth.state.value = AuthState.SignedIn(null)
        runCurrent()
        assertEquals(1, service.calls)

        auth.state.value = AuthState.SignedOut
        runCurrent()
        advanceTimeBy(60.minutes)
        assertEquals(1, service.calls)
    }

    @Test
    fun requestSyncIsDebounced() = runTest {
        val service = CountingService()
        val s = scheduler(service, FakeAuth())
        s.start()
        runCurrent()
        repeat(5) {
            s.requestSync()
            advanceTimeBy(3.seconds)
        }
        assertEquals(1, service.calls, "still within the quiet period")
        advanceTimeBy(11.seconds)
        assertEquals(2, service.calls)
    }

    @Test
    fun resumeSyncsImmediately() = runTest {
        val service = CountingService()
        val s = scheduler(service, FakeAuth())
        s.start()
        runCurrent()
        s.onResume()
        runCurrent()
        assertEquals(2, service.calls)
    }

    @Test
    fun watchLibraryRequestsSyncOnLocalChange() = runTest {
        val service = CountingService()
        val library = FakeLibrary()
        val s = scheduler(service, FakeAuth())
        s.start()
        s.watchLibrary(library)
        runCurrent()
        advanceTimeBy(20.seconds)
        assertEquals(1, service.calls, "initial emission doesn't trigger")

        library.saveProgress(PlaybackProgress(EpisodeId("f", "g"), 1, 2, false, 3))
        runCurrent()
        advanceTimeBy(11.seconds)
        assertEquals(2, service.calls)
    }

    @Test
    fun continuousChangesStillSyncWithinMaxWait() = runTest {
        val service = CountingService()
        val s = SyncScheduler(service, FakeAuth(), backgroundScope, debounce = 10.seconds, period = 15.minutes, maxWait = 60.seconds)
        s.start()
        runCurrent()
        assertEquals(1, service.calls)
        // A change every 5 s (e.g. progress saves) never leaves a 10 s quiet period.
        s.requestSync()
        repeat(11) {
            advanceTimeBy(5.seconds)
            s.requestSync()
        }
        runCurrent() // t = 55 s
        assertEquals(1, service.calls, "not before maxWait")
        advanceTimeBy(5.seconds)
        runCurrent() // t = 60 s
        assertEquals(2, service.calls, "max wait forces a sync")
        // Changes keep coming: the next forced sync is maxWait after the first request following that sync.
        repeat(12) {
            s.requestSync()
            advanceTimeBy(5.seconds)
        }
        runCurrent() // t = 120 s
        assertEquals(3, service.calls)
        // A lone request syncs after the normal debounce.
        s.requestSync()
        advanceTimeBy(9.seconds)
        assertEquals(3, service.calls)
        advanceTimeBy(2.seconds)
        assertEquals(4, service.calls)
        advanceTimeBy(5.minutes)
        assertEquals(4, service.calls)
    }

    @Test
    fun disabledSyncDoesNothingAndResumesWhenEnabled() = runTest {
        val service = CountingService()
        val enabled = MutableStateFlow(false)
        val s = SyncScheduler(service, FakeAuth(), backgroundScope, debounce = 10.seconds, period = 15.minutes, enabled = enabled)
        s.start()
        s.requestSync()
        s.onResume()
        advanceTimeBy(60.minutes)
        assertEquals(0, service.calls)

        enabled.value = true
        runCurrent()
        assertEquals(1, service.calls, "syncs on enabling")
        advanceTimeBy(1.minutes)
        assertEquals(1, service.calls, "requests made while disabled were dropped")
        s.requestSync()
        advanceTimeBy(11.seconds)
        assertEquals(2, service.calls)

        enabled.value = false
        runCurrent()
        s.requestSync()
        s.onResume()
        advanceTimeBy(60.minutes)
        assertEquals(2, service.calls)
    }

    @Test
    fun stopCancelsScheduling() = runTest {
        val service = CountingService()
        val s = scheduler(service, FakeAuth())
        s.start()
        runCurrent()
        s.stop()
        advanceTimeBy(60.minutes)
        assertEquals(1, service.calls)
    }
}
