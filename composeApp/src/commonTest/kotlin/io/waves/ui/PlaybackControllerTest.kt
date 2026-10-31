package io.waves.ui

import io.waves.data.InMemoryPlaybackSessionStore
import io.waves.data.PlaybackSession
import io.waves.data.UserSettings
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.ui.fake.InMemoryLibraryRepository
import io.waves.ui.fake.InMemorySettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    private val player = TestAudioPlayer()
    private val library = InMemoryLibraryRepository(clock = { 1L })
    private val changes = Counter()
    private var now = 1_000L

    private fun TestScope.controller() =
        PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now })

    @Test
    fun resumesFromSavedProgressAndRecordsHistory() = runTest {
        val ep = episode(podcastA, 1)
        library.saveProgress(PlaybackProgress(ep.id, 120_000, 600_000, false, 5))
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()

        assertEquals(listOf(ep to 120_000L), player.playCalls)
        assertEquals(listOf<String?>("Alpha Show"), player.playTitles)
        val history = library.history.first()
        assertEquals(1, history.size)
        assertEquals("dev-1", history[0].deviceId)
        assertEquals("Alpha Show", history[0].podcastTitle)
        assertEquals(now, history[0].playedAtEpochMs)
        assertTrue(changes.count >= 1)
        assertEquals(podcastA, c.podcast.value)
    }

    @Test
    fun aTappedEpisodeShowsAsPlayingBeforeThePlayerHasIt() = runTest {
        val first = episode(podcastA, 1)
        val second = episode(podcastA, 2)
        val c = controller()
        c.play(first, podcastA)
        runCurrent()

        c.play(second, podcastA)
        assertEquals(second.id, c.state.value.episode?.id, "shown before its saved position is looked up")
        assertTrue(c.state.value.isPlaying)
        assertEquals(listOf(first.id), player.playCalls.map { it.first.id })
        runCurrent()
        assertEquals(listOf(first.id, second.id), player.playCalls.map { it.first.id })
        assertEquals(second.id, c.state.value.episode?.id)
    }

    @Test
    fun playingAPlayedEpisodeAgainMarksItUnplayedFromTheStart() = runTest {
        val ep = episode(podcastA, 1)
        library.saveProgress(PlaybackProgress(ep.id, 600_000, 600_000, true, 5))
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()

        assertEquals(listOf(ep to 0L), player.playCalls)
        val progress = library.progress(ep.id).first()
        assertEquals(false, progress?.completed)
        assertEquals(0L, progress?.positionMs)
        assertEquals(600_000L, progress?.durationMs)
    }

    @Test
    fun playingAfterAnErrorOpensTheEpisodeAfreshWhereItStood() = runTest {
        val ep = episode(podcastA, 1)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        // Playing from a copy that's since gone: the player reports the file it read and an error.
        player.mutableState.value = player.state.value.copy(
            episode = ep.copy(audioUrl = "file:///gone.mp3"), isPlaying = false, positionMs = 90_000, error = "Source error",
        )

        c.togglePlayPause()
        runCurrent()
        assertEquals(ep to 90_000L, player.playCalls.last())
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun reloadKeepsAPausedEpisodePaused() = runTest {
        val ep = episode(podcastA, 1)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        player.advanceTo(30_000)
        c.pause()
        c.reload(ep.id)
        runCurrent()
        assertEquals(ep to 30_000L, player.playCalls.last())
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun pausingAnEpisodeStillStartingKeepsItPaused() = runTest {
        val ep = episode(podcastA, 1)
        val c = controller()
        c.play(ep, podcastA)
        c.pause()
        assertFalse(c.state.value.isPlaying)
        runCurrent()
        assertEquals(ep.id, c.state.value.episode?.id)
        assertFalse(c.state.value.isPlaying)
    }

    @Test
    fun restoresLastUnfinishedEpisodePausedUntilPlayed() = runTest {
        val older = episode(podcastA, 1)
        val newer = episode(podcastA, 2)
        val finished = episode(podcastA, 3)
        library.subscribe(podcastA)
        library.upsertEpisodes(podcastA.feedUrl, listOf(older, newer, finished))
        library.saveProgress(PlaybackProgress(older.id, 10_000, 600_000, false, 5))
        library.saveProgress(PlaybackProgress(newer.id, 90_000, 600_000, false, 7))
        library.saveProgress(PlaybackProgress(finished.id, 600_000, 600_000, true, 9))
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, restoreLastSession = true)
        runCurrent()

        assertEquals(newer.id, c.state.value.episode?.id)
        assertEquals(90_000L, c.state.value.positionMs)
        assertFalse(c.state.value.isPlaying)
        assertTrue(player.playCalls.isEmpty())
        assertEquals(podcastA.feedUrl, c.podcast.value?.feedUrl)

        // Seeking moves the restored position; play loads it into the player there.
        c.skipForward()
        c.togglePlayPause()
        runCurrent()
        assertEquals(listOf(newer to 120_000L), player.playCalls)
        assertEquals(1, library.history.first().size)
    }

    @Test
    fun restoresNothingWhenTheLastPlayedEpisodeWasFinished() = runTest {
        val unfinished = episode(podcastA, 1)
        val finished = episode(podcastA, 2)
        library.upsertEpisodes(podcastA.feedUrl, listOf(unfinished, finished))
        library.saveProgress(PlaybackProgress(unfinished.id, 10_000, 600_000, false, 5))
        library.saveProgress(PlaybackProgress(finished.id, 600_000, 600_000, true, 9))
        library.addHistory(HistoryEntry(unfinished.id, unfinished.title, podcastA.title, 4, "dev-1"))
        library.addHistory(HistoryEntry(finished.id, finished.title, podcastA.title, 8, "dev-1"))
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, restoreLastSession = true)
        runCurrent()
        assertNull(c.state.value.episode)
    }

    @Test
    fun restoresTheLastPlayedEpisodeEvenIfAnotherWasSavedLater() = runTest {
        val lastPlayed = episode(podcastA, 1)
        val other = episode(podcastA, 2)
        library.upsertEpisodes(podcastA.feedUrl, listOf(lastPlayed, other))
        library.saveProgress(PlaybackProgress(lastPlayed.id, 10_000, 600_000, false, 5))
        library.saveProgress(PlaybackProgress(other.id, 20_000, 600_000, false, 9))
        library.addHistory(HistoryEntry(other.id, other.title, podcastA.title, 2, "dev-1"))
        library.addHistory(HistoryEntry(lastPlayed.id, lastPlayed.title, podcastA.title, 4, "dev-1"))
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, restoreLastSession = true)
        runCurrent()
        assertEquals(lastPlayed.id, c.state.value.episode?.id)
    }

    @Test
    fun aDownloadInHistoryDoesNotHideTheLastPlayedEpisode() = runTest {
        val lastPlayed = episode(podcastA, 1)
        val downloaded = episode(podcastA, 2)
        library.upsertEpisodes(podcastA.feedUrl, listOf(lastPlayed, downloaded))
        library.saveProgress(PlaybackProgress(lastPlayed.id, 10_000, 600_000, false, 5))
        library.saveProgress(PlaybackProgress(downloaded.id, 0, 600_000, false, 0))
        library.addHistory(HistoryEntry(lastPlayed.id, lastPlayed.title, podcastA.title, 4, "dev-1"))
        library.addHistory(HistoryEntry(downloaded.id, downloaded.title, podcastA.title, 8, "dev-1"))
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, restoreLastSession = true)
        runCurrent()
        assertEquals(lastPlayed.id, c.state.value.episode?.id)
    }

    @Test
    fun resumesPlayingWhenInterruptedMomentsAgo() = runTest {
        val ep = episode(podcastA, 1)
        library.upsertEpisodes(podcastA.feedUrl, listOf(ep))
        library.saveProgress(PlaybackProgress(ep.id, 50_000, 600_000, false, 5))
        val store = InMemoryPlaybackSessionStore(PlaybackSession(ep.id.feedUrl, ep.id.guid, 61_000, playingAtEpochMs = now - 4_000))
        PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, restoreLastSession = true, session = store)
        runCurrent()
        assertEquals(listOf(ep to 61_000L), player.playCalls)
    }

    @Test
    fun staysPausedWhenInterruptedLongAgo() = runTest {
        val ep = episode(podcastA, 1)
        library.upsertEpisodes(podcastA.feedUrl, listOf(ep))
        library.saveProgress(PlaybackProgress(ep.id, 50_000, 600_000, false, 5))
        val store = InMemoryPlaybackSessionStore(PlaybackSession(ep.id.feedUrl, ep.id.guid, 61_000, playingAtEpochMs = now - 6_000))
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, restoreLastSession = true, session = store)
        runCurrent()
        assertTrue(player.playCalls.isEmpty())
        assertEquals(ep.id, c.state.value.episode?.id)
        assertNull(store.read())
    }

    @Test
    fun pausingClearsTheSessionButQuittingKeepsIt() = runTest {
        val ep = episode(podcastA, 1)
        val store = InMemoryPlaybackSessionStore()
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, session = store)
        c.play(ep, podcastA)
        runCurrent()
        player.advanceTo(30_000)
        advanceTimeBy(1_001)
        assertEquals(30_000L, store.read()?.positionMs)

        c.pause()
        runCurrent()
        assertNull(store.read())

        c.togglePlayPause()
        runCurrent()
        now += 2_000
        c.onShutdown()
        player.pause()
        runCurrent()
        assertEquals(now, store.read()?.playingAtEpochMs)
    }

    @Test
    fun restoreStepsAsideWhenSomethingPlays() = runTest {
        val restored = episode(podcastA, 1)
        val picked = episode(podcastA, 2)
        library.upsertEpisodes(podcastA.feedUrl, listOf(restored, picked))
        library.saveProgress(PlaybackProgress(restored.id, 10_000, 600_000, false, 5))
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, restoreLastSession = true)
        runCurrent()
        assertEquals(restored.id, c.state.value.episode?.id)

        c.play(picked, podcastA)
        runCurrent()
        assertEquals(listOf(picked to 0L), player.playCalls)
        assertEquals(picked.id, c.state.value.episode?.id)
    }

    @Test
    fun completedEpisodeRestartsFromBeginning() = runTest {
        val ep = episode(podcastA, 1)
        library.saveProgress(PlaybackProgress(ep.id, 600_000, 600_000, true, 5))
        controller().play(ep, podcastA)
        runCurrent()
        assertEquals(0L, player.playCalls.single().second)
    }

    @Test
    fun savesPeriodicallyWhilePlaying() = runTest {
        val ep = episode(podcastA, 1)
        controller().play(ep, podcastA)
        runCurrent()
        player.advanceTo(42_000)
        advanceTimeBy(10_001)
        assertEquals(42_000L, library.progress(ep.id).first()?.positionMs)

        player.advanceTo(52_000)
        advanceTimeBy(10_000)
        assertEquals(52_000L, library.progress(ep.id).first()?.positionMs)
    }

    @Test
    fun savesOnPauseAndNotWhilePaused() = runTest {
        val ep = episode(podcastA, 1)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        player.advanceTo(30_000)
        c.pause()
        runCurrent()
        val saved = library.progress(ep.id).first()
        assertEquals(30_000L, saved?.positionMs)
        assertFalse(saved!!.completed)

        val before = changes.count
        advanceTimeBy(60_000)
        assertEquals(before, changes.count, "no writes while paused")
    }

    @Test
    fun savesPreviousEpisodeOnSwitch() = runTest {
        val first = episode(podcastA, 1)
        val second = episode(podcastA, 2)
        val c = controller()
        c.play(first, podcastA)
        runCurrent()
        player.advanceTo(77_000)
        c.play(second, podcastA)
        runCurrent()
        assertEquals(77_000L, library.progress(first.id).first()?.positionMs)
        assertEquals(second, player.state.value.episode)
        assertEquals(2, library.history.first().size)
    }

    @Test
    fun markingCompleteKeepsThePositionAndNotCompleteStartsOver() = runTest {
        val ep = episode(podcastA, 1, durationSec = 600)
        library.saveProgress(PlaybackProgress(ep.id, 120_000, 600_000, false, 5))
        val c = controller()
        c.setCompleted(ep, true)
        runCurrent()
        assertEquals(PlaybackProgress(ep.id, 120_000, 600_000, true, now), library.progress(ep.id).first())

        c.setCompleted(ep, false)
        runCurrent()
        assertEquals(PlaybackProgress(ep.id, 0, 600_000, false, now), library.progress(ep.id).first())
        assertTrue(changes.count >= 2)
    }

    @Test
    fun theLoadedEpisodeMarkedCompleteStaysCompleteUntilResumed() = runTest {
        val ep = episode(podcastA, 1, durationSec = 600)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        player.advanceTo(60_000)
        c.setCompleted(ep, true)
        runCurrent()
        player.advanceTo(70_000)
        advanceTimeBy(10_001)
        assertEquals(true, library.progress(ep.id).first()?.completed, "saved while playing on")
        c.pause()
        runCurrent()
        assertEquals(true, library.progress(ep.id).first()?.completed, "saved on pause")

        c.togglePlayPause()
        runCurrent()
        player.advanceTo(80_000)
        c.pause()
        runCurrent()
        assertEquals(false, library.progress(ep.id).first()?.completed, "listening again")
    }

    @Test
    fun marksCompletedWithinThirtySecondsOfEnd() = runTest {
        val ep = episode(podcastA, 1, durationSec = 600)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        player.advanceTo(575_000)
        c.pause()
        runCurrent()
        assertTrue(library.progress(ep.id).first()!!.completed)
    }

    @Test
    fun savesWhenPlayerStopsByItself() = runTest {
        val ep = episode(podcastA, 1, durationSec = 600)
        controller().play(ep, podcastA)
        runCurrent()
        // Reached the end: the platform player flips isPlaying off.
        player.mutableState.value = player.state.value.copy(positionMs = 600_000, isPlaying = false)
        runCurrent()
        val saved = library.progress(ep.id).first()
        assertEquals(600_000L, saved?.positionMs)
        assertTrue(saved!!.completed)
    }

    @Test
    fun replayingCurrentEpisodeResumesWithoutNewHistory() = runTest {
        val ep = episode(podcastA, 1)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        c.pause()
        runCurrent()
        c.play(ep, podcastA)
        runCurrent()
        assertTrue(player.state.value.isPlaying)
        assertEquals(1, player.playCalls.size)
        assertEquals(1, library.history.first().size)
    }

    @Test
    fun marksCompletedWhenPlayerReportsEnded() = runTest {
        val ep = episode(podcastA, 1, durationSec = 3600)
        controller().play(ep, podcastA)
        runCurrent()
        // Duration estimate was off: the player ended well before the nominal duration.
        player.mutableState.value = player.state.value.copy(positionMs = 1_000_000, isPlaying = false, isEnded = true)
        runCurrent()
        assertTrue(library.progress(ep.id).first()!!.completed)
    }

    @Test
    fun playAfterEndRestartsEpisode() = runTest {
        val ep = episode(podcastA, 1)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        player.mutableState.value = player.state.value.copy(positionMs = 600_000, isPlaying = false, isEnded = true)
        runCurrent()
        c.play(ep, podcastA)
        runCurrent()
        assertEquals(listOf(0L, 0L), player.playCalls.map { it.second })
    }

    @Test
    fun startsAtDefaultSpeedAndUsesSkipIntervals() = runTest {
        val store = InMemorySettingsStore(UserSettings(defaultSpeed = 1.5f, skipBackSeconds = 10, skipForwardSeconds = 45))
        val c = PlaybackController(player, library, "dev-1", backgroundScope, changes::inc, clock = { now }, settings = store.settings)
        runCurrent()
        assertEquals(1.5f, player.state.value.speed)
        assertEquals(SkipIntervals(10, 45), c.skipIntervals.value)

        val ep = episode(podcastA, 1)
        c.play(ep, podcastA)
        runCurrent()
        c.seekTo(100_000)
        c.skipForward()
        assertEquals(145_000, player.state.value.positionMs)
        c.skipBack()
        assertEquals(135_000, player.state.value.positionMs)

        store.update { it.copy(defaultSpeed = 2f, skipForwardSeconds = 30) }
        runCurrent()
        assertEquals(2f, player.state.value.speed)
        assertEquals(30_000, c.skipForwardMs)
    }

    @Test
    fun speedIsClamped() = runTest {
        val c = controller()
        c.setSpeed(10f)
        assertEquals(3f, player.state.value.speed)
        c.setSpeed(0.1f)
        assertEquals(0.5f, player.state.value.speed)
        c.setSpeed(1.5f)
        assertEquals(1.5f, player.state.value.speed)
    }

    @Test
    fun skipClampsToBounds() = runTest {
        val ep = episode(podcastA, 1, durationSec = 100)
        val c = controller()
        c.play(ep, podcastA)
        runCurrent()
        player.advanceTo(5_000)
        c.skipBy(-15_000)
        assertEquals(0L, player.state.value.positionMs)
        c.skipBy(30_000)
        c.skipBy(30_000)
        c.skipBy(30_000)
        c.skipBy(30_000)
        assertEquals(100_000L, player.state.value.positionMs)
    }

    @Test
    fun noEpisodeMeansNothingSaved() = runTest {
        val c = controller()
        c.togglePlayPause()
        c.saveNow()
        assertNull(library.allProgress.first().firstOrNull())
    }

    @Test
    fun completionRule() {
        assertFalse(PlaybackController.isCompleted(0, 0))
        assertFalse(PlaybackController.isCompleted(100_000, 3_600_000))
        assertTrue(PlaybackController.isCompleted(3_571_000, 3_600_000)) // within 30s
        assertTrue(PlaybackController.isCompleted(3_430_000, 3_600_000)) // > 95%
        assertFalse(PlaybackController.isCompleted(3_400_000, 3_600_000))
    }
}
