package io.waves.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.waves.data.db.WavesDatabase
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import io.waves.sync.SubscriptionRecord
import io.waves.sync.SyncState
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlLibraryRepositoryTest {
    private var now = 1_000L
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) }
    private val repo = SqlLibraryRepository(driver, Dispatchers.IO) { now }

    @AfterTest fun close() = driver.close()

    private fun podcast(url: String, title: String) = Podcast(feedUrl = url, title = title, author = "a")
    private val id = EpisodeId("https://a/feed", "g1")

    private val live = YoutubeUrls.liveArchiveFeedUrl("UCexampleChannel00000000")

    private fun stream(guid: String, at: Long, seconds: Long? = null, title: String = guid) = Episode(
        guid = guid, podcastFeedUrl = live, title = title, audioUrl = YoutubeUrls.watchUrl(guid),
        publishedAtEpochMs = at, durationSeconds = seconds,
    )

    @Test fun followedLiveArchiveEpisodesGoOutWithSync() = runBlocking<Unit> {
        repo.subscribe(podcast(live, "Example · Lived"))
        repo.upsertEpisodes(live, listOf(stream("liveStream1", 2), stream("liveStream0", 1)))
        // Ordinary feeds list their whole back catalogue themselves, and an archive nobody follows isn't synced.
        repo.subscribe(podcast("https://a/feed", "Ordinary"))
        repo.upsertEpisodes("https://a/feed", listOf(Episode("g1", "https://a/feed", "t", "https://a/1.mp3")))
        val other = YoutubeUrls.liveArchiveFeedUrl("UCanotherChannel00000000")
        repo.rememberPodcast(podcast(other, "Not followed"))
        repo.upsertEpisodes(other, listOf(stream("liveStream9", 9).copy(podcastFeedUrl = other)))

        assertEquals(listOf("liveStream1", "liveStream0"), repo.exportSyncState().archive.map { it.guid })
    }

    @Test fun importAddsMissingArchivedEpisodesAndKeepsOurs() = runBlocking<Unit> {
        repo.upsertEpisodes(live, listOf(stream("liveStream1", 2, title = "Ours")))
        repo.importSyncState(
            SyncState(
                archive = listOf(
                    stream("liveStream1", 2, seconds = 3600, title = "Theirs"),
                    stream("liveStream0", 1, seconds = 60),
                    // Only archives come in this way.
                    Episode("g1", "https://a/feed", "t", "https://a/1.mp3"),
                ),
            ),
        )
        val stored = repo.episodes(live).first()
        assertEquals(listOf("liveStream1", "liveStream0"), stored.map { it.guid })
        assertEquals("Ours", stored[0].title)
        assertEquals(3600L, stored[0].durationSeconds) // the length we lacked
        assertTrue(repo.episodes("https://a/feed").first().isEmpty())
    }

    @Test fun subscriptionsSortedByTitleAndUnsubscribeKeepsTombstone() = runBlocking<Unit> {
        repo.subscribe(podcast("https://b", "beta"))
        repo.subscribe(podcast("https://a", "Alpha"))
        assertEquals(listOf("Alpha", "beta"), repo.subscriptions.first().map { it.title })

        now = 2_000L
        repo.unsubscribe("https://a")
        assertEquals(listOf("beta"), repo.subscriptions.first().map { it.title })

        val records = repo.exportSyncState().subscriptions.associateBy { it.podcast.feedUrl }
        assertEquals(SubscriptionRecord(podcast("https://a", "Alpha"), false, 2_000L), records["https://a"])
        assertEquals(SubscriptionRecord(podcast("https://b", "beta"), true, 1_000L), records["https://b"])

        now = 3_000L
        repo.subscribe(podcast("https://a", "Alpha"))
        assertEquals(listOf("Alpha", "beta"), repo.subscriptions.first().map { it.title })
        assertEquals(3_000L, repo.exportSyncState().subscriptions.first { it.podcast.feedUrl == "https://a" }.updatedAtEpochMs)
    }

    @Test fun updatePodcastMetadataLeavesSubscriptionAndTimestampAlone() = runBlocking<Unit> {
        repo.subscribe(Podcast("https://a", "Old", author = "a", artworkUrl = "https://a/old.png", appleId = 42))
        now = 5_000L
        repo.unsubscribe("https://a")
        now = 9_000L

        repo.updatePodcastMetadata(Podcast("https://a", "New", author = "b", description = "d", artworkUrl = "https://a/new.png"))

        val record = repo.exportSyncState().subscriptions.single()
        assertEquals(Podcast("https://a", "New", author = "b", description = "d", artworkUrl = "https://a/new.png", appleId = 42), record.podcast)
        assertEquals(false, record.subscribed, "subscription state untouched")
        assertEquals(5_000L, record.updatedAtEpochMs, "sync timestamp untouched")

        // Missing row: no-op (must not create a podcast row).
        repo.updatePodcastMetadata(Podcast("https://missing", "Ghost"))
        assertEquals(listOf("https://a"), repo.exportSyncState().subscriptions.map { it.podcast.feedUrl })
        assertTrue(repo.subscriptions.first().isEmpty())
    }

    @Test fun importKeepsLocallyRefreshedMetadataAgainstStaleRemoteAtSameTimestamp() = runBlocking<Unit> {
        // "Zzz Old" sorts after "New", so the metadata tie-break alone would pick the stale remote title.
        val old = Podcast("https://a", "Zzz Old", author = "a", artworkUrl = "https://a/old.png")
        repo.subscribe(old)
        repo.updatePodcastMetadata(old.copy(title = "New", artworkUrl = "https://a/new.png"))

        repo.importSyncState(SyncState(subscriptions = listOf(SubscriptionRecord(old.copy(description = "remote desc"), true, 1_000L))))

        val local = repo.subscriptions.first().single()
        assertEquals("New", local.title)
        assertEquals("https://a/new.png", local.artworkUrl)
        assertEquals("remote desc", local.description, "blank local fields are filled from sync")
    }

    @Test fun importAppliesSubscriptionStateButNotMetadataToExistingRow() = runBlocking<Unit> {
        repo.subscribe(Podcast("https://a", "Local", author = "me"))
        repo.importSyncState(SyncState(subscriptions = listOf(SubscriptionRecord(Podcast("https://a", "Remote", author = "them"), false, 2_000L))))
        val record = repo.exportSyncState().subscriptions.single()
        assertEquals(SubscriptionRecord(Podcast("https://a", "Local", author = "me"), false, 2_000L), record)

        // Unknown podcast: incoming metadata is all we have.
        repo.importSyncState(SyncState(subscriptions = listOf(SubscriptionRecord(Podcast("https://b", "Remote B"), true, 3_000L))))
        assertEquals(listOf("Remote B"), repo.subscriptions.first().map { it.title })
    }

    @Test fun rememberedPodcastIsNeitherSubscribedNorSynced() = runBlocking<Unit> {
        repo.rememberPodcast(podcast("https://a", "Kept"))
        assertEquals(podcast("https://a", "Kept"), repo.podcast("https://a"))
        assertTrue(repo.subscriptions.first().isEmpty())
        assertTrue(repo.exportSyncState().subscriptions.isEmpty())

        repo.rememberPodcast(podcast("https://a", "Renamed"))
        assertEquals("Renamed", repo.podcast("https://a")?.title)

        // Subscribing later works as usual; remembering then leaves the subscription alone.
        repo.subscribe(podcast("https://a", "Subscribed"))
        repo.rememberPodcast(podcast("https://a", "From search"))
        assertEquals(listOf("Subscribed"), repo.subscriptions.first().map { it.title })
        assertEquals(SubscriptionRecord(podcast("https://a", "Subscribed"), true, 1_000L), repo.exportSyncState().subscriptions.single())
        assertNull(repo.podcast("https://missing"))
    }

    @Test fun rememberingWithoutCoverOrDescriptionKeepsTheStoredOnes() = runBlocking<Unit> {
        repo.rememberPodcast(podcast("https://a", "Kept").copy(author = "Ann", description = "About", artworkUrl = "https://a/art.jpg"))
        repo.rememberPodcast(podcast("https://a", "Renamed").copy(author = null))
        assertEquals(
            podcast("https://a", "Renamed").copy(author = "Ann", description = "About", artworkUrl = "https://a/art.jpg"),
            repo.podcast("https://a"),
        )
    }

    @Test fun updatePodcastMetadataShowsInSubscriptions() = runBlocking<Unit> {
        repo.subscribe(podcast("https://a", "Old"))
        repo.updatePodcastMetadata(podcast("https://a", "Renamed"))
        assertEquals(listOf("Renamed"), repo.subscriptions.first().map { it.title })
        assertEquals(1_000L, repo.exportSyncState().subscriptions.single().updatedAtEpochMs)
    }

    @Test fun episodesNewestFirstAndLookup() = runBlocking<Unit> {
        val feed = "https://a/feed"
        val eps = listOf(
            Episode("old", feed, "Old", "https://a/1.mp3", publishedAtEpochMs = 1),
            Episode("new", feed, "New", "https://a/2.mp3", publishedAtEpochMs = 3, durationSeconds = 60),
            Episode("mid", feed, "Mid", "https://a/3.mp3", publishedAtEpochMs = 2),
        )
        repo.upsertEpisodes(feed, eps)
        assertEquals(listOf("new", "mid", "old"), repo.episodes(feed).first().map { it.guid })
        assertEquals(eps[1], repo.episode(EpisodeId(feed, "new")))
        assertNull(repo.episode(EpisodeId(feed, "nope")))

        repo.upsertEpisodes(feed, listOf(eps[0].copy(title = "Old v2")))
        assertEquals("Old v2", repo.episode(EpisodeId(feed, "old"))?.title)
        assertEquals(3, repo.episodes(feed).first().size)
    }

    @Test fun refreshWithoutADurationKeepsTheStoredOne() = runBlocking<Unit> {
        val feed = "https://a/feed"
        val ep = Episode("g", feed, "Ep", "https://a/1.mp3", durationSeconds = 60)
        repo.upsertEpisodes(feed, listOf(ep))
        repo.upsertEpisodes(feed, listOf(ep.copy(title = "Ep v2", durationSeconds = null)))
        assertEquals(ep.copy(title = "Ep v2"), repo.episode(EpisodeId(feed, "g")))
        repo.upsertEpisodes(feed, listOf(ep.copy(durationSeconds = 90)))
        assertEquals(90, repo.episode(EpisodeId(feed, "g"))?.durationSeconds)
    }

    @Test fun progressIsLastWriterWins() = runBlocking<Unit> {
        repo.saveProgress(PlaybackProgress(id, 10, 100, updatedAtEpochMs = 50))
        repo.saveProgress(PlaybackProgress(id, 5, 100, updatedAtEpochMs = 40)) // older: ignored
        assertEquals(10, repo.progress(id).first()?.positionMs)
        repo.saveProgress(PlaybackProgress(id, 20, 100, updatedAtEpochMs = 50)) // equal: wins
        assertEquals(20, repo.progress(id).first()?.positionMs)
        repo.saveProgress(PlaybackProgress(id, 100, 100, completed = true, updatedAtEpochMs = 60))
        assertEquals(PlaybackProgress(id, 100, 100, true, 60), repo.progress(id).first())
        assertNull(repo.progress(EpisodeId("x", "y")).first())
        assertEquals(1, repo.allProgress.first().size)
    }

    @Test fun historyNewestFirstAndDeduplicated() = runBlocking<Unit> {
        val h1 = HistoryEntry(id, "Ep", "Pod", 100, "dev1")
        val h2 = HistoryEntry(id, "Ep", "Pod", 200, "dev1")
        val h3 = HistoryEntry(id, "Ep", "Pod", 100, "dev2")
        listOf(h1, h2, h3, h1, h2).forEach { repo.addHistory(it) }
        val history = repo.history.first()
        assertEquals(3, history.size)
        assertEquals(h2, history.first())
        assertEquals(setOf(h1, h2, h3), history.toSet())
    }

    @Test fun exportImportRoundTripKeepsEpisodes() = runBlocking<Unit> {
        repo.upsertEpisodes(id.feedUrl, listOf(Episode(id.guid, id.feedUrl, "Ep", "https://a/1.mp3")))

        val state = SyncState(
            subscriptions = listOf(
                SubscriptionRecord(podcast("https://a/feed", "A").copy(appleId = 42, artworkUrl = "art"), true, 10),
                SubscriptionRecord(podcast("https://gone", "Gone"), false, 20),
            ),
            progress = listOf(PlaybackProgress(id, 30, 300, false, 15)),
            history = listOf(HistoryEntry(id, "Ep", "A", 5, "dev")),
        )
        repo.importSyncState(state)

        val exported = repo.exportSyncState()
        assertEquals(state.subscriptions.toSet(), exported.subscriptions.toSet())
        assertEquals(state.progress, exported.progress)
        assertEquals(state.history, exported.history)
        assertEquals(listOf("A"), repo.subscriptions.first().map { it.title })
        assertEquals("Ep", repo.episode(id)?.title)

        // Importing the export into a fresh database reproduces the same state.
        val other = SqlLibraryRepository(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { WavesDatabase.Schema.create(it) },
        )
        other.importSyncState(exported)
        assertEquals(exported.copy(subscriptions = exported.subscriptions.sortedBy { it.podcast.feedUrl }),
            other.exportSyncState().let { it.copy(subscriptions = it.subscriptions.sortedBy { s -> s.podcast.feedUrl }) })
    }

    @Test fun importKeepsNewerLocalProgress() = runBlocking<Unit> {
        repo.saveProgress(PlaybackProgress(id, 900, 1000, updatedAtEpochMs = 50))
        repo.importSyncState(SyncState(progress = listOf(PlaybackProgress(id, 100, 1000, updatedAtEpochMs = 40))))
        assertEquals(PlaybackProgress(id, 900, 1000, false, 50), repo.progress(id).first())
    }

    @Test fun importAppliesNewerIncomingProgress() = runBlocking<Unit> {
        repo.saveProgress(PlaybackProgress(id, 100, 1000, updatedAtEpochMs = 40))
        val other = EpisodeId("https://b/feed", "g2")
        repo.saveProgress(PlaybackProgress(other, 5, 10, updatedAtEpochMs = 1))
        repo.importSyncState(SyncState(progress = listOf(PlaybackProgress(id, 900, 1000, true, 50))))
        assertEquals(PlaybackProgress(id, 900, 1000, true, 50), repo.progress(id).first())
        // Episodes absent from the snapshot are untouched.
        assertEquals(5, repo.progress(other).first()?.positionMs)
    }

    @Test fun importTieOnProgressTimestampFollowsSyncMerge() = runBlocking<Unit> {
        repo.saveProgress(PlaybackProgress(id, 500, 1000, false, 50))
        // Equal timestamp, smaller position and not completed: SyncMerge keeps the stored record.
        repo.importSyncState(SyncState(progress = listOf(PlaybackProgress(id, 400, 1000, false, 50))))
        assertEquals(500, repo.progress(id).first()?.positionMs)
        // Equal timestamp, completed: wins.
        repo.importSyncState(SyncState(progress = listOf(PlaybackProgress(id, 400, 1000, true, 50))))
        assertEquals(PlaybackProgress(id, 400, 1000, true, 50), repo.progress(id).first())
    }

    @Test fun importKeepsNewerLocalUnsubscribe() = runBlocking<Unit> {
        now = 1_000L
        repo.subscribe(podcast("https://a", "A"))
        now = 2_000L
        repo.unsubscribe("https://a")

        repo.importSyncState(SyncState(subscriptions = listOf(SubscriptionRecord(podcast("https://a", "A"), true, 1_500L))))
        assertEquals(emptyList(), repo.subscriptions.first())
        assertEquals(
            SubscriptionRecord(podcast("https://a", "A"), false, 2_000L),
            repo.exportSyncState().subscriptions.single(),
        )

        // A newer incoming subscribe wins (its state, not its metadata: the local title is kept), and unknown
        // podcasts are inserted; local-only ones are kept.
        repo.subscribe(podcast("https://local", "Local"))
        repo.importSyncState(SyncState(subscriptions = listOf(
            SubscriptionRecord(podcast("https://a", "A2"), true, 2_500L),
            SubscriptionRecord(podcast("https://new", "New"), true, 1L),
        )))
        assertEquals(listOf("A", "Local", "New"), repo.subscriptions.first().map { it.title })
    }

    @Test fun importUnionsHistory() = runBlocking<Unit> {
        val local = HistoryEntry(id, "Ep", "Pod", 100, "dev1")
        val shared = HistoryEntry(id, "Ep", "Pod", 200, "dev1")
        val remote = HistoryEntry(id, "Ep", "Pod", 300, "dev2")
        repo.addHistory(local)
        repo.addHistory(shared)
        repo.importSyncState(SyncState(history = listOf(shared, remote)))
        assertEquals(listOf(remote, shared, local), repo.history.first())
    }

    @Test fun flowsEmitOnChanges() = runBlocking<Unit> {
        val subs = repo.subscriptions.collectInto(this)
        val progress = repo.progress(id).collectInto(this)
        val history = repo.history.collectInto(this)
        assertEquals(emptyList(), subs.next())
        assertNull(progress.next())
        assertEquals(emptyList(), history.next())

        repo.subscribe(podcast("https://a", "A"))
        assertEquals(listOf("A"), subs.next().map { it.title })
        repo.unsubscribe("https://a")
        assertEquals(emptyList(), subs.next())

        repo.saveProgress(PlaybackProgress(id, 1, 10, updatedAtEpochMs = 1))
        assertEquals(1, progress.next()?.positionMs)
        repo.saveProgress(PlaybackProgress(id, 2, 10, updatedAtEpochMs = 2))
        assertEquals(2, progress.next()?.positionMs)

        repo.addHistory(HistoryEntry(id, "Ep", "A", 1, "d"))
        assertEquals(1, history.next().size)

        coroutineContext.cancelChildren()
    }

    @Test fun deviceIdIsStableAndPersistedOnDisk() = runBlocking<Unit> {
        val first = repo.deviceId()
        assertEquals(first, repo.deviceId())
        assertTrue(first.length == 36)

        val dir = Files.createTempDirectory("waves-test").toFile()
        try {
            val file = File(dir, "nested/waves.db")
            val id1 = SqlLibraryRepository(createDesktopDriver(file)).deviceId()
            assertTrue(file.exists())
            val reopened = SqlLibraryRepository(createDesktopDriver(file))
            assertEquals(id1, reopened.deviceId())
            reopened.subscribe(podcast("https://p", "P"))
            assertEquals(listOf("P"), SqlLibraryRepository(createDesktopDriver(file)).subscriptions.first().map { it.title })
        } finally {
            dir.deleteRecursively()
        }
    }

    private class Collected<T>(val channel: Channel<T>) {
        suspend fun next(): T = withTimeout(5_000) { channel.receive() }
    }

    private fun <T> Flow<T>.collectInto(scope: kotlinx.coroutines.CoroutineScope): Collected<T> {
        val channel = Channel<T>(Channel.UNLIMITED)
        scope.launch(Dispatchers.IO) { collect { channel.send(it) } }
        return Collected(channel)
    }
}

private fun kotlin.coroutines.CoroutineContext.cancelChildren() =
    this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
