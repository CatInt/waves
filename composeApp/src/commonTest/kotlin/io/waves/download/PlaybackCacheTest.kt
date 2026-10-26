package io.waves.download

import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.player.PlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCacheTest {
    private class Recorder : DownloadManager {
        val cached = mutableListOf<EpisodeId>()
        val evictions = mutableListOf<Duration>()
        override val downloads: Flow<List<DownloadInfo>> = emptyFlow()
        override fun state(episodeId: EpisodeId): Flow<DownloadInfo?> = emptyFlow()
        override suspend fun enqueue(episode: Episode) {}
        override suspend fun cancel(episodeId: EpisodeId) {}
        override suspend fun delete(episodeId: EpisodeId) {}
        override fun localUri(episodeId: EpisodeId): String? = null
        override suspend fun deleteCompleted(olderThan: Duration) = 0
        override suspend fun deleteDownloads(episodeIds: Collection<EpisodeId>) = 0
        override suspend fun cache(episode: Episode) { cached += episode.id }
        override suspend fun evictCache(notAccessedFor: Duration): Int { evictions += notAccessedFor; return 0 }
    }

    private fun episode(guid: String) = Episode(guid = guid, podcastFeedUrl = "https://f", title = guid, audioUrl = "https://a/$guid.mp3")

    private fun TestScope.setup(): Pair<MutableStateFlow<PlaybackState>, Recorder> {
        val state = MutableStateFlow(PlaybackState())
        val recorder = Recorder()
        PlaybackCache(state, recorder, backgroundScope, clock = { currentTime }).start()
        runCurrent()
        return state to recorder
    }

    @Test fun cachesAfterOneMinuteOfPlayingAcrossPauses() = runTest {
        val (state, recorder) = setup()
        val ep = episode("a")
        state.value = PlaybackState(ep, isPlaying = true)
        advanceTimeBy(40_000)
        state.value = PlaybackState(ep, isPlaying = false, positionMs = 40_000)
        advanceTimeBy(10 * 60_000) // paused time doesn't count
        assertEquals(emptyList(), recorder.cached)

        state.value = PlaybackState(ep, isPlaying = true, positionMs = 40_000)
        advanceTimeBy(19_000)
        assertEquals(emptyList(), recorder.cached)
        advanceTimeBy(2_000)
        assertEquals(listOf(ep.id), recorder.cached)

        // Only once per episode.
        state.value = PlaybackState(ep, isPlaying = false)
        runCurrent()
        state.value = PlaybackState(ep, isPlaying = true)
        advanceTimeBy(5 * 60_000)
        assertEquals(listOf(ep.id), recorder.cached)
    }

    @Test fun shortListensToAnotherEpisodeDontCacheIt() = runTest {
        val (state, recorder) = setup()
        state.value = PlaybackState(episode("a"), isPlaying = true)
        advanceTimeBy(30_000)
        state.value = PlaybackState(episode("b"), isPlaying = true)
        advanceTimeBy(59_000)
        assertEquals(emptyList(), recorder.cached)
        advanceTimeBy(2_000)
        assertEquals(listOf(episode("b").id), recorder.cached)
    }

    @Test fun evictsUnplayedCacheOnStartAndPeriodically() = runTest {
        val (_, recorder) = setup()
        assertEquals(1, recorder.evictions.size)
        assertEquals(Duration.parse("3d"), recorder.evictions.single())
        advanceTimeBy(6 * 60 * 60_000L + 1)
        assertEquals(2, recorder.evictions.size)
    }

    @Test fun evictionWaitsUntilCachingIsEnabledAgain() = runTest {
        val recorder = Recorder()
        val enabled = MutableStateFlow(false)
        PlaybackCache(
            MutableStateFlow(PlaybackState()), recorder, backgroundScope,
            enabled = enabled, clock = { currentTime },
        ).start()
        advanceTimeBy(6 * 60 * 60_000L + 1)
        runCurrent()
        assertEquals(emptyList(), recorder.evictions)
        enabled.value = true
        runCurrent()
        assertEquals(1, recorder.evictions.size)
    }
}
