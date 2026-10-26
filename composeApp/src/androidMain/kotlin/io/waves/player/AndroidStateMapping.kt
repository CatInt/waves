package io.waves.player

import androidx.media3.common.C
import androidx.media3.common.Player

/** Pure mapping from Media3 player properties to [PlaybackState] flags (unit-tested on the JVM). */
internal object AndroidStateMapping {

    data class Flags(val isPlaying: Boolean, val isBuffering: Boolean, val isEnded: Boolean)

    /**
     * `isPlaying` follows the play intent (`playWhenReady`) so the UI shows "pause" while buffering;
     * `isBuffering` says sound isn't actually coming out yet.
     */
    fun flags(playbackState: Int, playWhenReady: Boolean, hasError: Boolean): Flags = when {
        hasError -> Flags(isPlaying = false, isBuffering = false, isEnded = false)
        playbackState == Player.STATE_ENDED -> Flags(isPlaying = false, isBuffering = false, isEnded = true)
        playbackState == Player.STATE_IDLE -> Flags(isPlaying = false, isBuffering = false, isEnded = false)
        playbackState == Player.STATE_BUFFERING -> Flags(isPlaying = playWhenReady, isBuffering = playWhenReady, isEnded = false)
        else -> Flags(isPlaying = playWhenReady, isBuffering = false, isEnded = false) // STATE_READY
    }

    /** Media3 reports unknown durations as [C.TIME_UNSET]. */
    fun durationOrNull(raw: Long): Long? = if (raw == C.TIME_UNSET || raw <= 0) null else raw
}
