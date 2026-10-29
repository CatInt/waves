package io.waves.offline

import io.waves.download.DownloadInfo
import io.waves.download.DownloadStatus
import io.waves.feed.Feed
import io.waves.model.EpisodeId
import io.waves.ui.HomeState
import io.waves.ui.MapFeedRepository
import io.waves.ui.episode
import io.waves.ui.fake.InMemoryDownloadManager
import io.waves.ui.fake.InMemoryLibraryRepository
import io.waves.ui.podcastA
import io.waves.ui.podcastB
import io.waves.ui.settle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineModeTest {
    private val epA = episode(podcastA, 1)
    private val epB = episode(podcastA, 2)
    private val epC = episode(podcastB, 1)

    private fun done(id: EpisodeId) = DownloadInfo(id, DownloadStatus.Done, 100, 100, "/f", null)
    private fun full(id: EpisodeId, at: Long = 1) = PlaybackCacheEntry(id, "https://a/${id.guid}", 100, 100, at)
    private fun partial(id: EpisodeId, at: Long) = PlaybackCacheEntry(id, "https://a/${id.guid}", 40, 100, at)

    @Test
    fun playableIdsAreCompletedDownloadsAndFullyCachedEpisodes() {
        val ids = offlinePlayableIds(
            downloads = listOf(done(epA.id), done(epB.id).copy(status = DownloadStatus.Downloading)),
            cache = listOf(full(epC.id), partial(epB.id, 1)),
        )
        assertEquals(setOf(epA.id, epC.id), ids)
    }

    @Test
    fun evictionDoesNothingWhileOffline() {
        val plan = evictionPlan(
            offline = true,
            removedIds = setOf(epA.id),
            cache = listOf(partial(epA.id, 0), full(epB.id, 0)),
            activeEpisodeId = null,
            nowEpochMs = 100_000,
            fullyCachedBudgetBytes = 0,
            partialGraceMs = 0,
        )
        assertEquals(EvictionPlan(), plan)
    }

    @Test
    fun evictionDropsOldPartialsRemovedEpisodesAndOverflowButKeepsTheActiveEpisode() {
        val plan = evictionPlan(
            offline = false,
            removedIds = setOf(epA.id, epC.id),
            cache = listOf(
                partial(epA.id, 0), // removed and active: kept
                partial(EpisodeId("https://a.example/feed", "old"), 0),
                full(epB.id, 10),
                full(epC.id, 20),
                partial(EpisodeId("https://a.example/feed", "fresh"), 90_000),
            ),
            activeEpisodeId = epA.id,
            nowEpochMs = 100_000,
            fullyCachedBudgetBytes = 100,
            partialGraceMs = 60_000,
        )
        assertEquals(setOf(epC.id), plan.downloadIds, "the episode loaded in the player is not auto-removed")
        assertEquals(
            setOf(EpisodeId("https://a.example/feed", "old"), epC.id, epB.id),
            plan.cacheIds,
        )
        assertTrue(EpisodeId("https://a.example/feed", "fresh") !in plan.cacheIds)
        assertTrue(epA.id !in plan.cacheIds)
    }

    @Test
    fun homeListsOnlyLocalEpisodesWhileOfflineAndSkipsRefresh() = runTest {
        val library = InMemoryLibraryRepository()
        library.subscribe(podcastA)
        library.upsertEpisodes(podcastA.feedUrl, listOf(epA, epB))
        val feeds = MapFeedRepository(mapOf(podcastA.feedUrl to Feed(podcastA, listOf(epA))))
        val offline = MutableStateFlow(true)
        val downloads = MutableStateFlow(listOf(done(epB.id)))
        val cache = MutableStateFlow(listOf(full(epA.id)))
        val absent = mutableListOf<Set<EpisodeId>>()
        val home = HomeState(
            library, feeds, backgroundScope,
            offline = offline,
            downloads = downloads,
            playbackCache = cache,
            onEpisodesAbsent = { absent += it },
            workContext = EmptyCoroutineContext,
        )
        settle()
        assertTrue(home.state.value.offline)
        assertEquals(listOf(epB.id, epA.id), home.state.value.upNext.map { it.row.episode.id })

        downloads.value = emptyList()
        cache.value = emptyList()
        settle()
        assertTrue(home.state.value.upNext.isEmpty())
        assertTrue(home.state.value.hasEpisodes)

        home.refreshAll()
        settle()
        assertTrue(feeds.fetched.isEmpty())
        assertTrue(absent.isEmpty())

        offline.value = false
        settle()
        assertEquals(2, home.state.value.upNext.size)
        home.refreshAll()
        settle()
        assertEquals(listOf(podcastA.feedUrl), feeds.fetched)
        assertEquals(setOf(epB.id), absent.single())
    }

    @Test
    fun cleanerKeepsRemovedDownloadsAndPartialsUntilOfflineEnds() = runTest {
        val offline = MutableStateFlow(true)
        val active = MutableStateFlow<EpisodeId?>(null)
        val cache = InMemoryPlaybackCache()
        val downloads = InMemoryDownloadManager(backgroundScope, stepMs = 1, steps = 1, clock = { 1L })
        val cleaner = LocalMediaCleaner(
            downloads = downloads,
            cache = cache,
            offline = offline,
            activeEpisode = active,
            scope = backgroundScope,
            budgetBytes = 50,
            partialGraceMs = 0,
            clock = { 10_000 },
        )
        downloads.enqueue(epA)
        cache.upsert(partial(epB.id, 0))
        cache.upsert(full(epC.id, 1))
        cleaner.start()
        settle()
        cleaner.noteRemoved(setOf(epA.id))
        settle()
        assertEquals(DownloadStatus.Done, downloads.downloads.first().single().status)
        assertEquals(2, cache.entries.first().size)

        offline.value = false
        settle()
        assertTrue(downloads.downloads.first().isEmpty(), "the removed episode's download is deleted once online")
        assertTrue(cache.entries.first().none { it.episodeId == epA.id })
        assertTrue(cache.entries.first().none { it.episodeId == epB.id }, "old partials are deleted once online")
    }

    @Test
    fun manualCacheRemovalWorksWhileOffline() = runTest {
        val cache = InMemoryPlaybackCache()
        cache.upsert(full(epA.id))
        val cleaner = LocalMediaCleaner(
            downloads = null,
            cache = cache,
            offline = MutableStateFlow(true),
            activeEpisode = MutableStateFlow(null),
            scope = backgroundScope,
            partialGraceMs = 0,
            clock = { 10_000 },
        )
        cleaner.start()
        settle()
        assertEquals(1, cache.entries.first().size)
        cache.remove(epA.id)
        settle()
        assertTrue(cache.entries.first().isEmpty())
    }

    @Test
    fun partialInsideTheGraceWindowIsRemovedAfterItExpires() = runTest {
        var now = 1_000L
        val cache = InMemoryPlaybackCache()
        val cleaner = LocalMediaCleaner(
            downloads = null,
            cache = cache,
            offline = MutableStateFlow(false),
            activeEpisode = MutableStateFlow(null),
            scope = backgroundScope,
            partialGraceMs = 5_000,
            clock = { now },
        )
        cache.upsert(partial(epA.id, at = 1_000))
        cleaner.start()
        runCurrent()
        assertEquals(1, cache.entries.first().size)
        now = 7_000
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(cache.entries.first().isEmpty())
    }
}
