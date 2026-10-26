package io.waves.sync

import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncMergeTest {
    private fun sub(feed: String, subscribed: Boolean, at: Long, title: String = feed) =
        SubscriptionRecord(Podcast(feedUrl = feed, title = title), subscribed, at)

    private fun ep(feed: String = "f", guid: String = "g") = EpisodeId(feed, guid)

    private fun prog(id: EpisodeId, pos: Long, at: Long, completed: Boolean = false, dur: Long = 100_000) =
        PlaybackProgress(id, pos, dur, completed, at)

    private fun hist(id: EpisodeId, at: Long, device: String = "d1", title: String = "t") =
        HistoryEntry(id, title, "p", at, device)

    @Test
    fun subscriptionsLastWriterWinsIncludingTombstones() {
        val a = SyncState(subscriptions = listOf(sub("x", true, 10), sub("y", true, 30)))
        val b = SyncState(subscriptions = listOf(sub("x", false, 20), sub("y", false, 25), sub("z", true, 5)))
        val m = SyncMerge.merge(a, b)
        assertEquals(listOf(sub("x", false, 20), sub("y", true, 30), sub("z", true, 5)), m.subscriptions)
    }

    @Test
    fun subscriptionTieOnTimestampPrefersSubscribed() {
        val a = SyncState(subscriptions = listOf(sub("x", false, 10)))
        val b = SyncState(subscriptions = listOf(sub("x", true, 10)))
        assertTrue(SyncMerge.merge(a, b).subscriptions.single().subscribed)
        assertTrue(SyncMerge.merge(b, a).subscriptions.single().subscribed)
    }

    @Test
    fun newerMetadataWinsWithSubscription() {
        val a = SyncState(subscriptions = listOf(sub("x", true, 10, title = "Old")))
        val b = SyncState(subscriptions = listOf(sub("x", true, 11, title = "New")))
        assertEquals("New", SyncMerge.merge(a, b).subscriptions.single().podcast.title)
    }

    @Test
    fun progressLastWriterWins() {
        val a = SyncState(progress = listOf(prog(ep(), 5_000, at = 100)))
        val b = SyncState(progress = listOf(prog(ep(), 1_000, at = 200)))
        assertEquals(1_000, SyncMerge.merge(a, b).progress.single().positionMs)
    }

    @Test
    fun progressTieBreakPrefersCompletedThenLargerPosition() {
        val p1 = prog(ep(), 9_000, at = 100)
        val p2 = prog(ep(), 2_000, at = 100, completed = true)
        val p3 = prog(ep(), 3_000, at = 100)
        assertEquals(p2, SyncMerge.merge(SyncState(progress = listOf(p1)), SyncState(progress = listOf(p2))).progress.single())
        assertEquals(p1, SyncMerge.merge(SyncState(progress = listOf(p3)), SyncState(progress = listOf(p1))).progress.single())
        assertEquals(p1, SyncMerge.merge(SyncState(progress = listOf(p1)), SyncState(progress = listOf(p3))).progress.single())
    }

    @Test
    fun progressKeyedByFeedAndGuid() {
        val a = SyncState(progress = listOf(prog(ep("f1", "g"), 1, 1)))
        val b = SyncState(progress = listOf(prog(ep("f2", "g"), 2, 1)))
        assertEquals(2, SyncMerge.merge(a, b).progress.size)
    }

    @Test
    fun historyUnionDedupedAndSortedNewestFirst() {
        val h1 = hist(ep(guid = "1"), 100)
        val h2 = hist(ep(guid = "2"), 200)
        val h3 = hist(ep(guid = "1"), 100, device = "d2")
        val m = SyncMerge.merge(SyncState(history = listOf(h1, h2)), SyncState(history = listOf(h1, h3)))
        assertEquals(listOf(h2, h3, h1), m.history)
    }

    @Test
    fun historyCappedToMostRecent() {
        val a = SyncState(history = (0 until 1500).map { hist(ep(guid = "$it"), it.toLong(), "a") })
        val b = SyncState(history = (0 until 1500).map { hist(ep(guid = "$it"), it.toLong(), "b") })
        val m = SyncMerge.merge(a, b)
        assertEquals(SyncMerge.HISTORY_CAP, m.history.size)
        assertEquals(1499, m.history.first().playedAtEpochMs)
        assertEquals(500, m.history.last().playedAtEpochMs)
        assertFalse(m.history.any { it.playedAtEpochMs < 500 })
    }

    @Test
    fun versionIsMax() {
        assertEquals(3, SyncMerge.merge(SyncState(version = 3), SyncState(version = 1)).version)
    }

    @Test
    fun emptyIsIdentityOnNormalizedState() {
        val s = SyncMerge.normalize(randomState(Random(1)))
        assertEquals(s, SyncMerge.merge(s, SyncState()))
        assertEquals(s, SyncMerge.merge(SyncState(), s))
    }

    // ---- property-style checks over random states with heavy key/timestamp collisions ----

    private fun randomState(r: Random, historyMax: Int = 30): SyncState {
        val feeds = listOf("a", "b", "c", "d")
        val guids = listOf("1", "2", "3")
        return SyncState(
            version = r.nextInt(1, 3),
            subscriptions = List(r.nextInt(0, 8)) {
                sub(feeds.random(r), r.nextBoolean(), r.nextLong(0, 5), title = listOf("T1", "T2").random(r))
            },
            progress = List(r.nextInt(0, 10)) {
                prog(ep(feeds.random(r), guids.random(r)), r.nextLong(0, 4), r.nextLong(0, 4), r.nextBoolean(), r.nextLong(1, 3))
            },
            history = List(r.nextInt(0, historyMax)) {
                hist(ep(feeds.random(r), guids.random(r)), r.nextLong(0, 10), listOf("d1", "d2").random(r), listOf("x", "y").random(r))
            },
        )
    }

    @Test
    fun commutativeAssociativeIdempotent() {
        val r = Random(42)
        repeat(500) {
            val a = randomState(r)
            val b = randomState(r)
            val c = randomState(r)
            val ab = SyncMerge.merge(a, b)
            assertEquals(ab, SyncMerge.merge(b, a), "commutative")
            assertEquals(ab, SyncMerge.merge(ab, ab), "idempotent")
            assertEquals(ab, SyncMerge.merge(ab, b), "absorbs b")
            assertEquals(ab, SyncMerge.merge(a, ab), "absorbs a")
            assertEquals(SyncMerge.merge(ab, c), SyncMerge.merge(a, SyncMerge.merge(b, c)), "associative")
            assertEquals(SyncMerge.normalize(a), SyncMerge.merge(a, a), "self-merge normalizes")
        }
    }

    @Test
    fun propertiesHoldWithHistoryCap() {
        val r = Random(7)
        fun big() = SyncState(history = List(1500) {
            hist(ep(guid = "${r.nextInt(50)}"), r.nextLong(0, 3000), listOf("d1", "d2", "d3").random(r), listOf("x", "y").random(r))
        })
        repeat(5) {
            val a = big(); val b = big(); val c = big()
            val ab = SyncMerge.merge(a, b)
            assertEquals(ab, SyncMerge.merge(b, a))
            assertEquals(SyncMerge.merge(ab, c), SyncMerge.merge(a, SyncMerge.merge(b, c)))
            assertEquals(ab, SyncMerge.merge(ab, ab))
            assertTrue(ab.history.size <= SyncMerge.HISTORY_CAP)
        }
    }

    @Test
    fun noDuplicateKeysAfterMerge() {
        val r = Random(3)
        repeat(200) {
            val m = SyncMerge.merge(randomState(r), randomState(r))
            assertEquals(m.subscriptions.size, m.subscriptions.map { it.podcast.feedUrl }.toSet().size)
            assertEquals(m.progress.size, m.progress.map { it.episodeId }.toSet().size)
            assertEquals(m.history.size, m.history.map { Triple(it.episodeId, it.playedAtEpochMs, it.deviceId) }.toSet().size)
        }
    }

    @Test
    fun keepLocalMetadataOnlyFillsBlanks() {
        val local = io.waves.model.Podcast("f", "New", author = " ", description = null, artworkUrl = "https://new.png")
        val incoming = io.waves.model.Podcast("f", "Old", author = "A", description = "D", artworkUrl = "https://old.png", appleId = 9)
        assertEquals(
            io.waves.model.Podcast("f", "New", author = "A", description = "D", artworkUrl = "https://new.png", appleId = 9),
            SyncMerge.keepLocalMetadata(local, incoming),
        )
    }
}
