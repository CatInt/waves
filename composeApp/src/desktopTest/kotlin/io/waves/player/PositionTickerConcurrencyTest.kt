package io.waves.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositionTickerConcurrencyTest {
    @Test
    fun concurrentStartsLaunchOneLoop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            repeat(200) {
                val ticks = AtomicInteger()
                val ticker = PositionTicker(scope, intervalMs = 60_000) { ticks.incrementAndGet() }
                val go = CountDownLatch(1)
                val threads = List(8) { thread { go.await(); ticker.start() } }
                go.countDown()
                threads.forEach { it.join() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (ticks.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1)
                Thread.sleep(5)
                assertEquals(1, ticks.get(), "exactly one ticking loop")
                ticker.stop()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentStartStopEndsConsistent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ticker = PositionTicker(scope, intervalMs = 1) {}
            val go = CountDownLatch(1)
            val threads = List(8) { i -> thread { go.await(); repeat(2_000) { if ((it + i) % 2 == 0) ticker.start() else ticker.stop() } } }
            go.countDown()
            threads.forEach { it.join() }
            ticker.stop()
            assertTrue(!ticker.isRunning)
        } finally {
            scope.cancel()
        }
    }
}
