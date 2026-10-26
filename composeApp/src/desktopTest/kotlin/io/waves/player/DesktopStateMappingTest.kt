package io.waves.player

import javafx.scene.media.MediaPlayer.Status
import javafx.util.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopStateMappingTest {
    private fun flags(status: Status?, want: Boolean, ended: Boolean = false) = DesktopStateMapping.flags(status, want, ended)
    private fun f(playing: Boolean, buffering: Boolean) = DesktopStateMapping.Flags(playing, buffering)

    @Test
    fun playingAndPaused() {
        assertEquals(f(true, false), flags(Status.PLAYING, true))
        assertEquals(f(false, false), flags(Status.PAUSED, false))
        assertEquals(f(false, false), flags(Status.STOPPED, false))
    }

    @Test
    fun loadingAndStalledBufferWhenWantingToPlay() {
        assertEquals(f(true, true), flags(Status.UNKNOWN, true))
        assertEquals(f(true, true), flags(null, true))
        assertEquals(f(true, true), flags(Status.READY, true))
        assertEquals(f(true, true), flags(Status.STALLED, true))
        assertEquals(f(false, false), flags(Status.READY, false))
    }

    @Test
    fun haltedAndEndedAreNotPlaying() {
        assertEquals(f(false, false), flags(Status.HALTED, true))
        assertEquals(f(false, false), flags(Status.DISPOSED, true))
        assertEquals(f(false, false), flags(Status.PLAYING, true, ended = true))
    }

    @Test
    fun durations() {
        assertNull(DesktopStateMapping.millisOrNull(Duration.UNKNOWN))
        assertNull(DesktopStateMapping.millisOrNull(Duration.INDEFINITE))
        assertNull(DesktopStateMapping.millisOrNull(null))
        assertEquals(1500L, DesktopStateMapping.millisOrNull(Duration.seconds(1.5)))
    }
}
