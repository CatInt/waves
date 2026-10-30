package io.waves.sync

import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.model.Podcast
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultSyncServiceTest {
    private val name = DefaultSyncService.DEFAULT_FILE_NAME
    private val json = DefaultSyncService.json

    private fun sub(feed: String, subscribed: Boolean = true, at: Long = 1) =
        SubscriptionRecord(Podcast(feed, feed), subscribed, at)
    private fun prog(guid: String, pos: Long, at: Long) = PlaybackProgress(EpisodeId("f", guid), pos, 10_000, false, at)

    private fun remoteState(cloud: FakeCloudStore) = json.decodeFromString(SyncState.serializer(), cloud.files.getValue(name).first)

    @Test
    fun firstSyncUploadsLocalWhenRemoteAbsent() = runTest {
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("a"))))
        val cloud = FakeCloudStore()
        val service = DefaultSyncService(library, cloud, clock = { 42 })

        service.syncNow()

        assertEquals(SyncStatus.Done(42), service.status.value)
        assertEquals(listOf(sub("a")), remoteState(cloud).subscriptions)
        assertEquals(0, library.imports, "nothing new to import")
    }

    @Test
    fun mergesBothWays() = runTest {
        val cloud = FakeCloudStore()
        cloud.files[name] = json.encodeToString(
            SyncState.serializer(),
            SyncState(subscriptions = listOf(sub("remote")), progress = listOf(prog("1", 500, at = 10)))
        ) to 7
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("local")), progress = listOf(prog("1", 900, at = 20))))

        DefaultSyncService(library, cloud).syncNow()

        val expected = SyncState(subscriptions = listOf(sub("local"), sub("remote")), progress = listOf(prog("1", 900, at = 20)))
        assertEquals(expected, remoteState(cloud))
        assertEquals(expected, library.stateFlow.value)
        assertEquals(1, library.imports)
    }

    @Test
    fun archivedEpisodesReachTheOtherDevice() = runTest {
        val stream = Episode(guid = "abcdefghijk", podcastFeedUrl = "live", title = "Fell off the feed", audioUrl = "a", publishedAtEpochMs = 1)
        val cloud = FakeCloudStore()
        cloud.files[name] = json.encodeToString(SyncState.serializer(), SyncState(archive = listOf(stream))) to 1
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("live"))))

        DefaultSyncService(library, cloud).syncNow()

        assertEquals(listOf(stream), library.stateFlow.value.archive)
        assertEquals(listOf(stream), remoteState(cloud).archive)
    }

    @Test
    fun skipsWriteWhenRemoteAlreadyUpToDate() = runTest {
        val state = SyncState(subscriptions = listOf(sub("a")))
        val cloud = FakeCloudStore()
        cloud.files[name] = json.encodeToString(SyncState.serializer(), state) to 1
        val library = FakeLibrary(state)

        DefaultSyncService(library, cloud).syncNow()

        assertEquals(0, cloud.writes)
        assertEquals(0, library.imports)
    }

    @Test
    fun retriesOnConflictAndIncludesOtherDevicesWrite() = runTest {
        val cloud = FakeCloudStore()
        cloud.files[name] = json.encodeToString(SyncState.serializer(), SyncState()) to 1
        var injected = false
        cloud.beforeWrite = {
            if (!injected) {
                injected = true
                it.files[name] = json.encodeToString(SyncState.serializer(), SyncState(subscriptions = listOf(sub("other")))) to 2
            }
        }
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("mine"))))
        val service = DefaultSyncService(library, cloud)

        service.syncNow()

        assertIs<SyncStatus.Done>(service.status.value)
        assertEquals(listOf(sub("mine"), sub("other")), remoteState(cloud).subscriptions)
        assertEquals(listOf(sub("mine"), sub("other")), library.stateFlow.value.subscriptions)
        assertEquals(2, cloud.reads)
    }

    @Test
    fun firstSyncMergesWhenAnotherDeviceCreatedTheFileMeanwhile() = runTest {
        val cloud = FakeCloudStore() // no remote file yet
        var injected = false
        cloud.beforeWrite = {
            if (!injected) {
                injected = true
                it.files[name] = json.encodeToString(SyncState.serializer(), SyncState(subscriptions = listOf(sub("other")))) to 1
            }
        }
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("mine"))))
        val service = DefaultSyncService(library, cloud)

        service.syncNow()

        assertIs<SyncStatus.Done>(service.status.value)
        assertEquals(listOf(sub("mine"), sub("other")), remoteState(cloud).subscriptions, "other device's data not overwritten")
        assertEquals(listOf(sub("mine"), sub("other")), library.stateFlow.value.subscriptions)
        assertEquals(2, cloud.reads)
    }

    @Test
    fun failsAfterThreeConflicts() = runTest {
        val cloud = FakeCloudStore()
        cloud.files[name] = "{}" to 1
        cloud.beforeWrite = { it.files[name] = "{}" to it.files.getValue(name).second + 1 }
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("a"))))
        val service = DefaultSyncService(library, cloud)

        service.syncNow()

        assertIs<SyncStatus.Failed>(service.status.value)
        assertEquals(3, cloud.reads)
        assertEquals(0, library.imports)
    }

    @Test
    fun reportsFailureWithoutThrowing() = runTest {
        val cloud = FakeCloudStore().apply { failWith = NotSignedInException() }
        val service = DefaultSyncService(FakeLibrary(), cloud)
        service.syncNow()
        assertEquals(SyncStatus.Failed("Not signed in to Google"), service.status.value)
    }

    @Test
    fun refusesToOverwriteUnreadableRemote() = runTest {
        val cloud = FakeCloudStore()
        cloud.files[name] = "not json" to 1
        val service = DefaultSyncService(FakeLibrary(SyncState(subscriptions = listOf(sub("a")))), cloud)
        service.syncNow()
        assertIs<SyncStatus.Failed>(service.status.value)
        assertEquals("not json", cloud.files.getValue(name).first)
    }

    @Test
    fun refusesFilesFromNewerFormatVersions() = runTest {
        val cloud = FakeCloudStore()
        val future = """{"version":2,"futureField":true,"subscriptions":[]}"""
        cloud.files[name] = future to 1
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("a"))))
        val service = DefaultSyncService(library, cloud)
        service.syncNow()
        val failed = assertIs<SyncStatus.Failed>(service.status.value)
        assertTrue("Update Waves" in failed.message, failed.message)
        assertEquals(future to 1, cloud.files.getValue(name))
        assertEquals(0, library.imports)
    }

    @Test
    fun keepsUnknownTopLevelKeysOfTheSameVersion() = runTest {
        val cloud = FakeCloudStore()
        cloud.files[name] = """{"version":1,"futureSection":{"x":[1,2]},"subscriptions":[]}""" to 1
        val service = DefaultSyncService(FakeLibrary(SyncState(subscriptions = listOf(sub("a")))), cloud)
        service.syncNow()
        assertIs<SyncStatus.Done>(service.status.value)
        val written = cloud.files.getValue(name).first
        assertTrue(""""futureSection":{"x":[1,2]}""" in written, written)
        assertEquals(listOf(sub("a")), remoteState(cloud).subscriptions)
    }

    @Test
    fun localChangesDuringSyncAreNotLostOnImport() = runTest {
        val cloud = FakeCloudStore()
        val library = FakeLibrary(SyncState(subscriptions = listOf(sub("local"))))
        val late = HistoryEntry(EpisodeId("f", "late"), "t", "p", 99, "d")
        val newerProgress = prog("1", 700, at = 50)
        cloud.files[name] = json.encodeToString(
            SyncState.serializer(),
            SyncState(subscriptions = listOf(sub("remote")), progress = listOf(prog("1", 100, at = 10))),
        ) to 1
        cloud.beforeWrite = {
            // Between export and import: playback records history and saves newer progress.
            library.stateFlow.value = library.stateFlow.value.copy(history = listOf(late), progress = listOf(newerProgress))
        }

        DefaultSyncService(library, cloud).syncNow()

        val local = library.stateFlow.value
        assertEquals(listOf(late), local.history)
        assertEquals(listOf(newerProgress), local.progress)
        assertEquals(listOf(sub("local"), sub("remote")), local.subscriptions)
    }

    @Test
    fun twoDevicesConverge() = runTest {
        val cloud = FakeCloudStore()
        val phone = FakeLibrary(SyncState(subscriptions = listOf(sub("a", at = 1)), progress = listOf(prog("1", 100, 5))))
        val mac = FakeLibrary(SyncState(subscriptions = listOf(sub("a", subscribed = false, at = 2)), progress = listOf(prog("1", 300, 4))))
        val s1 = DefaultSyncService(phone, cloud)
        val s2 = DefaultSyncService(mac, cloud)

        s1.syncNow(); s2.syncNow(); s1.syncNow()

        assertEquals(phone.stateFlow.value, mac.stateFlow.value)
        assertEquals(phone.stateFlow.value, remoteState(cloud))
        assertTrue(phone.stateFlow.value.subscriptions.single().let { !it.subscribed })
        assertEquals(100, phone.stateFlow.value.progress.single().positionMs)
    }
}
