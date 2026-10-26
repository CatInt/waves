package io.waves.player

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidStateMappingTest {
    private fun flags(state: Int, pwr: Boolean, err: Boolean = false) = AndroidStateMapping.flags(state, pwr, err)

    @Test
    fun readyFollowsPlayIntent() {
        assertEquals(AndroidStateMapping.Flags(isPlaying = true, isBuffering = false, isEnded = false), flags(Player.STATE_READY, true))
        assertEquals(AndroidStateMapping.Flags(isPlaying = false, isBuffering = false, isEnded = false), flags(Player.STATE_READY, false))
    }

    @Test
    fun bufferingOnlyWhenWantingToPlay() {
        assertEquals(AndroidStateMapping.Flags(isPlaying = true, isBuffering = true, isEnded = false), flags(Player.STATE_BUFFERING, true))
        assertEquals(AndroidStateMapping.Flags(isPlaying = false, isBuffering = false, isEnded = false), flags(Player.STATE_BUFFERING, false))
    }

    @Test
    fun endedIdleAndErrorAreNotPlaying() {
        assertEquals(AndroidStateMapping.Flags(isPlaying = false, isBuffering = false, isEnded = true), flags(Player.STATE_ENDED, true))
        assertEquals(AndroidStateMapping.Flags(isPlaying = false, isBuffering = false, isEnded = false), flags(Player.STATE_IDLE, true))
        assertEquals(AndroidStateMapping.Flags(isPlaying = false, isBuffering = false, isEnded = false), flags(Player.STATE_READY, true, err = true))
    }

    @Test
    fun unsetDurationIsNull() {
        assertNull(AndroidStateMapping.durationOrNull(C.TIME_UNSET))
        assertNull(AndroidStateMapping.durationOrNull(0))
        assertEquals(42L, AndroidStateMapping.durationOrNull(42))
    }
}
