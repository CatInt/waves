package io.waves.refresh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RefreshSchedulerTest {
    @Test
    fun refreshesOnStartThenHourlyAndOnDemandAndNotifies() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = emptyList() }
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        var now = 1_000_000_000L
        val notified = mutableListOf<List<PodcastUpdate>>()
        val scheduler = RefreshScheduler(
            FeedRefresher(feeds, library, clock = { now }),
            backgroundScope,
            notifier = { notified += it },
            period = 1.hours,
        )

        scheduler.start()
        runCurrent()
        assertEquals(1, feeds.fetches)
        assertEquals(0, notified.size, "no notification when nothing is new")

        advanceTimeBy(59.minutes)
        runCurrent()
        assertEquals(1, feeds.fetches)
        advanceTimeBy(2.minutes)
        runCurrent()
        assertEquals(2, feeds.fetches)

        now += 1.hours.inWholeMilliseconds
        feeds.feeds["a"] = listOf(episode("a", "new", now - 1))
        scheduler.refreshNow()
        runCurrent()
        assertEquals(3, feeds.fetches)
        assertEquals(listOf("new"), notified.single().single().newEpisodes.map { it.guid })

        scheduler.stop()
        advanceTimeBy(5.hours)
        assertEquals(3, feeds.fetches)
    }

    @Test
    fun staysQuietWhileDisabled() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = emptyList() }
        val enabled = MutableStateFlow(false)
        val scheduler = RefreshScheduler(
            FeedRefresher(feeds, RefreshFakeLibrary(listOf(podcast("a")))),
            backgroundScope,
            period = 1.hours,
            enabled = enabled,
            initiallyEnabled = false,
        )
        scheduler.start()
        runCurrent()
        scheduler.refreshNow()
        runCurrent()
        assertEquals(0, feeds.fetches)

        enabled.value = true
        runCurrent()
        assertEquals(1, feeds.fetches)

        enabled.value = false
        advanceTimeBy(3.hours)
        runCurrent()
        assertEquals(1, feeds.fetches)
    }
}
