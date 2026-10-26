package io.waves.sync

import io.waves.model.EpisodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two devices (separate SQLite files and device ids) syncing through one [FakeDriveServer] over real HTTP,
 * using the production [DriveCloudStore] / [DefaultSyncService] / SqlLibraryRepository stack.
 */
class SyncIntegrationTest {
    private val server = FakeDriveServer()
    private val clock = TestClock()
    private val a = Device("A", server, clock)
    private val b = Device("B", server, clock)
    private val file = DefaultSyncService.DEFAULT_FILE_NAME

    private val ep = episode(1, "ep-1")

    @AfterTest fun tearDown() {
        a.close(); b.close(); server.close()
    }

    private suspend fun Device.syncOk() {
        val status = syncNow()
        assertIs<SyncStatus.Done>(status, "$label sync: $status")
    }

    private fun remote(): SyncState = DefaultSyncService.json.decodeFromString(SyncState.serializer(), assertNotNull(server.text(file)))

    /** Both devices and the remote file hold the same data. */
    private suspend fun assertConverged() {
        val sa = a.state()
        assertEquals(sa, b.state(), "devices differ")
        assertEquals(sa.copy(version = 1), SyncMerge.normalize(remote()).copy(version = 1), "remote differs")
    }

    private suspend fun round() { clock.advance(); a.syncOk(); b.syncOk(); a.syncOk() }

