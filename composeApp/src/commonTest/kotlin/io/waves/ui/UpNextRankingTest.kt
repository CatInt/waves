package io.waves.ui

import io.waves.download.DownloadInfo
import io.waves.download.DownloadStatus
import io.waves.feed.Feed
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.ui.fake.InMemoryLibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpNextRankingTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_000 * day

    private fun ep(podcast: Podcast, n: Int, ageDays: Double) = Episode(
        guid = "g$n", podcastFeedUrl = podcast.feedUrl, title = "${podcast.title} #$n",
        audioUrl = "${podcast.feedUrl}/$n.mp3", publishedAtEpochMs = now - (ageDays * day).toLong(), durationSeconds = 1800,
    )

    private fun sessions(podcast: Podcast, count: Int, ageDays: Double = 1.0) = (1..count).map {
        HistoryEntry(EpisodeId(podcast.feedUrl, "h$it"), "h$it", podcast.title, now - (ageDays * day).toLong(), "d")
    }

    private fun progress(e: Episode, positionMs: Long, completed: Boolean = false, ageDays: Double = 0.5) =
        PlaybackProgress(e.id, positionMs, 1_800_000, completed, now - (ageDays * day).toLong())

    @Test
    fun unfinishedEpisodeComesFirstAndCompletedIsHidden() {
        val fresh = ep(podcastA, 1, 0.5)
        val unfinished = ep(podcastA, 2, 20.0)
        val done = ep(podcastA, 3, 0.1)
        val items = UpNextRanking.rank(
            listOf(podcastA),
            mapOf(podcastA.feedUrl to listOf(fresh, unfinished, done)),
            listOf(progress(unfinished, 600_000), progress(done, 1_800_000, completed = true)).associateBy { it.episodeId },
            emptyList(), now,
        )
        assertEquals(listOf(unfinished, fresh), items.map { it.row.episode })
        assertEquals(listOf(UpNextReason.ContinueListening, UpNextReason.New), items.map { it.reason })
    }

    @Test
    fun showsListenedToMoreRankHigherAtEqualAge() {
        val a = ep(podcastA, 1, 5.0)
        val b = ep(podcastB, 1, 5.0)
        val items = UpNextRanking.rank(
            listOf(podcastA, podcastB),
            mapOf(podcastA.feedUrl to listOf(a), podcastB.feedUrl to listOf(b)),
            emptyMap(), sessions(podcastB, 5), now,
        )
        assertEquals(listOf(podcastB, podcastA), items.map { it.podcast })
        assertEquals(UpNextReason.FrequentShow, items.first().reason)
    }

    @Test
    fun newerEpisodesWinWithoutListeningHistory() {
        val old = ep(podcastA, 1, 30.0)
        val recent = ep(podcastB, 1, 2.0)
        val items = UpNextRanking.rank(
            listOf(podcastA, podcastB),
            mapOf(podcastA.feedUrl to listOf(old), podcastB.feedUrl to listOf(recent)),
            emptyMap(), emptyList(), now,
        )
        assertEquals(listOf(recent, old), items.map { it.row.episode })
    }

    @Test
    fun oneProlificShowDoesNotFillTheTop() {
        val daily = (1..10).map { ep(podcastA, it, it * 0.5) }
        val other = ep(podcastB, 1, 3.0)
        val items = UpNextRanking.rank(
            listOf(podcastA, podcastB),
            mapOf(podcastA.feedUrl to daily, podcastB.feedUrl to listOf(other)),
            emptyMap(), emptyList(), now,
        )
        val bIndex = items.indexOfFirst { it.podcast == podcastB }
        assertTrue(bIndex in 1..2, "Beta should surface near the top, was at $bIndex")
    }

    @Test
    fun capsCandidatesPerShowAndTotal() {
        val many = (1..30).map { ep(podcastA, it, it.toDouble()) }
        val items = UpNextRanking.rank(
            listOf(podcastA), mapOf(podcastA.feedUrl to many), emptyMap(), emptyList(), now,
            limit = 50, perShowCandidates = 10,
        )
        assertEquals(10, items.size)
        assertEquals(many.take(10), items.map { it.row.episode })
        assertEquals(
            3,
            UpNextRanking.rank(listOf(podcastA), mapOf(podcastA.feedUrl to many), emptyMap(), emptyList(), now, limit = 3).size,
        )
    }

    @Test
    fun downloadsAreListedEvenBeyondTheCaps() {
        val many = (1..30).map { ep(podcastA, it, it.toDouble()) }
        val old = many.last()
        val items = UpNextRanking.rank(
            listOf(podcastA), mapOf(podcastA.feedUrl to many), emptyMap(), emptyList(), now,
            limit = 3, perShowCandidates = 10, downloaded = setOf(old.id),
        )
        assertEquals(many.take(3) + old, items.map { it.row.episode })
        assertEquals(UpNextReason.Downloaded, items.last().reason)
    }

    @Test
    fun playedDownloadsAreHidden() {
        val e = ep(podcastA, 1, 40.0)
        val items = UpNextRanking.rank(
            listOf(podcastA), mapOf(podcastA.feedUrl to listOf(e)),
            listOf(progress(e, 1_800_000, completed = true)).associateBy { it.episodeId }, emptyList(), now,
            downloaded = setOf(e.id),
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun shelfPutsEngagedShowsFirstThenAlphabetical() {
        val c = Podcast("https://c.example/feed", "Cee Talk")
        val shelf = UpNextRanking.orderShelf(
            listOf(c, podcastB, podcastA), emptyMap(), emptyMap(), sessions(c, 3), now,
        )
        assertEquals(listOf(c, podcastA, podcastB), shelf)
    }

    @Test
    fun affinityDecaysWithTime() {
        val recent = UpNextRanking.showAffinity(sessions(podcastA, 4, ageDays = 1.0), emptyList(), now)
        val old = UpNextRanking.showAffinity(sessions(podcastA, 4, ageDays = 120.0), emptyList(), now)
        assertTrue(recent.getValue(podcastA.feedUrl) > old.getValue(podcastA.feedUrl))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeUpNextStateTest {
    private fun done(id: EpisodeId) = DownloadInfo(id, DownloadStatus.Done, 1, 1, "/f", null)

    @Test
    fun buildsShelfAndUpNextAndMarksPlayed() = runTest {
        val library = InMemoryLibraryRepository()
        library.subscribe(podcastA)
        library.subscribe(podcastB)
        library.upsertEpisodes(podcastA.feedUrl, listOf(episode(podcastA, 1), episode(podcastA, 2)))
        library.upsertEpisodes(podcastB.feedUrl, listOf(episode(podcastB, 1)))
        val s = HomeState(library, MapFeedRepository(emptyMap()), backgroundScope, clock = { 10_000L })
        settle()

        assertEquals(setOf(podcastA, podcastB), s.state.value.shelf.toSet())
        assertEquals(3, s.state.value.upNext.size)
        assertTrue(s.state.value.hasEpisodes)

        val first = s.state.value.upNext.first()
        library.saveProgress(PlaybackProgress(first.row.episode.id, 600_000, 600_000, true, 9_000))
        s.releaseOrder()
        settle()
        assertEquals(2, s.state.value.upNext.size)
        assertTrue(s.state.value.upNext.none { it.row.episode.id == first.row.episode.id })
    }

    @Test
    fun orderHoldsStillUntilReleased() = runTest {
        val library = InMemoryLibraryRepository()
        library.subscribe(podcastA)
        library.subscribe(podcastB)
        library.upsertEpisodes(podcastA.feedUrl, listOf(episode(podcastA, 1), episode(podcastA, 2)))
        library.upsertEpisodes(podcastB.feedUrl, listOf(episode(podcastB, 1)))
        val s = HomeState(library, MapFeedRepository(emptyMap()), backgroundScope, clock = { 10_000L })
        settle()
        val before = s.state.value.upNext.map { it.row.episode.id }
        val started = before.last()

        library.saveProgress(PlaybackProgress(started, 60_000, 600_000, false, 9_000))
        settle()
        assertEquals(before, s.state.value.upNext.map { it.row.episode.id })
        assertEquals(60_000, s.state.value.upNext.last().row.progress?.positionMs)

        library.upsertEpisodes(podcastB.feedUrl, listOf(episode(podcastB, 1), episode(podcastB, 2)))
        settle()
        assertEquals(before, s.state.value.upNext.map { it.row.episode.id }.filter { it in before })
        assertEquals(before.size + 1, s.state.value.upNext.size)

        s.releaseOrder()
        settle()
        assertEquals(started, s.state.value.upNext.first().row.episode.id)
    }

    @Test
    fun upNextFollowsDownloads() = runTest {
        val library = InMemoryLibraryRepository()
        library.subscribe(podcastA)
        library.upsertEpisodes(podcastA.feedUrl, listOf(episode(podcastA, 1), episode(podcastA, 2)))
        val downloads = MutableStateFlow(emptyList<DownloadInfo>())
        val s = HomeState(library, MapFeedRepository(emptyMap()), backgroundScope, upNextLimit = 1, downloads = downloads)
        settle()
        assertEquals(1, s.state.value.upNext.size)
        val other = (listOf(episode(podcastA, 1), episode(podcastA, 2)) - s.state.value.upNext.single().row.episode).single()
        downloads.value = listOf(done(other.id))
        settle()
        assertEquals(other, s.state.value.upNext.last().row.episode)
    }

    @Test
    fun listsDownloadsOfShowsNotSubscribedTo() = runTest {
        val library = InMemoryLibraryRepository()
        val e = episode(podcastB, 1)
        library.upsertEpisodes(podcastB.feedUrl, listOf(e))
        val feeds = MapFeedRepository(mapOf(podcastB.feedUrl to Feed(podcastB, listOf(e))))
        val s = HomeState(library, feeds, backgroundScope, downloads = flowOf(listOf(done(e.id))))
        settle()
        assertTrue(s.state.value.subscriptions.isEmpty())
        assertTrue(s.state.value.shelf.isEmpty())
        val item = s.state.value.upNext.single()
        assertEquals(e, item.row.episode)
        // The show isn't stored, so its name comes from the feed, which is then remembered.
        assertEquals(podcastB, item.podcast)
        assertEquals(podcastB, library.podcast(podcastB.feedUrl))
        assertTrue(library.subscriptions.first().isEmpty())
    }

    @Test
    fun refreshesOnceWhenSubscriptionsHaveNoEpisodes() = runTest {
        val library = InMemoryLibraryRepository()
        library.subscribe(podcastA)
        val feeds = MapFeedRepository(mapOf(podcastA.feedUrl to Feed(podcastA, listOf(episode(podcastA, 1)))))
        val s = HomeState(library, feeds, backgroundScope)
        settle()
        assertEquals(listOf(podcastA.feedUrl), feeds.fetched)
        assertEquals(1, s.state.value.upNext.size)
    }
}
