package io.waves.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PositionTickerTest {
    @Test
    fun ticksImmediatelyThenEveryInterval() = runTest {
        var ticks = 0
        val ticker = PositionTicker(backgroundScope, intervalMs = 500) { ticks++ }
        ticker.start()
        runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(499); runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, ticks)
        advanceTimeBy(1000); runCurrent()
        assertEquals(4, ticks)
        ticker.stop()
    }

    @Test
    fun stopHaltsTicksAndStartIsIdempotent() = runTest {
        var ticks = 0
        val ticker = PositionTicker(backgroundScope, intervalMs = 500) { ticks++ }
        ticker.start()
        ticker.start() // second start must not double the rate
        runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(2, ticks)
        assertTrue(ticker.isRunning)

        ticker.setRunning(false)
        assertFalse(ticker.isRunning)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, ticks)

        ticker.setRunning(true)
        runCurrent()
        assertEquals(3, ticks)
        ticker.stop()
    }

    @Test
    fun reentrantStartFromTickOnImmediateDispatcherDoesNotDoubleTick() = runTest {
        // Players publish from onTick, and publish() calls setRunning(true): with an immediate dispatcher the
        // first tick runs inside start() itself, so the ticker must already count as running by then.
        var ticks = 0
        lateinit var ticker: PositionTicker
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        ticker = PositionTicker(scope, intervalMs = 500) { ticks++; ticker.setRunning(true) }
        ticker.start()
        assertEquals(1, ticks)
        advanceTimeBy(500); runCurrent()
        assertEquals(2, ticks)
        ticker.stop()
    }
}
