package io.waves.ui

import io.waves.player.PlaybackState
import io.waves.sync.ListeningRecord
import io.waves.ui.fake.InMemoryLibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningTrackerTest {
    // 2026-10-28 (a Wednesday) at noon UTC.
    private val start = 1_793_188_800_000L

    @Test
    fun countsOnlyTheTimeItPlays() = runTest {
        val library = InMemoryLibraryRepository()
        val playback = MutableStateFlow(PlaybackState())
        ListeningTracker(playback, library, "phone", backgroundScope, clock = { start + currentTime }, timeZone = { TimeZone.UTC }).start()
        runCurrent()

        playback.value = PlaybackState(episode(podcastA, 1), isPlaying = true)
        runCurrent()
        advanceTimeBy(95_000)
        playback.value = playback.value.copy(isPlaying = false)
        runCurrent()
        advanceTimeBy(600_000) // paused: not counted
        runCurrent()

        assertEquals(listOf(ListeningRecord("phone", "2026-10-28", 95_000)), library.listening.first())
    }

    @Test
    fun pastListeningIsEstimatedFromHistoryUntilADeviceMeasuresIt() {
        val ep1 = episode(podcastA, 1)
        val ep2 = episode(podcastA, 2)
        val ep3 = episode(podcastA, 3)
        val min = 60_000L
        val history = listOf(
            // Listened 20 min into ep1, then switched to ep2 after 30 min: only the 20 min it got through count.
            io.waves.model.HistoryEntry(ep1.id, ep1.title, "A", start, "phone"),
            io.waves.model.HistoryEntry(ep2.id, ep2.title, "A", start + 30 * min, "phone"),
            // Finished ep2 (60 min long) well before the next session.
            io.waves.model.HistoryEntry(ep3.id, ep3.title, "A", start + 300 * min, "phone"),
        )
        val progress = listOf(
            io.waves.model.PlaybackProgress(ep1.id, 20 * min, 60 * min, false, 0),
            io.waves.model.PlaybackProgress(ep2.id, 60 * min, 60 * min, true, 0),
            // ep3 only downloaded: never got going.
            io.waves.model.PlaybackProgress(ep3.id, 0, 60 * min, false, 0),
        )
        val estimated = HistoryState.estimatedListening(history, progress, start + 400 * min, TimeZone.UTC)
        assertEquals(listOf(ListeningRecord("phone", "2026-10-28", 80 * min)), estimated)

        // Measured days replace estimates from then on, on that device.
        val measured = listOf(ListeningRecord("phone", "2026-10-28", 5 * min))
        assertEquals(measured, HistoryState.withEstimates(measured, estimated))
        assertEquals(estimated, HistoryState.withEstimates(emptyList(), estimated))
    }

    @Test
    fun thisWeekCountsFromMondayOnEveryDevice() {
        val listening = listOf(
            ListeningRecord("phone", "2026-10-25", 1_000), // Sunday before
            ListeningRecord("phone", "2026-10-26", 2_000), // Monday
            ListeningRecord("mac", "2026-10-28", 3_000),
        )
        assertEquals(5_000L, HistoryState.thisWeek(listening, start, TimeZone.UTC))
        assertEquals("3 h 20 min", formatListened(200 * 60_000L))
        assertEquals("45 min", formatListened(45 * 60_000L))
    }
}