    @Test fun subscriptionAndPositionReachSecondDevice() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1))
        clock.advance()
        a.play(ep, 10 * MIN)
        a.syncOk()
        b.syncOk()

        assertEquals(listOf(podcast(1).feedUrl), b.subscriptions())
        assertEquals(10 * MIN, b.progress(ep)?.positionMs)
        // Synced as plain appDataFolder file; first sync used the multipart create, B only read.
        assertEquals(1, server.appDataFiles().size)
        assertEquals(1, server.requests.count { it.method == "POST" })
        assertEquals(0, server.requests.count { it.method == "PATCH" })
        assertConverged()
    }

    @Test fun concurrentEditsOfOneEpisodeConvergeDeterministically() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1)); a.play(ep, 10 * MIN)
        a.syncOk(); b.syncOk()

        // Offline edits: B keeps listening to 20 min, slightly later A marks the episode played.
        clock.advance(); b.play(ep, 20 * MIN)
        clock.advance(); a.play(ep, 60 * MIN, completed = true)
        b.syncOk(); a.syncOk(); b.syncOk()
        assertConverged()
        assertEquals(true, a.progress(ep)?.completed)
        assertEquals(true, b.progress(ep)?.completed)

        // Same edits, opposite order of time: B's later edit (resume at 25 min) wins on both.
        clock.advance(); a.play(ep, 0, completed = true)
        clock.advance(); b.play(ep, 25 * MIN)
        a.syncOk(); b.syncOk(); a.syncOk()
        assertConverged()
        assertEquals(25 * MIN, a.progress(ep)?.positionMs)
        assertEquals(false, a.progress(ep)?.completed)

        // A timestamp tie is broken by content, never by who synced first: completed wins.
        val tie = clock.now + 10_000
        a.repo.saveProgress(io.waves.model.PlaybackProgress(ep, 30 * MIN, 3_600_000, false, tie))
        b.repo.saveProgress(io.waves.model.PlaybackProgress(ep, 1 * MIN, 3_600_000, true, tie))
        b.syncOk(); a.syncOk(); b.syncOk()
        assertConverged()
        assertEquals(true, a.progress(ep)?.completed)
    }

    @Test fun unsubscribePropagatesAndIsNotResurrectedByStaleDevice() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1)); a.repo.subscribe(podcast(2))
        round(); b.syncOk()
        assertEquals(2, b.subscriptions().size)

        clock.advance(); b.repo.unsubscribe(podcast(1).feedUrl)
        b.syncOk()
        // A still has the old "subscribed" record and keeps making unrelated changes before it syncs.
        clock.advance(); a.play(ep, 5 * MIN)
        a.syncOk()
        assertEquals(listOf(podcast(2).feedUrl), a.subscriptions())
        b.syncOk(); a.syncOk()
        assertEquals(listOf(podcast(2).feedUrl), b.subscriptions())
        assertEquals(false, remote().subscriptions.single { it.podcast.feedUrl == podcast(1).feedUrl }.subscribed)
        assertConverged()

        // A later re-subscribe is a newer edit and does propagate.
        clock.advance(); a.repo.subscribe(podcast(1))
        round(); b.syncOk()
        assertEquals(2, b.subscriptions().size)
    }

    @Test fun historyFromBothDevicesIsUnioned() = runBlocking<Unit> {
        repeat(3) { clock.advance(); a.listened(episode(1, "a$it")) }
        repeat(2) { clock.advance(); b.listened(episode(2, "b$it")) }
        // Same episode at the same instant on both devices: distinct entries (different device ids).
        clock.advance(); a.listened(episode(3, "x")); b.listened(episode(3, "x"))
        round(); b.syncOk()
        assertConverged()
        val history = a.history()
        assertEquals(7, history.size)
        assertEquals(setOf(a.deviceId(), b.deviceId()), history.map { it.deviceId }.toSet())
        assertEquals(history.sortedByDescending { it.playedAtEpochMs }.map { it.playedAtEpochMs }, history.map { it.playedAtEpochMs })
    }

    @Test fun conflictingWritesAreRetriedAndBothConverge() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1))
        round(); b.syncOk()

        clock.advance(); a.repo.subscribe(podcast(2)); a.listened(episode(2, "a"))
        clock.advance(); b.repo.subscribe(podcast(3)); b.listened(episode(3, "b"))
        // When A re-checks the version right before uploading, B completes a whole sync first.
        var aLists = 0
        server.beforeHandle = { rec ->
            if (rec.token?.startsWith("A") == true && rec.path == "/drive/v3/files" && ++aLists == 2) {
                runBlocking { withContext(Dispatchers.IO) { b.syncOk() } }
            }
        }
        a.syncOk() // got a ConflictException on the first attempt, re-read B's write, merged, wrote
        server.beforeHandle = {}
        assertTrue(aLists >= 3, "A should have re-read after the conflict ($aLists lists)")
        b.syncOk()
        assertConverged()
        assertEquals(3, a.subscriptions().size)
        assertEquals(2, a.history().size)
    }

    @Test fun lostUpdateInsideDrivesNoCasWindowHealsOnNextSync() = runBlocking<Unit> {
        // Drive has no conditional write: if B writes between A's version re-check and A's PATCH, A's PATCH
        // overwrites B's file. Nothing is lost locally, and B's next sync re-uploads its data.
        a.repo.subscribe(podcast(1))
        round(); b.syncOk()
        clock.advance(); a.repo.subscribe(podcast(2))
        clock.advance(); b.repo.subscribe(podcast(3))
        var fired = false
        server.beforeHandle = { rec ->
            if (!fired && rec.token?.startsWith("A") == true && rec.method == "PATCH") {
                fired = true
                runBlocking { withContext(Dispatchers.IO) { b.syncOk() } }
            }
        }
        a.syncOk()
        server.beforeHandle = {}
        assertTrue(fired)
        assertTrue(remote().subscriptions.none { it.podcast.feedUrl == podcast(3).feedUrl }, "B's write was overwritten")
        assertTrue(podcast(3).feedUrl in b.subscriptions())
        b.syncOk(); a.syncOk()
        assertConverged()
        assertEquals(3, a.subscriptions().size)
    }

    @Test fun randomConcurrentSyncsConvergeWithoutLosingAnything() = runBlocking<Unit> {
        val rnd = Random(42)
        val expectedHistory = java.util.Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())
        val lastProgress = java.util.concurrent.ConcurrentHashMap<EpisodeId, Long>()
        suspend fun worker(d: Device, feedBase: Int) {
            repeat(12) { i ->
                d.repo.subscribe(podcast(feedBase + i))
                val id = episode(feedBase, "e$i")
                val pos = rnd.nextLong(1, 60) * MIN
                d.play(id, pos)
                lastProgress[id] = pos
                d.listened(id)
                expectedHistory += d.deviceId() to id.guid
                d.syncNow() // may fail after repeated conflicts; that's fine, the next one catches up
            }
        }
        listOf(async(Dispatchers.IO) { worker(a, 100) }, async(Dispatchers.IO) { worker(b, 200) }).awaitAll()
        repeat(2) { round() }
        b.syncOk()
        assertConverged()
        assertEquals(24, a.subscriptions().size)
        for ((id, pos) in lastProgress) assertEquals(pos, a.progress(id)?.positionMs, "progress of $id")
        assertEquals(expectedHistory.toSet(), a.history().map { it.deviceId to it.episodeId.guid }.toSet())
    }

    @Test fun networkFailureMidSyncLeavesLocalAndRemoteIntact() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1)); a.play(ep, MIN)
        round(); b.syncOk()
        val remoteBefore = server.file(file)!!.let { it.version to it.content.decodeToString() }

        clock.advance(); a.repo.subscribe(podcast(2)); a.play(ep, 2 * MIN)
        val localBefore = a.state()
        server.dropNext { it.method == "PATCH" } // connection dies during the upload
        assertIs<SyncStatus.Failed>(a.syncNow())
        assertEquals(localBefore, a.state())
        assertEquals(remoteBefore, server.file(file)!!.let { it.version to it.content.decodeToString() })

        // B's download breaks (the JDK client transparently retries an idempotent GET once, so keep failing):
        // B's database is untouched too.
        clock.advance(); b.listened(ep)
        val bBefore = b.state()
        server.dropNext(times = 10) { it.token?.startsWith("B") == true && it.query["alt"] == "media" }
        assertIs<SyncStatus.Failed>(b.syncNow())
        assertEquals(bBefore, b.state())
        server.clearFaults()

        a.syncOk(); b.syncOk(); a.syncOk()
        assertEquals(2, b.subscriptions().size)
        assertEquals(2 * MIN, b.progress(ep)?.positionMs)
        assertConverged()
    }

    @Test fun transientDriveErrorsAreRetried() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1))
        server.failNext(429, match = { it.path == "/drive/v3/files" })
        server.failNext(403, "userRateLimitExceeded", match = { it.method == "POST" })
        a.syncOk()
        clock.advance(); a.repo.subscribe(podcast(2))
        server.failNext(503, match = { it.method == "PATCH" })
        server.failNext(500, match = { it.query["alt"] == "media" })
        a.syncOk()
        b.syncOk()
        assertEquals(2, b.subscriptions().size)
    }

    @Test fun persistentDriveErrorsFailWithoutTouchingData() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1))
        a.syncOk()
        clock.advance(); a.repo.subscribe(podcast(2))
        val before = a.state()
        val remoteBefore = server.text(file)

        server.failNext(503, times = 100, match = { it.method == "PATCH" })
        val failed = assertIs<SyncStatus.Failed>(a.syncNow())
        assertTrue("503" in failed.message, failed.message)
        assertEquals(3, server.requests.count { it.method == "PATCH" }) // 1 + 2 retries

        server.clearFaults()
        server.failNext(403, "storageQuotaExceeded", times = 100, match = { it.method == "PATCH" })
        assertIs<SyncStatus.Failed>(a.syncNow())
        assertEquals(4, server.requests.count { it.method == "PATCH" }) // not retryable

        // A 5xx on create is not retried blindly (the file may have been created).
        server.clearFaults()
        assertEquals(before, a.state())
        assertEquals(remoteBefore, server.text(file))
        a.syncOk()
        server.failNext(500, times = 100, match = { it.method == "POST" })
        val c = DriveCloudStore(b.http, b.auth, server.baseUrl, retryDelaysMs = listOf(1, 1))
        val e = runCatching { c.write("other.json", "{}", ifRevision = null) }.exceptionOrNull()
        assertIs<GoogleApiException>(e)
        assertEquals(1, server.requests.count { it.method == "POST" && it.token?.startsWith("B") == true })
    }

    @Test fun expiredTokenIsRefreshedOnceThenSyncFailsCleanly() = runBlocking<Unit> {
        a.repo.subscribe(podcast(1))
        a.syncOk()
        server.expire("A-1")
        clock.advance(); a.repo.subscribe(podcast(2))
        a.syncOk()
        assertEquals(listOf("A-1"), a.auth.invalidated)
        b.syncOk()
        assertEquals(2, b.subscriptions().size)

        server.expire("A-2"); server.expire("A-3")
        val failed = assertIs<SyncStatus.Failed>(a.syncNow())
        assertTrue("401" in failed.message, failed.message)
    }

    @Test fun corruptRemoteFileIsRefusedAndNotOverwritten() = runBlocking<Unit> {
        val corruptGzip = gzip("""{"subscriptions":[]}""".encodeToByteArray()).let { it.copyOf(it.size - 6) }
        val samples = listOf(
            """{"subscriptions": [{"podcast": """, """{"subscriptions": 5}""", """{"version":"two"}""", "[]", "\u0000\u0001binary",
        ).map { it.encodeToByteArray() } + listOf(corruptGzip)
        for ((i, garbage) in samples.withIndex()) {
            val name = "corrupt-$i.json"
            val stored = server.put(name, garbage)
            val sync = DefaultSyncService(a.repo, a.store, fileName = name, clock = a::now)
            a.repo.subscribe(podcast(1))
            val before = a.state()
            sync.syncNow()
            val failed = assertIs<SyncStatus.Failed>(sync.status.value)
            assertTrue("unreadable" in failed.message, failed.message)
            assertTrue(garbage.contentEquals(server.file(name)!!.content))
            assertEquals(stored.version, server.file(name)!!.version)
            assertEquals(before, a.state())
        }
        assertEquals(0, server.requests.count { it.method == "PATCH" || it.method == "POST" })
    }

    @Test fun fileFromNewerAppVersionIsNeitherOverwrittenNorImported() = runBlocking<Unit> {
        val future = """
            {"version":${SyncFormat.SUPPORTED_VERSION + 1},
             "subscriptions":[{"podcast":{"feedUrl":"https://x/feed","title":"X"},"subscribed":true,"updatedAtEpochMs":5,"folder":"News"}],
             "progress":[],"history":[],"playlists":[{"name":"Commute"}]}
        """.trimIndent()
        val stored = server.put(file, future)
        a.repo.subscribe(podcast(1))
        val before = a.state()
        val failed = assertIs<SyncStatus.Failed>(a.syncNow())
        assertTrue("Update Waves" in failed.message, failed.message)
        assertEquals(future, server.text(file))
        assertEquals(stored.version, server.file(file)!!.version)
        assertEquals(before, a.state())
    }

    @Test fun plainJsonFileFromEarlierBuildIsReadAndUpgradedToGzip() = runBlocking<Unit> {
        // An earlier build (no compression) created the file; the current build reads it and rewrites it gzipped.
        val legacy = DefaultSyncService(b.repo, DriveCloudStore(b.http, b.auth, server.baseUrl, compress = false))
        b.repo.subscribe(podcast(1)); b.play(ep, 7 * MIN)
        legacy.syncNow()
        assertIs<SyncStatus.Done>(legacy.status.value)
        assertTrue(server.file(file)!!.content.decodeToString().startsWith("{"))
        assertEquals("application/json", server.file(file)!!.mimeType)

        clock.advance(); a.repo.subscribe(podcast(2))
        a.syncOk()
        assertEquals(listOf(podcast(1).feedUrl, podcast(2).feedUrl), a.subscriptions())
        assertEquals(7 * MIN, a.progress(ep)?.positionMs)
        assertTrue(isGzip(server.file(file)!!.content))
        assertEquals("application/gzip", server.file(file)!!.mimeType)
        b.syncOk()
        assertConverged()
    }

    @Test fun unknownTopLevelSectionsOfTheSameVersionArePreserved() = runBlocking<Unit> {
        // A same-version writer may add new top-level sections; older apps must carry them along verbatim.
        server.put(file, """{"version":1,"subscriptions":[],"progress":[],"history":[],"playlists":[{"name":"Commute"}]}""")
        a.repo.subscribe(podcast(1))
        a.syncOk()
        val text = server.text(file)!!
        assertTrue(""""playlists":[{"name":"Commute"}]""" in text, text)
        assertEquals(listOf(podcast(1).feedUrl), remote().subscriptions.map { it.podcast.feedUrl })
        b.syncOk()
        assertEquals(listOf(podcast(1).feedUrl), b.subscriptions())
    }

    @Test fun duplicateFilesResolveToTheOldestOnEveryDevice() = runBlocking<Unit> {
        // Two devices creating the file at the same time can leave two files with the same name.
        a.repo.subscribe(podcast(1))
        b.repo.subscribe(podcast(2))
        var fired = false
        server.beforeHandle = { rec ->
            if (!fired && rec.method == "POST" && rec.token?.startsWith("A") == true) {
                fired = true
                runBlocking { withContext(Dispatchers.IO) { b.syncOk() } } // B creates first
            }
        }
        a.syncOk() // B's create landed after A's existence check: A creates a duplicate
        server.beforeHandle = {}
        assertEquals(2, server.appDataFiles().count { it.name == file })
        repeat(2) { round() }
        assertConverged() // remote() reads the oldest, which is what both devices use
        assertEquals(2, a.subscriptions().size)
    }

    @Test fun signedOutDeviceDoesNothing() = runBlocking<Unit> {
        a.auth.signOut()
        a.repo.subscribe(podcast(1))
        assertIs<SyncStatus.Failed>(a.syncNow())
        assertTrue(server.requests.isEmpty())
        assertNull(server.text(file))
    }
}
