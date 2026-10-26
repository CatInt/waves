package io.waves.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.waves.data.InMemoryFeedFetchLog
import io.waves.model.EpisodeId
import io.waves.model.Podcast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class FeedRefresherTest {
    private val day = 1.days.inWholeMilliseconds
    private val hour = 1.hours.inWholeMilliseconds
    private var now = 100 * day

    private fun refresher(
        feeds: FakeFeeds,
        library: RefreshFakeLibrary,
        store: RefreshStateStore = InMemoryRefreshStateStore(),
    ) = FeedRefresher(feeds, library, store, clock = { now })

    @Test
    fun firstRefreshOnlyAnnouncesLastSevenDays() = runTest {
        val feeds = FakeFeeds().apply {
            feeds["a"] = listOf(
                episode("a", "recent", now - 2 * day),
                episode("a", "old", now - 30 * day),
                episode("a", "undated", null),
            )
        }
        val library = RefreshFakeLibrary(listOf(podcast("a")))

        val result = refresher(feeds, library).refreshAll()

        assertEquals(listOf("recent"), result.updates.single().newEpisodes.map { it.guid })
        assertEquals("Feed a", result.updates.single().podcast.title, "announced under the feed's current title")
        assertEquals(3, library.stored.value["a"]!!.size, "whole backlog is stored")
    }

    @Test
    fun refreshUpdatesChangedPodcastMetadataKeepingMissingFields() = runTest {
        val feeds = FakeFeeds().apply {
            feeds["a"] = emptyList()
            channels["a"] = Podcast("a", "Renamed", author = null, description = "New desc", artworkUrl = "https://a/new.png")
        }
        val library = RefreshFakeLibrary(listOf(Podcast("a", "Old", author = "Me", artworkUrl = "https://a/old.png", appleId = 7)))
        val r = refresher(feeds, library)

        r.refreshAll()
        assertEquals(
            listOf(Podcast("a", "Renamed", author = "Me", description = "New desc", artworkUrl = "https://a/new.png", appleId = 7)),
            library.metadataUpdates,
        )

        // Unchanged feed: no redundant write.
        r.refreshAll()
        assertEquals(1, library.metadataUpdates.size)

        // A feed without a <title> (parser falls back to the URL) doesn't clobber the stored title.
        feeds.channels["a"] = Podcast("a", "a", description = "New desc", artworkUrl = "https://a/new.png")
        r.refreshAll()
        assertEquals(1, library.metadataUpdates.size)
    }

    @Test
    fun failedFetchDoesNotTouchMetadata() = runTest {
        val feeds = FakeFeeds() // no feed for "a": fetch fails
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        refresher(feeds, library).refreshAll()
        assertTrue(library.metadataUpdates.isEmpty())
    }

    @Test
    fun laterRefreshAnnouncesOnlyUnknownEpisodesPublishedSinceLastRefresh() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = listOf(episode("a", "e1", now - day)) }
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        val r = refresher(feeds, library)
        assertEquals(1, r.refreshAll().newEpisodeCount)

        // Nothing changed: nothing new.
        now += 4 * hour
        assertTrue(r.refreshAll().updates.isEmpty())

        // A new episode plus a back-catalogue episode that just showed up in the feed.
        val lastRefresh = now
        now += 4 * hour
        feeds.feeds["a"] = feeds.feeds["a"]!! +
            episode("a", "e2", lastRefresh + hour) +
            episode("a", "backfill", lastRefresh - 10 * day)
        val result = r.refreshAll()
        assertEquals(listOf("e2"), result.updates.single().newEpisodes.map { it.guid })

        // Known guids are never re-announced.
        now += 4 * hour
        assertTrue(r.refreshAll().updates.isEmpty())
    }

    @Test
    fun lateArrivingEpisodeWithinSlackIsStillNew() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = emptyList() }
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        val r = refresher(feeds, library)
        r.refreshAll()
        val lastRefresh = now
        now += 4 * hour
        // pubDate 1h before our last refresh, but it only appeared in the feed afterwards.
        feeds.feeds["a"] = listOf(episode("a", "late", lastRefresh - hour))
        assertEquals(1, r.refreshAll().newEpisodeCount)
    }

    @Test
    fun alreadyKnownEpisodesOnFirstRefreshAreNotNew() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = listOf(episode("a", "e1", now - day)) }
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        library.upsertEpisodes("a", feeds.feeds["a"]!!) // e.g. stored when subscribing
        assertTrue(refresher(feeds, library).refreshAll().updates.isEmpty())
    }

    @Test
    fun failingFeedDoesNotBreakOthers() = runTest {
        val feeds = FakeFeeds().apply {
            feeds["ok"] = listOf(episode("ok", "e", now - hour))
            feeds["slow"] = listOf(episode("slow", "e", now - hour))
            // "broken" is missing -> throws
            behavior = { url -> if (url == "slow") awaitCancellation() }
        }
        val library = RefreshFakeLibrary(listOf(podcast("broken"), podcast("ok"), podcast("slow")))
        val r = FeedRefresher(feeds, library, perFeedTimeout = 5.seconds, clock = { now })

        val result = r.refreshAll()

        assertEquals(3, result.total)
        assertEquals(1, result.succeeded)
        assertEquals(setOf("broken", "slow"), result.failures.map { it.feedUrl }.toSet())
        assertEquals(listOf("ok"), result.updates.map { it.podcast.feedUrl })
        assertEquals(null, library.stored.value["broken"])
    }

    @Test
    fun failedFeedKeepsItsCutoffSoNothingIsMissed() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = emptyList() }
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        val store = InMemoryRefreshStateStore()
        val r = refresher(feeds, library, store)
        r.refreshAll()
        val firstRefresh = now

        now += 1.days.inWholeMilliseconds
        feeds.behavior = { error("offline") }
        r.refreshAll()
        assertEquals(firstRefresh, store.lastRefreshedAt("a"))

        now += 1.days.inWholeMilliseconds
        feeds.behavior = {}
        feeds.feeds["a"] = listOf(episode("a", "during-outage", firstRefresh + 2 * hour))
        assertEquals(1, r.refreshAll().newEpisodeCount)
    }

    @Test
    fun successfulFetchesAreLoggedForShowPages() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = emptyList() }
        val log = InMemoryFeedFetchLog()
        val r = FeedRefresher(feeds, RefreshFakeLibrary(listOf(podcast("a"))), fetchLog = log, clock = { now })
        r.refreshAll()
        assertEquals(now, log.fetchedAt("a"))

        val fetched = now
        now += hour
        feeds.behavior = { error("offline") }
        r.refreshAll()
        assertEquals(fetched, log.fetchedAt("a"))
    }

    @Test
    fun concurrencyIsBoundedToFour() = runTest {
        var active = 0
        var maxActive = 0
        val urls = (1..10).map { "f$it" }
        val feeds = FakeFeeds().apply {
            urls.forEach { feeds[it] = emptyList() }
            behavior = {
                active++
                maxActive = maxOf(maxActive, active)
                delay(1000)
                active--
            }
        }
        val r = refresher(feeds, RefreshFakeLibrary(urls.map(::podcast)))

        val result = r.refreshAll()

        assertEquals(10, result.succeeded)
        assertEquals(4, maxActive)
    }

    @Test
    fun statusReportsProgressAndLastResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val feeds = FakeFeeds().apply {
            feeds["a"] = emptyList()
            feeds["b"] = emptyList()
            behavior = { url -> if (url == "b") gate.await() }
        }
        val r = refresher(feeds, RefreshFakeLibrary(listOf(podcast("a"), podcast("b"))))
        assertIs<RefreshStatus.Idle>(r.status.value)

        val running = async { r.refreshAll() }
        runCurrent()
        assertEquals(RefreshStatus.Refreshing(done = 1, total = 2, lastResult = null), r.status.value)

        gate.complete(Unit)
        val result = running.await()
        assertEquals(RefreshStatus.Idle(result), r.status.value)
    }

    @Test
    fun refreshSingleFeed() = runTest {
        val feeds = FakeFeeds().apply {
            feeds["a"] = listOf(episode("a", "e", now - hour))
            feeds["b"] = listOf(episode("b", "e", now - hour))
        }
        val library = RefreshFakeLibrary(listOf(podcast("a"), podcast("b")))
        val result = refresher(feeds, library).refresh("b")
        assertEquals(1, result.total)
        assertEquals(listOf("b"), result.updates.map { it.podcast.feedUrl })
        assertEquals(1, feeds.fetches)
    }

    @Test
    fun reportsEpisodesMissingFromANonEmptyFetch() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = listOf(episode("a", "keep", now - hour)) }
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        library.upsertEpisodes("a", listOf(episode("a", "keep", now - hour), episode("a", "gone", now - 2 * hour)))
        val absent = mutableListOf<Set<EpisodeId>>()
        val r = refresher(feeds, library)
        r.onEpisodesAbsent = { absent += it }
        r.refreshAll()
        assertEquals(setOf(EpisodeId("a", "gone")), absent.single())
    }

    @Test
    fun emptyFetchDoesNotReportRemovals() = runTest {
        val feeds = FakeFeeds().apply { feeds["a"] = emptyList() }
        val library = RefreshFakeLibrary(listOf(podcast("a")))
        library.upsertEpisodes("a", listOf(episode("a", "keep", now - hour)))
        val absent = mutableListOf<Set<EpisodeId>>()
        val r = refresher(feeds, library)
        r.onEpisodesAbsent = { absent += it }
        r.refreshAll()
        assertEquals(listOf(emptySet()), absent)
    }

    @Test
    fun newEpisodeRuleIsNewestFirstAndDeduplicated() {
        val eps = listOf(
            episode("a", "x", 10), episode("a", "y", 30), episode("a", "y", 30), episode("a", "z", 20), episode("a", "k", 40),
        )
        val fresh = FeedRefresher.newEpisodes(eps, knownGuids = setOf("k"), cutoffEpochMs = 10)
        assertEquals(listOf("y", "z"), fresh.map { it.guid })
    }
}
