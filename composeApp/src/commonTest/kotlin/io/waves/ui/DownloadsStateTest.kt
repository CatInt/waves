package io.waves.ui

import io.waves.data.SettingsStore
import io.waves.data.UserSettings
import io.waves.download.DownloadInfo
import io.waves.download.DownloadStatus
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.offline.InMemoryPlaybackCache
import io.waves.offline.PlaybackCache
import io.waves.offline.PlaybackCacheEntry
import io.waves.player.PlaybackState
import io.waves.ui.fake.InMemoryDownloadManager
import io.waves.ui.fake.InMemoryLibraryRepository
import io.waves.ui.fake.InMemorySettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsStateTest {
    private val library = InMemoryLibraryRepository(clock = { 1L })
    private val playback = MutableStateFlow(PlaybackState())
    private val ep1 = episode(podcastA, 1)
    private val ep2 = episode(podcastA, 2)

    private class Setup(val manager: InMemoryDownloadManager, val state: DownloadsState)

    private fun TestScope.setup(
        sizeOf: (Episode) -> Long? = { 1_000_000L },
        failOnce: (Episode) -> Boolean = { false },
        settings: SettingsStore = InMemorySettingsStore(),
        cache: PlaybackCache? = null,
        offline: StateFlow<Boolean> = MutableStateFlow(false),
    ): Setup {
        val m = InMemoryDownloadManager(backgroundScope, stepMs = 100, steps = 10, sizeOf = sizeOf, failOnce = failOnce, clock = { 5L })
        return Setup(m, DownloadsState(m, library, playback, backgroundScope, settings, playbackCache = cache, offline = offline))
    }

    private suspend fun DownloadsState.controlOf(e: Episode) = control(e.id).first()

    @Test
    fun controlMapping() {
        fun info(status: DownloadStatus, done: Long = 0, total: Long? = 100) =
            DownloadInfo(ep1.id, status, done, total, "/f", if (status == DownloadStatus.Failed) "boom" else null)
        assertEquals(DownloadControl.NotDownloaded, downloadControlFor(null))
        assertEquals(DownloadControl.InProgress(null), downloadControlFor(info(DownloadStatus.Queued, 50)))
        assertEquals(DownloadControl.InProgress(0.5f), downloadControlFor(info(DownloadStatus.Downloading, 50)))
        assertEquals(DownloadControl.InProgress(null), downloadControlFor(info(DownloadStatus.Downloading, 50, total = null)))
        assertEquals(DownloadControl.Downloaded, downloadControlFor(info(DownloadStatus.Done, 100)))
        assertEquals(DownloadControl.Failed("boom"), downloadControlFor(info(DownloadStatus.Failed)))
    }

    @Test
    fun downloadProgressesToDone() = runTest {
        val s = setup()
        s.state.download(ep1)
        runCurrent()
        assertEquals(DownloadControl.InProgress(null), s.state.controlOf(ep1)) // queued
        advanceTimeBy(100 + 500 + 1)
        val mid = s.state.controlOf(ep1)
        assertIs<DownloadControl.InProgress>(mid)
        assertEquals(0.5f, mid.progress)
        settle()
        assertEquals(DownloadControl.Downloaded, s.state.controlOf(ep1))
        assertEquals("file:///fake/episodes/g1.mp3", s.manager.localUri(ep1.id))
        assertEquals(listOf(ep1.id), s.state.state.value.items.map { it.episodeId })
    }

    @Test
    fun finishedDownloadEntersHistoryOnce() = runTest {
        library.subscribe(podcastA)
        var changes = 0
        val m = InMemoryDownloadManager(backgroundScope, stepMs = 100, steps = 10, clock = { 5L })
        val state = DownloadsState(m, library, playback, backgroundScope, deviceId = "dev", onLocalChange = { changes++ })
        state.download(ep1)
        settle()
        state.download(ep2)
        settle()
        val history = library.history.first()
        assertEquals(setOf(ep1.id, ep2.id), history.map { it.episodeId }.toSet())
        assertEquals(2, history.size)
        val entry = history.first { it.episodeId == ep1.id }
        assertEquals(HistoryEntry(ep1.id, ep1.title, podcastA.title, 5L, "dev"), entry)
        assertEquals(2, changes)
    }

    @Test
    fun finishedDownloadStartsNewEpisodesAtZeroAndKeepsExistingProgress() = runTest {
        library.subscribe(podcastA)
        library.upsertEpisodes(podcastA.feedUrl, listOf(ep1, ep2))
        library.saveProgress(PlaybackProgress(ep2.id, 42_000, 600_000, false, 3))
        val m = InMemoryDownloadManager(backgroundScope, stepMs = 100, steps = 10, clock = { 5L })
        val state = DownloadsState(m, library, playback, backgroundScope, deviceId = "dev")
        state.download(ep1)
        state.download(ep2)
        settle()
        assertEquals(PlaybackProgress(ep1.id, 0, 600_000, false, 0), library.progress(ep1.id).first())
        assertEquals(PlaybackProgress(ep2.id, 42_000, 600_000, false, 3), library.progress(ep2.id).first())
    }

    @Test
    fun unknownSizeIsIndeterminate() = runTest {
        val s = setup(sizeOf = { null })
        s.state.download(ep1)
        advanceTimeBy(350)
        assertEquals(DownloadControl.InProgress(null), s.state.controlOf(ep1))
        settle()
        assertEquals(DownloadControl.Downloaded, s.state.controlOf(ep1))
    }

    @Test
    fun tapWhileDownloadingCancels() = runTest {
        val s = setup()
        s.state.download(ep1)
        advanceTimeBy(300)
        val c = s.state.controlOf(ep1)
        assertIs<DownloadControl.InProgress>(c)
        s.state.onControlClick(ep1, c)
        settle()
        assertEquals(DownloadControl.NotDownloaded, s.state.controlOf(ep1))
        assertTrue(s.state.state.value.items.isEmpty())
    }

    @Test
    fun failedDownloadRetries() = runTest {
        val s = setup(failOnce = { it == ep1 })
        s.state.download(ep1)
        settle()
        assertEquals(DownloadControl.Failed("Simulated network error"), s.state.controlOf(ep1))
        s.state.onControlClick(ep1, s.state.controlOf(ep1))
        settle()
        assertEquals(DownloadControl.Downloaded, s.state.controlOf(ep1))
    }

    @Test
    fun removeDownloadedEpisode() = runTest {
        val s = setup()
        s.state.download(ep1)
        settle()
        s.state.onControlClick(ep1, DownloadControl.Downloaded) // tap only opens the menu
        runCurrent()
        assertEquals(DownloadControl.Downloaded, s.state.controlOf(ep1))
        s.state.remove(ep1.id)
        runCurrent()
        assertEquals(DownloadControl.NotDownloaded, s.state.controlOf(ep1))
        assertNull(s.manager.localUri(ep1.id))
    }

    @Test
    fun downloadFromPlayerUsesStoredEpisodeNotFileUrl() = runTest {
        library.upsertEpisodes(podcastA.feedUrl, listOf(ep1))
        val s = setup()
        s.state.download(ep1.copy(audioUrl = "file:///old/copy.mp3"))
        settle()
        assertEquals(ep1.audioUrl, s.state.state.value.items.single().audioUrl)
    }

    @Test
    fun autoDeleteAfterFinishing() = runTest {
        val s = setup()
        s.state.download(ep1)
        s.state.download(ep2)
        settle()
        // Already completed before enabling: kept.
        library.saveProgress(PlaybackProgress(ep2.id, 600_000, 600_000, true, 5))
        settle()
        s.state.setAutoDeleteAfterFinishing(true)
        settle()
        assertTrue(s.state.state.value.autoDeleteAfterFinishing)
        assertEquals(2, s.state.state.value.items.size)

        // ep1 finishes while it is still loaded (not ended) in the player, reported with its file:// URL.
        playback.value = PlaybackState(ep1.copy(audioUrl = s.manager.localUri(ep1.id)!!), isPlaying = true)
        library.saveProgress(PlaybackProgress(ep1.id, 590_000, 600_000, true, 6))
        settle()
        assertNotNull(s.manager.localUri(ep1.id), "kept while still playing")
        playback.value = playback.value.copy(isPlaying = false, isEnded = true)
        settle()
        assertNull(s.manager.localUri(ep1.id))
        assertEquals(listOf(ep2.id), s.state.state.value.items.map { it.episodeId })
    }

    @Test
    fun autoDeleteIsPersistedInTheSettingsStore() = runTest {
        val store = InMemorySettingsStore()
        val s = setup(settings = store)
        assertFalse(s.state.autoDeleteAfterFinishing.value)
        s.state.setAutoDeleteAfterFinishing(true)
        settle()
        assertTrue(store.settings.value.autoDeleteDownloads)

        // A new holder (e.g. after restart) picks it up from the store, and follows external edits.
        val again = setup(settings = store)
        assertTrue(again.state.autoDeleteAfterFinishing.value)
        assertTrue(again.state.state.value.autoDeleteAfterFinishing)
        store.update { it.copy(autoDeleteDownloads = false) }
        settle()
        assertFalse(again.state.state.value.autoDeleteAfterFinishing)
    }

    @Test
    fun autoDeleteFromStoredSettingDeletesFinishedDownloads() = runTest {
        val s = setup(settings = InMemorySettingsStore(UserSettings(autoDeleteDownloads = true)))
        s.state.download(ep1)
        settle()
        library.saveProgress(PlaybackProgress(ep1.id, 600_000, 600_000, true, 5))
        settle()
        assertNull(s.manager.localUri(ep1.id))
    }

    @Test
    fun autoDeleteWaitsUntilBackOnline() = runTest {
        val offline = MutableStateFlow(true)
        val s = setup(settings = InMemorySettingsStore(UserSettings(autoDeleteDownloads = true)), offline = offline)
        s.state.download(ep1)
        settle()
        library.saveProgress(PlaybackProgress(ep1.id, 600_000, 600_000, true, 5))
        settle()
        assertNotNull(s.manager.localUri(ep1.id))
        offline.value = false
        settle()
        assertNull(s.manager.localUri(ep1.id))
    }

    @Test
    fun tapOnACachedEpisodeDownloadsIt() = runTest {
        val cache = InMemoryPlaybackCache()
        val s = setup(cache = cache)
        cache.upsert(PlaybackCacheEntry(ep1.id, ep1.audioUrl, 100, 100, 1))
        runCurrent()
        assertEquals(DownloadControl.Cached, s.state.controlOf(ep1))
        s.state.onControlClick(ep1, DownloadControl.Cached)
        settle()
        assertEquals(DownloadControl.Downloaded, s.state.controlOf(ep1))
    }

    @Test
    fun aCachedEpisodeGoesADayAfterItWasPlayed() = runTest {
        val cache = InMemoryPlaybackCache()
        var now = 0L
        val m = InMemoryDownloadManager(backgroundScope, clock = { 5L })
        val state = DownloadsState(
            m, library, playback, backgroundScope, playbackCache = cache,
            cacheSweepEvery = 1.hours, clock = { now },
        )
        cache.upsert(PlaybackCacheEntry(ep1.id, ep1.audioUrl, 100, 100, 1))
        cache.upsert(PlaybackCacheEntry(ep2.id, ep2.audioUrl, 100, 100, 1))
        library.saveProgress(PlaybackProgress(ep1.id, 600_000, 600_000, completed = true, updatedAtEpochMs = 0))
        library.saveProgress(PlaybackProgress(ep2.id, 60_000, 600_000, completed = false, updatedAtEpochMs = 0))
        settle()
        assertEquals(DownloadControl.Cached, state.controlOf(ep1))

        now = 23 * 3_600_000L
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(DownloadControl.Cached, state.controlOf(ep1), "kept for the first day")

        now = 25 * 3_600_000L
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(DownloadControl.NotDownloaded, state.controlOf(ep1))
        assertEquals(DownloadControl.Cached, state.controlOf(ep2), "not played yet")
    }

    @Test
    fun fullyCachedEpisodeCanBeRemovedByHandWhileOffline() = runTest {
        val cache = InMemoryPlaybackCache()
        val s = setup(cache = cache, offline = MutableStateFlow(true))
        cache.upsert(PlaybackCacheEntry(ep1.id, ep1.audioUrl, 100, 100, 1))
        settle()
        assertEquals(DownloadControl.Cached, s.state.controlOf(ep1))
        s.state.remove(ep1.id)
        settle()
        assertTrue(cache.entries.first().isEmpty())
    }

    @Test
    fun autoDeleteOffKeepsDownloads() = runTest {
        val s = setup()
        s.state.download(ep1)
        settle()
        library.saveProgress(PlaybackProgress(ep1.id, 600_000, 600_000, true, 5))
        settle()
        assertNotNull(s.manager.localUri(ep1.id))
    }
}
