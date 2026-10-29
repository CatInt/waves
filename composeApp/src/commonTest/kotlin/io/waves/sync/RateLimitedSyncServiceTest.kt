package io.waves.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitedSyncServiceTest {
    private class CountingService : SyncService {
        override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
        var calls = 0
        override suspend fun syncNow() { calls++ }
    }

    private var now = 1_000_000L
    private val inner = CountingService()
    private val service = RateLimitedSyncService(inner, clock = { now })

    /** Calls [RateLimitedSyncService.syncNow] at each offset (ms after the start) and returns how many got through. */
    private suspend fun syncAt(vararg offsetsMs: Long): Int {
        val start = now
        val before = inner.calls
        for (offset in offsetsMs) {
            now = start + offset
            service.syncNow()
        }
        return inner.calls - before
    }

    @Test
    fun oneSyncPerTenSeconds() = runTest {
        assertEquals(1, syncAt(0, 1_000, 9_999))
        // 10 s after the first.
        assertEquals(1, syncAt(1))
    }

    @Test
    fun threeSyncsPerMinute() = runTest {
        assertEquals(3, syncAt(0, 10_000, 20_000, 30_000, 40_000, 50_000))
        // The first of those three is a minute old now.
        assertEquals(1, syncAt(60_000 - 50_000))
    }

    @Test
    fun tenSyncsPerHour() = runTest {
        // Every 20 s for an hour: the first 10 (up to 3 minutes in) get through, then none.
        assertEquals(10, syncAt(*LongArray(180) { it * 20_000L }))
        // An hour after the first.
        assertEquals(1, syncAt(20_000))
    }

    @Test
    fun skippedSyncsDontCountTowardTheLimits() = runTest {
        assertEquals(1, syncAt(0, 1_000, 2_000, 5_000, 9_000))
        assertEquals(1, syncAt(10_000 - 9_000))
    }
}
