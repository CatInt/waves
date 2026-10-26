package io.waves.search

import io.waves.model.Podcast
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CachingPodcastDirectoryTest {
    private val daily = Podcast("https://example.com/daily.xml", "Daily Example", appleId = 1)
    private val serial = Podcast("https://example.com/serial.xml", "Serial Example", appleId = 2)

    private class CountingDirectory(var chart: List<Podcast>, var fail: Boolean = false) : PodcastDirectory {
        var charts = 0
        var searches = 0
        var lookups = 0

        override suspend fun search(term: String, limit: Int): List<Podcast> = search(term, "", limit)
        override suspend fun search(term: String, country: String, limit: Int): List<Podcast> {
            searches++
            if (fail) throw PodcastDirectoryException("offline")
            return chart.filter { it.title.contains(term.trim(), ignoreCase = true) }
        }
        override suspend fun topPodcasts(country: String, limit: Int): List<Podcast> {
            charts++
            if (fail) throw PodcastDirectoryException("offline")
            return chart
        }
        override suspend fun lookup(appleId: Long): Podcast? {
            lookups++
            return chart.firstOrNull { it.appleId == appleId }
        }
    }

    private class MapStore : DirectoryCacheStore {
        val map = mutableMapOf<String, String>()
        override suspend fun read(key: String) = map[key]
        override suspend fun write(key: String, value: String) { map[key] = value }
    }

    private val hour = 60 * 60 * 1000L

    @Test
    fun chartIsServedFromMemoryThenFromTheStoreAfterARestart() = runTest {
        val apple = CountingDirectory(listOf(daily, serial))
        val store = MapStore()
        var now = 0L
        val first = CachingPodcastDirectory(apple, store, now = { now })
        assertEquals(listOf(daily, serial), first.topPodcasts("us"))
        assertEquals(listOf(daily, serial), first.topPodcasts("US"))
        assertEquals(1, apple.charts)

        now = hour
        val restarted = CachingPodcastDirectory(apple, store, now = { now })
        assertEquals(listOf(daily, serial), restarted.topPodcasts("us"))
        assertEquals(1, apple.charts)
        assertEquals(daily, restarted.lookup(1))
        assertEquals(0, apple.lookups)
    }

    @Test
    fun staleChartIsRefetchedAndServedWhenTheRefreshFails() = runTest {
        val apple = CountingDirectory(listOf(daily))
        var now = 0L
        val directory = CachingPodcastDirectory(apple, MapStore(), now = { now })
        directory.topPodcasts("us")

        now = 7 * hour
        apple.chart = listOf(serial)
        assertEquals(listOf(serial), directory.topPodcasts("us"))
        assertEquals(2, apple.charts)

        now = 14 * hour
        apple.fail = true
        assertEquals(listOf(serial), directory.topPodcasts("us"))
        assertFailsWith<PodcastDirectoryException> { directory.topPodcasts("gb") }
    }

    @Test
    fun searchesAreCachedPerTermUntilTheyExpire() = runTest {
        val apple = CountingDirectory(listOf(daily, serial))
        var now = 0L
        val directory = CachingPodcastDirectory(apple, now = { now })
        assertEquals(listOf(daily), directory.search("daily", "us"))
        assertEquals(listOf(daily), directory.search(" Daily ", "US"))
        assertEquals(1, apple.searches)
        directory.search("daily", "gb")
        assertEquals(2, apple.searches)
        directory.search("serial", "us")
        assertEquals(serial, directory.lookup(2))
        assertEquals(0, apple.lookups)

        now = 2 * hour
        directory.search("daily", "us")
        assertEquals(4, apple.searches)
    }

    @Test
    fun failedSearchesAreNotCached() = runTest {
        val apple = CountingDirectory(listOf(daily), fail = true)
        val directory = CachingPodcastDirectory(apple)
        assertFailsWith<PodcastDirectoryException> { directory.search("daily", "us") }
        apple.fail = false
        assertEquals(listOf(daily), directory.search("daily", "us"))
        assertEquals(2, apple.searches)
    }
}
