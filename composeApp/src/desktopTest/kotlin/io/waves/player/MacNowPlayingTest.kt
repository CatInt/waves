package io.waves.player

import io.waves.ui.AppState
import io.waves.ui.fake.SampleData
import io.waves.ui.fake.fakeDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Drives the real MediaPlayer framework; skipped off macOS. */
class MacNowPlayingTest {
    private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) {
        while (!check()) delay(20)
    }

    @Test
    fun publishesEpisodeAndHandlesRemoteCommands() {
        if (!isMac) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deps = fakeDependencies(scope = scope, playerTickMs = 60 * 60 * 1000L)
        val app = AppState(deps, scope)
        val show = SampleData.podcasts.first()
        val episode = SampleData.episodes(show).first()
        val cover = MutableStateFlow<CoverArt?>(CoverArt("test://cover", png()))
        val nowPlaying = assertNotNull(MacNowPlaying.start(app.playback, cover, scope))
        try {
            runBlocking {
                app.playback.play(episode, show)
                eventually { MacNowPlaying.publishedTitle() == episode.title && app.playback.state.value.isPlaying }

                // Commands go through the Objective-C runtime exactly as MPRemoteCommandCenter sends them.
                val target = ObjC.new("WavesRemoteCommandTarget")
                assertEquals(0L, ObjC.sendLong(target, "wavesToggle:", null))
                eventually { !app.playback.state.value.isPlaying }
                ObjC.sendLong(target, "wavesPlay:", null)
                eventually { app.playback.state.value.isPlaying }
                val before = app.playback.state.value.positionMs
                ObjC.sendLong(target, "wavesSkipForward:", null)
                eventually { app.playback.state.value.positionMs >= before + 30_000 }
                ObjC.release(target)
            }
        } finally {
            nowPlaying.stop()
            scope.cancel()
        }
        assertNull(MacNowPlaying.publishedTitle())
    }

    @Test
    fun pausedEpisodeAtLaunchIsBrieflyReportedAsPlayingToClaimMediaKeys() {
        if (!isMac) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deps = fakeDependencies(scope = scope, playerTickMs = 60 * 60 * 1000L)
        val app = AppState(deps, scope)
        val show = SampleData.podcasts.first()
        val episode = SampleData.episodes(show).first()
        runBlocking {
            app.playback.play(episode, show)
            eventually { app.playback.state.value.isPlaying }
            app.playback.pause()
            eventually { !app.playback.state.value.isPlaying }
        }
        val nowPlaying = assertNotNull(MacNowPlaying.start(app.playback, MutableStateFlow(null), scope))
        try {
            runBlocking {
                eventually { MacNowPlaying.publishedTitle() == episode.title }
                assertEquals(1L, MacNowPlaying.publishedState(), "announced as playing first")
                eventually { MacNowPlaying.publishedState() == 2L }
                assertEquals(false, app.playback.state.value.isPlaying, "nothing actually started playing")
            }
        } finally {
            nowPlaying.stop()
            scope.cancel()
        }
    }
}
